package com.mthtamim.phonelink

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * v1.2.0 camera / media actions. No new dependency, no FileProvider:
 * photo and video go through a MediaStore output Uri (Android 10+),
 * so the camera app saves the full file without any result callback
 * (actions run from background threads with no Activity). Audio uses
 * MediaRecorder into app storage. Every failure degrades to a clear
 * run-log message, never a crash.
 */
object FlowMedia {

    /** ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE with a MediaStore output file. */
    fun capture(ctx: Context, video: Boolean) {
        if (Build.VERSION.SDK_INT < 29) {
            FlowStore.log(ctx, ctx.getString(R.string.flow_media_old))
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FlowStore.log(ctx, "camera permission missing")
            return
        }
        try {
            val stamp = System.currentTimeMillis().toString()
            val values = ContentValues()
            if (video) {
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "flow_" + stamp + ".mp4")
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PhoneLink")
            } else {
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "flow_" + stamp + ".jpg")
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhoneLink")
            }
            val collection = if (video) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri: Uri? = ctx.contentResolver.insert(collection, values)
            if (uri == null) {
                FlowStore.log(ctx, "media store insert failed")
                return
            }
            val intent = Intent(
                if (video) android.provider.MediaStore.ACTION_VIDEO_CAPTURE
                else android.provider.MediaStore.ACTION_IMAGE_CAPTURE
            )
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                FlowStore.log(ctx, ctx.getString(R.string.flow_no_camera))
                return
            }
            if (video) {
                FlowStore.setVar(ctx, "video_uri", uri.toString())
                FlowStore.log(ctx, ctx.getString(R.string.flow_video_saved, uri.toString()))
            } else {
                FlowStore.setVar(ctx, "photo_uri", uri.toString())
                FlowStore.log(ctx, ctx.getString(R.string.flow_photo_saved, uri.toString()))
            }
        } catch (e: Exception) {
            FlowStore.log(ctx, "capture error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------- audio recording ----------------

    @Volatile
    private var recorder: MediaRecorder? = null

    @Volatile
    private var audioFile: File? = null

    /**
     * Param: "stop" stops; anything else starts ("start" or
     * "start|name"). File lands in filesDir/lab_audio/.
     */
    fun audio(ctx: Context, param: String) {
        val p = param.trim()
        if (p == "stop") {
            stopAudio(ctx)
            return
        }
        if (recorder != null) {
            FlowStore.log(ctx, ctx.getString(R.string.flow_audio_busy))
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FlowStore.log(ctx, ctx.getString(R.string.flow_audio_need_mic))
            return
        }
        try {
            val name = p.substringAfter("start", "").trimStart('|').trim()
                .ifEmpty { "audio_" + System.currentTimeMillis().toString() }
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val dir = File(ctx.filesDir, "flow_audio")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, if (safe.endsWith(".m4a")) safe else safe + ".m4a")
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            audioFile = file
            FlowStore.setVar(ctx, "audio_path", file.absolutePath)
            FlowStore.log(ctx, ctx.getString(R.string.flow_audio_started, file.absolutePath))
        } catch (e: Exception) {
            FlowStore.log(ctx, "record_audio error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun stopAudio(ctx: Context) {
        val rec = recorder
        val file = audioFile
        recorder = null
        audioFile = null
        if (rec == null || file == null) {
            FlowStore.log(ctx, ctx.getString(R.string.flow_audio_not_rec))
            return
        }
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
        FlowStore.setVar(ctx, "audio_path", file.absolutePath)
        FlowStore.log(ctx, ctx.getString(R.string.flow_audio_saved, file.absolutePath))
    }
}
