package com.autotap.app

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Coarse template matching over a screenshot: both images are downscaled,
 * then the template is slid over the search region comparing sampled pixels.
 * Good enough for distinct icons / buttons; not a computer-vision library.
 */
object ImageSearch {
    fun find(screen: Bitmap, template: Bitmap, regionPreset: Int, maxDiff: Double = 26.0): IntArray? {
        val scale = 400f / screen.width.coerceAtLeast(1)
        val sw = (screen.width * scale).toInt().coerceAtLeast(1)
        val sh = (screen.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(screen, sw, sh, true)
        val tw0 = (template.width * scale).toInt()
        val th0 = (template.height * scale).toInt()
        if (tw0 < 6 || th0 < 6 || tw0 >= sw || th0 >= sh) return null
        val tpl = Bitmap.createScaledBitmap(template, tw0, th0, true)
        val sPix = IntArray(sw * sh)
        small.getPixels(sPix, 0, sw, 0, 0, sw, sh)
        val tPix = IntArray(tw0 * th0)
        tpl.getPixels(tPix, 0, tw0, 0, 0, tw0, th0)
        val region = Region.bounds(regionPreset, screen.width, screen.height)
        val rx0 = (region[0] * scale).toInt()
        val ry0 = (region[1] * scale).toInt()
        val rx1 = (region[2] * scale).toInt() - tw0
        val ry1 = (region[3] * scale).toInt() - th0
        if (rx1 < rx0 || ry1 < ry0) return null
        val sampleStepX = (tw0 / 8).coerceAtLeast(1)
        val sampleStepY = (th0 / 8).coerceAtLeast(1)
        var bestScore = Double.MAX_VALUE
        var bestX = -1
        var bestY = -1
        var y = ry0
        while (y <= ry1) {
            var x = rx0
            while (x <= rx1) {
                var diff = 0L
                var samples = 0
                var ty = 0
                while (ty < th0) {
                    var tx = 0
                    while (tx < tw0) {
                        val s = sPix[(y + ty) * sw + (x + tx)]
                        val t = tPix[ty * tw0 + tx]
                        diff += abs(((s shr 16) and 0xFF) - ((t shr 16) and 0xFF))
                        diff += abs(((s shr 8) and 0xFF) - ((t shr 8) and 0xFF))
                        diff += abs((s and 0xFF) - (t and 0xFF))
                        samples++
                        tx += sampleStepX
                    }
                    ty += sampleStepY
                }
                val avg = diff.toDouble() / (samples * 3.0)
                if (avg < bestScore) {
                    bestScore = avg
                    bestX = x
                    bestY = y
                }
                x += 3
            }
            y += 3
        }
        // Average channel difference below ~26 counts as the same picture.
        if (bestX < 0 || bestScore > maxDiff) return null
        val cx = ((bestX + tw0 / 2) / scale).toInt()
        val cy = ((bestY + th0 / 2) / scale).toInt()
        return intArrayOf(cx, cy)
    }
}
