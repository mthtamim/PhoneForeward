package com.mthtamim.phonelink

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Camera QR scanner (CameraX + ZXing core) for pairing by scan.
 *
 * v2.1 black-preview fix: on several OEM builds (notably Transsion) the
 * default SurfaceView preview renders pure black while analysis frames
 * still flow. We therefore force PreviewView's TextureView (COMPATIBLE)
 * mode, raise window brightness while scanning, pack the YUV plane
 * honouring row/pixel stride (padding bytes used to corrupt frames),
 * and add a torch toggle for dark rooms.
 */
class ScanActivity : LocalizedActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var done = false
    private var camera: Camera? = null
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        // Scanning needs light: full brightness + keep the screen awake.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        findViewById<Button>(R.id.btnTorch).setOnClickListener {
            torchOn = !torchOn
            try { camera?.cameraControl?.enableTorch(torchOn) } catch (_: Exception) { }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 7 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            toast(getString(R.string.toast_camera_denied))
            finish()
        }
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                bind(provider, previewView, CameraSelector.DEFAULT_BACK_CAMERA)
            } catch (e: Exception) {
                // Some devices expose no usable back camera: try the front one.
                try {
                    val provider = future.get()
                    bind(provider, previewView, CameraSelector.DEFAULT_FRONT_CAMERA)
                } catch (e2: Exception) {
                    toast(getString(R.string.toast_camera_denied))
                    finish()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(provider: ProcessCameraProvider, previewView: PreviewView, selector: CameraSelector) {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
        provider.unbindAll()
        camera = provider.bindToLifecycle(this, selector, preview, analysis)
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (!done) {
                val bytes = packLuma(proxy)
                if (bytes != null) {
                    val source = PlanarYUVLuminanceSource(
                        bytes, proxy.width, proxy.height, 0, 0, proxy.width, proxy.height, false
                    )
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    val text = QRCodeReader().decode(bitmap).text
                    if (text.isNotEmpty()) {
                        done = true
                        runOnUiThread {
                            setResult(RESULT_OK, Intent().putExtra("code", text.trim()))
                            finish()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Not a QR frame — keep scanning.
        } finally {
            proxy.close()
        }
    }

    /**
     * Copies the Y plane into a tightly packed width*height array. Camera
     * buffers pad rows (rowStride > width); feeding the raw buffer to
     * ZXing misaligns every row after the first.
     */
    private fun packLuma(proxy: ImageProxy): ByteArray? {
        val plane = proxy.planes.getOrNull(0) ?: return null
        val width = proxy.width
        val height = proxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val out = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, y * width, minOf(width, toRead))
            } else {
                for (x in 0 until width) {
                    val src = x * pixelStride
                    if (src < toRead) out[y * width + x] = row[src]
                }
            }
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
