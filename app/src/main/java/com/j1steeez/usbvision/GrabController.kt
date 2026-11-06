package com.j1steeez.usbvision

import android.graphics.Bitmap
import android.util.Base64
import android.view.TextureView
import java.io.ByteArrayOutputStream

/**
 * Pulls the current TextureView frame, scales the long edge to 1280, JPEG q85, Base64.
 */
class GrabController {
    fun grabJpegBase64(textureView: TextureView?): String? {
        val view = textureView ?: return null
        if (!view.isAvailable) return null

        val source = view.bitmap ?: return null
        return try {
            val scaled = scaleLongEdge(source, TARGET_LONG_EDGE)
            if (scaled !== source) {
                source.recycle()
            }
            val jpeg = compressJpeg(scaled, JPEG_QUALITY)
            scaled.recycle()
            Base64.encodeToString(jpeg, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleLongEdge(source: Bitmap, longEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val currentLong = maxOf(w, h)
        if (currentLong <= longEdge) return source
        val scale = longEdge.toFloat() / currentLong.toFloat()
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, tw, th, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    companion object {
        private const val TARGET_LONG_EDGE = 1280
        private const val JPEG_QUALITY = 85
    }
}
