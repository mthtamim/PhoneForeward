package com.mthtamim.phonelink

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream

/**
 * v2.4 consent-based screen sharing. Android never lets an app capture
 * the screen silently: the owner starts a session from the app (system
 * consent dialog), and only during that session can the peer request
 * screenshots (SCREENSHOT command, command-PIN gated).
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, LinkService.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.svc_running))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.app.ServiceCompat.startForeground(this, 42, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(42, notif)
        }
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (resultCode != 0 && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { projection = null }
            }, Handler(Looper.getMainLooper()))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        super.onDestroy()
    }

    companion object {
        @Volatile var projection: MediaProjection? = null

        fun sessionActive(): Boolean = projection != null

        /** Grabs one frame and sends it as a file. False = no session. */
        fun capture(ctx: Context): Boolean {
            val proj = projection ?: return false
            Thread {
                var display: VirtualDisplay? = null
                var reader: ImageReader? = null
                try {
                    val metrics = ctx.resources.displayMetrics
                    val w = metrics.widthPixels
                    val h = metrics.heightPixels
                    reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                    display = proj.createVirtualDisplay("plshare", w, h, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface, null, null)
                    Thread.sleep(700)
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w
                        val bmp = Bitmap.createBitmap(
                            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(buffer)
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                        val out = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        image.close()
                        Relay.postFile(ctx, "screenshot-${System.currentTimeMillis()}.jpg",
                            out.toByteArray(), "image/jpeg")
                    }
                } catch (e: Exception) { }
                try { display?.release() } catch (e: Exception) { }
                try { reader?.close() } catch (e: Exception) { }
            }.start()
            return true
        }
    }
}
