package com.example.server

import com.example.storage.StorageAccessDeniedException
import com.example.storage.StorageFileNotFoundException
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Optional local HTTP server delegating all authentication and file operations to [FileCommandHandler].
 *
 * Security hardening (Phase 1):
 * - Disabled by default; only started if the user explicitly enables "LAN HTTP Debug Mode" in settings.
 * - Accepts `x-auth-token` strictly from HTTP request headers (never from query parameters `?token=` or `?x-auth-token=`).
 * - Verifies tokens in constant time via [FileCommandHandler.isAuthorizedHeaderToken] and rejects when expected tokens are empty.
 * - Does NOT emit `Access-Control-Allow-Origin: *`.
 * - Routes `/files`, `/download`, `/upload`, `/delete`, and `/stream` through [FileCommandHandler] -> [com.example.storage.StorageGateway],
 *   enforcing SAF shared roots and rejecting traversal or non-shared paths with `403 Forbidden`.
 */
class LocalFileServer(
    val serverPort: Int = DEFAULT_PORT,
    val commandHandler: FileCommandHandler
) : NanoHTTPD(serverPort) {

    override fun serve(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress ?: "Unknown"
        val uri = session.uri ?: "/"
        val method = session.method ?: Method.GET

        // Extract x-auth-token strictly from HTTP request headers (NEVER from query string)
        val headerToken = session.headers.entries
            .firstOrNull {
                it.key.equals("x-auth-token", ignoreCase = true) ||
                    it.key.equals("http_x_auth_token", ignoreCase = true)
            }
            ?.value

        if (!commandHandler.isAuthorizedHeaderToken(headerToken, method.name, uri, clientIp)) {
            val unauth = commandHandler.unauthorizedResult()
            return jsonResponse(Response.Status.UNAUTHORIZED, unauth.body)
        }

        return try {
            when {
                method == Method.GET && uri == "/status" -> {
                    val res = commandHandler.handleStatus(transportLabel = "HTTP", clientId = clientIp)
                    toNanoJsonResponse(res)
                }
                method == Method.GET && uri == "/screenshot" -> handleScreenshotRequest(session)
                method == Method.GET && uri == "/files" -> {
                    val rawPath = getQueryParam(session, "path")
                    val res = commandHandler.handleListFiles(
                        rawPath = rawPath,
                        transportLabel = "HTTP",
                        clientId = clientIp
                    )
                    toNanoJsonResponse(res)
                }
                method == Method.GET && uri == "/download" -> handleDownloadRequest(session, clientIp)
                method == Method.POST && uri == "/upload" -> handleUploadRequest(session, clientIp)
                method == Method.DELETE && uri == "/delete" -> {
                    val rawPath = getQueryParam(session, "path")
                    if (rawPath.isNullOrBlank()) {
                        return toNanoJsonResponse(
                            commandHandler.errorJsonResult(400, "400 Bad Request: Missing 'path' query parameter")
                        )
                    }
                    val res = commandHandler.handleDelete(
                        rawPath = rawPath,
                        transportLabel = "HTTP",
                        clientId = clientIp
                    )
                    toNanoJsonResponse(res)
                }
                method == Method.GET && uri == "/stream" -> handleStreamRequest(session, clientIp)
                else -> {
                    commandHandler.emitSanitizedLog(
                        method = method.name,
                        endpoint = uri,
                        message = "مسار غير موجود (404)",
                        clientIp = clientIp,
                        statusCode = 404,
                        isError = true
                    )
                    toNanoJsonResponse(
                        commandHandler.errorJsonResult(404, "Endpoint not found: ${method.name} $uri")
                    )
                }
            }
        } catch (e: StorageAccessDeniedException) {
            toNanoJsonResponse(
                commandHandler.errorJsonResult(403, e.message ?: "403 Forbidden")
            )
        } catch (e: StorageFileNotFoundException) {
            toNanoJsonResponse(
                commandHandler.errorJsonResult(404, e.message ?: "404 Not Found")
            )
        } catch (_: Exception) {
            commandHandler.emitSanitizedLog(
                method = method.name,
                endpoint = uri,
                message = "خطأ داخلي في الخادم (500)",
                clientIp = clientIp,
                statusCode = 500,
                isError = true
            )
            toNanoJsonResponse(
                commandHandler.errorJsonResult(500, "500 Internal Server Error")
            )
        }
    }

    private fun handleScreenshotRequest(session: IHTTPSession): Response {
        val quality = getQueryParam(session, "quality")?.toIntOrNull()?.coerceIn(30, 95) ?: 78
        val jpegBytes = commandHandler.captureScreenshotJpegBytes(quality)
            ?: return toNanoJsonResponse(
                commandHandler.errorJsonResult(503, "Unable to capture screen Bitmap on Home Node")
            )

        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_JPEG,
            ByteArrayInputStream(jpegBytes),
            jpegBytes.size.toLong()
        ).apply {
            addHeader("Content-Length", jpegBytes.size.toString())
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            addHeader("Pragma", "no-cache")
            addHeader("X-Timestamp", System.currentTimeMillis().toString())
        }
    }

    private fun handleDownloadRequest(session: IHTTPSession, clientIp: String): Response {
        val rawPath = getQueryParam(session, "path")
            ?: return toNanoJsonResponse(
                commandHandler.errorJsonResult(400, "Missing 'path' query parameter")
            )

        return try {
            val (stat, inputStream) = commandHandler.openVerifiedReadStream(
                rawPath = rawPath,
                transportLabel = "HTTP",
                endpoint = "/download",
                clientId = clientIp
            )
            val encodedFileName = URLEncoder.encode(stat.name, "UTF-8").replace("+", "%20")
            newChunkedResponse(Response.Status.OK, stat.mimeType, inputStream).apply {
                addHeader("Content-Length", stat.sizeBytes.toString())
                addHeader(
                    "Content-Disposition",
                    "attachment; filename=\"${stat.name}\"; filename*=UTF-8''$encodedFileName"
                )
                addHeader("Accept-Ranges", "bytes")
            }
        } catch (e: StorageAccessDeniedException) {
            toNanoJsonResponse(commandHandler.errorJsonResult(403, e.message ?: "403 Forbidden"))
        } catch (e: StorageFileNotFoundException) {
            toNanoJsonResponse(commandHandler.errorJsonResult(404, e.message ?: "404 Not Found"))
        }
    }

    private fun handleUploadRequest(session: IHTTPSession, clientIp: String): Response {
        val rawDirPath = getQueryParam(session, "path")
        val rawFileName = getQueryParam(session, "filename")
            ?: return toNanoJsonResponse(
                commandHandler.errorJsonResult(400, "Missing 'filename' query parameter")
            )

        val contentLengthHeader = session.headers["content-length"] ?: session.headers["Content-Length"]
        val expectedBytes = contentLengthHeader?.toLongOrNull() ?: -1L

        val res = commandHandler.handleUploadStream(
            rawDirPath = rawDirPath,
            rawFileName = rawFileName,
            inputStream = session.inputStream,
            expectedBytes = expectedBytes,
            transportLabel = "HTTP",
            clientId = clientIp
        )
        return toNanoJsonResponse(res)
    }

    private fun handleStreamRequest(session: IHTTPSession, clientIp: String): Response {
        val rawPath = getQueryParam(session, "path")
            ?: return toNanoJsonResponse(
                commandHandler.errorJsonResult(400, "Missing 'path' query parameter")
            )

        val (stat, stream) = try {
            commandHandler.openVerifiedReadStream(
                rawPath = rawPath,
                transportLabel = "HTTP",
                endpoint = "/stream",
                clientId = clientIp
            )
        } catch (e: StorageAccessDeniedException) {
            return toNanoJsonResponse(commandHandler.errorJsonResult(403, e.message ?: "403 Forbidden"))
        } catch (e: StorageFileNotFoundException) {
            return toNanoJsonResponse(commandHandler.errorJsonResult(404, e.message ?: "404 Not Found"))
        }

        val fileLength = stat.sizeBytes
        val mimeType = stat.mimeType
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeValue = rangeHeader.removePrefix("bytes=").trim()
            val parts = rangeValue.split("-")
            val startFrom = parts.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val requestedEnd = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            val endAt = (requestedEnd ?: (fileLength - 1L)).coerceAtMost(fileLength - 1L)

            if (startFrom >= fileLength || startFrom > endAt) {
                try {
                    stream.close()
                } catch (_: Throwable) {
                }
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
            skipFully(stream, startFrom)

            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                stream,
                contentLength
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLength")
                addHeader("Content-Length", contentLength.toString())
                addHeader("Connection", "keep-alive")
            }
        } else {
            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                stream,
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

    private fun toNanoJsonResponse(result: CommandJsonResult): Response {
        val status = when (result.statusCode) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.INTERNAL_ERROR
        }
        return jsonResponse(status, result.body)
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        return newFixedLengthResponse(status, MIME_JSON, body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MIME_JPEG = "image/jpeg"
    }
}
