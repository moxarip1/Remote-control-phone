package com.example.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import android.view.PixelCopy
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.HomeNodeInfo
import com.example.model.RemoteFileItem
import com.example.model.ServerLog
import com.example.repository.SmartCloudRepository
import com.example.server.LocalFileServer
import com.example.webrtc.WebRtcP2pClientManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
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
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.NV21Buffer
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HomeServerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverBootJob: Job? = null
    private var heartbeatJob: Job? = null
    private var syntheticFramePumpJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var localFileServer: LocalFileServer? = null
    private var repository: SmartCloudRepository? = null
    private var sessionSecretToken: String = UUID.randomUUID().toString()
    private var activePort: Int = LocalFileServer.DEFAULT_PORT

    // WebRTC P2P Engine Components (Shared Factory + Global RTCDataChannel + Video Stream)
    private val webRtcLock = Any()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var activeDataChannel: DataChannel? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    @Volatile
    private var isPeerSessionActive: Boolean = false

    private var rtdbSignalingRef: DatabaseReference? = null
    private var rtdbOfferListener: ValueEventListener? = null
    private var firestoreSignalingRegistration: ListenerRegistration? = null
    private var legacyFirestoreSignalingRegistration: ListenerRegistration? = null

    private val addedRemoteCandidateKeys = mutableSetOf<String>()
    private val pendingAdminCandidates = mutableListOf<IceCandidate>()
    private var isRemoteOfferSet: Boolean = false
    private var isAnswerPublished: Boolean = false
    private val localServerCandidatesArray = JSONArray()
    private var lastHandledOfferKey: String? = null
    private var currentP2pSessionId: String = ""

    // Rate-limited screenshot cache to avoid hammering the UI thread or allocating excessive bitmaps
    private val screenshotCacheLock = Any()
    private var cachedScreenshotBitmap: Bitmap? = null
    private var cachedScreenshotTimeMs: Long = 0L

    private data class IncomingUploadSession(
        val uploadId: String,
        val targetFile: File,
        val outputStream: FileOutputStream,
        var receivedBytes: Long = 0L
    )

    private val activeUploads = ConcurrentHashMap<String, IncomingUploadSession>()

    override fun onCreate() {
        super.onCreate()
        try {
            repository = SmartCloudRepository(applicationContext)
        } catch (_: Throwable) {
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                appendLog(
                    ServerLog(
                        method = "SYSTEM",
                        endpoint = "/service",
                        message = "جاري إيقاف الخادم المنزلي P2P بناءً على طلب المستخدم..."
                    )
                )
                stopServerAndSelf()
                return START_NOT_STICKY
            }

            ACTION_ENABLE_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val dataIntent = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA_INTENT)
                    }
                } catch (_: Throwable) {
                    null
                }
                if (dataIntent != null && resultCode == Activity.RESULT_OK) {
                    mediaProjectionPermissionData = dataIntent
                    mediaProjectionResultCode = resultCode
                    _isMediaProjectionEnabled.value = true

                    val uid = try {
                        FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    val signalingPath = if (uid.isNotBlank()) "users/$uid/signaling" else "users/{uid}/signaling"
                    // Mandatory on Android 14+ (API 34+): promote service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    // before starting ScreenCapturerAndroid.
                    tryPromoteToForeground(signalingPath, includeMediaProjection = true)

                    appendLog(
                        ServerLog(
                            method = "WEBRTC",
                            endpoint = "/media-projection",
                            message = "تم تفعيل صلاحية بث الشاشة الكاملة عبر WebRTC P2P (30 FPS)"
                        )
                    )
                    serviceScope.launch {
                        restartWebRtcCapturerIfNeeded()
                    }
                }
            }

            else -> {
                val requestedPort = intent?.getIntExtra(EXTRA_PORT, LocalFileServer.DEFAULT_PORT)
                    ?: LocalFileServer.DEFAULT_PORT
                val uid = try {
                    FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                val signalingPath = if (uid.isNotBlank()) "users/$uid/signaling" else "users/{uid}/signaling"

                // Always promote safely on start command if notification permission is granted
                tryPromoteToForeground(signalingPath, includeMediaProjection = _isMediaProjectionEnabled.value)

                if (!_isRunning.value) {
                    startHomeNodeServer(requestedPort)
                }
            }
        }
        return START_STICKY
    }

    private fun tryPromoteToForeground(signalingPath: String, includeMediaProjection: Boolean) {
        if (!hasNotificationPermission()) {
            appendLog(
                ServerLog(
                    method = "SYSTEM",
                    endpoint = "/notification",
                    message = "يعمل الخادم المنزلي في الوضع الصامت (صلاحية الإشعارات غير مفعّلة)"
                )
            )
            return
        }

        try {
            val notification = buildForegroundNotification(signalingPath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgType = if (includeMediaProjection && _isMediaProjectionEnabled.value) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, notification, fgType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            // Fall back to basic DATA_SYNC foreground promotion if mediaProjection type was rejected
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeMediaProjection) {
                try {
                    val notification = buildForegroundNotification(signalingPath)
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    return
                } catch (_: Throwable) {
                }
            }
            appendLog(
                ServerLog(
                    method = "SYSTEM",
                    endpoint = "/notification",
                    message = "يعمل الخادم المنزلي في الوضع الصامت (تعذر عرض الإشعار: ${e.javaClass.simpleName})"
                )
            )
        }
    }

    private fun startHomeNodeServer(port: Int) {
        activePort = port
        sessionSecretToken = UUID.randomUUID().toString()
        _isRunning.value = true

        val uid = try {
            FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val signalingPath = if (uid.isNotBlank()) "users/$uid/signaling" else "users/{uid}/signaling"
        _webRtcStateText.value = "بانتظار ربط الآدمن عبر $signalingPath"

        // Boot all hardware locks, file preparation, local HTTP server, and WebRTC signaling
        // asynchronously on Dispatchers.IO so the Main UI Thread never blocks or crashes.
        serverBootJob?.cancel()
        serverBootJob = serviceScope.launch {
            try {
                acquireHardwareLocks()
                ensureSampleCloudFilesIfNeeded()
                startLocalHttpServerWithFallback(port)

                appendLog(
                    ServerLog(
                        method = "WEBRTC-P2P",
                        endpoint = signalingPath,
                        message = "تم تشغيل عقدة المنزل الذكية (WebRTC P2P + RTCDataChannel) عبر مسار الإشارات السحابي"
                    )
                )

                registerNetworkCallback()
                startTelemetryHeartbeatLoop()
                initializeWebRtcServerSignaling()
            } catch (t: Throwable) {
                appendLog(
                    ServerLog(
                        method = "WARN",
                        endpoint = "/boot",
                        message = "ملاحظة أثناء بدء الخادم المنزلي (يستمر الخادم بالعمل): ${t.localizedMessage ?: t.javaClass.simpleName}",
                        isError = false
                    )
                )
            }
        }
    }

    private fun startLocalHttpServerWithFallback(preferredPort: Int) {
        try {
            localFileServer?.stop()
        } catch (_: Throwable) {
        }
        localFileServer = null

        val candidatePorts = listOf(preferredPort, 8081, 8082, 8088, 8888).distinct()
        for (candidatePort in candidatePorts) {
            try {
                val server = LocalFileServer(
                    serverPort = candidatePort,
                    secretTokenProvider = { sessionSecretToken },
                    statusProvider = { readHardwareStatus() },
                    rootDirProvider = { resolveStorageRootDir() },
                    screenshotBitmapProvider = { captureHomeNodeScreenBitmap() },
                    onLog = { logItem -> appendLog(logItem) }
                )
                server.start(NanoHttpdSocketTimeoutMs, false)
                localFileServer = server
                activePort = candidatePort
                return
            } catch (_: Throwable) {
                // Try next port if address is already in use
            }
        }
    }

    // =========================================================================
    // 1. SCREENSHOT BITMAP EXTRACTION (Rate-Limited PixelCopy + Safe Telemetry HUD)
    // =========================================================================

    fun captureHomeNodeScreenBitmap(): Bitmap {
        val now = System.currentTimeMillis()
        synchronized(screenshotCacheLock) {
            val cached = cachedScreenshotBitmap
            if (cached != null && !cached.isRecycled && (now - cachedScreenshotTimeMs) < 400L) {
                return cached.copy(Bitmap.Config.ARGB_8888, false) ?: renderBackgroundTelemetryBitmap(540, 960)
            }
        }

        val captured = captureFreshScreenOrTelemetryBitmap()
        synchronized(screenshotCacheLock) {
            cachedScreenshotBitmap = captured
            cachedScreenshotTimeMs = now
            return captured.copy(Bitmap.Config.ARGB_8888, false) ?: captured
        }
    }

    private fun captureFreshScreenOrTelemetryBitmap(): Bitmap {
        // Only attempt PixelCopy when called off the Main Thread and when the Activity window is actively visible
        if (Looper.myLooper() != Looper.getMainLooper() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activity = activeActivityRef?.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val window = activity.window
                val decorView = window?.peekDecorView()
                if (
                    window != null &&
                    decorView != null &&
                    decorView.isAttachedToWindow &&
                    decorView.isShown &&
                    decorView.width > 0 &&
                    decorView.height > 0
                ) {
                    val targetWidth = 540
                    val targetHeight = ((decorView.height.toFloat() / decorView.width.toFloat()) * targetWidth)
                        .toInt()
                        .coerceIn(480, 1200)

                    try {
                        val pixelBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        val latch = CountDownLatch(1)
                        var copyResult = -1
                        PixelCopy.request(
                            window,
                            pixelBitmap,
                            { result ->
                                copyResult = result
                                latch.countDown()
                            },
                            mainHandler
                        )
                        if (latch.await(300, TimeUnit.MILLISECONDS) && copyResult == PixelCopy.SUCCESS) {
                            stampLiveOverlayBadge(pixelBitmap)
                            return pixelBitmap
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        return renderBackgroundTelemetryBitmap(width = 540, height = 960)
    }

    private fun stampLiveOverlayBadge(bitmap: Bitmap) {
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val (battery, _, _) = readHardwareStatus()

            paint.color = Color.argb(195, 7, 15, 30)
            val badgeRect = RectF(16f, 16f, bitmap.width - 16f, 70f)
            canvas.drawRoundRect(badgeRect, 14f, 14f, paint)

            paint.color = Color.rgb(16, 185, 129)
            canvas.drawCircle(42f, 43f, 8f, paint)

            paint.color = Color.WHITE
            paint.textSize = 19f
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("GLOBAL WEBRTC P2P • BAT $battery% • $timeStr", 62f, 50f, paint)
        } catch (_: Throwable) {
        }
    }

    private fun renderBackgroundTelemetryBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.rgb(10, 25, 47), Color.rgb(17, 34, 64), Color.rgb(7, 15, 30)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val (battery, freeBytes, totalBytes) = readHardwareStatus()
        val deviceName = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        paint.color = Color.rgb(30, 41, 59)
        canvas.drawRoundRect(RectF(28f, 40f, width - 28f, 195f), 24f, 24f, paint)

        paint.color = Color.rgb(56, 189, 248)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 26f
        canvas.drawText("SMART HOME CLOUD • WEBRTC P2P", 48f, 92f, paint)

        paint.color = Color.rgb(16, 185, 129)
        paint.textSize = 19f
        canvas.drawText("● GLOBAL P2P DATA & VIDEO • $nowStr", 48f, 132f, paint)

        paint.color = Color.rgb(148, 163, 184)
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        paint.textSize = 17f
        canvas.drawText("Device: $deviceName | Mode: WebRTC P2P", 48f, 168f, paint)

        paint.color = Color.rgb(15, 23, 42)
        canvas.drawRoundRect(RectF(28f, 220f, width - 28f, 410f), 24f, 24f, paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 21f
        canvas.drawText("Battery Level : $battery%", 52f, 268f, paint)
        canvas.drawText("Free Storage  : ${RemoteFileItem.formatBytes(freeBytes)}", 52f, 312f, paint)
        canvas.drawText("Total Storage : ${RemoteFileItem.formatBytes(totalBytes)}", 52f, 356f, paint)
        canvas.drawText("P2P Channel   : ${_webRtcStateText.value.take(26)}", 52f, 392f, paint)

        paint.color = Color.rgb(15, 23, 42)
        canvas.drawRoundRect(RectF(28f, 435f, width - 28f, height - 40f), 24f, 24f, paint)

        paint.color = Color.rgb(56, 189, 248)
        paint.textSize = 20f
        canvas.drawText("RECENT P2P DATACHANNEL LOGS:", 52f, 476f, paint)

        paint.textSize = 16f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        val recentLogs = _serverLogs.value.take(10)
        var yPos = 515f
        for (log in recentLogs) {
            paint.color = if (log.isError) Color.rgb(248, 113, 113) else Color.rgb(226, 232, 240)
            val line = "[${log.formattedTime()}] ${log.method} ${log.endpoint}"
            canvas.drawText(line.take(42), 52f, yPos, paint)
            yPos += 38f
        }

        return bitmap
    }

    // =========================================================================
    // 2. WEBRTC P2P ENGINE, RTCDATACHANNEL PROTOCOL & FIREBASE SIGNALING
    // =========================================================================

    private fun initializeWebRtcServerSignaling() {
        val uid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Throwable) {
            null
        }
        if (uid.isNullOrBlank()) return

        try {
            val sharedFactory = WebRtcP2pClientManager.getSharedPeerConnectionFactory(applicationContext)
            if (sharedFactory == null) {
                _webRtcStateText.value = "الخادم نشط (وضع التوافق بدون WebRTC الأصلي)"
                return
            }
            peerConnectionFactory = sharedFactory

            setupLocalVideoSourceOnly()

            // Attach listeners first and announce server readiness without wiping an in-flight Admin Offer
            listenForAdminWebRtcSignaling(uid)

            publishServerSignalingFields(
                uid = uid,
                fields = mapOf(
                    "userId" to uid,
                    "serverStatus" to "ONLINE_WAITING_FOR_OFFER",
                    "updatedAt" to System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            appendLog(
                ServerLog(
                    method = "WEBRTC",
                    endpoint = "/init",
                    message = "ملاحظة تهيئة WebRTC P2P: ${t.localizedMessage ?: t.javaClass.simpleName}",
                    isError = false
                )
            )
        }
    }

    private fun setupLocalVideoSourceOnly() {
        synchronized(webRtcLock) {
            val factory = peerConnectionFactory ?: return

            syntheticFramePumpJob?.cancel()
            try {
                screenCapturer?.stopCapture()
                screenCapturer?.dispose()
            } catch (_: Throwable) {
            }
            screenCapturer = null

            if (videoSource == null) {
                try {
                    // Use non-screencast source (false) so software NV21 frames never trigger
                    // hardware MediaCodec screencast surface crashes on older Android devices.
                    videoSource = factory.createVideoSource(false)
                } catch (_: Throwable) {
                }
            }

            val projectionData = mediaProjectionPermissionData
            val eglContext = WebRtcP2pClientManager.getSharedEglBase()?.eglBaseContext
            if (projectionData != null && _isMediaProjectionEnabled.value && eglContext != null && videoSource != null) {
                try {
                    surfaceTextureHelper?.dispose()
                    surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
                    val capturer = ScreenCapturerAndroid(
                        projectionData,
                        object : android.media.projection.MediaProjection.Callback() {
                            override fun onStop() {
                                super.onStop()
                                _isMediaProjectionEnabled.value = false
                            }
                        }
                    )
                    capturer.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
                    capturer.startCapture(540, 960, 15)
                    screenCapturer = capturer
                    _webRtcStateText.value = "متاح عالمياً (MediaProjection نشط)"
                } catch (_: Throwable) {
                    screenCapturer = null
                    startLiveBitmapVideoFramePump(fps = 6)
                }
            } else {
                startLiveBitmapVideoFramePump(fps = 6)
                _webRtcStateText.value = "متاح عالمياً عبر WebRTC P2P"
            }
        }
    }

    private fun restartWebRtcCapturerIfNeeded() {
        if (!_isRunning.value) return
        setupLocalVideoSourceOnly()
    }

    private fun startLiveBitmapVideoFramePump(fps: Int = 6) {
        syntheticFramePumpJob?.cancel()
        val intervalMs = (1000L / fps.coerceIn(2, 12))
        syntheticFramePumpJob = serviceScope.launch {
            while (isActive && _isRunning.value && screenCapturer == null) {
                val src = videoSource
                if (src != null && isPeerSessionActive && peerConnection != null) {
                    var bmp: Bitmap? = null
                    try {
                        bmp = captureHomeNodeScreenBitmap()
                        val w = (bmp.width / 2) * 2
                        val h = (bmp.height / 2) * 2
                        if (w > 0 && h > 0) {
                            val nv21 = bitmapToNv21(bmp, w, h)
                            val buffer = NV21Buffer(nv21, w, h, null)
                            val frame = VideoFrame(buffer, 0, SystemClock.elapsedRealtimeNanos())
                            src.capturerObserver.onFrameCaptured(frame)
                            frame.release()
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try {
                            bmp?.recycle()
                        } catch (_: Throwable) {
                        }
                    }
                }
                delay(intervalMs)
            }
        }
    }

    private fun bitmapToNv21(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv.size) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    /**
     * Listens to `users/{uid}/signaling` in Firebase Firestore and Realtime Database
     * with strict session/offer deduplication and candidate queuing.
     */
    private fun listenForAdminWebRtcSignaling(uid: String) {
        val firestore = try {
            FirebaseFirestore.getInstance(
                applicationContext.getString(R.string.firestore_database_id)
            )
        } catch (_: Throwable) {
            null
        }
        val signalingDoc = firestore?.collection("users")?.document(uid)
            ?.collection("signaling")?.document("main")
        val legacySignalingDoc = firestore?.collection("users")?.document(uid)
            ?.collection("webrtc_signaling")?.document("main")

        fun onSignalingPayload(
            sessionId: String,
            offerSdp: String,
            offerType: String,
            adminCandidatesJson: String
        ) {
            if (!_isRunning.value) return

            // If signaling node was cleaned/deleted before a new Offer, reset offer tracking immediately
            if (offerSdp.isBlank()) {
                synchronized(webRtcLock) {
                    lastHandledOfferKey = null
                }
                return
            }

            // Handle fresh SDP Offer from Admin Controller
            if (offerSdp.isNotBlank() && offerType.equals("offer", ignoreCase = true)) {
                val offerKey = "${sessionId.ifBlank { "default" }}:${offerSdp.hashCode()}"
                val isNewOffer = synchronized(webRtcLock) {
                    if (offerKey != lastHandledOfferKey) {
                        lastHandledOfferKey = offerKey
                        currentP2pSessionId = sessionId
                        true
                    } else {
                        false
                    }
                }
                if (isNewOffer) {
                    serviceScope.launch {
                        handleIncomingWebRtcOffer(
                            uid = uid,
                            sessionId = sessionId,
                            offerSdp = offerSdp,
                            initialAdminCandidatesJson = adminCandidatesJson
                        )
                    }
                    return
                }
            }

            if (adminCandidatesJson.isNotBlank() && adminCandidatesJson != "[]") {
                serviceScope.launch {
                    processIncomingAdminCandidates(adminCandidatesJson)
                }
            }
        }

        try {
            firestoreSignalingRegistration?.remove()
            firestoreSignalingRegistration = signalingDoc?.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (!snapshot.exists()) {
                    synchronized(webRtcLock) {
                        lastHandledOfferKey = null
                    }
                    return@addSnapshotListener
                }
                onSignalingPayload(
                    sessionId = snapshot.getString("sessionId").orEmpty(),
                    offerSdp = snapshot.getString("offerSdp").orEmpty(),
                    offerType = snapshot.getString("offerType").orEmpty(),
                    adminCandidatesJson = snapshot.getString("adminCandidatesJson").orEmpty()
                )
            }
        } catch (_: Throwable) {
        }

        try {
            legacyFirestoreSignalingRegistration?.remove()
            legacyFirestoreSignalingRegistration = legacySignalingDoc?.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (!snapshot.exists()) {
                    synchronized(webRtcLock) {
                        lastHandledOfferKey = null
                    }
                    return@addSnapshotListener
                }
                onSignalingPayload(
                    sessionId = snapshot.getString("sessionId").orEmpty(),
                    offerSdp = snapshot.getString("offerSdp").orEmpty(),
                    offerType = snapshot.getString("offerType").orEmpty(),
                    adminCandidatesJson = snapshot.getString("adminCandidatesJson").orEmpty()
                )
            }
        } catch (_: Throwable) {
        }

        try {
            val ref = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid)
                .child("signaling")
            rtdbSignalingRef = ref
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        synchronized(webRtcLock) {
                            lastHandledOfferKey = null
                        }
                        return
                    }
                    onSignalingPayload(
                        sessionId = snapshot.child("sessionId").getValue(String::class.java).orEmpty(),
                        offerSdp = snapshot.child("offerSdp").getValue(String::class.java).orEmpty(),
                        offerType = snapshot.child("offerType").getValue(String::class.java).orEmpty(),
                        adminCandidatesJson = snapshot.child("adminCandidatesJson").getValue(String::class.java).orEmpty()
                    )
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            rtdbOfferListener = listener
            ref.addValueEventListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun buildPeerConnection(uid: String, sessionId: String): PeerConnection? {
        synchronized(webRtcLock) {
            val factory = peerConnectionFactory ?: return null

            isPeerSessionActive = false
            isRemoteOfferSet = false
            isAnswerPublished = false

            try {
                activeDataChannel?.unregisterObserver()
            } catch (_: Throwable) {
            }
            try {
                activeDataChannel?.close()
            } catch (_: Throwable) {
            }
            activeDataChannel = null

            try {
                peerConnection?.close()
            } catch (_: Throwable) {
            }
            peerConnection = null

            addedRemoteCandidateKeys.clear()
            pendingAdminCandidates.clear()
            synchronized(localServerCandidatesArray) {
                while (localServerCandidatesArray.length() > 0) {
                    localServerCandidatesArray.remove(0)
                }
            }

            // Configure Google STUN + OpenRelay Public TURN Servers in PeerConnection.RTCConfiguration
            val iceServers = WebRtcP2pClientManager.getInstance(applicationContext).buildPublicStunTurnIceServers()
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }

            val pc = try {
                factory.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

                        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                            val stateLabel = when (newState) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    isPeerSessionActive = true
                                    "الحالة اللحظية: open • متصل عالمياً عبر P2P (ICE CONNECTED)"
                                }
                                PeerConnection.IceConnectionState.NEW,
                                PeerConnection.IceConnectionState.CHECKING -> {
                                    isPeerSessionActive = true
                                    "الحالة اللحظية: connecting • جاري فحص مسارات STUN/TURN (ICE CHECKING)..."
                                }
                                PeerConnection.IceConnectionState.DISCONNECTED -> {
                                    isPeerSessionActive = false
                                    "الحالة اللحظية: connecting • انقطع اتصال P2P مؤقتاً..."
                                }
                                PeerConnection.IceConnectionState.FAILED,
                                PeerConnection.IceConnectionState.CLOSED -> {
                                    isPeerSessionActive = false
                                    "الحالة اللحظية: failed • تعذر مسار ICE وبانتظار تفاوض جديد"
                                }
                                else -> "الحالة اللحظية: connecting • WebRTC P2P (${newState?.name ?: "STANDBY"})"
                            }
                            _webRtcStateText.value = stateLabel
                            appendLog(
                                ServerLog(
                                    method = "WEBRTC-P2P",
                                    endpoint = "/ice-state",
                                    message = stateLabel
                                )
                            )
                            serviceScope.launch {
                                syncNodeStateWithCloud(isOnline = _isRunning.value)
                            }
                        }

                        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                                val canPublish = synchronized(webRtcLock) { isAnswerPublished }
                                if (!canPublish) return
                                val serialized = synchronized(localServerCandidatesArray) {
                                    localServerCandidatesArray.toString()
                                }
                                val fields = mutableMapOf<String, Any>(
                                    "userId" to uid,
                                    "serverCandidatesJson" to serialized,
                                    "updatedAt" to System.currentTimeMillis()
                                )
                                if (sessionId.isNotBlank()) {
                                    fields["sessionId"] = sessionId
                                }
                                publishServerSignalingFields(uid = uid, fields = fields)
                            }
                        }

                        override fun onIceCandidate(candidate: IceCandidate?) {
                            if (candidate == null) return
                            val candidateObj = JSONObject().apply {
                                put("sdpMid", candidate.sdpMid ?: "")
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                                put("sdp", candidate.sdp)
                            }
                            val serialized = synchronized(localServerCandidatesArray) {
                                localServerCandidatesArray.put(candidateObj)
                                localServerCandidatesArray.toString()
                            }
                            val canPublish = synchronized(webRtcLock) { isAnswerPublished }
                            if (!canPublish) return

                            val fields = mutableMapOf<String, Any>(
                                "userId" to uid,
                                "serverCandidatesJson" to serialized,
                                "updatedAt" to System.currentTimeMillis()
                            )
                            if (sessionId.isNotBlank()) {
                                fields["sessionId"] = sessionId
                            }
                            publishServerSignalingFields(uid = uid, fields = fields)
                        }

                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                        override fun onAddStream(stream: MediaStream?) {}
                        override fun onRemoveStream(stream: MediaStream?) {}

                        override fun onDataChannel(dataChannel: DataChannel?) {
                            if (dataChannel != null) {
                                registerServerDataChannel(dataChannel)
                            }
                        }

                        override fun onRenegotiationNeeded() {}
                        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
                    }
                )
            } catch (_: Throwable) {
                null
            } ?: return null

            peerConnection = pc

            // Always create a fresh VideoTrack per PeerConnection so closed tracks are never reused
            try {
                val src = videoSource
                if (src != null) {
                    val freshTrack = factory.createVideoTrack(
                        "HOME_NODE_TRACK_${UUID.randomUUID().toString().take(6)}",
                        src
                    )
                    freshTrack?.setEnabled(true)
                    localVideoTrack = freshTrack
                    if (freshTrack != null) {
                        pc.addTrack(freshTrack, listOf("HOME_NODE_STREAM"))
                    }
                }
            } catch (_: Throwable) {
            }

            return pc
        }
    }

    private fun handleIncomingWebRtcOffer(
        uid: String,
        sessionId: String,
        offerSdp: String,
        initialAdminCandidatesJson: String = ""
    ) {
        _webRtcStateText.value = "الحالة اللحظية: connecting • جاري معالجة Offer وإعداد STUN/TURN..."
        appendLog(
            ServerLog(
                method = "WEBRTC-P2P",
                endpoint = "users/$uid/signaling",
                message = "تم استلام SDP Offer من الآدمن — جاري تهيئة STUN/TURN وإرسال SDP Answer..."
            )
        )

        val pc = buildPeerConnection(uid, sessionId) ?: return

        // Pre-queue any Admin ICE candidates that arrived together with the Offer in Candidate Queue
        if (initialAdminCandidatesJson.isNotBlank() && initialAdminCandidatesJson != "[]") {
            processIncomingAdminCandidates(initialAdminCandidatesJson)
        }

        try {
            val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            pc.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        // Flush the Candidate Queue ONLY after setRemoteDescription succeeds!
                        synchronized(webRtcLock) {
                            isRemoteOfferSet = true
                            val queued = pendingAdminCandidates.toList()
                            pendingAdminCandidates.clear()
                            for (candidate in queued) {
                                try {
                                    pc.addIceCandidate(candidate)
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        try {
                            val constraints = MediaConstraints()
                            pc.createAnswer(
                                object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(desc: SessionDescription?) {
                                        if (desc == null) return
                                        try {
                                            pc.setLocalDescription(
                                                object : SimpleSdpObserver() {
                                                    override fun onSetSuccess() {
                                                        isPeerSessionActive = true
                                                        val currentServerCandidates = synchronized(localServerCandidatesArray) {
                                                            localServerCandidatesArray.toString()
                                                        }
                                                        synchronized(webRtcLock) {
                                                            isAnswerPublished = true
                                                        }
                                                        val fields = mutableMapOf<String, Any>(
                                                            "userId" to uid,
                                                            "answerSdp" to desc.description,
                                                            "answerType" to "answer",
                                                            "serverCandidatesJson" to currentServerCandidates,
                                                            "updatedAt" to System.currentTimeMillis()
                                                        )
                                                        if (sessionId.isNotBlank()) {
                                                            fields["sessionId"] = sessionId
                                                        }
                                                        publishServerSignalingFields(uid = uid, fields = fields)
                                                        appendLog(
                                                            ServerLog(
                                                                method = "WEBRTC-P2P",
                                                                endpoint = "users/$uid/signaling",
                                                                message = "تم إرسال SDP Answer ومرشحات STUN/TURN عبر السحابة بنجاح"
                                                            )
                                                        )
                                                    }
                                                },
                                                desc
                                            )
                                        } catch (_: Throwable) {
                                        }
                                    }
                                },
                                constraints
                            )
                        } catch (_: Throwable) {
                        }
                    }
                },
                remoteOffer
            )
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // 3. RTCDATACHANNEL UNIFIED COMMAND HANDLER (/status, /files, /download, /upload, /delete, /screenshot)
    // =========================================================================

    private fun registerServerDataChannel(channel: DataChannel) {
        activeDataChannel = channel
        try {
            when (channel.state()) {
                DataChannel.State.CONNECTING -> {
                    _webRtcStateText.value = "الحالة اللحظية: connecting • جاري فتح قناة البيانات (${channel.label()})..."
                }
                DataChannel.State.OPEN -> {
                    isPeerSessionActive = true
                    _webRtcStateText.value = "الحالة اللحظية: open • قناة البيانات (${channel.label()}) مفتوحة ونشطة"
                    serviceScope.launch {
                        syncNodeStateWithCloud(isOnline = _isRunning.value)
                    }
                }
                DataChannel.State.CLOSED -> {
                    _webRtcStateText.value = "الحالة اللحظية: failed • تم إغلاق قناة البيانات"
                }
                else -> {}
            }
        } catch (_: Throwable) {
        }

        try {
            channel.registerObserver(
                object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}

                    override fun onStateChange() {
                        val state = try {
                            channel.state()
                        } catch (_: Throwable) {
                            return
                        }
                        when (state) {
                            DataChannel.State.CONNECTING -> {
                                _webRtcStateText.value =
                                    "الحالة اللحظية: connecting • جاري فتح قناة البيانات (${channel.label()})..."
                            }
                            DataChannel.State.OPEN -> {
                                isPeerSessionActive = true
                                _webRtcStateText.value =
                                    "الحالة اللحظية: open • قناة البيانات (${channel.label()}) مفتوحة ونشطة"
                                appendLog(
                                    ServerLog(
                                        method = "DATACHANNEL",
                                        endpoint = channel.label(),
                                        message = "الحالة اللحظية: open • تم فتح قناة البيانات المباشرة (${channel.label()}) بنجاح مع هاتف الآدمن"
                                    )
                                )
                                serviceScope.launch {
                                    syncNodeStateWithCloud(isOnline = _isRunning.value)
                                }
                            }
                            DataChannel.State.CLOSED -> {
                                _webRtcStateText.value =
                                    "الحالة اللحظية: failed • تم إغلاق قناة البيانات (${channel.label()})"
                                serviceScope.launch {
                                    syncNodeStateWithCloud(isOnline = _isRunning.value)
                                }
                            }
                            else -> {}
                        }
                    }

                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        if (buffer == null) return
                        try {
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            if (!buffer.binary) {
                                val rawJson = String(bytes, StandardCharsets.UTF_8)
                                serviceScope.launch {
                                    handleDataChannelCommand(channel, rawJson)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private suspend fun handleDataChannelCommand(channel: DataChannel, rawJson: String) {
        val request = try {
            JSONObject(rawJson)
        } catch (_: Throwable) {
            return
        }

        val reqId = request.optString("reqId")
        val action = request.optString("action")
        val token = request.optString("token")

        // Verify session token if provided and server token is set
        if (token.isNotBlank() && sessionSecretToken.isNotBlank() && token != sessionSecretToken) {
            sendDataChannelJson(
                channel,
                JSONObject().apply {
                    put("reqId", reqId)
                    put("success", false)
                    put("error", "رمز المصادقة السري غير مطابق (Unauthorized P2P Token)")
                }
            )
            return
        }

        when (action) {
            "/status" -> {
                val (battery, freeBytes, totalBytes) = readHardwareStatus()
                val response = JSONObject().apply {
                    put("reqId", reqId)
                    put("success", true)
                    put("batteryLevel", battery)
                    put("freeStorageBytes", freeBytes)
                    put("totalStorageBytes", totalBytes)
                    put("timestamp", System.currentTimeMillis())
                }
                sendDataChannelJson(channel, response)
                appendLog(
                    ServerLog(
                        method = "P2P",
                        endpoint = "/status",
                        message = "تم إرسال حالة البطارية والتخزين عبر RTCDataChannel"
                    )
                )
            }

            "/files" -> {
                val requestedPath = request.optString("path", "")
                val rootDir = resolveStorageRootDir()
                val targetDir = resolveSafeFile(rootDir, requestedPath)
                if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("success", false)
                            put("error", "المجلد المطلوب غير موجود")
                        }
                    )
                    return
                }

                val children = targetDir.listFiles()?.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }
                ).orEmpty()

                val filesArray = JSONArray()
                for (child in children) {
                    filesArray.put(
                        JSONObject().apply {
                            put("name", child.name)
                            put("path", child.absolutePath)
                            put("isDirectory", child.isDirectory)
                            put("sizeBytes", if (child.isDirectory) 0L else child.length())
                            put("lastModified", child.lastModified())
                        }
                    )
                }

                val rootCanonical = try { rootDir.canonicalPath } catch (_: Throwable) { rootDir.absolutePath }
                val currentCanonical = try { targetDir.canonicalPath } catch (_: Throwable) { targetDir.absolutePath }
                val parentPath = if (currentCanonical != rootCanonical) {
                    try { targetDir.parentFile?.canonicalPath.orEmpty() } catch (_: Throwable) { targetDir.parentFile?.absolutePath.orEmpty() }
                } else {
                    ""
                }

                val response = JSONObject().apply {
                    put("reqId", reqId)
                    put("success", true)
                    put("currentPath", currentCanonical)
                    put("parentPath", parentPath)
                    put("rootPath", rootCanonical)
                    put("files", filesArray)
                }
                sendDataChannelJson(channel, response)
                appendLog(
                    ServerLog(
                        method = "P2P",
                        endpoint = "/files",
                        message = "تصفح مجلد P2P: ${targetDir.name} (${children.size} عنصر)"
                    )
                )
            }

            "/screenshot" -> {
                val quality = request.optInt("quality", 70).coerceIn(30, 95)
                var bitmap: Bitmap? = null
                try {
                    bitmap = captureHomeNodeScreenBitmap()
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val base64Jpeg = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                    val response = JSONObject().apply {
                        put("reqId", reqId)
                        put("success", true)
                        put("jpegBase64", base64Jpeg)
                        put("timestamp", System.currentTimeMillis())
                    }
                    sendDataChannelJson(channel, response)
                } catch (e: Throwable) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("success", false)
                            put("error", e.localizedMessage ?: "فشل التقاط الشاشة")
                        }
                    )
                } finally {
                    try {
                        bitmap?.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }

            "/delete" -> {
                val path = request.optString("path", "")
                val rootDir = resolveStorageRootDir()
                val targetFile = resolveSafeFile(rootDir, path)
                val rootCanonical = try { rootDir.canonicalPath } catch (_: Throwable) { rootDir.absolutePath }
                val targetCanonical = try { targetFile?.canonicalPath } catch (_: Throwable) { targetFile?.absolutePath }
                if (targetFile == null || !targetFile.exists() || targetCanonical == rootCanonical) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("success", false)
                            put("error", "تعذر العثور على الملف أو لا يمكن حذف المجلد الجذري")
                        }
                    )
                    return
                }
                val deleted = try { targetFile.deleteRecursively() } catch (_: Throwable) { false }
                sendDataChannelJson(
                    channel,
                    JSONObject().apply {
                        put("reqId", reqId)
                        put("success", deleted)
                        if (!deleted) put("error", "تعذر حذف الملف من وحدة التخزين")
                    }
                )
                appendLog(
                    ServerLog(
                        method = "P2P",
                        endpoint = "/delete",
                        message = "حذف ملف عبر P2P: ${targetFile.name}",
                        isError = !deleted
                    )
                )
            }

            "/download" -> {
                val path = request.optString("path", "")
                val rootDir = resolveStorageRootDir()
                val targetFile = resolveSafeFile(rootDir, path)
                if (targetFile == null || !targetFile.exists() || targetFile.isDirectory) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("type", "download_error")
                            put("error", "الملف المطلوب غير موجود أو أنه مجلد")
                        }
                    )
                    return
                }

                appendLog(
                    ServerLog(
                        method = "P2P",
                        endpoint = "/download",
                        message = "بدء إرسال الملف عبر نفق P2P: ${targetFile.name} (${RemoteFileItem.formatBytes(targetFile.length())})"
                    )
                )

                try {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("type", "download_start")
                            put("fileName", targetFile.name)
                            put("totalBytes", targetFile.length())
                        }
                    )

                    val chunkBuf = ByteArray(24 * 1024)
                    FileInputStream(targetFile).use { fis ->
                        while (true) {
                            val read = fis.read(chunkBuf)
                            if (read <= 0) break

                            var backpressureWait = 0
                            while (try { channel.bufferedAmount() > 1_048_576L } catch (_: Throwable) { false } && backpressureWait < 100) {
                                delay(25L)
                                backpressureWait++
                            }

                            val slice = if (read == chunkBuf.size) chunkBuf else chunkBuf.copyOf(read)
                            val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
                            sendDataChannelJson(
                                channel,
                                JSONObject().apply {
                                    put("reqId", reqId)
                                    put("type", "download_chunk")
                                    put("data", b64)
                                }
                            )
                        }
                    }

                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("type", "download_end")
                        }
                    )
                } catch (e: Throwable) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("type", "download_error")
                            put("error", e.localizedMessage ?: "انقطع تدفق الملف عبر P2P")
                        }
                    )
                }
            }

            "/upload_start" -> {
                val uploadId = request.optString("uploadId")
                val dirPath = request.optString("path")
                val filename = request.optString("filename", "uploaded_${System.currentTimeMillis()}")
                    .replace("/", "_")
                    .replace("\\", "_")

                val rootDir = resolveStorageRootDir()
                val targetDir = resolveSafeFile(rootDir, dirPath)?.takeIf { it.exists() && it.isDirectory } ?: rootDir
                val destFile = File(targetDir, filename)

                try {
                    val fos = FileOutputStream(destFile)
                    activeUploads[uploadId] = IncomingUploadSession(
                        uploadId = uploadId,
                        targetFile = destFile,
                        outputStream = fos
                    )
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("success", true)
                        }
                    )
                } catch (e: Throwable) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("success", false)
                            put("error", e.localizedMessage ?: "تعذر إنشاء الملف على هاتف المنزل")
                        }
                    )
                }
            }

            "/upload_chunk" -> {
                val uploadId = request.optString("uploadId")
                val session = activeUploads[uploadId] ?: return
                val base64Data = request.optString("data")
                if (base64Data.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        session.outputStream.write(bytes)
                        session.receivedBytes += bytes.size
                    } catch (_: Throwable) {
                    }
                }
            }

            "/upload_finish" -> {
                val uploadId = request.optString("uploadId")
                val session = activeUploads.remove(uploadId)
                if (session != null) {
                    try {
                        session.outputStream.flush()
                        session.outputStream.close()
                    } catch (_: Throwable) {
                    }
                    appendLog(
                        ServerLog(
                            method = "P2P",
                            endpoint = "/upload",
                            message = "اكتمال استلام ملف P2P: ${session.targetFile.name} (${RemoteFileItem.formatBytes(session.receivedBytes)})"
                        )
                    )
                }
                sendDataChannelJson(
                    channel,
                    JSONObject().apply {
                        put("reqId", reqId)
                        put("success", true)
                    }
                )
            }
        }
    }

    private fun resolveSafeFile(rootDir: File, requestedPath: String): File? {
        return try {
            val rootCanonical = rootDir.canonicalFile
            val candidate = if (requestedPath.isBlank()) {
                rootCanonical
            } else {
                val raw = File(requestedPath)
                if (raw.isAbsolute) raw.canonicalFile else File(rootCanonical, requestedPath).canonicalFile
            }
            if (candidate.path.startsWith(rootCanonical.path)) candidate else rootCanonical
        } catch (_: Throwable) {
            null
        }
    }

    private fun sendDataChannelJson(channel: DataChannel, json: JSONObject): Boolean {
        return try {
            if (channel.state() != DataChannel.State.OPEN) return false
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (_: Throwable) {
            false
        }
    }

    private fun processIncomingAdminCandidates(candidatesJson: String) {
        synchronized(webRtcLock) {
            try {
                val array = JSONArray(candidatesJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val sdp = obj.optString("sdp")
                    val sdpMid = obj.optString("sdpMid")
                    val sdpMLineIndex = obj.optInt("sdpMLineIndex", 0)
                    val key = "$sdpMid:$sdpMLineIndex:$sdp"
                    if (sdp.isNotBlank() && addedRemoteCandidateKeys.add(key)) {
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        val pc = peerConnection
                        if (isRemoteOfferSet && pc != null) {
                            try {
                                pc.addIceCandidate(candidate)
                            } catch (_: Throwable) {
                            }
                        } else {
                            // Queue until setRemoteDescription(OFFER) completes onSetSuccess()
                            pendingAdminCandidates.add(candidate)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun publishServerSignalingFields(uid: String, fields: Map<String, Any>) {
        try {
            val firestore = FirebaseFirestore.getInstance(
                applicationContext.getString(R.string.firestore_database_id)
            )
            firestore.collection("users").document(uid)
                .collection("signaling").document("main")
                .set(fields, SetOptions.merge())
            firestore.collection("users").document(uid)
                .collection("webrtc_signaling").document("main")
                .set(fields, SetOptions.merge())
        } catch (_: Throwable) {
        }

        try {
            rtdbSignalingRef?.updateChildren(fields)
        } catch (_: Throwable) {
        }
    }

    private fun releaseWebRtcResources() {
        synchronized(webRtcLock) {
            isPeerSessionActive = false
            syntheticFramePumpJob?.cancel()
            try {
                firestoreSignalingRegistration?.remove()
            } catch (_: Throwable) {
            }
            firestoreSignalingRegistration = null
            try {
                legacyFirestoreSignalingRegistration?.remove()
            } catch (_: Throwable) {
            }
            legacyFirestoreSignalingRegistration = null
            try {
                rtdbOfferListener?.let { rtdbSignalingRef?.removeEventListener(it) }
            } catch (_: Throwable) {
            }
            rtdbOfferListener = null

            try {
                activeDataChannel?.unregisterObserver()
            } catch (_: Throwable) {
            }
            try {
                activeDataChannel?.close()
            } catch (_: Throwable) {
            }
            activeDataChannel = null

            try {
                screenCapturer?.stopCapture()
                screenCapturer?.dispose()
            } catch (_: Throwable) {
            }
            screenCapturer = null

            try {
                peerConnection?.close()
            } catch (_: Throwable) {
            }
            peerConnection = null

            try {
                surfaceTextureHelper?.dispose()
            } catch (_: Throwable) {
            }
            surfaceTextureHelper = null

            try {
                videoSource?.dispose()
            } catch (_: Throwable) {
            }
            videoSource = null
            localVideoTrack = null
            peerConnectionFactory = null
        }
    }

    // =========================================================================
    // 4. TELEMETRY HEARTBEAT, WIFI LOCKS & STORAGE MANAGEMENT
    // =========================================================================

    private fun startTelemetryHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && _isRunning.value) {
                if (firestoreSignalingRegistration == null && rtdbOfferListener == null) {
                    initializeWebRtcServerSignaling()
                }
                syncNodeStateWithCloud(isOnline = true)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun syncNodeStateWithCloud(isOnline: Boolean) {
        val uid = try {
            FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val signalingPath = if (uid.isNotBlank()) "users/$uid/signaling" else "users/{uid}/signaling"
        val (battery, freeBytes, totalBytes) = readHardwareStatus()
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val p2pState = if (try { activeDataChannel?.state() == DataChannel.State.OPEN } catch (_: Throwable) { false }) {
            "P2P_CONNECTED"
        } else {
            "WAITING_FOR_PEER"
        }

        val info = HomeNodeInfo(
            id = "main",
            userId = uid,
            deviceName = deviceName,
            signalingPath = signalingPath,
            p2pState = p2pState,
            ipAddress = "WebRTC-P2P-Global",
            port = activePort,
            isOnline = isOnline,
            secretToken = sessionSecretToken,
            batteryLevel = battery,
            freeStorageBytes = freeBytes,
            totalStorageBytes = totalBytes,
            lastUpdated = System.currentTimeMillis()
        )

        _currentNodeInfo.value = info
        updateForegroundNotification(signalingPath)

        val repo = repository ?: try {
            SmartCloudRepository(applicationContext).also { repository = it }
        } catch (_: Throwable) {
            null
        } ?: return

        val result = repo.publishHomeNodeInfo(info, registerOnDisconnect = isOnline)
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
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    super.onLinkPropertiesChanged(network, linkProperties)
                    serviceScope.launch {
                        syncNodeStateWithCloud(isOnline = true)
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
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
        }
    }

    private fun releaseHardwareLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    private fun readHardwareStatus(): Triple<Int, Long, Long> {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 } ?: 100

            val rootDir = resolveStorageRootDir()
            val statFs = StatFs(rootDir.absolutePath)
            val freeBytes = statFs.availableBytes
            val totalBytes = statFs.totalBytes
            Triple(battery, freeBytes, totalBytes)
        } catch (_: Throwable) {
            Triple(100, 0L, 0L)
        }
    }

    private fun resolveStorageRootDir(): File {
        return try {
            val externalRoot = Environment.getExternalStorageDirectory()
            if (externalRoot != null && externalRoot.exists() && externalRoot.canRead() && !externalRoot.listFiles().isNullOrEmpty()) {
                return externalRoot
            }
            val appExternal = getExternalFilesDir(null)
            if (appExternal != null && appExternal.exists() && appExternal.canRead()) {
                return appExternal
            }
            filesDir
        } catch (_: Throwable) {
            filesDir
        }
    }

    private fun ensureSampleCloudFilesIfNeeded() {
        try {
            val root = resolveStorageRootDir()
            val cloudFolder = File(root, "SmartHomeCloud_Shared")
            if (!cloudFolder.exists()) {
                cloudFolder.mkdirs()
                File(cloudFolder, "Welcome_SmartHomeCloud.txt").writeText(
                    "مرحباً بك في نظام السحابة المنزلية الذكية العالمي (Smart Home Cloud P2P)!\n" +
                        "يعمل هذا الخادم الآن عبر نفق WebRTC P2P وقناة RTCDataChannel للوصول من أي شبكة في العالم (4G/5G/Wi-Fi)."
                )
                val docsDir = File(cloudFolder, "Documents").apply { mkdirs() }
                File(docsDir, "Home_Server_Specs.json").writeText(
                    "{\n  \"transport\": \"WebRTC P2P (RTCDataChannel + 30 FPS VideoTrack)\",\n  \"signaling\": \"Firebase users/{uid}/signaling\"\n}"
                )
                val mediaDir = File(cloudFolder, "Media_Samples").apply { mkdirs() }
                createSampleWavFile(File(mediaDir, "SmartHome_Chime_Sample.wav"))
            }
        } catch (_: Throwable) {
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
            header[20] = 1; header[21] = 0
            header[22] = 1; header[23] = 0
            writeIntLE(header, 24, sampleRate)
            writeIntLE(header, 28, byteRate)
            header[32] = 2; header[33] = 0
            header[34] = 16; header[35] = 0
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

    private fun stopServerAndSelf() {
        _isRunning.value = false
        serverBootJob?.cancel()
        heartbeatJob?.cancel()
        releaseWebRtcResources()

        try {
            localFileServer?.stop()
        } catch (_: Throwable) {
        }
        localFileServer = null

        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {
        }
        networkCallback = null

        releaseHardwareLocks()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val offlineNode = (_currentNodeInfo.value ?: HomeNodeInfo()).copy(
                    isOnline = false,
                    p2pState = "OFFLINE",
                    lastUpdated = System.currentTimeMillis()
                )
                _currentNodeInfo.value = offlineNode
                repository?.markHomeNodeOffline(offlineNode)
            } catch (_: Throwable) {
            } finally {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {
                }
                try {
                    stopSelf()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Always keep the Home Node Server running even if the user swipes the Activity away from Recent Apps
        if (_isRunning.value) {
            try {
                val restartIntent = Intent(applicationContext, HomeServerForegroundService::class.java).apply {
                    action = ACTION_START_SERVER
                    putExtra(EXTRA_PORT, activePort)
                }
                applicationContext.startService(restartIntent)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        if (_isRunning.value) {
            stopServerAndSelf()
        } else {
            releaseWebRtcResources()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasNotificationPermission(): Boolean {
        return try {
            val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            runtimeGranted && NotificationManagerCompat.from(this).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildForegroundNotification(signalingPath: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use monochrome vector icon R.drawable.ic_stat_cloud_server (never AdaptiveIconDrawable)
        // to prevent StatusBarManagerService RemoteServiceException on Android 8-16.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cloud_server)
            .setContentTitle(getString(R.string.server_running_title))
            .setContentText("يعمل تلقائياً ودائماً عبر WebRTC P2P ($signalingPath)")
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .build()
    }

    private fun updateForegroundNotification(signalingPath: String) {
        if (!hasNotificationPermission()) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(signalingPath))
        } catch (_: Throwable) {
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        const val ACTION_START_SERVER = "com.example.service.ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "com.example.service.ACTION_STOP_SERVER"
        const val ACTION_ENABLE_MEDIA_PROJECTION = "com.example.service.ACTION_ENABLE_MEDIA_PROJECTION"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA_INTENT = "extra_projection_data_intent"

        private const val CHANNEL_ID = "smart_home_cloud_server_channel"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val NanoHttpdSocketTimeoutMs = 15_000

        @Volatile
        private var activeActivityRef: WeakReference<Activity>? = null

        @Volatile
        private var mediaProjectionPermissionData: Intent? = null

        @Volatile
        private var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentNodeInfo = MutableStateFlow<HomeNodeInfo?>(null)
        val currentNodeInfo: StateFlow<HomeNodeInfo?> = _currentNodeInfo.asStateFlow()

        private val _serverLogs = MutableStateFlow<List<ServerLog>>(emptyList())
        val serverLogs: StateFlow<List<ServerLog>> = _serverLogs.asStateFlow()

        private val _isMediaProjectionEnabled = MutableStateFlow(false)
        val isMediaProjectionEnabled: StateFlow<Boolean> = _isMediaProjectionEnabled.asStateFlow()

        private val _webRtcStateText = MutableStateFlow("في انتظار اتصال الآدمن عبر P2P")
        val webRtcStateText: StateFlow<String> = _webRtcStateText.asStateFlow()

        fun registerActiveActivity(activity: Activity) {
            activeActivityRef = WeakReference(activity)
        }

        fun unregisterActiveActivity(activity: Activity) {
            if (activeActivityRef?.get() === activity) {
                activeActivityRef = null
            }
        }

        fun appendLog(log: ServerLog) {
            _serverLogs.value = (listOf(log) + _serverLogs.value).take(200)
        }

        fun clearLogs() {
            _serverLogs.value = emptyList()
        }
    }
}
