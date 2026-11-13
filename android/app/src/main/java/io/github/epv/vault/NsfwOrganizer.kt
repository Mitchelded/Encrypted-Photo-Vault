package io.github.epv.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Move classified local files as original bytes into a user-chosen SAF folder.
 * No PNG conversion or encryption. Safe cross-provider move:
 * copy -> verify SHA-256 against written bytes -> delete original.
 */
class NsfwOrganizer(private val context: Context) {
    private val resolver = context.contentResolver

    data class Result(
        val total: Int, val moved: Int, val safe: Int, val previouslyChecked: Int,
        val copiedNotDeleted: Int, val errors: Int, val examples: List<String>
    )
    private data class Source(val document: DocumentFile, val relativeDir: List<String>)

    private fun documentId(uri: Uri): String? =
        try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { null }

    private fun digest(file: DocumentFile, cancelled: () -> Unit): Pair<Long, ByteArray> {
        val md = MessageDigest.getInstance("SHA-256")
        var length = 0L
        resolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                cancelled()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                md.update(buffer, 0, n)
                length += n
            }
        } ?: error("Cannot read destination for verification")
        return length to md.digest()
    }

    private fun destinationFolder(target: DocumentFile, path: List<String>): DocumentFile {
        var current = target
        for (segment in path) {
            current = current.findFile(segment) ?: current.createDirectory(segment)
                ?: error("Cannot create subfolder: " + segment)
            require(current.isDirectory) { "A file is blocking a subfolder: " + segment }
        }
        return current
    }

    private fun createUnique(parent: DocumentFile, name: String, mime: String): DocumentFile {
        require(name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name) { "Unsafe filename" }
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 0..10000) {
            val chosen = if (i == 0) name else stem + " (" + i + ")" + ext
            if (parent.findFile(chosen) == null) return parent.createFile(mime, chosen)
                ?: error("Cannot create destination file: " + chosen)
        }
        error("Too many filename collisions")
    }

    /**
     * Cancellation during the copy/verification removes the incomplete copy.
     * Deletion happens only after full integrity verification.
     * false = source provider refused deletion; verified copy is retained.
     */
    private fun moveVerified(
        source: DocumentFile, target: DocumentFile, path: List<String>,
        cancelled: () -> Unit
    ): Boolean {
        val folder = destinationFolder(target, path)
        val dest = createUnique(folder, source.name.orEmpty(),
            resolver.getType(source.uri) ?: "application/octet-stream")
        var keepCopy = false
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            resolver.openInputStream(source.uri)?.use { input ->
                resolver.openOutputStream(dest.uri, "w")?.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        cancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        md.update(buffer, 0, n)
                        bytesWritten += n
                    }
                    output.flush()
                } ?: error("Cannot write destination")
            } ?: error("Cannot read source")
            cancelled()
            val (verifiedSize, verifiedSha) = digest(dest, cancelled)
            check(bytesWritten == verifiedSize && MessageDigest.isEqual(md.digest(), verifiedSha)) {
                "SHA-256 differs: source has not been deleted"
            }
            cancelled()
            keepCopy = true
            return source.delete()
        } finally {
            if (!keepCopy) try { dest.delete() } catch (_: Exception) {}
        }
    }

    fun moveFolder(
        sourceUri: Uri, destinationUri: Uri, threshold: Float,
        score: (Uri) -> Float?,
        alreadyChecked: (Uri, Long, Long) -> Boolean,
        markChecked: (Uri, Long, Long) -> Unit,
        cancelled: () -> Unit,
        onProgress: (Int, Int, String) -> Unit
    ): Result {
        val sourceRoot = DocumentFile.fromTreeUri(context, sourceUri)
            ?: error("Cannot access source folder")
        val target = DocumentFile.fromTreeUri(context, destinationUri)
            ?: error("Cannot access target folder")
        require(sourceRoot.isDirectory && target.isDirectory) { "Select folders" }
        require(sourceRoot.canRead()) { "Cannot read source folder" }
        require(target.canWrite()) { "Cannot write destination folder" }

        val sameProvider = sourceRoot.uri.authority == target.uri.authority
        val targetDocumentId = if (sameProvider) documentId(target.uri) else null
        require(sourceRoot.uri != target.uri && !(sameProvider &&
            targetDocumentId != null && documentId(sourceRoot.uri) == targetDocumentId)) {
            "Source and destination folders must differ"
        }
        val sources = ArrayList<Source>()
        val visited = HashSet<String>()
        val encodedPng = Regex("EPV1_[0-9a-fA-F]{32}_[0-9]{6}\\.png", RegexOption.IGNORE_CASE)

        fun scan(dir: DocumentFile, relativeDir: List<String>, depth: Int) {
            cancelled()
            require(depth <= 32) { "Too many nested folders" }
            if (!visited.add(dir.uri.toString())) return
            for (child in dir.listFiles()) {
                cancelled()
                if (child.isDirectory) {
                    // Destination can reside within source: exclude it from scanning.
                    if (child.uri == target.uri ||
                        (sameProvider && targetDocumentId != null &&
                         documentId(child.uri) == targetDocumentId)) continue
                    val name = child.name.orEmpty()
                    if (name.isBlank() || name == "." || name == "..") continue
                    scan(child, relativeDir + name, depth + 1)
                } else if (child.isFile && !encodedPng.matches(child.name.orEmpty())) {
                    val mime = resolver.getType(child.uri).orEmpty()
                    if (mime.startsWith("image/") || mime.startsWith("video/")) {
                        require(sources.size < 100_000) { "100,000-file scan limit exceeded" }
                        sources.add(Source(child, relativeDir))
                    }
                }
            }
        }
        scan(sourceRoot, emptyList(), 0)
        var moved = 0
        var safe = 0
        var previously = 0
        var copiedOnly = 0
        var errors = 0
        val examples = ArrayList<String>()

        for ((index, source) in sources.withIndex()) {
            cancelled()
            val doc = source.document
            try {
                val length = doc.length()
                val modified = doc.lastModified()
                if (alreadyChecked(doc.uri, length, modified)) {
                    previously++
                    continue
                }
                onProgress(index + 1, sources.size, doc.name.orEmpty())
                val prediction = score(doc.uri)
                cancelled()
                if (prediction == null) {
                    errors++
                    if (examples.size < 5) examples.add(doc.name + ": unsupported or damaged")
                } else if (prediction < threshold) {
                    markChecked(doc.uri, length, modified)
                    safe++
                } else {
                    val deleted = moveVerified(doc, target, source.relativeDir, cancelled)
                    // A successful verified copy is also a checkpoint if
                    // deletion was denied, to avoid duplicating on resume.
                    markChecked(doc.uri, length, modified)
                    if (deleted) moved++ else copiedOnly++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
                if (examples.size < 5) examples.add(doc.name + ": " + e.localizedMessage)
            }
        }
        return Result(sources.size, moved, safe, previously, copiedOnly, errors, examples)
    }
}
