package com.mthtamim.phonelink

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** Shows this phone's pair code as a QR code for easy pairing. */
class QrActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)
        val code = Config.pairCode(this)
        findViewById<TextView>(R.id.tvQrCode).text = code.chunked(5).joinToString(" ")
        try {
            val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 720, 720)
            val bmp = Bitmap.createBitmap(720, 720, Bitmap.Config.RGB_565)
            for (x in 0 until 720) {
                for (y in 0 until 720) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            findViewById<ImageView>(R.id.ivQr).setImageBitmap(bmp)
        } catch (_: Exception) { }
        findViewById<Button>(R.id.btnQrClose).setOnClickListener { finish() }
    }
}
