package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.HomeNodeInfo
import com.example.model.ServerLog
import com.example.repository.SmartCloudRepository
import com.example.server.LocalFileServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

class HomeServerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var localFileServer: LocalFileServer? = null
    private lateinit var repository: SmartCloudRepository
    private var sessionSecretToken: String = UUID.randomUUID().toString()
    private var activePort: Int = LocalFileServer.DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        repository = SmartCloudRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                appendLog(
                    ServerLog(
                        method = "SYSTEM",
                        endpoint = "/service",
                        message = "جاري إيقاف الخادم المنزلي بناءً على طلب المستخدم..."
                    )
                )
                stopServerAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!_isRunning.value) {
                    val requestedPort = intent?.getIntExtra(EXTRA_PORT, LocalFileServer.DEFAULT_PORT)
                        ?: LocalFileServer.DEFAULT_PORT
                    startHomeNodeServer(requestedPort)
                }
            }
        }
        return START_STICKY
    }

    private fun startHomeNodeServer(port: Int) {
        activePort = port
        sessionSecretToken = UUID.randomUUID().toString()

        acquireHardwareLocks()
        ensureSampleCloudFilesIfNeeded()

        val initialIp = resolveLocalIpv4Address()
        val notification = buildForegroundNotification(initialIp, activePort)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            localFileServer?.stop()
            localFileServer = LocalFileServer(
                serverPort = activePort,
                secretTokenProvider = { sessionSecretToken },
                statusProvider = { readHardwareStatus() },
                rootDirProvider = { resolveStorageRootDir() },
                onLog = { logItem -> appendLog(logItem) }
            ).also { server ->
                server.start(NanoHttpdSocketTimeoutMs, false)
            }

            _isRunning.value = true
            appendLog(
                ServerLog(
                    method = "SYSTEM",
                    endpoint = "/boot",
                    message = "تم تشغيل خادم NanoHTTPD بنجاح على العنوان $initialIp:$activePort"
                )
            )

            registerNetworkCallback()
            startTelemetryHeartbeatLoop()
        } catch (e: Exception) {
            appendLog(
                ServerLog(
                    method = "ERROR",
                    endpoint = "/boot",
                    message = "تعذر تشغيل الخادم المحلي: ${e.localizedMessage}",
                    statusCode = 500,
                    isError = true
                )
            )
            stopServerAndSelf()
        }
    }

    private fun startTelemetryHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && _isRunning.value) {
                syncNodeStateWithCloud(isOnline = true)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun syncNodeStateWithCloud(isOnline: Boolean) {
        val ip = resolveLocalIpv4Address()
        val (battery, freeBytes, totalBytes) = readHardwareStatus()
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

        val info = HomeNodeInfo(
            id = "main",
            deviceName = deviceName,
            ipAddress = ip,
            port = activePort,
            isOnline = isOnline,
            secretToken = sessionSecretToken,
            batteryLevel = battery,
            freeStorageBytes = freeBytes,
            totalStorageBytes = totalBytes,
            lastUpdated = System.currentTimeMillis()
        )

        _currentNodeInfo.value = info
        updateForegroundNotification(ip, activePort)

        val result = repository.publishHomeNodeInfo(info, registerOnDisconnect = isOnline)
        result.onFailure { error ->
            appendLog(
                ServerLog(
                    method = "SYNC",
                    endpoint = "firebase/home_node",
                    message = "تنبيه مزامنة السحابة: ${error.localizedMessage ?: "خطأ غير معروف"}",
                    statusCode = 503,
                    isError = true
                )
            )
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                val newIp = resolveLocalIpv4Address()
                val previousIp = _currentNodeInfo.value?.ipAddress
                if (newIp.isNotBlank() && newIp != previousIp) {
                    appendLog(
                        ServerLog(
                            method = "NETWORK",
                            endpoint = "/wifi-ip",
                            message = "تم اكتشاف تغير في عنوان الشبكة المحلية: $newIp (تحديث تلقائي في السحابة)"
                        )
                    )
                    serviceScope.launch {
                        syncNodeStateWithCloud(isOnline = true)
                    }
                }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                serviceScope.launch {
                    delay(1000L)
                    syncNodeStateWithCloud(isOnline = true)
                }
            }
        }

        networkCallback = callback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }
    }

    private fun acquireHardwareLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmartHomeCloud::HomeNodeWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "SmartHomeCloud::HomeNodeWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseHardwareLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun readHardwareStatus(): Triple<Int, Long, Long> {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 100

        val rootDir = resolveStorageRootDir()
        return try {
            val statFs = StatFs(rootDir.absolutePath)
            val freeBytes = statFs.availableBytes
            val totalBytes = statFs.totalBytes
            Triple(battery, freeBytes, totalBytes)
        } catch (_: Exception) {
            Triple(battery, 0L, 0L)
        }
    }

    private fun resolveStorageRootDir(): File {
        val externalRoot = Environment.getExternalStorageDirectory()
        if (externalRoot.exists() && externalRoot.canRead() && !externalRoot.listFiles().isNullOrEmpty()) {
            return externalRoot
        }
        val appExternal = getExternalFilesDir(null)
        if (appExternal != null && appExternal.exists() && appExternal.canRead()) {
            return appExternal
        }
        return filesDir
    }

    /**
     * Ensures that if the device/emulator has restricted root storage or empty folders,
     * accessible sample folders & files exist so the Admin Controller can browse immediately.
     */
    private fun ensureSampleCloudFilesIfNeeded() {
        try {
            val root = resolveStorageRootDir()
            val cloudFolder = File(root, "SmartHomeCloud_Shared")
            if (!cloudFolder.exists()) {
                cloudFolder.mkdirs()
                File(cloudFolder, "Welcome_SmartHomeCloud.txt").writeText(
                    "مرحباً بك في نظام السحابة المنزلية الذكية (Smart Home Cloud)!\n" +
                        "يمكنك تصفح الملفات، سحبها، رفع ملفات جديدة، أو بث الوسائط مباشرة عبر الشبكة المحلية."
                )
                val docsDir = File(cloudFolder, "Documents").apply { mkdirs() }
                File(docsDir, "Home_Server_Specs.json").writeText(
                    "{\n  \"server\": \"NanoHTTPD 2.3.1\",\n  \"streaming\": \"RFC 7233 Byte-Range 206 Partial Content\"\n}"
                )
                val mediaDir = File(cloudFolder, "Media_Samples").apply { mkdirs() }
                createSampleWavFile(File(mediaDir, "SmartHome_Chime_Sample.wav"))
            }
        } catch (_: Exception) {
        }
    }

    private fun createSampleWavFile(target: File) {
        if (target.exists()) return
        val sampleRate = 8000
        val durationSeconds = 3
        val numSamples = sampleRate * durationSeconds
        val dataSize = numSamples * 2
        val totalDataLen = dataSize + 36
        val byteRate = sampleRate * 2

        FileOutputStream(target).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            writeIntLE(header, 4, totalDataLen)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeIntLE(header, 16, 16)
            header[20] = 1; header[21] = 0 // PCM
            header[22] = 1; header[23] = 0 // Mono
            writeIntLE(header, 24, sampleRate)
            writeIntLE(header, 28, byteRate)
            header[32] = 2; header[33] = 0 // Block align
            header[34] = 16; header[35] = 0 // Bits per sample
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            writeIntLE(header, 40, dataSize)
            out.write(header)

            val pcm = ByteArray(dataSize)
            for (i in 0 until numSamples) {
                val freq = if (i < numSamples / 2) 440.0 else 554.37
                val angle = 2.0 * Math.PI * i * freq / sampleRate
                val sample = (Math.sin(angle) * 12000).toInt().toShort()
                pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            out.write(pcm)
        }
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun resolveLocalIpv4Address(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(activeNet)
            val linkIpv4 = linkProps?.linkAddresses
                ?.mapNotNull { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
            if (!linkIpv4.isNullOrBlank()) {
                return linkIpv4
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (host.startsWith("192.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    private fun stopServerAndSelf() {
        _isRunning.value = false
        heartbeatJob?.cancel()

        try {
            localFileServer?.stop()
        } catch (_: Exception) {
        }
        localFileServer = null

        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null

        releaseHardwareLocks()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val offlineNode = (_currentNodeInfo.value ?: HomeNodeInfo()).copy(
                    isOnline = false,
                    lastUpdated = System.currentTimeMillis()
                )
                _currentNodeInfo.value = offlineNode
                repository.markHomeNodeOffline(offlineNode)
            } catch (_: Exception) {
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        if (_isRunning.value) {
            stopServerAndSelf()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(ipAddress: String, port: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HomeServerForegroundService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.server_running_title))
            .setContentText("متصل على الشبكة: http://$ipAddress:$port")
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_server_action),
                stopPendingIntent
            )
            .build()
    }

    private fun updateForegroundNotification(ipAddress: String, port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(ipAddress, port))
    }

    companion object {
        const val ACTION_START_SERVER = "com.example.service.ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "com.example.service.ACTION_STOP_SERVER"
        const val EXTRA_PORT = "extra_port"

        private const val CHANNEL_ID = "smart_home_cloud_server_channel"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val NanoHttpdSocketTimeoutMs = 15_000

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentNodeInfo = MutableStateFlow<HomeNodeInfo?>(null)
        val currentNodeInfo: StateFlow<HomeNodeInfo?> = _currentNodeInfo.asStateFlow()

        private val _serverLogs = MutableStateFlow<List<ServerLog>>(emptyList())
        val serverLogs: StateFlow<List<ServerLog>> = _serverLogs.asStateFlow()

        fun appendLog(log: ServerLog) {
            _serverLogs.value = (listOf(log) + _serverLogs.value).take(200)
        }

        fun clearLogs() {
            _serverLogs.value = emptyList()
        }
    }
}
