package io.github.epv.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Context
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max

/** Exact RGB bytes embedded in PNG pixels (no PNG custom chunks, no EXIF). */
object PngCodec {
    fun write(payload: ByteArray, target: OutputStream) {
        val width = listOf(512, 1024, 2048).firstOrNull { width ->
            val h = max(if (width == 512) 512 else 256, (payload.size + width * 3 - 1) / (width * 3))
            h <= width
        } ?: error("Part does not fit RGB PNG")
        val height = max(if (width == 512) 512 else 256, (payload.size + width * 3 - 1) / (width * 3))
        val pixels = ByteArray(width * height * 3)
        payload.copyInto(pixels)
        val pad = EpvCore.random(pixels.size - payload.size)
        pad.copyInto(pixels, payload.size)
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val j = i * 3
            val r = pixels[j].toInt() and 255
            val g = pixels[j + 1].toInt() and 255
            val b = pixels[j + 2].toInt() and 255
            argb[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(argb, 0, width, 0, 0, width, height)
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, target)) { "PNG write failed" }
        } finally { bitmap.recycle() }
    }

    fun read(context: Context, uri: Uri): ByteArray {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Cannot decode PNG: $uri")
        try {
            require(bitmap.width.toLong() * bitmap.height <= 2048L * 2048L) { "PNG too large" }
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val raw = ByteArray(argb.size * 3)
            for (i in argb.indices) {
                val p = argb[i]
                raw[i * 3] = (p shr 16).toByte()
                raw[i * 3 + 1] = (p shr 8).toByte()
                raw[i * 3 + 2] = p.toByte()
            }
            return raw
        } finally { bitmap.recycle() }
    }
}
