package com.mthtamim.phonelink

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.4.3 QR/barcode scanner for the qr_scan action. CameraX preview
 * + ML Kit bundled barcode scanner (offline, no Play Services model
 * download, all formats). One code ends the scan: the decoded text
 * goes back to FlowScan.complete() and the activity finishes. Cancel
 * and the timeout do the same with null, so the waiting flow thread
 * is never left hanging. Every failure path logs and finishes.
 */
class FlowScanActivity : LocalizedActivity() {

    private var callId: Long = -1L
    private var timeoutSeconds: Int = 60
    private val done = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val scanner = BarcodeScanning.getClient()

    @Volatile
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_scan)
        callId = intent?.getLongExtra("call_id", -1L) ?: -1L
        timeoutSeconds = intent?.getIntExtra("timeout", 60) ?: 60
        findViewById<TextView>(R.id.scanHint).text = getString(R.string.scan_hint)
        findViewById<Button>(R.id.btnScanCancel).setOnClickListener {
            finishScan(null)
        }
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            FlowStore.log(this, "qr_scan: camera permission not granted")
            finishScan(null)
            return
        }
        handler.postDelayed({ finishScan(null) }, timeoutSeconds * 1000L)
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener(
                {
                    try {
                        bindScanner(future.get())
                    } catch (e: Exception) {
                        FlowStore.log(
                            this,
                            "qr_scan bind error: " + (e.message ?: e.javaClass.simpleName)
                        )
                        finishScan(null)
                    }
                },
                ContextCompat.getMainExecutor(this)
            )
        } catch (e: Exception) {
            FlowStore.log(this, "qr_scan bind error: " + (e.message ?: e.javaClass.simpleName))
            finishScan(null)
        }
    }

    private fun bindScanner(provider: ProcessCameraProvider) {
        val previewView = findViewById<PreviewView>(R.id.scanPreview)
        val preview = androidx.camera.core.Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
            val mediaImage = proxy.image
            if (mediaImage != null && !busy) {
                busy = true
                val image = InputImage.fromMediaImage(
                    mediaImage, proxy.imageInfo.rotationDegrees
                )
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val text = barcodes.firstNotNullOfOrNull { it.rawValue }
                        if (text != null) {
                            finishScan(text)
                        }
                    }
                    .addOnFailureListener {
                        // A frame that fails to decode is not an
                        // error for the scan — keep looking.
                    }
                    .addOnCompleteListener {
                        busy = false
                        proxy.close()
                    }
            } else {
                proxy.close()
            }
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, analysis)
    }

    private fun finishScan(value: String?) {
        if (!done.compareAndSet(false, true)) {
            return
        }
        handler.removeCallbacksAndMessages(null)
        FlowScan.complete(callId, value)
        finish()
    }

    override fun onBackPressed() {
        finishScan(null)
    }

    override fun onDestroy() {
        // Back press and system kills land here without finishScan
        // only if the scan never completed: release the waiter.
        if (!done.get()) {
            FlowScan.complete(callId, null)
        }
        try {
            scanner.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
