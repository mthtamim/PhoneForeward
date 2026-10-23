package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** App install / uninstall events (needs its own receiver because the
 *  intent-filter carries a package data scheme, unlike boot/battery). */
class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        // v2.18 App Locker: auto-lock fresh installs — independent
        // of the forwarding toggles below (it is a local feature).
        if (intent.action == Intent.ACTION_PACKAGE_ADDED &&
            !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            val newPkg = intent.data?.schemeSpecificPart
            if (!newPkg.isNullOrEmpty() && newPkg != ctx.packageName) {
                try { AppLocker.onPackageAdded(ctx, newPkg) }
                catch (_: Exception) { }
            }
        }
        if (!Config.isSender(ctx) || !Config.fwdApps(ctx)) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (pkg == ctx.packageName) return
                SecLog.add(ctx, "অ্যাপ ইনস্টল: $pkg")
                Relay.send(ctx, Relay.TYPE_APP, "নতুন অ্যাপ ইনস্টল",
                    "${Contacts.appLabel(ctx, pkg)} ($pkg)")
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                SecLog.add(ctx, "অ্যাপ আনইনস্টল: $pkg")
                Relay.send(ctx, Relay.TYPE_APP, "অ্যাপ আনইনস্টল", pkg)
            }
        }
    }
}
