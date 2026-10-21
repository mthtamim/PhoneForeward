package com.mthtamim.phonelink

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import kotlin.concurrent.thread

/**
 * One-shot camera snapshot, started by the SNAP remote command. Runs as a
 * short-lived foreground service, takes a single back-camera photo and
 * uploads it to the peer through the relay's file channel.
 */
class SnapService : LifecycleService() {

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startSnapForeground()
        takePhoto()
        // Watchdog: never linger if the camera stack misbehaves.
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 20_000)
        return START_NOT_STICKY
    }

    private fun startSnapForeground() {
        val notif = Notification.Builder(this, LinkService.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.svc_running))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, notif)
        }
    }

    private fun takePhoto() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder()
                    .setTargetResolution(Size(1280, 960))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                val selector = if (SessionState.snapFront) {
                    SessionState.snapFront = false
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.bindToLifecycle(this, selector, capture)
                // Give the sensor a moment to settle before exposing.
                Handler(Looper.getMainLooper()).postDelayed({
                    val file = File(cacheDir, "snap_${System.currentTimeMillis()}.jpg")
                    val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(opts, ContextCompat.getMainExecutor(this),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                thread {
                                    try {
                                        val bytes = file.readBytes()
                                        Relay.postFile(applicationContext,
                                            "snapshot.jpg", bytes, "image/jpeg")
                                    } catch (_: Exception) { }
                                    try { file.delete() } catch (_: Exception) { }
                                    stopSelf()
                                }
                            }
                            override fun onError(exc: ImageCaptureException) { stopSelf() }
                        })
                }, 800)
            } catch (_: Exception) { stopSelf() }
        }, ContextCompat.getMainExecutor(this))
    }
}
