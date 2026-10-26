package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.telephony.TelephonyManager
import java.security.MessageDigest

/**
 * PhoneLink v2.24.0 (Lab merge): the Lab v1.5.0 action helpers
 * that still live here — torch level, installer intent, screen
 * pin, and the Shizuku privileged actions (reboot, perm grant /
 * revoke). Everything else Lab v1.5.0 implemented here moved to
 * PhoneLink's own implementations instead of being duplicated:
 * peer relay -> Relay, redial -> Redial, MQTT -> MqttManager,
 * QR show -> QrShow + QrShowActivity, app lock -> AppLocker.
 * Lost mode and AutoTap control are NOT here: lost-tracking is
 * not ported in this merge, and the AutoTap engine is not wired
 * into Flow (it stays rule-side); both types are absent from
 * FlowCatalog, so a flow can never mistake them for working.
 */
object FlowNewActions {

    // ---------------- peer relay (ntfy.sh, PhoneLink-compatible topics) ----------------

    // ---------------- redial until answered (Lab-native port) ----------------

    // ---------------- app lock (set + PIN gate + a11y enforcement) ----------------

    // ---------------- lost mode (flag only — PARTIAL) ----------------

    // ---------------- MQTT publish (one-shot Paho client) ----------------

    // ---------------- QR show (generation; scan already exists) ----------------

    /** Holder for the QR text, mirroring PhoneLink's QrShow slot. */

    // ---------------- torch level (API 33+ strength, else on/off) ----------------

    fun runTorchLevel(ctx: Context, param: String) {
        val pct = param.trim().toIntOrNull() ?: 100
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            }
            if (id == null) {
                FlowStore.log(ctx, "torch_level: no flash camera")
                return
            }
            if (pct <= 0) {
                cm.setTorchMode(id, false)
                FlowStore.log(ctx, "torch_level: off")
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val max = cm.getCameraCharacteristics(id).get(
                    android.hardware.camera2.CameraCharacteristics
                        .FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                )
                if (max != null && max > 1) {
                    val level = (pct.coerceIn(1, 100) * max / 100).coerceIn(1, max)
                    cm.turnOnTorchWithStrengthLevel(id, level)
                    FlowStore.log(ctx, "torch_level: " + pct + "%")
                    return
                }
            }
            cm.setTorchMode(id, true)
            FlowStore.log(ctx, "torch_level: strength unsupported, torch on")
        } catch (e: Exception) {
            FlowStore.log(ctx, "torch_level FAILED: " + (e.message ?: ""))
        }
    }

    // ---------------- install via system installer (not silent) ----------------

    /**
     * install_app: user-visible installer intent. This is NOT
     * install_silent (Shizuku pm install, already shipped) — the
     * system installer shows its normal confirmation screen.
     * Param is a Lab file name or absolute path.
     */
    fun runInstallApp(ctx: Context, param: String) {
        val p = param.trim()
        if (p.isEmpty()) {
            FlowStore.log(ctx, "install_app: empty path")
            return
        }
        val f = if (p.startsWith("/")) {
            java.io.File(p)
        } else {
            java.io.File(java.io.File(ctx.filesDir, "flowfiles"), p)
        }
        if (!f.exists()) {
            FlowStore.log(ctx, "install_app: file not found: " + p)
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".files", f
            )
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
        } catch (e: Exception) {
            FlowStore.log(ctx, "install_app FAILED: " + (e.message ?: ""))
        }
    }

    // ---------------- Shizuku privileged actions ----------------

    fun runReboot(ctx: Context, param: String) {
        val cmd = when (param.trim().lowercase()) {
            "recovery" -> "reboot recovery"
            "bootloader" -> "reboot bootloader"
            "off" -> "reboot -p"
            else -> "reboot"
        }
        val out = ShizukuHelper.run(cmd)
        if (ShizukuHelper.lastError != null) {
            FlowStore.log(ctx, "reboot FAILED: " + ShizukuHelper.lastError)
        } else {
            FlowStore.log(ctx, "reboot: " + cmd + " " + out.take(100))
        }
    }

    fun runPerm(ctx: Context, param: String, grant: Boolean) {
        val parts = param.split("|").map { it.trim() }
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            FlowStore.log(ctx, "perm: need pkg|permission")
            return
        }
        val verb = if (grant) "grant" else "revoke"
        ShizukuHelper.run("pm " + verb + " " + parts[0] + " " + parts[1])
        if (ShizukuHelper.lastError != null) {
            FlowStore.log(ctx, "perm " + verb + " FAILED: " + ShizukuHelper.lastError)
        } else {
            FlowStore.log(ctx, "perm " + verb + ": " + parts[0] + " " + parts[1])
        }
    }

    // ---------------- screen pin / unpin ----------------

    /**
     * Screen pinning needs a foreground activity (startLockTask
     * pins the calling activity's task), so both actions go
     * through FlowPinActivity. First pin shows the system
     * confirmation prompt; that is Android behavior, not a bug.
     */
    fun runPin(ctx: Context, pin: Boolean) {
        try {
            val i = Intent(ctx, FlowPinActivity::class.java)
            i.putExtra("unpin", !pin)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            FlowStore.log(ctx, "pin FAILED: " + (e.message ?: ""))
        }
    }

    // ---------------- AutoTap control (PARTIAL — engine not ported) ----------------
}
