package com.mthtamim.phonelink

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

object Contacts {
    /** Best-effort number -> contact name. Returns null without permission. */
    fun nameForNumber(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    fun labelForNumber(ctx: Context, number: String?): String {
        if (number.isNullOrBlank()) return "অজানা নম্বর"
        val name = nameForNumber(ctx, number)
        return if (name != null) "$name ($number)" else number
    }

    fun appLabel(ctx: Context, pkg: String): String {
        return try {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) { pkg }
    }
}
