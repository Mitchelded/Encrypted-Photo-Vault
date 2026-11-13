package io.github.epv.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully offline local classification API.
 * Model: GantMan nsfw_model MobileNetV2, 224x224 float RGB [0..1].
 * Output classes: drawings, hentai, neutral, porn, sexy.
 *
 * No network permission, no image upload, no external service.
 * Score is a probability estimate, NOT a statement of fact.
 */
class LocalNsfwClassifier(context: Context) : Closeable {
    private val app = context.applicationContext
    private val interpreter: Interpreter
    private val lock = Any()

    init {
        val bytes = try {
            app.assets.open("nsfw.tflite").use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException("Не найдена локальная модель nsfw.tflite в APK", e)
        }
        require(bytes.size > 1_000_000) { "Некорректная локальная модель" }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).rewind()
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 224, 224, 3)) &&
            interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 5)) &&
            interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 &&
            interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32) {
            "Неожиданный формат модели; ожидается MobileNetV2 (1,224,224,3) → (1,5)"
        }
    }

    /** Estimate NSFW probability from an in-memory bitmap. Never writes plaintext. */
    fun score(bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        try {
            val pixels = IntArray(224 * 224)
            resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            val input = ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
            for (px in pixels) {
                input.putFloat(((px ushr 16) and 255) / 255f)
                input.putFloat(((px ushr 8) and 255) / 255f)
                input.putFloat((px and 255) / 255f)
            }
            input.rewind()
            val output = Array(1) { FloatArray(5) }
            synchronized(lock) { interpreter.run(input, output) }
            // class order: drawings, hentai, neutral, porn, sexy
            val v = output[0][1] + output[0][3] + output[0][4]
            require(v.isFinite()) { "Неверный результат модели" }
            return v.coerceIn(0f, 1f)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    /** Score image or three representative video frames. Unsupported types are ignored. */
    fun scoreMedia(uri: Uri): Float? {
        val mime = app.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("image/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                var factor = 1
                while (bounds.outWidth / factor > 640 || bounds.outHeight / factor > 640) factor *= 2
                inSampleSize = factor
            }
            val bitmap = app.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            return try { score(bitmap) } finally { bitmap.recycle() }
        }
        if (mime.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val sampleTimes = listOf(0L, durationMs / 2L, durationMs * 9L / 10L).distinct()
                var best: Float? = null
                for (ms in sampleTimes) {
                    val frame = retriever.getFrameAtTime(ms * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    try { best = maxOf(best ?: 0f, score(frame)) }
                    finally { frame.recycle() }
                }
                return best
            } finally { retriever.release() }
        }
        return null
    }

    override fun close() { synchronized(lock) { interpreter.close() } }
}
