package com.example.server

import com.example.model.RemoteFileItem
import com.example.model.ServerLog
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

class LocalFileServer(
    val serverPort: Int = DEFAULT_PORT,
    private val secretTokenProvider: () -> String,
    private val statusProvider: () -> Triple<Int, Long, Long>,
    private val rootDirProvider: () -> File,
    private val onLog: (ServerLog) -> Unit
) : NanoHTTPD(serverPort) {

    override fun serve(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "Unknown"
        val uri = session.uri ?: "/"
        val method = session.method ?: Method.GET

        // 1. Zero-Trust Session Token Verification via x-auth-token Header
        val expectedToken = secretTokenProvider()
        val providedToken = session.headers["x-auth-token"]
            ?: session.headers["X-Auth-Token"]
            ?: ""

        if (expectedToken.isNotBlank() && providedToken != expectedToken) {
            emitLog(
                method = method.name,
                endpoint = uri,
                message = "رفض طلب غير مصرح به (401 Unauthorized) من $clientIp",
                clientIp = clientIp,
                statusCode = 401,
                isError = true
            )
            return jsonResponse(
                status = Response.Status.UNAUTHORIZED,
                body = JSONObject()
                    .put("success", false)
                    .put("error", "401 Unauthorized: Invalid or missing x-auth-token header")
            )
        }

        return try {
            when {
                method == Method.GET && uri == "/status" -> handleStatusRequest(clientIp)
                method == Method.GET && uri == "/files" -> handleFilesListRequest(session, clientIp)
                method == Method.GET && uri == "/download" -> handleDownloadRequest(session, clientIp)
                method == Method.POST && uri == "/upload" -> handleUploadRequest(session, clientIp)
                method == Method.DELETE && uri == "/delete" -> handleDeleteRequest(session, clientIp)
                method == Method.GET && uri == "/stream" -> handleStreamRequest(session, clientIp)
                else -> {
                    emitLog(
                        method = method.name,
                        endpoint = uri,
                        message = "مسار غير موجود (404): $uri",
                        clientIp = clientIp,
                        statusCode = 404,
                        isError = true
                    )
                    jsonResponse(
                        status = Response.Status.NOT_FOUND,
                        body = JSONObject()
                            .put("success", false)
                            .put("error", "Endpoint not found: ${method.name} $uri")
                    )
                }
            }
        } catch (e: Exception) {
            emitLog(
                method = method.name,
                endpoint = uri,
                message = "خطأ داخلي في الخادم: ${e.localizedMessage ?: e.javaClass.simpleName}",
                clientIp = clientIp,
                statusCode = 500,
                isError = true
            )
            jsonResponse(
                status = Response.Status.INTERNAL_ERROR,
                body = JSONObject()
                    .put("success", false)
                    .put("error", e.localizedMessage ?: "Internal server error")
            )
        }
    }

    /**
     * 1. GET /status
     * Returns live battery level, free storage bytes, and total storage bytes.
     */
    private fun handleStatusRequest(clientIp: String): Response {
        val (batteryLevel, freeBytes, totalBytes) = statusProvider()
        val rootPath = rootDirProvider().absolutePath
        emitLog(
            method = "GET",
            endpoint = "/status",
            message = "استعلام حالة الخادم من $clientIp (البطارية $batteryLevel%)",
            clientIp = clientIp,
            statusCode = 200
        )
        val payload = JSONObject()
            .put("success", true)
            .put("status", "online")
            .put("batteryLevel", batteryLevel)
            .put("freeStorageBytes", freeBytes)
            .put("totalStorageBytes", totalBytes)
            .put("rootPath", rootPath)
            .put("timestamp", System.currentTimeMillis())
        return jsonResponse(Response.Status.OK, payload)
    }

    /**
     * 2. GET /files?path={dirPath}
     * Lists directory contents sorted (folders first, then files alphabetically).
     */
    private fun handleFilesListRequest(session: IHTTPSession, clientIp: String): Response {
        val defaultRoot = rootDirProvider()
        val requestedPath = getQueryParam(session, "path")?.takeIf { it.isNotBlank() }
            ?: defaultRoot.absolutePath

        val targetDir = File(requestedPath)
        val effectiveDir = if (targetDir.exists() && targetDir.isDirectory && targetDir.canRead()) {
            targetDir
        } else {
            defaultRoot
        }

        val rawFiles = effectiveDir.listFiles()?.toList() ?: emptyList()
        val sortedFiles = rawFiles.sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )

        val filesArray = JSONArray()
        for (file in sortedFiles) {
            val fileObj = JSONObject()
                .put("name", file.name)
                .put("path", file.absolutePath)
                .put("isDirectory", file.isDirectory)
                .put("sizeBytes", if (file.isDirectory) 0L else file.length())
                .put("lastModified", file.lastModified())
            filesArray.put(fileObj)
        }

        emitLog(
            method = "GET",
            endpoint = "/files",
            message = "تصفح المجلد: ${effectiveDir.absolutePath} (${sortedFiles.size} عنصر)",
            clientIp = clientIp,
            statusCode = 200
        )

        val parentPath = effectiveDir.parentFile?.takeIf { it.canRead() }?.absolutePath ?: ""
        val responseBody = JSONObject()
            .put("success", true)
            .put("currentPath", effectiveDir.absolutePath)
            .put("parentPath", parentPath)
            .put("rootPath", defaultRoot.absolutePath)
            .put("files", filesArray)

        return jsonResponse(Response.Status.OK, responseBody)
    }

    /**
     * 3. GET /download?path={filePath}
     * Streams the file using newChunkedResponse with Content-Length and Content-Disposition.
     */
    private fun handleDownloadRequest(session: IHTTPSession, clientIp: String): Response {
        val filePath = getQueryParam(session, "path")
            ?: return badRequest("Missing 'path' query parameter")

        val targetFile = File(filePath)
        if (!targetFile.exists() || !targetFile.isFile || !targetFile.canRead()) {
            emitLog(
                method = "GET",
                endpoint = "/download",
                message = "ملف غير موجود للتحميل: $filePath",
                clientIp = clientIp,
                statusCode = 404,
                isError = true
            )
            return jsonResponse(
                Response.Status.NOT_FOUND,
                JSONObject().put("success", false).put("error", "File not found or unreadable: $filePath")
            )
        }

        val mimeType = RemoteFileItem.resolveMimeType(targetFile.name)
        val fileLength = targetFile.length()
        val encodedFileName = URLEncoder.encode(targetFile.name, "UTF-8").replace("+", "%20")

        emitLog(
            method = "GET",
            endpoint = "/download",
            message = "بدء سحب الملف: ${targetFile.name} (${RemoteFileItem.formatBytes(fileLength)})",
            clientIp = clientIp,
            statusCode = 200
        )

        val fis = FileInputStream(targetFile)
        return newChunkedResponse(Response.Status.OK, mimeType, fis).apply {
            addHeader("Content-Length", fileLength.toString())
            addHeader(
                "Content-Disposition",
                "attachment; filename=\"${targetFile.name}\"; filename*=UTF-8''$encodedFileName"
            )
            addHeader("Accept-Ranges", "bytes")
        }
    }

    /**
     * 4. POST /upload?path={targetDir}&filename={fileName}
     * Streams incoming InputStream directly to disk in 64KB chunks without loading into RAM.
     */
    private fun handleUploadRequest(session: IHTTPSession, clientIp: String): Response {
        val targetDirPath = getQueryParam(session, "path")?.takeIf { it.isNotBlank() }
            ?: rootDirProvider().absolutePath
        val rawFileName = getQueryParam(session, "filename")?.takeIf { it.isNotBlank() }
            ?: "uploaded_${System.currentTimeMillis()}.bin"

        val safeFileName = File(rawFileName).name
        val targetDir = File(targetDirPath)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        if (!targetDir.isDirectory || !targetDir.canWrite()) {
            return badRequest("Target directory is not writable: $targetDirPath")
        }

        val destFile = File(targetDir, safeFileName)
        val contentLengthHeader = session.headers["content-length"]
            ?: session.headers["Content-Length"]
        val expectedBytes = contentLengthHeader?.toLongOrNull() ?: -1L

        emitLog(
            method = "POST",
            endpoint = "/upload",
            message = "جاري استقبال ملف: $safeFileName إلى ${targetDir.name}",
            clientIp = clientIp,
            statusCode = 200
        )

        var totalWritten = 0L
        val buffer = ByteArray(64 * 1024)
        val inputStream = session.inputStream

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

        emitLog(
            method = "POST",
            endpoint = "/upload",
            message = "اكتمل رفع الملف: $safeFileName (${RemoteFileItem.formatBytes(totalWritten)})",
            clientIp = clientIp,
            statusCode = 200
        )

        val payload = JSONObject()
            .put("success", true)
            .put("name", destFile.name)
            .put("path", destFile.absolutePath)
            .put("sizeBytes", totalWritten)

        return jsonResponse(Response.Status.OK, payload)
    }

    /**
     * 5. DELETE /delete?path={targetPath}
     * Deletes the requested file or folder and returns the operation result.
     */
    private fun handleDeleteRequest(session: IHTTPSession, clientIp: String): Response {
        val targetPath = getQueryParam(session, "path")
            ?: return badRequest("Missing 'path' query parameter")

        val targetFile = File(targetPath)
        if (!targetFile.exists()) {
            return jsonResponse(
                Response.Status.NOT_FOUND,
                JSONObject().put("success", false).put("error", "Target file does not exist: $targetPath")
            )
        }

        val fileName = targetFile.name
        val deleted = if (targetFile.isDirectory) {
            targetFile.deleteRecursively()
        } else {
            targetFile.delete()
        }

        emitLog(
            method = "DELETE",
            endpoint = "/delete",
            message = if (deleted) "تم حذف: $fileName بنجاح" else "فشل حذف: $fileName",
            clientIp = clientIp,
            statusCode = if (deleted) 200 else 500,
            isError = !deleted
        )

        val payload = JSONObject()
            .put("success", deleted)
            .put("path", targetPath)
            .put("message", if (deleted) "Deleted successfully" else "Failed to delete file")

        return jsonResponse(
            if (deleted) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            payload
        )
    }

    /**
     * 6. GET /stream?path={filePath}
     * RFC 7233 HTTP Byte-Range video & audio streaming endpoint with 206 Partial Content
     * and FileInputStream.skip(startFrom) for instant seeking without full download.
     */
    private fun handleStreamRequest(session: IHTTPSession, clientIp: String): Response {
        val filePath = getQueryParam(session, "path")
            ?: return badRequest("Missing 'path' query parameter")

        val mediaFile = File(filePath)
        if (!mediaFile.exists() || !mediaFile.isFile || !mediaFile.canRead()) {
            emitLog(
                method = "GET",
                endpoint = "/stream",
                message = "ملف الوسائط غير موجود للبث: $filePath",
                clientIp = clientIp,
                statusCode = 404,
                isError = true
            )
            return jsonResponse(
                Response.Status.NOT_FOUND,
                JSONObject().put("success", false).put("error", "Media file not found: $filePath")
            )
        }

        val fileLength = mediaFile.length()
        val mimeType = RemoteFileItem.resolveMimeType(mediaFile.name)
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeValue = rangeHeader.removePrefix("bytes=").trim()
            val parts = rangeValue.split("-")
            val startFrom = parts.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val requestedEnd = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            val endAt = (requestedEnd ?: (fileLength - 1L)).coerceAtMost(fileLength - 1L)

            if (startFrom >= fileLength || startFrom > endAt) {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    MIME_PLAINTEXT,
                    ""
                ).apply {
                    addHeader("Content-Range", "bytes */$fileLength")
                    addHeader("Accept-Ranges", "bytes")
                }
            }

            val contentLength = (endAt - startFrom) + 1L
            val fis = FileInputStream(mediaFile)
            skipFully(fis, startFrom)

            emitLog(
                method = "GET",
                endpoint = "/stream",
                message = "بث جزئي (206): ${mediaFile.name} [$startFrom-$endAt/$fileLength]",
                clientIp = clientIp,
                statusCode = 206
            )

            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                fis,
                contentLength
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLength")
                addHeader("Content-Length", contentLength.toString())
                addHeader("Connection", "keep-alive")
            }
        } else {
            val fis = FileInputStream(mediaFile)
            emitLog(
                method = "GET",
                endpoint = "/stream",
                message = "بدء البث المباشر (200): ${mediaFile.name} (${RemoteFileItem.formatBytes(fileLength)})",
                clientIp = clientIp,
                statusCode = 200
            )
            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                fis,
                fileLength
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", fileLength.toString())
                addHeader("Connection", "keep-alive")
            }
        }
    }

    private fun skipFully(inputStream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0L) {
                if (inputStream.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun getQueryParam(session: IHTTPSession, key: String): String? {
        val list = session.parameters[key]
        if (!list.isNullOrEmpty()) {
            return list.firstOrNull()
        }
        val rawQuery = session.queryParameterString ?: return null
        for (pair in rawQuery.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                if (k == key) {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }
        }
        return null
    }

    private fun badRequest(message: String): Response {
        return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("success", false).put("error", message)
        )
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        return newFixedLengthResponse(status, MIME_JSON, body.toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun emitLog(
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

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MIME_JSON = "application/json; charset=utf-8"
    }
}
