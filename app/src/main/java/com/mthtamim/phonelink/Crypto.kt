package com.mthtamim.phonelink

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for relay payloads. The AES-256 key is derived from
 * the pair code, so the public relay only ever sees ciphertext; anyone
 * without the code cannot read a message even if they find the topic.
 * Wire format: {"enc":1,"data":"<base64 of IV(12) + ciphertext+tag>"}
 */
object Crypto {

    private fun key(ctx: Context): SecretKeySpec {
        val material = "phonelink-enc-v1-" + Config.pairCode(ctx).trim().uppercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encrypt(ctx: Context, plain: String): JSONObject {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = iv + ct
        return JSONObject().apply {
            put("enc", 1)
            put("data", Base64.encodeToString(packed, Base64.NO_WRAP))
        }
    }

    /** Returns the decrypted payload JSON, or null when it cannot be read. */
    fun decrypt(ctx: Context, raw: String): JSONObject? {
        return try {
            val outer = JSONObject(raw)
            if (outer.optInt("enc", 0) != 1) return JSONObject(raw) // plain payload
            val packed = Base64.decode(outer.getString("data"), Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val ct = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun pinHash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("phonelink-pin-v1-" + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
