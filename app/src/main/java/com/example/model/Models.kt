package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow

enum class AppMode {
    NONE,
    HOME_NODE_SERVER,
    ADMIN_CONTROLLER
}

@IgnoreExtraProperties
data class HomeNodeInfo(
    @DocumentId val id: String = "main",
    val userId: String = "",
    val deviceName: String = "",
    val ipAddress: String = "",
    val port: Int = 8080,
    @get:PropertyName("isOnline")
    @field:PropertyName("isOnline")
    val isOnline: Boolean = false,
    val secretToken: String = "",
    val batteryLevel: Int = 100,
    val freeStorageBytes: Long = 0L,
    val totalStorageBytes: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun baseUrl(): String = "http://$ipAddress:$port"

    fun usedStorageBytes(): Long = (totalStorageBytes - freeStorageBytes).coerceAtLeast(0L)

    fun usedStorageFraction(): Float {
        if (totalStorageBytes <= 0L) return 0f
        return (usedStorageBytes().toDouble() / totalStorageBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun toFirestoreCreateMap(uid: String): Map<String, Any> {
        return mapOf(
            "userId" to uid,
            "deviceName" to deviceName.take(120).ifBlank { "Android Home Node" },
            "ipAddress" to ipAddress.take(64),
            "port" to port.coerceIn(1024, 65535),
            "isOnline" to isOnline,
            "secretToken" to secretToken.take(128),
            "batteryLevel" to batteryLevel.coerceIn(0, 100),
            "freeStorageBytes" to freeStorageBytes.coerceAtLeast(0L),
            "totalStorageBytes" to totalStorageBytes.coerceAtLeast(0L),
            "lastUpdated" to lastUpdated.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    fun toFirestoreUpdateMap(): Map<String, Any> {
        return mapOf(
            "deviceName" to deviceName.take(120).ifBlank { "Android Home Node" },
            "ipAddress" to ipAddress.take(64),
            "port" to port.coerceIn(1024, 65535),
            "isOnline" to isOnline,
            "secretToken" to secretToken.take(128),
            "batteryLevel" to batteryLevel.coerceIn(0, 100),
            "freeStorageBytes" to freeStorageBytes.coerceAtLeast(0L),
            "totalStorageBytes" to totalStorageBytes.coerceAtLeast(0L),
            "lastUpdated" to lastUpdated.coerceAtLeast(0L),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    fun toRealtimeDbMap(): Map<String, Any> {
        return mapOf(
            "deviceName" to deviceName.take(120).ifBlank { "Android Home Node" },
            "ipAddress" to ipAddress.take(64),
            "port" to port.coerceIn(1024, 65535),
            "isOnline" to isOnline,
            "secretToken" to secretToken.take(128),
            "batteryLevel" to batteryLevel.coerceIn(0, 100),
            "freeStorageBytes" to freeStorageBytes.coerceAtLeast(0L),
            "totalStorageBytes" to totalStorageBytes.coerceAtLeast(0L),
            "lastUpdated" to lastUpdated.coerceAtLeast(0L)
        )
    }
}

data class RemoteFileItem(
    val name: String = "",
    val path: String = "",
    val isDirectory: Boolean = false,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isVideoFile(): Boolean = !isDirectory && extension in VIDEO_EXTENSIONS

    fun isAudioFile(): Boolean = !isDirectory && extension in AUDIO_EXTENSIONS

    fun isStreamableMedia(): Boolean = !isDirectory && (isVideoFile() || isAudioFile())

    fun isImageFile(): Boolean = !isDirectory && extension in IMAGE_EXTENSIONS

    fun isArchiveFile(): Boolean = !isDirectory && extension in ARCHIVE_EXTENSIONS

    fun isDocumentFile(): Boolean = !isDirectory && extension in DOCUMENT_EXTENSIONS

    fun formattedSize(): String = if (isDirectory) "مجلد" else formatBytes(sizeBytes)

    fun formattedDate(): String {
        if (lastModified <= 0L) return ""
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(lastModified))
    }

    fun mimeType(): String = resolveMimeType(name)

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi")
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg")
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2")
        val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "json", "xml", "csv")

        fun resolveMimeType(fileName: String): String {
            return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                "avi" -> "video/x-msvideo"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg", "opus" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "pdf" -> "application/pdf"
                "txt", "md", "csv", "log" -> "text/plain; charset=utf-8"
                "json" -> "application/json"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
            val value = bytes / 1024.0.pow(digitGroups.toDouble())
            return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
        }
    }
}

data class ServerLog(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val method: String = "GET",
    val endpoint: String = "",
    val message: String = "",
    val clientIp: String = "",
    val statusCode: Int = 200,
    val isError: Boolean = false
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

data class TransferProgress(
    val isActive: Boolean = false,
    val fileName: String = "",
    val isUpload: Boolean = false,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val statusMessage: String = ""
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val percentage: Int
        get() = (progressFraction * 100).toInt().coerceIn(0, 100)
}
