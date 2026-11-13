package io.github.epv.vault

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Arrays
import kotlin.math.max

/** SAF read, MediaStore write. EPV1 PNGs are indexed under Pictures/EncryptedPhotoVault. */
class VaultRepository(private val context: Context) {
    private val resolver = context.contentResolver
    data class Item(val id: String, val parts: Int, val expected: Int, val name: String?,
                    val mime: String? = null, val size: Long? = null)

    private fun nameOf(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) return cur.getString(0) ?: "file.bin"
        }
        return "file.bin"
    }

    private fun readChunk(stream: InputStream, maxBytes: Int): ByteArray {
        val result = ByteArrayOutputStream(maxBytes)
        val block = ByteArray(64 * 1024)
        while (result.size() < maxBytes) {
            val n = stream.read(block, 0, minOf(block.size, maxBytes - result.size()))
            if (n < 0) break
            if (n == 0) continue
            result.write(block, 0, n)
        }
        return result.toByteArray()
    }

    private fun shaHex(digest: MessageDigest): String = EpvCore.hex(digest.digest())

    fun encryptOne(uri: Uri, password: String, onProgress: (Int, Int) -> Unit): Int {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                md.update(buffer, 0, n)
                size += n
            }
        } ?: error("Cannot open input file")
        val name = nameOf(uri).substringAfterLast('/').substringAfterLast('\\')
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val meta = JSONObject().put("n", name).put("s", size)
            .put("h", shaHex(md)).put("m", mime).toString()
        require(meta.toByteArray(Charsets.UTF_8).size <= EpvCore.MAX_METADATA) { "Filename too long" }
        val count = max(1, ((size + EpvCore.CHUNK_SIZE - 1) / EpvCore.CHUNK_SIZE).toInt())
        val fid = EpvCore.random(16)
        val salt = EpvCore.random(16)
        val key = EpvCore.deriveKey(password, salt)
        val created = mutableListOf<Uri>()
        try {
            resolver.openInputStream(uri)?.use { input ->
                for (index in 0 until count) {
                    val chunk = readChunk(input, EpvCore.CHUNK_SIZE)
                    val encrypted = EpvCore.encryptPart(key, fid, salt, index, count, meta, chunk)
                    val pngName = "EPV1_${EpvCore.hex(fid)}_${(index + 1).toString().padStart(6, '0')}.png"
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, pngName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/EncryptedPhotoVault/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        ?: error("Cannot create PNG in MediaStore")
                    created.add(target)
                    resolver.openOutputStream(target, "w")?.use { out -> PngCodec.write(encrypted, out) }
                        ?: error("Cannot open created PNG")
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(target, ready, null, null)
                    onProgress(index + 1, count)
                }
            } ?: error("Cannot reopen original file")
            return count
        } catch (t: Throwable) {
            created.forEach { resolver.delete(it, null, null) }
            throw t
        } finally { Arrays.fill(key, 0.toByte()) }
    }

    private data class EncodedPart(val uri: Uri, val header: EpvCore.Header)

    private fun collect(root: DocumentFile): Map<String, List<EncodedPart>> {
        val groups = linkedMapOf<String, MutableList<EncodedPart>>()
        fun visit(directory: DocumentFile, depth: Int) {
            require(depth <= 5) { "Folder nesting too deep" }
            for (file in directory.listFiles()) {
                if (file.isDirectory) { visit(file, depth + 1); continue }
                if (!file.isFile || !file.name.orEmpty().endsWith(".png", ignoreCase = true)) continue
                try {
                    val header = EpvCore.readHeader(PngCodec.read(context, file.uri))
                    groups.getOrPut(header.id()) { mutableListOf() }.add(EncodedPart(file.uri, header))
                } catch (_: Exception) { /* Ignore unrelated PNGs */ }
            }
        }
        visit(root, 0)
        return groups
    }

    fun inspect(rootUri: Uri): List<Item> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cannot access folder")
        return collect(root).map { (id, parts) ->
            Item(id, parts.map { it.header.partIndex }.distinct().size, parts.first().header.partCount, null)
        }
    }

    /** Unlock names and media types for a private gallery, without restoring to Downloads. */
    fun gallery(rootUri: Uri, password: String): List<Item> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cannot access folder")
        return collect(root).map { (id, parts) ->
            val count = parts.first().header.partCount
            if (parts.size != count) Item(id, parts.size, count, "Неполный набор")
            else try {
                val source = EpvMediaDataSource(context,
                    parts.sortedBy { it.header.partIndex }.map { it.uri to it.header }, password)
                val item = Item(id, parts.size, count, source.filename, source.mime, source.size)
                source.close()
                item
            } catch (e: Exception) { Item(id, parts.size, count, "Ошибка расшифровки") }
        }
    }

    /** A password-gated random-access source for in-memory photo and streamed video preview. */
    fun openMedia(rootUri: Uri, id: String, password: String): EpvMediaDataSource {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cannot access folder")
        val group = collect(root)[id] ?: error("File no longer in vault folder")
        return EpvMediaDataSource(context,
            group.sortedBy { it.header.partIndex }.map { it.uri to it.header }, password)
    }

    fun restore(rootUri: Uri, password: String, onProgress: (Int, Int) -> Unit): List<String> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cannot access folder")
        val groups = collect(root)
        require(groups.isNotEmpty()) { "EPV1 PNG files not found in selected folder" }
        val recovered = mutableListOf<String>()
        for ((id, entries) in groups) {
            val first = entries.first().header
            require(entries.size == first.partCount && entries.map { it.header.partIndex }.toSet().size == first.partCount) {
                "Missing or duplicate parts for $id (${entries.size}/${first.partCount})"
            }
            require(entries.all { Arrays.equals(it.header.salt, first.salt) &&
                    it.header.partCount == first.partCount && Arrays.equals(it.header.fileId, first.fileId) }) {
                "Inconsistent headers in $id"
            }
            val key = EpvCore.deriveKey(password, first.salt)
            var output: Uri? = null
            try {
                val ordered = entries.sortedBy { it.header.partIndex }
                val md = MessageDigest.getInstance("SHA-256")
                var totalSize = 0L
                var referenceMetadata: String? = null
                var originalName = ""
                var originalSize = 0L
                var originalSha = ""
                for ((index, partFile) in ordered.withIndex()) {
                    val raw = PngCodec.read(context, partFile.uri)
                    val part = EpvCore.decryptPart(key, raw)
                    if (referenceMetadata == null) {
                        referenceMetadata = part.metadataJson
                        val meta = JSONObject(referenceMetadata)
                        originalName = meta.getString("n")
                        originalSize = meta.getLong("s")
                        originalSha = meta.getString("h")
                        require(originalSize >= 0 && originalName.isNotBlank() &&
                                originalName == originalName.substringAfterLast('/').substringAfterLast('\\')) {
                            "Unsafe filename"
                        }
                        originalName = originalName.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim(' ', '.')
                        require(originalName.isNotEmpty()) { "Unsafe filename" }
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
                            put(MediaStore.MediaColumns.MIME_TYPE, meta.optString("m", "application/octet-stream"))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/EncryptedPhotoVault/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        output = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Cannot create restored file")
                    } else require(referenceMetadata == part.metadataJson) { "Metadata differs between parts" }
                    md.update(part.data)
                    totalSize += part.data.size
                    require(totalSize <= originalSize) { "Excess restored data" }
                    val mode = if (index == 0) "w" else "wa"
                    resolver.openOutputStream(output!!, mode)?.use { out -> out.write(part.data) }
                        ?: error("Cannot open output stream")
                    onProgress(index + 1, ordered.size)
                }
                require(totalSize == originalSize && shaHex(md) == originalSha) { "SHA-256 mismatch" }
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(output!!, ready, null, null)
                recovered.add(originalName)
            } catch (t: Throwable) {
                output?.let { resolver.delete(it, null, null) }
                throw t
            } finally { Arrays.fill(key, 0.toByte()) }
        }
        return recovered
    }
}
