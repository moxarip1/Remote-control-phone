package com.example.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.example.security.KeystoreSecretStore
import com.example.security.KeystoreUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Represents a user-selected Storage Access Framework (SAF) shared folder tree.
 *
 * @property rootId Opaque identifier (e.g. `root_a1b2c3d4e5f6`) used in all APIs instead of filesystem paths.
 * @property uriString Persisted SAF Tree URI string (`content://...`).
 * @property displayName Human-readable folder name shown in the UI.
 * @property allowUploads Whether the HOST user allows uploads/writes into this shared root. Defaults to `false` (read-only).
 * @property addedAt Timestamp when the shared root was granted.
 */
data class SharedRootEntry(
    val rootId: String,
    val uriString: String,
    val displayName: String,
    val allowUploads: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rootId", rootId)
        put("uriString", uriString)
        put("displayName", displayName)
        put("allowUploads", allowUploads)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): SharedRootEntry? {
            val rootId = obj.optString("rootId").trim()
            val uriString = obj.optString("uriString").trim()
            val displayName = obj.optString("displayName").trim()
            if (rootId.isBlank() || uriString.isBlank()) return null
            return SharedRootEntry(
                rootId = rootId,
                uriString = uriString,
                displayName = displayName.ifBlank { rootId },
                allowUploads = obj.optBoolean("allowUploads", false),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Manages the persisted list of Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) shared roots.
 *
 * Security invariants (Phase 1.1):
 * - Validates persisted URI permissions (`contentResolver.persistedUriPermissions`) on startup and drops
 *   any root whose permission was revoked or whose underlying folder no longer exists.
 * - Newly added shared roots default to `allowUploads = false` (read-only by default).
 * - Exposes only opaque `rootId` identifiers (`root_<12-hex>`) to network APIs—never raw filesystem paths.
 */
object SharedRootsRepository {

    private const val KEY_SHARED_ROOTS_JSON = "saf_shared_roots_v1"

    private val _sharedRootsFlow = MutableStateFlow<List<SharedRootEntry>>(emptyList())
    val sharedRootsFlow: StateFlow<List<SharedRootEntry>> = _sharedRootsFlow.asStateFlow()

    /**
     * Generates a deterministic opaque `rootId` (`root_<12-hex>`) from the URI string.
     */
    fun computeOpaqueRootId(uriString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uriString.trim().toByteArray(StandardCharsets.UTF_8))
        val hex = StringBuilder(12)
        for (i in 0 until 6) {
            hex.append(String.format("%02x", digest[i]))
        }
        return "root_$hex"
    }

    /**
     * Loads and validates all persisted SAF tree URIs on startup.
     * Drops any entry whose persistable read permission is missing or whose document tree is inaccessible.
     * Fails closed (returns empty list) if [KeystoreSecretStore] throws [KeystoreUnavailableException].
     */
    fun validateAndLoadRoots(context: Context): List<SharedRootEntry> {
        val rawJson = try {
            KeystoreSecretStore.getString(context, KEY_SHARED_ROOTS_JSON, "[]") ?: "[]"
        } catch (_: KeystoreUnavailableException) {
            _sharedRootsFlow.value = emptyList()
            return emptyList()
        }
        val storedList = parseRootsJson(rawJson)

        val persistedPermissions = try {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }

        val validList = mutableListOf<SharedRootEntry>()
        for (entry in storedList) {
            if (entry.uriString in persistedPermissions && isTreeUriAccessible(context, Uri.parse(entry.uriString))) {
                validList.add(entry)
            }
        }

        if (validList.size != storedList.size) {
            saveRootsList(context, validList)
        } else {
            _sharedRootsFlow.value = validList
        }
        return validList
    }

    /**
     * Takes persistable read/write URI permission on [treeUri] and adds it to the shared roots list.
     * Newly added shared roots default to `allowUploads = false` (read-only).
     */
    fun addSharedRootUri(
        context: Context,
        treeUri: Uri,
        allowUploads: Boolean = false
    ): SharedRootEntry? {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (_: SecurityException) {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            if (!isTreeUriAccessible(context, treeUri)) {
                return null
            }

            val uriStr = treeUri.toString()
            val rootId = computeOpaqueRootId(uriStr)
            val displayName = resolveTreeDisplayName(context, treeUri).ifBlank { "مجلد مشترك ($rootId)" }

            val current = validateAndLoadRoots(context).toMutableList()
            val existingIdx = current.indexOfFirst { it.rootId == rootId || it.uriString == uriStr }
            val entry = SharedRootEntry(
                rootId = rootId,
                uriString = uriStr,
                displayName = displayName,
                allowUploads = allowUploads,
                addedAt = System.currentTimeMillis()
            )
            if (existingIdx >= 0) {
                current[existingIdx] = entry
            } else {
                current.add(entry)
            }
            saveRootsList(context, current)
            entry
        } catch (_: Throwable) {
            null
        }
    }

    fun removeSharedRoot(context: Context, rootId: String) {
        val current = _sharedRootsFlow.value.toMutableList()
        val removed = current.firstOrNull { it.rootId == rootId }
        if (removed != null) {
            current.removeAll { it.rootId == rootId }
            saveRootsList(context, current)
            try {
                val uri = Uri.parse(removed.uriString)
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun setAllowUploads(context: Context, rootId: String, allowUploads: Boolean) {
        val updated = _sharedRootsFlow.value.map {
            if (it.rootId == rootId) it.copy(allowUploads = allowUploads) else it
        }
        saveRootsList(context, updated)
    }

    fun getRootById(context: Context, rootId: String): SharedRootEntry? {
        val current = _sharedRootsFlow.value.ifEmpty { validateAndLoadRoots(context) }
        return current.firstOrNull { it.rootId == rootId }
    }

    private fun saveRootsList(context: Context, roots: List<SharedRootEntry>) {
        val array = JSONArray()
        for (r in roots) {
            array.put(r.toJson())
        }
        try {
            KeystoreSecretStore.putValues(context, mapOf(KEY_SHARED_ROOTS_JSON to array.toString()))
            _sharedRootsFlow.value = roots
        } catch (_: KeystoreUnavailableException) {
            _sharedRootsFlow.value = emptyList()
        }
    }

    private fun parseRootsJson(rawJson: String): List<SharedRootEntry> {
        return try {
            val array = JSONArray(rawJson)
            val list = mutableListOf<SharedRootEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val entry = SharedRootEntry.fromJson(obj) ?: continue
                list.add(entry)
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isTreeUriAccessible(context: Context, treeUri: Uri): Boolean {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveTreeDisplayName(context: Context, treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0).orEmpty().trim()
                } else {
                    ""
                }
            }.orEmpty().ifBlank {
                docId.substringAfterLast(':').substringAfterLast('/')
            }
        } catch (_: Throwable) {
            ""
        }
    }
}
