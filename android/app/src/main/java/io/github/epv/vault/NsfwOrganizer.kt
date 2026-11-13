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
            // A read-only provider may throw rather than return false.
            // In either case preserve both copies and mark as copied-only.
            return try { source.delete() } catch (_: SecurityException) { false }
        } finally {
            if (!keepCopy) try { dest.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Indexed, resumable organizer:
     * 1. Only the FIRST launch enumerates the whole source tree.
     * 2. Process persisted pending entries, never rescan at restart.
     * 3. Once pending entries are exhausted, ONE refresh discovers new files.
     * 4. Process newly discovered entries and exit (no endless scan loop).
     */
    fun moveFolder(
        sourceUri: Uri, destinationUri: Uri, threshold: Float,
        scanId: String, queue: NsfwFileQueue,
        score: (Uri) -> Float?,
        alreadyChecked: (Uri, Long, Long) -> Boolean,
        markChecked: (Uri, Long, Long) -> Unit,
        cancelled: () -> Unit,
        onIndexing: (String, Int) -> Unit,
        onProgress: (Int, Int, String) -> Unit
    ): Result {
        val sourceRoot = DocumentFile.fromTreeUri(context, sourceUri)
            ?: error("Cannot access source folder")
        val target = DocumentFile.fromTreeUri(context, destinationUri)
            ?: error("Cannot access destination folder")
        require(sourceRoot.isDirectory && target.isDirectory) { "Select folders" }
        require(sourceRoot.canRead()) { "Cannot read source folder" }
        require(target.canWrite()) { "Cannot write destination folder" }

        val sameProvider = sourceRoot.uri.authority == target.uri.authority
        val targetDocumentId = if (sameProvider) documentId(target.uri) else null
        require(sourceRoot.uri != target.uri && !(sameProvider &&
            targetDocumentId != null && documentId(sourceRoot.uri) == targetDocumentId)) {
            "Source and destination folders must differ"
        }

        val encodedPng = Regex("EPV1_[0-9a-fA-F]{32}_[0-9]{6}\\.png",
            RegexOption.IGNORE_CASE)
        val knownExtensions = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp"
        )

        fun discover(stage: String): Int {
            val visited = HashSet<String>()
            val batch = ArrayList<NsfwFileQueue.Entry>(160)
            var scanned = 0
            var added = 0

            fun flush() {
                if (batch.isEmpty()) return
                cancelled()
                added += queue.addDiscovered(scanId, batch)
                batch.clear()
            }

            fun walk(dir: DocumentFile, relative: List<String>, depth: Int) {
                cancelled()
                require(depth <= 32) { "More than 32 nested directories" }
                if (!visited.add(dir.uri.toString())) return
                for (child in dir.listFiles()) {
                    cancelled()
                    if (child.isDirectory) {
                        // If the destination is inside the source tree, do not
                        // add our newly moved files back into the work queue.
                        if (child.uri == target.uri ||
                            (sameProvider && targetDocumentId != null &&
                                documentId(child.uri) == targetDocumentId)) continue
                        val name = child.name.orEmpty()
                        if (name.isBlank() || name == "." || name == "..") continue
                        walk(child, relative + name, depth + 1)
                    } else if (child.isFile) {
                        val filename = child.name.orEmpty()
                        if (encodedPng.matches(filename)) continue
                        val extension = filename.substringAfterLast('.', "").lowercase()
                        // Avoid a slow ContentResolver.getType IPC for extensions
                        // that are known media types.
                        val isMedia = extension in knownExtensions ||
                            (resolver.getType(child.uri)?.let {
                                it.startsWith("image/") || it.startsWith("video/")
                            } == true)
                        if (!isMedia) continue
                        require(scanned < 100_000) { "100,000-file limit exceeded" }
                        batch.add(NsfwFileQueue.Entry(
                            child.uri, relative, child.length(), child.lastModified()))
                        scanned++
                        if (batch.size >= 160) flush()
                        if (scanned == 1 || scanned % 100 == 0) onIndexing(stage, scanned)
                    }
                }
            }

            onIndexing(stage, 0)
            walk(sourceRoot, emptyList(), 0)
            flush()
            onIndexing(stage, scanned)
            return added
        }

        // If the process was killed during first indexing, the flag remains
        // false. A resumed scan upserts already-indexed files without duplicates.
        if (!queue.isInitialized(scanId)) {
            discover("Первичная индексация")
            cancelled()
            queue.setInitialized(scanId)
        }
        // Do not retry failing files in an infinite loop within a single run.
        // Failures are retried when the user runs the organizer again.
        queue.resetFailed(scanId)

        var moved = 0
        var safe = 0
        var previously = queue.done(scanId).toInt()
        var copiedOnly = 0
        var errors = 0
        val examples = ArrayList<String>()

        fun drain() {
            while (true) {
                cancelled()
                val pending = queue.nextPending(scanId)
                if (pending.isEmpty()) return
                for (entry in pending) {
                    cancelled()
                    try {
                        val doc = DocumentFile.fromSingleUri(context, entry.uri)
                            ?: error("Cannot resolve document URI")
                        if (!doc.exists()) {
                            // Already moved/deleted while EPV was stopped.
                            queue.markDone(scanId, entry.uri)
                            previously++
                            continue
                        }
                        val size = doc.length()
                        val modified = doc.lastModified()
                        val total = queue.total(scanId).toInt()
                        val current = queue.done(scanId).toInt() + 1
                        onProgress(current, total, doc.name.orEmpty())
                        if (alreadyChecked(entry.uri, size, modified)) {
                            queue.markDone(scanId, entry.uri)
                            previously++
                            continue
                        }
                        val confidence = score(entry.uri)
                        cancelled()
                        if (confidence == null) {
                            error("Unsupported or damaged image/video")
                        } else if (confidence < threshold) {
                            markChecked(entry.uri, size, modified)
                            queue.markDone(scanId, entry.uri)
                            safe++
                        } else {
                            val deleted = moveVerified(doc, target, entry.relativeDir, cancelled)
                            // Completion is recorded only after verified copy
                            // (and deletion when allowed).
                            markChecked(entry.uri, size, modified)
                            queue.markDone(scanId, entry.uri)
                            if (deleted) moved++ else copiedOnly++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        queue.markFailed(scanId, entry.uri)
                        errors++
                        if (examples.size < 5)
                            examples.add(entry.uri.lastPathSegment + ": " + e.localizedMessage)
                    }
                }
            }
        }

        // Fast resume: NO tree enumeration while there are pending entries.
        drain()
        cancelled()

        // Exactly one refresh after the existing queue is exhausted. In the
        // common "mostly moved" case this directory tree is much smaller.
        discover("Поиск новых файлов")
        cancelled()
        drain()

        return Result(
            total = queue.total(scanId).toInt(),
            moved = moved, safe = safe, previouslyChecked = previously,
            copiedNotDeleted = copiedOnly, errors = errors,
            examples = examples
        )
    }
}
