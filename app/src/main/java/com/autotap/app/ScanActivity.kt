package com.autotap.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Live QR scan (Camera2 + zxing core, no extra libraries): point the camera
 * at a friend's AutoTap QR and the script imports instantly.
 */
class ScanActivity : Activity() {

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private lateinit var texture: TextureView
    @Volatile private var done = false
    @Volatile private var lastTry = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(TextView(this).apply {
            text = "QR-এর দিকে ক্যামেরা ধরো - script নিজে import হয়ে যাবে"
            setTextColor(Ui.TEXT)
            textSize = 14f
            setPadding(Ui.dp(this@ScanActivity, 16), Ui.dp(this@ScanActivity, 16), Ui.dp(this@ScanActivity, 16), Ui.dp(this@ScanActivity, 8))
        })
        texture = TextureView(this)
        root.addView(texture, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        } else {
            startCameraWhenReady()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        } else if (requestCode == 7) {
            Toast.makeText(this, "ক্যামেরা permission ছাড়া live scan হবে না", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCameraWhenReady() {
        if (texture.isAvailable) openCamera(texture.width, texture.height)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                openCamera(w, h)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun openCamera(viewW: Int, viewH: Int) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try { openCameraInner(viewW, viewH) } catch (e: Exception) {
            Toast.makeText(this, "ক্যামেরা খোলা যায়নি", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun openCameraInner(viewW: Int, viewH: Int) {
        bgThread = HandlerThread("autotap-scan").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
        val manager = getSystemService(CameraManager::class.java) ?: return
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        val w = 1280
        val h = 720
        reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
        reader?.setOnImageAvailableListener({ ir ->
            val now = System.currentTimeMillis()
            if (done || now - lastTry < 250) {
                try { ir.acquireLatestImage()?.close() } catch (e: Exception) {}
                return@setOnImageAvailableListener
            }
            lastTry = now
            val image = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                val rowStride = plane.rowStride
                val cropped = if (rowStride == image.width) data else {
                    val out = ByteArray(image.width * image.height)
                    for (row in 0 until image.height) {
                        System.arraycopy(data, row * rowStride, out, row * image.width, image.width)
                    }
                    out
                }
                val source = PlanarYUVLuminanceSource(
                    cropped, image.width, image.height, 0, 0, image.width, image.height, false
                )
                val text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
                if (text != null && !done) {
                    done = true
                    runOnUiThread { onScanned(text) }
                }
            } catch (e: Exception) {
                // No QR in this frame; keep scanning.
            } finally {
                image.close()
            }
        }, bgHandler)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val st = texture.surfaceTexture ?: return
                st.setDefaultBufferSize(viewW.coerceAtLeast(640), viewH.coerceAtLeast(480))
                val previewSurface = Surface(st)
                val surfaces = listOf(previewSurface, reader!!.surface)
                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(reader!!.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        s.setRepeatingRequest(req.build(), null, bgHandler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bgHandler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close() }
        }, bgHandler)
    }

    private fun onScanned(text: String) {
        val n = Store.importJson(this, text)
        if (n > 0) {
            Toast.makeText(this, "$n টি script import হলো", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "QR পড়া গেছে কিন্তু script মেলেনি", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        done = true
        try { session?.close() } catch (e: Exception) {}
        try { camera?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
