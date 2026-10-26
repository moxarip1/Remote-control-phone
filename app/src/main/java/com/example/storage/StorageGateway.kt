package com.example.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.model.RemoteFileItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.file.Files

/**
 * Thrown whenever a caller attempts to access a path outside registered shared roots,
 * uses path traversal (`..`, absolute paths, symlinks), or writes to a read-only root.
 * Maps to HTTP 403 Forbidden / DataChannel status 403.
 */
class StorageAccessDeniedException(message: String) : SecurityException(message)

/**
 * Thrown when a requested file or directory does not exist inside a valid shared root.
 * Maps to HTTP 404 Not Found / DataChannel status 404.
 */
class StorageFileNotFoundException(message: String) : Exception(message)

/**
 * Normalized, validated reference to either the virtual root listing (`rootId == ""`)
 * or a specific shared root (`rootId`) + validated relative path (`relativePath`).
 */
data class ValidatedStoragePath(
    val rootId: String,
    val relativePath: String
) {
    val isVirtualRootList: Boolean
        get() = rootId.isEmpty()

    val opaquePath: String
        get() = when {
            rootId.isEmpty() -> ""
            relativePath.isEmpty() -> rootId
            else -> "$rootId/$relativePath"
        }

    val parentOpaquePath: String
        get() = when {
            rootId.isEmpty() -> ""
            relativePath.isEmpty() -> ""
            !relativePath.contains('/') -> rootId
            else -> "$rootId/${relativePath.substringBeforeLast('/')}"
        }
}

/**
 * Metadata for an item inside a shared root. Never exposes raw device filesystem paths.
 */
data class StorageEntryStat(
    val name: String,
    val opaquePath: String,
    val rootId: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String
) {
    fun toRemoteFileItem(): RemoteFileItem = RemoteFileItem(
        name = name,
        path = opaquePath,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        mimeType = mimeType
    )
}

/**
 * Directory listing result returned by [StorageGateway.list].
 */
data class StorageDirectoryListing(
    val currentPath: String,
    val parentPath: String,
    val rootPath: String,
    val entries: List<StorageEntryStat>
)

/**
 * Abstraction for an individual shared root backend (SAF `ContentResolver`/`DocumentsContract`
 * in production, or symlink-checked canonical directory in JVM unit tests).
 */
interface SharedRootBackend {
    val rootId: String
    val displayName: String
    val allowUploads: Boolean

    fun stat(relativePath: String): StorageEntryStat
    fun list(relativePath: String): List<StorageEntryStat>
    fun openRead(relativePath: String): InputStream
    fun writeStream(dirRelativePath: String, fileName: String, inputStream: InputStream, expectedBytes: Long = -1L): StorageEntryStat
    fun delete(relativePath: String): Boolean
}

/**
 * Single security-hardened gateway for all file operations (`list`, `stat`, `openRead`, `writeStream`, `delete`).
 *
 * Security Invariants (Phase 1.1 & 1.2):
 * 1. Rejects all raw filesystem absolute paths (`/storage/...`, `/sdcard/...`, `/etc/...`, `/data/...`, `C:\...`, `file://`).
 * 2. Rejects any path containing `..`, `.`, backslashes `\`, null bytes `\u0000`, or URL-encoded traversal (`%2e%2e`).
 * 3. Operates strictly inside registered [SharedRootBackend] roots; rejects unknown `rootId`s with [StorageAccessDeniedException] (403).
 * 4. Rejects symlink escapes when backed by filesystem directories in tests.
 * 5. Never returns or logs absolute device paths; uses opaque `rootId/relativePath` everywhere.
 */
class StorageGateway(
    private val rootsProvider: () -> List<SharedRootBackend>
) {

    companion object {
        private val VALID_ROOT_ID_REGEX = Regex("^root_[a-zA-Z0-9_\\-]{3,64}$")
        private val FORBIDDEN_ABSOLUTE_PREFIXES = listOf(
            "storage/",
            "sdcard/",
            "mnt/",
            "data/",
            "etc/",
            "proc/",
            "sys/",
            "var/",
            "usr/",
            "dev/",
            "system/",
            "root/",
            "file:"
        )

        /**
         * Validates and normalizes an incoming API path into a [ValidatedStoragePath].
         * Throws [StorageAccessDeniedException] (403) on any traversal, absolute path, or malformed input.
         */
        fun validateAndParsePath(rawInput: String?): ValidatedStoragePath {
            if (rawInput == null) {
                return ValidatedStoragePath(rootId = "", relativePath = "")
            }

            if (rawInput.indexOf('\u0000') >= 0) {
                throw StorageAccessDeniedException("403 Forbidden: Null byte in path is prohibited")
            }

            val trimmed = rawInput.trim()
            if (trimmed.isEmpty() || trimmed == "/") {
                return ValidatedStoragePath(rootId = "", relativePath = "")
            }

            // Reject backslashes (Windows/mixed traversal) and URL-encoded traversal sequences
            if (trimmed.contains('\\')) {
                throw StorageAccessDeniedException("403 Forbidden: Backslash characters are prohibited")
            }
            val lowerRaw = trimmed.lowercase()
            if (lowerRaw.contains("%2e") || lowerRaw.contains("%2f") || lowerRaw.contains("%5c") || lowerRaw.contains("%00")) {
                throw StorageAccessDeniedException("403 Forbidden: Encoded path traversal sequences are prohibited")
            }

            // Also check URL-decoded form to catch double-encoding
            val decoded = try {
                URLDecoder.decode(trimmed, "UTF-8")
            } catch (_: Throwable) {
                trimmed
            }
            if (decoded.contains('\u0000') || decoded.contains('\\') || decoded.contains("..")) {
                throw StorageAccessDeniedException("403 Forbidden: Path traversal sequence detected")
            }

            // Reject any leading slash (absolute Unix path like /etc/passwd or /storage/emulated/0)
            if (trimmed.startsWith("/")) {
                throw StorageAccessDeniedException("403 Forbidden: Absolute filesystem paths are prohibited")
            }

            // Reject Windows drive letters (e.g., C:...) unless it is `root_<id>:relativePath`
            val normalizedInput = if (trimmed.contains(':')) {
                val beforeColon = trimmed.substringBefore(':')
                val afterColon = trimmed.substringAfter(':')
                if (!VALID_ROOT_ID_REGEX.matches(beforeColon)) {
                    throw StorageAccessDeniedException("403 Forbidden: URI schemes and drive letters are prohibited")
                }
                if (afterColon.isEmpty()) beforeColon else "$beforeColon/${afterColon.trimStart('/')}"
            } else {
                trimmed
            }

            val lowerNormalized = normalizedInput.lowercase()
            for (prefix in FORBIDDEN_ABSOLUTE_PREFIXES) {
                if (lowerNormalized.startsWith(prefix)) {
                    throw StorageAccessDeniedException("403 Forbidden: Direct filesystem path access is prohibited")
                }
            }

            val rawSegments = normalizedInput.split('/')
            if (rawSegments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw StorageAccessDeniedException("403 Forbidden: Relative traversal segments ('.' or '..') are prohibited")
            }

            val rootId = rawSegments.first()
            if (!VALID_ROOT_ID_REGEX.matches(rootId)) {
                throw StorageAccessDeniedException("403 Forbidden: Invalid or unauthorized shared root ID")
            }

            val relativeSegments = rawSegments.drop(1)
            for (seg in relativeSegments) {
                if (seg == "." || seg == ".." || seg.contains("..") || seg.contains('/') || seg.contains('\\') || seg.contains('\u0000')) {
                    throw StorageAccessDeniedException("403 Forbidden: Unsafe path segment rejected")
                }
            }

            val relativePath = relativeSegments.joinToString("/")
            return ValidatedStoragePath(rootId = rootId, relativePath = relativePath)
        }

        /**
         * Validates a single file or directory name (for uploads / creation).
         */
        fun validateSafeSingleFileName(rawName: String?): String {
            val name = rawName?.trim().orEmpty()
            if (name.isEmpty() ||
                name == "." ||
                name == ".." ||
                name.contains('/') ||
                name.contains('\\') ||
                name.contains('\u0000') ||
                name.contains("..")
            ) {
                throw StorageAccessDeniedException("403 Forbidden: Invalid or unsafe filename")
            }
            return name
        }

        /**
         * Creates a production [StorageGateway] backed by the user's persisted SAF tree URIs in [SharedRootsRepository].
         */
        fun fromContext(context: Context): StorageGateway {
            val appContext = context.applicationContext
            return StorageGateway {
                val entries = SharedRootsRepository.validateAndLoadRoots(appContext)
                entries.map { entry ->
                    SafTreeSharedRootBackend(
                        context = appContext,
                        rootId = entry.rootId,
                        displayName = entry.displayName,
                        treeUri = Uri.parse(entry.uriString),
                        allowUploads = entry.allowUploads
                    )
                }
            }
        }
    }

    private fun resolveAuthorizedRoot(rootId: String): SharedRootBackend {
        val roots = rootsProvider()
        return roots.firstOrNull { it.rootId == rootId }
            ?: throw StorageAccessDeniedException("403 Forbidden: Shared root '$rootId' is not registered or access was revoked")
    }

    /**
     * Lists either all registered shared roots (when `rawPath` is blank or `"/"`)
     * or the contents of a directory inside a registered shared root.
     */
    fun list(rawPath: String?): StorageDirectoryListing {
        val parsed = validateAndParsePath(rawPath)
        if (parsed.isVirtualRootList) {
            val roots = rootsProvider()
            val rootEntries = roots.map { root ->
                StorageEntryStat(
                    name = root.displayName,
                    opaquePath = root.rootId,
                    rootId = root.rootId,
                    relativePath = "",
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModified = 0L,
                    mimeType = "inode/directory"
                )
            }
            return StorageDirectoryListing(
                currentPath = "",
                parentPath = "",
                rootPath = "",
                entries = rootEntries
            )
        }

        val backend = resolveAuthorizedRoot(parsed.rootId)
        val entries = backend.list(parsed.relativePath)
        return StorageDirectoryListing(
            currentPath = parsed.opaquePath,
            parentPath = parsed.parentOpaquePath,
            rootPath = "",
            entries = entries
        )
    }

    fun stat(rawPath: String?): StorageEntryStat {
        val parsed = validateAndParsePath(rawPath)
        if (parsed.isVirtualRootList) {
            return StorageEntryStat(
                name = "Shared Roots",
                opaquePath = "",
                rootId = "",
                relativePath = "",
                isDirectory = true,
                sizeBytes = 0L,
                lastModified = 0L,
                mimeType = "inode/directory"
            )
        }
        val backend = resolveAuthorizedRoot(parsed.rootId)
        return backend.stat(parsed.relativePath)
    }

    fun openRead(rawPath: String?): Pair<StorageEntryStat, InputStream> {
        val parsed = validateAndParsePath(rawPath)
        if (parsed.isVirtualRootList || parsed.relativePath.isEmpty()) {
            throw StorageAccessDeniedException("403 Forbidden: Cannot open root directory as a file stream")
        }
        val backend = resolveAuthorizedRoot(parsed.rootId)
        val stat = backend.stat(parsed.relativePath)
        if (stat.isDirectory) {
            throw StorageFileNotFoundException("404 Not Found: Requested path is a directory, not a file")
        }
        return stat to backend.openRead(parsed.relativePath)
    }

    fun writeStream(
        rawDirPath: String?,
        rawFileName: String?,
        inputStream: InputStream,
        expectedBytes: Long = -1L
    ): StorageEntryStat {
        val safeFileName = validateSafeSingleFileName(rawFileName)
        val parsedDir = validateAndParsePath(rawDirPath)
        if (parsedDir.isVirtualRootList) {
            // If only a single shared root is configured and allows uploads, default to it; otherwise reject with 403
            val roots = rootsProvider()
            val singleRoot = roots.singleOrNull()
                ?: throw StorageAccessDeniedException("403 Forbidden: Must select a specific shared folder root for upload")
            if (!singleRoot.allowUploads) {
                throw StorageAccessDeniedException("403 Forbidden: Uploads are disabled for shared root '${singleRoot.rootId}'")
            }
            return singleRoot.writeStream("", safeFileName, inputStream, expectedBytes)
        }

        val backend = resolveAuthorizedRoot(parsedDir.rootId)
        if (!backend.allowUploads) {
            throw StorageAccessDeniedException("403 Forbidden: Uploads are disabled for shared root '${backend.rootId}'")
        }
        return backend.writeStream(parsedDir.relativePath, safeFileName, inputStream, expectedBytes)
    }

    fun delete(rawPath: String?): Boolean {
        val parsed = validateAndParsePath(rawPath)
        if (parsed.isVirtualRootList || parsed.relativePath.isEmpty()) {
            throw StorageAccessDeniedException("403 Forbidden: Cannot delete a top-level shared root")
        }
        val backend = resolveAuthorizedRoot(parsed.rootId)
        return backend.delete(parsed.relativePath)
    }
}

/**
 * Production SAF backend operating strictly via [ContentResolver] and [DocumentsContract] on a persisted tree [Uri].
 */
class SafTreeSharedRootBackend(
    private val context: Context,
    override val rootId: String,
    override val displayName: String,
    private val treeUri: Uri,
    override val allowUploads: Boolean = false
) : SharedRootBackend {

    private data class DocNode(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModified: Long
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun resolveDocNode(relativePath: String): DocNode {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        if (relativePath.isEmpty()) {
            return querySingleDoc(rootDocId) ?: DocNode(
                documentId = rootDocId,
                name = displayName,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = 0L,
                lastModified = 0L
            )
        }

        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        var currentDocId = rootDocId
        var currentNode: DocNode? = null

        for ((index, segment) in segments.withIndex()) {
            if (segment == "." || segment == "..") {
                throw StorageAccessDeniedException("403 Forbidden: Traversal segment rejected")
            }
            val children = queryChildDocs(currentDocId)
            val matches = children.filter { it.name == segment }
            if (matches.size > 1) {
                throw StorageAccessDeniedException(
                    "403 Forbidden: Ambiguous document name '$segment' (${matches.size} children share the same DISPLAY_NAME)"
                )
            }
            val match = matches.firstOrNull()
                ?: throw StorageFileNotFoundException("404 Not Found: '$segment' does not exist inside shared root")
            if (index < segments.lastIndex && !match.isDirectory) {
                throw StorageFileNotFoundException("404 Not Found: Intermediate segment '$segment' is not a directory")
            }
            currentDocId = match.documentId
            currentNode = match
        }

        return currentNode ?: throw StorageFileNotFoundException("404 Not Found")
    }

    private fun querySingleDoc(documentId: String): DocNode? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return try {
            context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getString(0) ?: documentId
                    val name = cursor.getString(1) ?: displayName
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val lastMod = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                    DocNode(id, name, mime, size, lastMod)
                } else {
                    null
                }
            }
        } catch (_: SecurityException) {
            throw StorageAccessDeniedException("403 Forbidden: SAF permission revoked for root '$rootId'")
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryChildDocs(parentDocumentId: String): List<DocNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val list = mutableListOf<DocNode>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val lastMod = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                    list.add(DocNode(id, name, mime, size, lastMod))
                }
            }
        } catch (_: SecurityException) {
            throw StorageAccessDeniedException("403 Forbidden: SAF permission revoked for root '$rootId'")
        }
        return list
    }

    override fun stat(relativePath: String): StorageEntryStat {
        val node = resolveDocNode(relativePath)
        val opaque = if (relativePath.isEmpty()) rootId else "$rootId/$relativePath"
        return StorageEntryStat(
            name = if (relativePath.isEmpty()) displayName else node.name,
            opaquePath = opaque,
            rootId = rootId,
            relativePath = relativePath,
            isDirectory = node.isDirectory,
            sizeBytes = if (node.isDirectory) 0L else node.sizeBytes,
            lastModified = node.lastModified,
            mimeType = if (node.isDirectory) "inode/directory" else node.mimeType
        )
    }

    override fun list(relativePath: String): List<StorageEntryStat> {
        val dirNode = resolveDocNode(relativePath)
        if (!dirNode.isDirectory) {
            throw StorageFileNotFoundException("404 Not Found: Target path is not a directory")
        }
        val rawChildren = queryChildDocs(dirNode.documentId)
        val duplicateNames = rawChildren.groupBy { it.name }.filter { it.value.size > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            throw StorageAccessDeniedException(
                "403 Forbidden: Ambiguous DISPLAY_NAME in SAF directory (${duplicateNames.joinToString(", ")})"
            )
        }
        val children = rawChildren
            .sortedWith(compareByDescending<DocNode> { it.isDirectory }.thenBy { it.name.lowercase() })

        return children.map { child ->
            val childRel = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
            StorageEntryStat(
                name = child.name,
                opaquePath = "$rootId/$childRel",
                rootId = rootId,
                relativePath = childRel,
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isDirectory) 0L else child.sizeBytes,
                lastModified = child.lastModified,
                mimeType = if (child.isDirectory) "inode/directory" else RemoteFileItem.resolveMimeType(child.name)
            )
        }
    }

    override fun openRead(relativePath: String): InputStream {
        val node = resolveDocNode(relativePath)
        if (node.isDirectory) {
            throw StorageFileNotFoundException("404 Not Found: Cannot stream a directory")
        }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
        return try {
            context.contentResolver.openInputStream(docUri)
                ?: throw StorageFileNotFoundException("404 Not Found: Unable to open SAF input stream")
        } catch (_: SecurityException) {
            throw StorageAccessDeniedException("403 Forbidden: SAF read permission denied")
        }
    }

    override fun writeStream(
        dirRelativePath: String,
        fileName: String,
        inputStream: InputStream,
        expectedBytes: Long
    ): StorageEntryStat {
        if (!allowUploads) {
            throw StorageAccessDeniedException("403 Forbidden: Uploads are not permitted on root '$rootId'")
        }
        val safeName = StorageGateway.validateSafeSingleFileName(fileName)
        val parentNode = resolveDocNode(dirRelativePath)
        if (!parentNode.isDirectory) {
            throw StorageFileNotFoundException("404 Not Found: Target upload directory does not exist")
        }

        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentNode.documentId)
        val existingMatches = queryChildDocs(parentNode.documentId).filter { it.name == safeName }
        if (existingMatches.size > 1) {
            throw StorageAccessDeniedException(
                "403 Forbidden: Ambiguous document name '$safeName' (${existingMatches.size} items share the same DISPLAY_NAME)"
            )
        }
        val existingChild = existingMatches.firstOrNull()
        if (existingChild != null) {
            if (existingChild.isDirectory) {
                throw StorageAccessDeniedException(
                    "403 Forbidden: Cannot overwrite directory '$safeName' with a file"
                )
            }
            try {
                val existingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existingChild.documentId)
                DocumentsContract.deleteDocument(context.contentResolver, existingUri)
            } catch (_: Throwable) {
            }
        }

        val mimeType = RemoteFileItem.resolveMimeType(safeName)
        val createdUri = try {
            DocumentsContract.createDocument(context.contentResolver, parentDocUri, mimeType, safeName)
                ?: throw StorageAccessDeniedException("403 Forbidden: Could not create document in SAF tree")
        } catch (_: SecurityException) {
            throw StorageAccessDeniedException("403 Forbidden: SAF write permission denied")
        }

        var totalWritten = 0L
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openOutputStream(createdUri, "w")?.use { out ->
            if (expectedBytes >= 0L) {
                var remaining = expectedBytes
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val readCount = inputStream.read(buffer, 0, toRead)
                    if (readCount <= 0) break
                    out.write(buffer, 0, readCount)
                    totalWritten += readCount
                    remaining -= readCount
                }
            } else {
                while (true) {
                    val readCount = inputStream.read(buffer)
                    if (readCount <= 0) break
                    out.write(buffer, 0, readCount)
                    totalWritten += readCount
                }
            }
            out.flush()
        } ?: throw StorageAccessDeniedException("403 Forbidden: Unable to open SAF output stream")

        val childRel = if (dirRelativePath.isEmpty()) safeName else "$dirRelativePath/$safeName"
        return StorageEntryStat(
            name = safeName,
            opaquePath = "$rootId/$childRel",
            rootId = rootId,
            relativePath = childRel,
            isDirectory = false,
            sizeBytes = totalWritten,
            lastModified = System.currentTimeMillis(),
            mimeType = mimeType
        )
    }

    override fun delete(relativePath: String): Boolean {
        if (relativePath.isEmpty()) {
            throw StorageAccessDeniedException("403 Forbidden: Cannot delete root directory")
        }
        val node = resolveDocNode(relativePath)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, docUri)
        } catch (_: SecurityException) {
            throw StorageAccessDeniedException("403 Forbidden: SAF delete permission denied")
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Canonicalized, symlink-rejecting directory backend used for unit testing and isolated app-sandboxed storage.
 *
 * Strictly verifies:
 * 1. Every segment from [rootDirectory] to target is checked for [Files.isSymbolicLink] — any symlink is rejected with 403.
 * 2. The target's canonical path must strictly equal [rootCanonical] or start with `"${rootCanonical.path}${File.separator}"`.
 */
class DirectorySharedRootBackend(
    override val rootId: String,
    override val displayName: String,
    rootDirectory: File,
    override val allowUploads: Boolean = false
) : SharedRootBackend {

    private val rootCanonical: File = rootDirectory.canonicalFile

    init {
        require(rootId.matches(Regex("^root_[a-zA-Z0-9_\\-]{3,64}$"))) {
            "Invalid rootId format: $rootId"
        }
    }

    private fun resolveStrictFile(relativePath: String, mustExist: Boolean = true): File {
        if (Files.isSymbolicLink(rootCanonical.toPath())) {
            throw StorageAccessDeniedException("403 Forbidden: Shared root directory cannot be a symlink")
        }
        if (relativePath.isEmpty()) {
            if (mustExist && (!rootCanonical.exists() || !rootCanonical.isDirectory)) {
                throw StorageFileNotFoundException("404 Not Found: Root directory does not exist")
            }
            return rootCanonical
        }

        val segments = relativePath.split('/')
        var current = rootCanonical
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == ".." || segment.contains('\\') || segment.contains('\u0000')) {
                throw StorageAccessDeniedException("403 Forbidden: Invalid segment '$segment'")
            }
            val next = File(current, segment)
            if (Files.isSymbolicLink(next.toPath())) {
                throw StorageAccessDeniedException("403 Forbidden: Symlink escape rejected at '$segment'")
            }
            current = next
        }

        val canonicalCandidate = current.canonicalFile
        val rootPathPrefix = rootCanonical.path + File.separator
        if (canonicalCandidate != rootCanonical && !canonicalCandidate.path.startsWith(rootPathPrefix)) {
            throw StorageAccessDeniedException("403 Forbidden: Path escaped shared root boundary")
        }

        if (mustExist && !canonicalCandidate.exists()) {
            throw StorageFileNotFoundException("404 Not Found: Requested item does not exist")
        }
        return canonicalCandidate
    }

    override fun stat(relativePath: String): StorageEntryStat {
        val target = resolveStrictFile(relativePath, mustExist = true)
        val opaque = if (relativePath.isEmpty()) rootId else "$rootId/$relativePath"
        return StorageEntryStat(
            name = if (relativePath.isEmpty()) displayName else target.name,
            opaquePath = opaque,
            rootId = rootId,
            relativePath = relativePath,
            isDirectory = target.isDirectory,
            sizeBytes = if (target.isDirectory) 0L else target.length(),
            lastModified = target.lastModified(),
            mimeType = if (target.isDirectory) "inode/directory" else RemoteFileItem.resolveMimeType(target.name)
        )
    }

    override fun list(relativePath: String): List<StorageEntryStat> {
        val dir = resolveStrictFile(relativePath, mustExist = true)
        if (!dir.isDirectory) {
            throw StorageFileNotFoundException("404 Not Found: Path is not a directory")
        }
        val children = dir.listFiles()
            ?.filter { !Files.isSymbolicLink(it.toPath()) }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()

        return children.map { child ->
            val childRel = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
            StorageEntryStat(
                name = child.name,
                opaquePath = "$rootId/$childRel",
                rootId = rootId,
                relativePath = childRel,
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isDirectory) 0L else child.length(),
                lastModified = child.lastModified(),
                mimeType = if (child.isDirectory) "inode/directory" else RemoteFileItem.resolveMimeType(child.name)
            )
        }
    }

    override fun openRead(relativePath: String): InputStream {
        val file = resolveStrictFile(relativePath, mustExist = true)
        if (!file.isFile || !file.canRead()) {
            throw StorageFileNotFoundException("404 Not Found: File not readable")
        }
        return FileInputStream(file)
    }

    override fun writeStream(
        dirRelativePath: String,
        fileName: String,
        inputStream: InputStream,
        expectedBytes: Long
    ): StorageEntryStat {
        if (!allowUploads) {
            throw StorageAccessDeniedException("403 Forbidden: Uploads are disabled for root '$rootId'")
        }
        val safeName = StorageGateway.validateSafeSingleFileName(fileName)
        val dir = resolveStrictFile(dirRelativePath, mustExist = true)
        if (!dir.isDirectory) {
            throw StorageFileNotFoundException("404 Not Found: Target upload directory does not exist")
        }

        val targetRel = if (dirRelativePath.isEmpty()) safeName else "$dirRelativePath/$safeName"
        val destFile = resolveStrictFile(targetRel, mustExist = false)
        var totalWritten = 0L
        val buffer = ByteArray(64 * 1024)
        FileOutputStream(destFile).use { fos ->
            if (expectedBytes >= 0L) {
                var remaining = expectedBytes
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val readCount = inputStream.read(buffer, 0, toRead)
                    if (readCount <= 0) break
                    fos.write(buffer, 0, readCount)
                    totalWritten += readCount
                    remaining -= readCount
                }
            } else {
                while (true) {
                    val readCount = inputStream.read(buffer)
                    if (readCount <= 0) break
                    fos.write(buffer, 0, readCount)
                    totalWritten += readCount
                }
            }
            fos.flush()
        }

        return StorageEntryStat(
            name = safeName,
            opaquePath = "$rootId/$targetRel",
            rootId = rootId,
            relativePath = targetRel,
            isDirectory = false,
            sizeBytes = totalWritten,
            lastModified = destFile.lastModified(),
            mimeType = RemoteFileItem.resolveMimeType(safeName)
        )
    }

    override fun delete(relativePath: String): Boolean {
        if (relativePath.isEmpty()) {
            throw StorageAccessDeniedException("403 Forbidden: Cannot delete root directory")
        }
        val target = resolveStrictFile(relativePath, mustExist = true)
        if (target == rootCanonical) {
            throw StorageAccessDeniedException("403 Forbidden: Cannot delete root directory")
        }
        return if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
    }
}
