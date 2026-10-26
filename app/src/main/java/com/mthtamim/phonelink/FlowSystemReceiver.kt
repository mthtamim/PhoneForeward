package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * v1.4.0 system receiver: one class for the manifest-declared
 * broadcasts that cannot come from dynamic registration (or that
 * must arrive while the app is closed):
 * - ACTION_SHUTDOWN -> device_shutdown
 * - ACTION_WALLPAPER_CHANGED -> wallpaper_changed
 * - MEDIA_MOUNTED / MEDIA_UNMOUNTED (data scheme "file") ->
 *   storage_mounted {mounted}
 * - WAP_PUSH_RECEIVED (mimeType application/vnd.wap.mms-message,
 *   RECEIVE_MMS) -> mms_received; the push is detected only, the
 *   MMS content is never downloaded (documented limit).
 * - Custom Lab actions: lab_sms_sent / lab_sms_delivered (from
 *   sendSms delivery intents), lab_alarm (from the set_alarm
 *   AlarmManager alarm — Lab-set alarms only), lab_proximity
 *   (LocationManager proximity alerts -> geofence_in/out),
 *   lab_activity (Play Services activity recognition result).
 */
class FlowSystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_SHUTDOWN ->
                    FlowEngine.onEvent(ctx, "device_shutdown", JSONObject())
                Intent.ACTION_WALLPAPER_CHANGED ->
                    FlowEngine.onEvent(ctx, "wallpaper_changed", JSONObject())
                Intent.ACTION_MEDIA_MOUNTED -> {
                    val data = JSONObject()
                    data.put("mounted", "true")
                    FlowEngine.onEvent(ctx, "storage_mounted", data)
                }
                Intent.ACTION_MEDIA_UNMOUNTED -> {
                    val data = JSONObject()
                    data.put("mounted", "false")
                    FlowEngine.onEvent(ctx, "storage_mounted", data)
                }
                "android.provider.Telephony.WAP_PUSH_RECEIVED" -> {
                    val data = JSONObject()
                    data.put("title", "mms")
                    FlowEngine.onEvent(ctx, "mms_received", data)
                }
                "flow_sms_sent" -> {
                    val data = JSONObject()
                    data.put("title", intent.getStringExtra("number") ?: "")
                    data.put("body", intent.getStringExtra("number") ?: "")
                    FlowEngine.onEvent(ctx, "sms_sent", data)
                }
                "flow_sms_delivered" -> {
                    val data = JSONObject()
                    data.put("title", intent.getStringExtra("number") ?: "")
                    data.put("body", intent.getStringExtra("number") ?: "")
                    FlowEngine.onEvent(ctx, "sms_delivered", data)
                }
                "flow_alarm" -> {
                    val data = JSONObject()
                    data.put("title", intent.getStringExtra("time") ?: "")
                    FlowEngine.onEvent(ctx, "alarm", data)
                }
                "flow_proximity" -> {
                    val entering = intent.getBooleanExtra(
                        android.location.LocationManager.KEY_PROXIMITY_ENTERING, false
                    )
                    FlowEngine.onEvent(
                        ctx, if (entering) "geofence_in" else "geofence_out",
                        JSONObject()
                    )
                }
                "flow_activity" -> {
                    val result = com.google.android.gms.location.ActivityRecognitionResult
                        .extractResult(intent)
                    val activity = result?.mostProbableActivity
                    if (activity != null) {
                        val data = JSONObject()
                        data.put("kind", activityName(activity.type))
                        FlowEngine.onEvent(ctx, "activity_recognition", data)
                    }
                }
                else -> {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun activityName(type: Int): String {
        return when (type) {
            com.google.android.gms.location.DetectedActivity.IN_VEHICLE -> "vehicle"
            com.google.android.gms.location.DetectedActivity.ON_BICYCLE -> "bicycle"
            com.google.android.gms.location.DetectedActivity.ON_FOOT -> "foot"
            com.google.android.gms.location.DetectedActivity.RUNNING -> "running"
            com.google.android.gms.location.DetectedActivity.STILL -> "still"
            com.google.android.gms.location.DetectedActivity.TILTING -> "tilting"
            com.google.android.gms.location.DetectedActivity.WALKING -> "walking"
            else -> "unknown"
        }
    }
}
