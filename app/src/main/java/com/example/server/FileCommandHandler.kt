package com.example.server

import android.graphics.Bitmap
import com.example.model.RemoteFileItem
import com.example.model.ServerLog
import com.example.security.ConstantTimeAuth
import com.example.storage.StorageAccessDeniedException
import com.example.storage.StorageEntryStat
import com.example.storage.StorageFileNotFoundException
import com.example.storage.StorageGateway
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Result of executing a JSON-producing command through [FileCommandHandler].
 */
data class CommandJsonResult(
    val statusCode: Int,
    val body: JSONObject
)

/**
 * Unified, security-hardened command dispatcher shared by both [LocalFileServer] (HTTP)
 * and `HomeServerForegroundService.handleDataChannelCommand` (WebRTC DataChannel).
 *
 * Security invariants (Phase 1.1 – 1.8):
 * 1. Header-only constant-time token verification via [ConstantTimeAuth.verifyToken].
 *    Rejects immediately when the expected token set is empty or when the token is only in a query string.
 * 2. All file operations go through [StorageGateway] using opaque `rootId/relativePath` identifiers.
 * 3. Any attempt to access outside shared roots, use `..`, absolute paths, or symlinks is rejected with 403.
 * 4. Logs never contain secret tokens or raw filesystem paths of private files.
 */
class FileCommandHandler(
    val storageGateway: StorageGateway,
    private val validTokensProvider: () -> Set<String>,
    private val statusProvider: () -> Triple<Int, Long, Long>,
    private val screenshotBitmapProvider: () -> Bitmap? = { null },
    private val onLog: (ServerLog) -> Unit = {}
) {

    /**
     * Verifies `x-auth-token` provided strictly via request headers in constant time.
     * Never accepts tokens from query parameters.
     */
    fun isAuthorizedHeaderToken(headerToken: String?, transportLabel: String, endpoint: String, clientId: String): Boolean {
        val expectedTokens = validTokensProvider()
        val authorized = ConstantTimeAuth.verifyToken(headerToken, expectedTokens)
        if (!authorized) {
            emitSanitizedLog(
                method = transportLabel,
                endpoint = endpoint,
                message = "رفض طلب غير مصرح به (401 Unauthorized)",
                clientIp = clientId,
                statusCode = 401,
                isError = true
            )
        }
        return authorized
    }

    fun unauthorizedResult(reqId: String? = null): CommandJsonResult {
        val body = JSONObject()
            .put("success", false)
            .put("statusCode", 401)
            .put("error", "401 Unauthorized: Invalid or missing x-auth-token header")
        if (!reqId.isNullOrBlank()) {
            body.put("reqId", reqId)
        }
        return CommandJsonResult(statusCode = 401, body = body)
    }

    fun handleStatus(reqId: String? = null, transportLabel: String = "P2P", clientId: String = "Peer"): CommandJsonResult {
        val (batteryLevel, freeBytes, totalBytes) = statusProvider()
        emitSanitizedLog(
            method = transportLabel,
            endpoint = "/status",
            message = "استعلام حالة الخادم (البطارية $batteryLevel%)",
            clientIp = clientId,
            statusCode = 200
        )
        val payload = JSONObject()
            .put("success", true)
            .put("statusCode", 200)
            .put("status", "online")
            .put("batteryLevel", batteryLevel)
            .put("freeStorageBytes", freeBytes)
            .put("totalStorageBytes", totalBytes)
            .put("rootPath", "")
            .put("timestamp", System.currentTimeMillis())
        if (!reqId.isNullOrBlank()) {
            payload.put("reqId", reqId)
        }
        return CommandJsonResult(statusCode = 200, body = payload)
    }

    fun handleListFiles(
        rawPath: String?,
        reqId: String? = null,
        transportLabel: String = "P2P",
        clientId: String = "Peer"
    ): CommandJsonResult {
        return try {
            val listing = storageGateway.list(rawPath)
            val filesArray = JSONArray()
            for (entry in listing.entries) {
                filesArray.put(
                    JSONObject()
                        .put("name", entry.name)
                        .put("path", entry.opaquePath)
                        .put("rootId", entry.rootId)
                        .put("relativePath", entry.relativePath)
                        .put("isDirectory", entry.isDirectory)
                        .put("sizeBytes", entry.sizeBytes)
                        .put("lastModified", entry.lastModified)
                )
            }

            val safeScopeLabel = listing.currentPath.substringBefore('/').ifBlank { "roots" }
            emitSanitizedLog(
                method = transportLabel,
                endpoint = "/files",
                message = "تصفح مجلد مشترك [$safeScopeLabel] (${listing.entries.size} عنصر)",
                clientIp = clientId,
                statusCode = 200
            )

            val body = JSONObject()
                .put("success", true)
                .put("statusCode", 200)
                .put("currentPath", listing.currentPath)
                .put("parentPath", listing.parentPath)
                .put("rootPath", listing.rootPath)
                .put("files", filesArray)
            if (!reqId.isNullOrBlank()) {
                body.put("reqId", reqId)
            }
            CommandJsonResult(statusCode = 200, body = body)
        } catch (e: StorageAccessDeniedException) {
            emitSanitizedLog(
                method = transportLabel,
                endpoint = "/files",
                message = "رفض الوصول إلى مسار خارج المجلدات المشتركة (403 Forbidden)",
                clientIp = clientId,
                statusCode = 403,
                isError = true
            )
            errorJsonResult(403, e.message ?: "403 Forbidden: Access outside shared roots is denied", reqId)
        } catch (e: StorageFileNotFoundException) {
            errorJsonResult(404, e.message ?: "404 Not Found: Directory not found", reqId)
        } catch (_: Throwable) {
            errorJsonResult(500, "500 Internal Server Error", reqId)
        }
    }

    fun handleDelete(
        rawPath: String?,
        reqId: String? = null,
        transportLabel: String = "P2P",
        clientId: String = "Peer"
    ): CommandJsonResult {
        return try {
            val deleted = storageGateway.delete(rawPath)
            val rootLabel = rawPath?.trim()?.substringBefore('/').orEmpty().ifBlank { "root" }
            emitSanitizedLog(
                method = transportLabel,
                endpoint = "/delete",
                message = if (deleted) "تم حذف عنصر داخل [$rootLabel]" else "تعذر حذف عنصر داخل [$rootLabel]",
                clientIp = clientId,
                statusCode = if (deleted) 200 else 500,
                isError = !deleted
            )
            val body = JSONObject()
                .put("success", deleted)
                .put("statusCode", if (deleted) 200 else 500)
                .put("message", if (deleted) "Deleted successfully" else "Failed to delete item")
            if (!deleted) {
                body.put("error", "تعذر حذف العنصر من المجلد المشترك")
            }
            if (!reqId.isNullOrBlank()) {
                body.put("reqId", reqId)
            }
            CommandJsonResult(statusCode = if (deleted) 200 else 500, body = body)
        } catch (e: StorageAccessDeniedException) {
            emitSanitizedLog(
                method = transportLabel,
                endpoint = "/delete",
                message = "رفض محاولة حذف خارج المجلدات المشتركة (403 Forbidden)",
                clientIp = clientId,
                statusCode = 403,
                isError = true
            )
            errorJsonResult(403, e.message ?: "403 Forbidden: Delete outside shared roots is denied", reqId)
        } catch (e: StorageFileNotFoundException) {
            errorJsonResult(404, e.message ?: "404 Not Found: Target item does not exist", reqId)
        } catch (_: Throwable) {
            errorJsonResult(500, "500 Internal Server Error", reqId)
        }
    }

    /**
     * Opens a verified read stream inside an authorized shared root.
     * Throws [StorageAccessDeniedException] (403) or [StorageFileNotFoundException] (404).
     */
    fun openVerifiedReadStream(
        rawPath: String?,
        transportLabel: String = "P2P",
        endpoint: String = "/download",
        clientId: String = "Peer"
    ): Pair<StorageEntryStat, InputStream> {
        try {
            val (stat, stream) = storageGateway.openRead(rawPath)
            emitSanitizedLog(
                method = transportLabel,
                endpoint = endpoint,
                message = "بدء قراءة ملف من [${stat.rootId}] (${RemoteFileItem.formatBytes(stat.sizeBytes)})",
                clientIp = clientId,
                statusCode = 200
            )
            return stat to stream
        } catch (e: StorageAccessDeniedException) {
            emitSanitizedLog(
                method = transportLabel,
                endpoint = endpoint,
                message = "رفض قراءة ملف خارج المجلدات المشتركة (403 Forbidden)",
                clientIp = clientId,
                statusCode = 403,
                isError = true
            )
            throw e
        }
    }

    /**
     * Writes an incoming stream strictly inside an authorized shared root that permits uploads.
     */
    fun handleUploadStream(
        rawDirPath: String?,
        rawFileName: String?,
        inputStream: InputStream,
        expectedBytes: Long = -1L,
        reqId: String? = null,
        transportLabel: String = "P2P",
        clientId: String = "Peer"
    ): CommandJsonResult {
        return try {
            val stat = storageGateway.writeStream(
                rawDirPath = rawDirPath,
                rawFileName = rawFileName,
                inputStream = inputStream,
                expectedBytes = expectedBytes
            )
            emitSanitizedLog(
                method = transportLabel,
                endpoint = "/upload",
                message = "اكتمل حفظ ملف في [${stat.rootId}] (${RemoteFileItem.formatBytes(stat.sizeBytes)})",
                clientIp = clientId,
                statusCode = 200
            )
            val body = JSONObject()
                .put("success", true)
                .put("statusCode", 200)
                .put("name", stat.name)
                .put("path", stat.opaquePath)
                .put("rootId", stat.rootId)
                .put("relativePath", stat.relativePath)
                .put("sizeBytes", stat.sizeBytes)
            if (!reqId.isNullOrBlank()) {
                body.put("reqId", reqId)
            }
            CommandJsonResult(statusCode = 200, body = body)
        } catch (e: StorageAccessDeniedException) {
            emitSanitizedLog(
                method = transportLabel,
                endpoint = "/upload",
                message = "رفض رفع ملف خارج المجلدات المشتركة أو لمجلد للقراءة فقط (403 Forbidden)",
                clientIp = clientId,
                statusCode = 403,
                isError = true
            )
            errorJsonResult(403, e.message ?: "403 Forbidden: Upload denied", reqId)
        } catch (e: StorageFileNotFoundException) {
            errorJsonResult(404, e.message ?: "404 Not Found: Target upload directory not found", reqId)
        } catch (_: Throwable) {
            errorJsonResult(500, "500 Internal Server Error during upload", reqId)
        }
    }

    fun captureScreenshotJpegBytes(quality: Int): ByteArray? {
        val clampedQuality = quality.coerceIn(30, 95)
        val bitmap = screenshotBitmapProvider() ?: return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, baos)
        return baos.toByteArray()
    }

    fun errorJsonResult(statusCode: Int, message: String, reqId: String? = null): CommandJsonResult {
        val body = JSONObject()
            .put("success", false)
            .put("statusCode", statusCode)
            .put("error", message)
        if (!reqId.isNullOrBlank()) {
            body.put("reqId", reqId)
        }
        return CommandJsonResult(statusCode = statusCode, body = body)
    }

    fun emitSanitizedLog(
        method: String,
        endpoint: String,
        message: String,
        clientIp: String,
        statusCode: Int,
        isError: Boolean = false
    ) {
        onLog(
            ServerLog(
                method = method,
                endpoint = endpoint,
                message = message,
                clientIp = clientIp,
                statusCode = statusCode,
                isError = isError
            )
        )
    }
}
