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

    /** A folder-relative directory, never an absolute path or a traversal path. */
    private fun safeRelativeDirectory(raw: String): String {
        if (raw.isEmpty()) return ""
        val segments = raw.split('/')
        require(segments.size <= 32) { "Too many nested folders" }
        return segments.joinToString("/") { segment ->
            require(segment.isNotEmpty() && segment != "." && segment != "..") { "Unsafe folder path" }
            val clean = segment.replace(Regex("""[<>:"\\\\|?*\x00-\x1f]"""), "_").trim(' ', '.')
            require(clean.isNotEmpty() && clean != "." && clean != "..") { "Unsafe folder name" }
            clean.take(120)
        }.also { require(it.length <= 1024) { "Folder path too long" } }
    }

    data class BatchResult(val total: Int, val successful: Int, val pngCount: Int,
                           val failures: List<String>, val skipped: Int = 0) {
        val failed: Int get() = total - successful - skipped
    }

    /** Encrypt every regular file in a SAF tree, including nested folders.
     * Each original keeps its relative directory encrypted inside EPV metadata.
     * Never recurse into our own PNG output folder.
     */
    fun encryptFolder(rootUri: Uri, password: String,
                      onProgress: (Int, Int, String, Int, Int) -> Unit,
                      checkCancelled: () -> Unit = {},
                      shouldEncrypt: (Uri) -> Boolean = { true }): BatchResult {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Cannot access selected folder")
        require(root.isDirectory) { "Select a folder" }
        data class Source(val uri: Uri, val name: String, val relativeDir: String)
        val found = ArrayList<Source>()
        val visited = HashSet<String>()
        val encryptedPngName = Regex("EPV1_[0-9a-fA-F]{32}_[0-9]{6}\\.png", RegexOption.IGNORE_CASE)

        fun scan(dir: DocumentFile, relativeDir: String, depth: Int) {
            checkCancelled()
            require(depth <= 32) { "Selected folder exceeds 32 nested levels" }
            if (!visited.add(dir.uri.toString())) return
            for (child in dir.listFiles()) {
                checkCancelled()
                val childName = child.name.orEmpty()
                if (child.isDirectory) {
                    // Avoid backing up generated PNGs if Pictures/ is selected.
                    if (childName.equals("EncryptedPhotoVault", ignoreCase = true)) continue
                    val nextDir = safeRelativeDirectory(
                        if (relativeDir.isEmpty()) childName else "$relativeDir/$childName"
                    )
                    scan(child, nextDir, depth + 1)
                } else if (child.isFile && !encryptedPngName.matches(childName)) {
                    require(found.size < 100_000) { "Folder has more than 100,000 files" }
                    found.add(Source(child.uri, childName, relativeDir))
                }
            }
        }
        // Do not recursively encrypt the export folder itself.
        require(!root.name.equals("EncryptedPhotoVault", ignoreCase = true)) {
            "Please select the source folder, not Pictures/EncryptedPhotoVault"
        }
        scan(root, "", 0)
        var successful = 0
        var pngCount = 0
        var skipped = 0
        val errors = ArrayList<String>()
        for ((index, source) in found.withIndex()) {
            checkCancelled()
            try {
                onProgress(index + 1, found.size, source.name, 0, 0)
                if (!shouldEncrypt(source.uri)) { skipped++; continue }
                pngCount += encryptOne(source.uri, password, { part, count ->
                    checkCancelled()
                    onProgress(index + 1, found.size, source.name, part, count)
                }, source.relativeDir, checkCancelled)
                successful++
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (errors.size < 5) errors.add("${source.name}: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
        return BatchResult(found.size, successful, pngCount, errors, skipped)
    }

    fun encryptOne(uri: Uri, password: String, onProgress: (Int, Int) -> Unit,
                   relativeDirectory: String = "", checkCancelled: () -> Unit = {}): Int {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                md.update(buffer, 0, n)
                size += n
            }
        } ?: error("Cannot open input file")
        val name = nameOf(uri).substringAfterLast('/').substringAfterLast('\\')
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val metaObject = JSONObject().put("n", name).put("s", size)
            .put("h", shaHex(md)).put("m", mime)
        val safeDirectory = safeRelativeDirectory(relativeDirectory)
        if (safeDirectory.isNotEmpty()) metaObject.put("p", safeDirectory)
        val meta = metaObject.toString()
        require(meta.toByteArray(Charsets.UTF_8).size <= EpvCore.MAX_METADATA) { "Filename too long" }
        val count = max(1, ((size + EpvCore.CHUNK_SIZE - 1) / EpvCore.CHUNK_SIZE).toInt())
        val fid = EpvCore.random(16)
        val salt = EpvCore.random(16)
        val key = EpvCore.deriveKey(password, salt)
        val created = mutableListOf<Uri>()
        try {
            resolver.openInputStream(uri)?.use { input ->
                for (index in 0 until count) {
                    checkCancelled()
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


    // Cloud Photo Picker grants a read URI for each photo the user selects.
    // The provider may fetch cloud data behind the scenes; EPV does not export
    // a decrypted copy and only retains URIs for the current app session.
    private var pickedGroups: Map<String, List<EncodedPart>> = emptyMap()
    private var lastFolderUri: Uri? = null
    private var lastFolderGroups: Map<String, List<EncodedPart>> = emptyMap()

    fun galleryPicked(selected: List<Uri>, password: String): List<Item> {
        val grouped = linkedMapOf<String, MutableList<EncodedPart>>()
        for (uri in selected.distinct()) {
            try {
                val header = EpvCore.readHeader(PngCodec.read(context, uri))
                grouped.getOrPut(header.id()) { mutableListOf() }.add(EncodedPart(uri, header))
            } catch (_: Exception) {
                // The selected image may not be an EPV PNG, or the picker
                // provider may have transcoded it. Never claim it is valid.
            }
        }
        pickedGroups = grouped.mapValues { it.value.toList() }
        return pickedGroups.map { (id, parts) ->
            val headers = parts.map { it.header }
            val expected = headers.first().partCount
            val unique = headers.map { it.partIndex }.distinct().size
            if (unique != expected || parts.size != expected) {
                Item(id, unique, expected, "Выбраны не все части (добавьте оставшиеся PNG)")
            } else {
                try {
                    val opened = EpvMediaDataSource(context,
                        parts.sortedBy { it.header.partIndex }.map { it.uri to it.header }, password)
                    try { Item(id, unique, expected, opened.filename, opened.mime, opened.size) }
                    finally { opened.close() }
                } catch (_: Exception) {
                    Item(id, unique, expected, "Не удалось расшифровать")
                }
            }
        }
    }

    fun openPickedMedia(id: String, password: String): EpvMediaDataSource {
        val group = pickedGroups[id] ?: error("Выберите части заново в Google Фото")
        return EpvMediaDataSource(context,
            group.sortedBy { it.header.partIndex }.map { it.uri to it.header }, password)
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
        val groups = collect(root)
        lastFolderUri = rootUri
        lastFolderGroups = groups
        return groups.map { (id, parts) ->
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
        val groups = if (rootUri == lastFolderUri) lastFolderGroups else collect(root)
        val group = groups[id] ?: error("File no longer in vault folder")
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
                var relativeDirectory = ""
                for ((index, partFile) in ordered.withIndex()) {
                    val raw = PngCodec.read(context, partFile.uri)
                    val part = EpvCore.decryptPart(key, raw)
                    if (referenceMetadata == null) {
                        referenceMetadata = part.metadataJson
                        val meta = JSONObject(referenceMetadata)
                        originalName = meta.getString("n")
                        originalSize = meta.getLong("s")
                        originalSha = meta.getString("h")
                        relativeDirectory = safeRelativeDirectory(meta.optString("p", ""))
                        require(originalSize >= 0 && originalName.isNotBlank() &&
                                originalName == originalName.substringAfterLast('/').substringAfterLast('\\')) {
                            "Unsafe filename"
                        }
                        originalName = originalName.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim(' ', '.')
                        require(originalName.isNotEmpty()) { "Unsafe filename" }
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
                            put(MediaStore.MediaColumns.MIME_TYPE, meta.optString("m", "application/octet-stream"))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/EncryptedPhotoVault/" +
                                if (relativeDirectory.isEmpty()) "" else "$relativeDirectory/")
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
                recovered.add(if (relativeDirectory.isEmpty()) originalName else "$relativeDirectory/$originalName")
            } catch (t: Throwable) {
                output?.let { resolver.delete(it, null, null) }
                throw t
            } finally { Arrays.fill(key, 0.toByte()) }
        }
        return recovered
    }
}
