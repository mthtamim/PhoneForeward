package com.mthtamim.phonelink

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** v2.16: hand-off slot for the qr_show automation action. */
object QrShow {
    @Volatile var text: String = ""
}

/** Shows an arbitrary text/Wi-Fi payload as a big QR code
 *  (qr_show action). Programmatic UI — no layout file needed.
 *  Tap anywhere to close. */
class QrShowActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = QrShow.text
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        val iv = ImageView(this)
        try {
            val matrix = MultiFormatWriter().encode(
                text, BarcodeFormat.QR_CODE, 720, 720)
            val bmp = Bitmap.createBitmap(720, 720, Bitmap.Config.RGB_565)
            for (x in 0 until 720) for (y in 0 until 720) {
                bmp.setPixel(x, y, if (matrix.get(x, y))
                    Color.BLACK else Color.WHITE)
            }
            iv.setImageBitmap(bmp)
        } catch (_: Exception) { }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        root.addView(iv)
        root.addView(tv)
        root.setOnClickListener { finish() }
        setContentView(root)
    }
}
