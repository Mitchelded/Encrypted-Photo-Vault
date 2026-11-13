package io.github.epv.vault

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.util.Arrays

/**
 * Read decrypted bytes on demand *without* writing a plaintext video to disk.
 * Works with Android MediaPlayer via MediaDataSource and BitmapFactory via InputStream.
 * The original PNGs must be present locally; this class does not fetch from Google Photos.
 */
class EpvMediaDataSource(
    private val context: Context,
    private val chunks: List<Pair<Uri, EpvCore.Header>>,
    password: String
) : MediaDataSource() {
    val filename: String
    val mime: String
    private val length: Long
    private val metadata: String
    private val key: ByteArray
    private var closed = false

    // Avoid repeatedly decrypting the same 8 MiB while a video decoder seeks.
    private val cache = object : LinkedHashMap<Int, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean {
            if (size > 2) {
                eldest?.value?.fill(0)
                return true
            }
            return false
        }
    }

    init {
        require(chunks.isNotEmpty()) { "No vault parts" }
        val first = chunks[0].second
        require(chunks.size == first.partCount &&
                chunks.map { it.second.partIndex }.toSet().size == first.partCount &&
                chunks.all { Arrays.equals(it.second.fileId, first.fileId) &&
                    Arrays.equals(it.second.salt, first.salt) && it.second.partCount == first.partCount }) {
            "Incomplete or inconsistent set of vault PNGs"
        }
        key = EpvCore.deriveKey(password, first.salt)
        try {
            val decoded = decode(0)
            metadata = decoded.metadataJson
            val info = JSONObject(metadata)
            filename = info.getString("n")
            mime = info.optString("m", "application/octet-stream")
            length = info.getLong("s")
            require(length >= 0) { "Negative file size" }
            val minParts = maxOf(1L, (length + EpvCore.CHUNK_SIZE - 1) / EpvCore.CHUNK_SIZE)
            require(minParts == chunks.size.toLong()) { "Original file size does not match part count" }
            cache[0] = decoded.data
        } catch (t: Throwable) {
            key.fill(0)
            throw t
        }
    }

    private fun decode(index: Int): EpvCore.Part {
        val (uri, expected) = chunks[index]
        val raw = PngCodec.read(context, uri)
        val actual = EpvCore.readHeader(raw)
        require(actual.partIndex == index && Arrays.equals(actual.raw, expected.raw)) {
            "EPV1 header changed"
        }
        return EpvCore.decryptPart(key, raw)
    }

    @Synchronized
    private fun load(index: Int): ByteArray {
        check(!closed) { "Vault source is closed" }
        cache[index]?.let { return it }
        val decoded = decode(index)
        require(decoded.metadataJson == metadata) { "Encrypted metadata differs" }
        if (index < chunks.lastIndex) require(decoded.data.size == EpvCore.CHUNK_SIZE) {
            "Invalid nonfinal chunk length"
        }
        cache[index] = decoded.data
        return decoded.data
    }

    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || offset < 0 || size < 0 || offset.toLong() + size > buffer.size)
            throw IOException("Invalid read request")
        if (size == 0) return 0
        if (position >= length) return -1
        var copied = 0
        val wanted = minOf(size.toLong(), length - position).toInt()
        while (copied < wanted) {
            val at = position + copied
            val index = (at / EpvCore.CHUNK_SIZE).toInt()
            val inChunk = (at % EpvCore.CHUNK_SIZE).toInt()
            val data = load(index)
            val available = minOf(wanted - copied, data.size - inChunk)
            if (available <= 0) throw IOException("Truncated decrypted EPV chunk")
            System.arraycopy(data, inChunk, buffer, offset + copied, available)
            copied += available
        }
        return copied
    }

    fun inputStream(): InputStream = object : InputStream() {
        private var position = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 255
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = this@EpvMediaDataSource.readAt(position, b, off, len)
            if (n > 0) position += n
            return n
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        cache.values.forEach { it.fill(0) }
        cache.clear()
    }
}
