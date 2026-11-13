package io.github.epv.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Uses ONLY http://127.0.0.1:8765 through an ADB reverse USB tunnel.
 * The PC receives at most three 224px JPEG thumbnails per original.
 */
class PcNsfwClient(private val context: Context, private val secret: String) : Closeable {
    companion object {
        private const val BASE = "http://127.0.0.1:8765"
        const val MAX_BATCH = 16
    }

    private val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "EPV-Preview").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    init { require(secret.length >= 12) { "Введите код ПК из окна сервера (от 12 символов)" } }

    private fun connection(path: String, method: String): HttpURLConnection {
        check(!closed) { "PC client closed" }
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("X-EPV-Token", secret)
        connection.setRequestProperty("Cache-Control", "no-store")
        connection.connectTimeout = 5_000
        connection.readTimeout = 50_000
        connection.useCaches = false
        return connection
    }

    fun checkConnection() {
        val connection = connection("/v1/health", "GET")
        try {
            if (connection.responseCode != 200)
                throw IOException("PC server rejected token (HTTP " + connection.responseCode + ")")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("ok")) throw IOException("Unexpected PC response")
        } catch (e: IOException) {
            throw IOException("Нет соединения с ПК. Проверьте adb reverse, сервер и код. " + e.localizedMessage, e)
        } finally { connection.disconnect() }
    }

    private fun jpeg(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        try {
            val bytes = ByteArrayOutputStream(16 * 1024)
            check(small.compress(Bitmap.CompressFormat.JPEG, 78, bytes)) { "Cannot encode preview" }
            val raw = bytes.toByteArray()
            require(raw.size <= 180_000) { "JPEG preview too large" }
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        } finally { if (small !== bitmap) small.recycle() }
    }

    private fun frames(uri: Uri): List<String> {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        val image = mime.startsWith("image/") ||
            extension in setOf("jpg","jpeg","png","webp","heic","heif","bmp","gif","avif")
        val video = mime.startsWith("video/") ||
            extension in setOf("mp4","mkv","m4v","mov","avi","webm","3gp")
        if (image) {
            // ImageDecoder requests final dimensions in one provider read.
            // The older BitmapFactory path opens every original twice
            // (bounds + pixels), a bottleneck for 10,000 SAF documents.
            val preview = try {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(resolver, uri)
                ) { decoder, _, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.setTargetSize(224, 224)
                }
            } catch (_: Exception) { null }
            if (preview != null) {
                return try { listOf(jpeg(preview)) } finally { preview.recycle() }
            }
            // Fallback for document providers not supported by ImageDecoder.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()
            val options = BitmapFactory.Options().apply {
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512)
                    sample *= 2
                inSampleSize = sample
            }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return emptyList()
            return try { listOf(jpeg(decoded)) } finally { decoded.recycle() }
        }
        if (video) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val times = listOf(0L, duration / 2L, duration * 9L / 10L).distinct()
                val result = ArrayList<String>(times.size)
                for (ms in times) {
                    val bitmap = retriever.getScaledFrameAtTime(
                        ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 224, 224
                    ) ?: continue
                    try { result.add(jpeg(bitmap)) } finally { bitmap.recycle() }
                }
                return result
            } finally { retriever.release() }
        }
        return emptyList()
    }

    fun scoreBatch(uris: List<Uri>, cancelled: () -> Unit): List<Float?> {
        require(uris.size in 1..MAX_BATCH)
        val futures = uris.map { uri ->
            pool.submit<List<String>> {
                try { frames(uri) } catch (_: Exception) { emptyList() }
            }
        }
        val prepared = ArrayList<List<String>>()
        try {
            for (future in futures) {
                while (true) {
                    cancelled()
                    try {
                        prepared.add(future.get(200, TimeUnit.MILLISECONDS))
                        break
                    } catch (_: TimeoutException) { /* poll cancellation */ }
                }
            }
        } catch (t: Throwable) {
            futures.forEach { it.cancel(true) }
            throw t
        }
        cancelled()
        val items = JSONArray()
        for (frames in prepared) {
            val arr = JSONArray()
            frames.forEach { arr.put(it) }
            items.put(JSONObject().put("frames", arr))
        }
        val payload = JSONObject().put("items", items)
            .toString().toByteArray(Charsets.UTF_8)
        require(payload.size < 8 * 1024 * 1024) { "Too many thumbnails" }
        val conn = connection("/v1/classify", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            cancelled()
            if (conn.responseCode != 200)
                throw IOException("PC returned HTTP " + conn.responseCode)
            val result = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .getJSONArray("scores")
            if (result.length() != uris.size) throw IOException("PC returned invalid number of results")
            return List(result.length()) { index ->
                if (result.isNull(index)) null
                else result.getDouble(index).toFloat().also {
                    if (!it.isFinite() || it !in 0f..1f) throw IOException("PC score invalid")
                }
            }
        } catch (e: IOException) {
            throw IOException("Соединение с ПК прервано, прогресс сохранён. " + e.localizedMessage, e)
        } finally { conn.disconnect() }
    }

    fun scoreSingle(uri: Uri, cancelled: () -> Unit): Float? =
        scoreBatch(listOf(uri), cancelled).first()

    override fun close() {
        closed = true
        pool.shutdownNow()
    }
}
