package com.mthtamim.phonelink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * v1.3.0 CameraX photo capture entry point. Thin launcher only: the
 * real capture lives in FlowCameraActivity (no preview view, ImageCapture
 * bound to the activity lifecycle). Never crashes, never blocks the
 * calling thread — the activity does all CameraX work asynchronously.
 * The old intent-based FlowMedia.capture path stays as the fallback
 * when this returns false or the activity logs a bind/capture error.
 */
object FlowCamera {

    /**
     * facing: "front" selects the front lens, anything else the back
     * lens. flash: "on" / "off" / "auto", anything else means "off".
     * Returns false (with a run-log line) when CAMERA permission is
     * missing or the activity cannot be started.
     */
    fun captureOptions(ctx: Context, facing: String, flash: String): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FlowStore.log(ctx, "camera permission missing")
            return false
        }
        return try {
            val intent = Intent(ctx, FlowCameraActivity::class.java)
            intent.putExtra("facing", facing)
            intent.putExtra("flash", flash)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            FlowStore.log(ctx, "camera start error: " + (e.message ?: e.javaClass.simpleName))
            false
        }
    }
}
