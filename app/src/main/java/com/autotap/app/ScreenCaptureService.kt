package com.autotap.app
import com.mthtamim.phonelink.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.HandlerThread
import android.os.IBinder

/**
 * Screen capture for OCR_TAP and IMAGE_TAP steps (v2). Uses the standard
 * MediaProjection consent the user grants in MainActivity; frames stay in
 * memory on-device, are never saved or uploaded, and the service stops as
 * soon as no script needs it.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var recorder: android.media.MediaRecorder? = null
    private var recordDisplay: VirtualDisplay? = null
    @Volatile private var recordingPath: String? = null
    @Volatile var isRecording: Boolean = false
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), foregroundType())
        } else {
            @Suppress("DEPRECATION") startForeground(NOTIF_ID, buildNotification())
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi
        thread = HandlerThread("autotap-capture").apply { start() }
        val handler = Handler(thread!!.looper)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            try {
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                val cropped = if (rowPadding > 0) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                synchronized(lock) {
                    lastFrame?.recycle()
                    lastFrame = cropped
                }
                image.close()
            } catch (e: Exception) {
                // A dropped frame is harmless; the next one replaces it.
            }
        }, handler)
        display = proj.createVirtualDisplay(
            "autotap", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        instance = this
        return START_STICKY
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    /** Starts recording the screen to an mp4 (run videos). Best-effort. */
    fun startRecording(): Boolean {
        if (recorder != null) return true
        val proj = projection ?: return false
        return try {
            val metrics = resources.displayMetrics
            val w = (metrics.widthPixels / 2) and 0x7FFFFFFE
            val h = (metrics.heightPixels / 2) and 0x7FFFFFFE
            val dir = java.io.File(getExternalFilesDir("movies") ?: filesDir, "").apply { mkdirs() }
            val out = java.io.File(dir, "run-" + System.currentTimeMillis() + ".mp4")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") android.media.MediaRecorder()
            }
            rec.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(4_000_000)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            recordDisplay = proj.createVirtualDisplay(
                "autotap-rec", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
            )
            rec.start()
            recorder = rec
            recordingPath = out.absolutePath
            isRecording = true
            true
        } catch (e: Exception) {
            recorder = null
            false
        }
    }

    /** Stops recording; returns the file path when one was saved. */
    fun stopRecording(): String? {
        val rec = recorder ?: return null
        recorder = null
        isRecording = false
        val path = recordingPath
        recordingPath = null
        try { rec.stop() } catch (e: Exception) {}
        try { rec.release() } catch (e: Exception) {}
        try { recordDisplay?.release() } catch (e: Exception) {}
        recordDisplay = null
        return path
    }

    override fun onDestroy() {
        stopRecording()
        instance = null
        synchronized(lock) {
            lastFrame?.recycle()
            lastFrame = null
        }
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        thread?.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("AutoTap")
            .setContentText("Image / রঙ ধাপের জন্য স্ক্রিন দেখা চালু আছে")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 43
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        private val lock = Any()

        @Volatile
        private var lastFrame: Bitmap? = null

        fun startRecording(): Boolean = instance?.startRecording() ?: false

        fun stopRecording(): String? = instance?.stopRecording()

        fun isRecording(): Boolean = instance?.isRecording == true

        /** A private copy of the newest frame, or null if capture is not running. */
        fun snapshot(): Bitmap? = synchronized(lock) {
            val f = lastFrame ?: return null
            try { f.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { null }
        }

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }
}
