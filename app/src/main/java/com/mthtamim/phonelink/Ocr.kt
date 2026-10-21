package com.mthtamim.phonelink

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * v2.11: on-device OCR (ML Kit). Used on images this phone sends and on
 * chunk-delivered images it receives; any text found is filed as an
 * inbox note so it is searchable like everything else.
 */
object Ocr {

    fun scanBitmap(ctx: Context, bmp: Bitmap, onText: (String) -> Unit) {
        try {
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bmp, 0)
            client.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    if (text.isNotEmpty()) onText(text.take(1500))
                    client.close()
                }
                .addOnFailureListener { client.close() }
        } catch (_: Exception) { }
    }
}
