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
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
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
    private var heartbeatJob: Job? = null
    private var syntheticFramePumpJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var localFileServer: LocalFileServer? = null
    private lateinit var repository: SmartCloudRepository
    private var sessionSecretToken: String = UUID.randomUUID().toString()
    private var activePort: Int = LocalFileServer.DEFAULT_PORT

    // WebRTC P2P Engine Components (Global RTCDataChannel + 30 FPS Video Stream + Firebase Signaling)
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var activeDataChannel: DataChannel? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var rtdbSignalingRef: DatabaseReference? = null
    private var rtdbOfferListener: ValueEventListener? = null
    private var firestoreSignalingRegistration: ListenerRegistration? = null
    private var legacyFirestoreSignalingRegistration: ListenerRegistration? = null

    private val addedRemoteCandidateKeys = mutableSetOf<String>()
    private val localServerCandidatesArray = JSONArray()
    private var lastHandledOfferSdp: String? = null
    private var lastHandledAnswerSdp: String? = null
    private var currentP2pSessionId: String = ""

    private data class IncomingUploadSession(
        val uploadId: String,
        val targetFile: File,
        val outputStream: FileOutputStream,
        var receivedBytes: Long = 0L
    )

    private val activeUploads = ConcurrentHashMap<String, IncomingUploadSession>()

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
                        message = "جاري إيقاف الخادم المنزلي P2P بناءً على طلب المستخدم..."
                    )
                )
                stopServerAndSelf()
                return START_NOT_STICKY
            }
            ACTION_ENABLE_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA_INTENT)
                }
                if (dataIntent != null) {
                    mediaProjectionPermissionData = dataIntent
                    mediaProjectionResultCode = resultCode
                    _isMediaProjectionEnabled.value = true
                    appendLog(
                        ServerLog(
                            method = "WEBRTC",
                            endpoint = "/media-projection",
                            message = "تم تفعيل صلاحية بث الشاشة الكاملة عبر WebRTC P2P (30 FPS)"
                        )
                    )
                    restartWebRtcCapturerIfNeeded()
                }
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

        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val signalingPath = if (uid.isNotBlank()) "users/$uid/signaling" else "users/{uid}/signaling"

        // Safely attempt to show foreground notification only if permission is granted,
        // and catch any SecurityException / ForegroundServiceStartNotAllowedException so the service
        // continues running silently if notifications are denied by the user.
        if (hasNotificationPermission()) {
            try {
                val notification = buildForegroundNotification(signalingPath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fgType = if (_isMediaProjectionEnabled.value) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(NOTIFICATION_ID, notification, fgType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Throwable) {
                appendLog(
                    ServerLog(
                        method = "SYSTEM",
                        endpoint = "/notification",
                        message = "يعمل الخادم المنزلي في الوضع الصامت (تعذر عرض الإشعار: ${e.javaClass.simpleName})"
                    )
                )
            }
        } else {
            appendLog(
                ServerLog(
                    method = "SYSTEM",
                    endpoint = "/notification",
                    message = "يعمل الخادم المنزلي في الوضع الصامت (صلاحية الإشعارات غير مفعّلة)"
                )
            )
        }

        try {
            // Keep LocalFileServer running as local loopback helper while primary transport is Global WebRTC P2P
            localFileServer?.stop()
            localFileServer = LocalFileServer(
                serverPort = activePort,
                secretTokenProvider = { sessionSecretToken },
                statusProvider = { readHardwareStatus() },
                rootDirProvider = { resolveStorageRootDir() },
                screenshotBitmapProvider = { captureHomeNodeScreenBitmap() },
                onLog = { logItem -> appendLog(logItem) }
            ).also { server ->
                server.start(NanoHttpdSocketTimeoutMs, false)
            }

            _isRunning.value = true
            _webRtcStateText.value = "بانتظار ربط الآدمن عبر $signalingPath"
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
        } catch (e: Exception) {
            appendLog(
                ServerLog(
                    method = "ERROR",
                    endpoint = "/boot",
                    message = "تعذر تشغيل محرك الخادم المنزلي: ${e.localizedMessage}",
                    statusCode = 500,
                    isError = true
                )
            )
            stopServerAndSelf()
        }
    }

    // =========================================================================
    // 1. SCREENSHOT BITMAP EXTRACTION (PixelCopy + View Isolation + Live HUD Fallback)
    // =========================================================================

    fun captureHomeNodeScreenBitmap(): Bitmap {
        val activity = activeActivityRef?.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            val window = activity.window
            val decorView = window?.decorView
            if (decorView != null && decorView.width > 0 && decorView.height > 0) {
                val targetWidth = 540
                val targetHeight = ((decorView.height.toFloat() / decorView.width.toFloat()) * targetWidth)
                    .toInt()
                    .coerceIn(480, 1200)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                        if (latch.await(350, TimeUnit.MILLISECONDS) && copyResult == PixelCopy.SUCCESS) {
                            stampLiveOverlayBadge(pixelBitmap)
                            return pixelBitmap
                        }
                    } catch (_: Exception) {
                    }
                }

                try {
                    val viewBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val latch = CountDownLatch(1)
                    mainHandler.post {
                        try {
                            val canvas = Canvas(viewBitmap)
                            val scaleX = targetWidth.toFloat() / decorView.width.toFloat()
                            val scaleY = targetHeight.toFloat() / decorView.height.toFloat()
                            canvas.scale(scaleX, scaleY)
                            decorView.draw(canvas)
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                    if (latch.await(350, TimeUnit.MILLISECONDS)) {
                        stampLiveOverlayBadge(viewBitmap)
                        return viewBitmap
                    }
                } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            if (eglBase == null) {
                eglBase = EglBase.create()
            }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val eglContext = eglBase!!.eglBaseContext
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()

            setupLocalVideoTrack()
            // Publish initial standby Offer so Home Node can act as Offerer OR Answerer seamlessly
            createAndPublishHomeNodeOffer(uid)
            listenForAdminWebRtcSignaling(uid)
        } catch (e: Exception) {
            appendLog(
                ServerLog(
                    method = "WEBRTC",
                    endpoint = "/init",
                    message = "ملاحظة تهيئة WebRTC P2P: ${e.localizedMessage}",
                    isError = true
                )
            )
        }
    }

    private fun setupLocalVideoTrack() {
        val factory = peerConnectionFactory ?: return
        val eglContext = eglBase?.eglBaseContext ?: return

        syntheticFramePumpJob?.cancel()
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
        } catch (_: Exception) {
        }
        screenCapturer = null

        videoSource?.dispose()
        videoSource = factory.createVideoSource(true)

        val projectionData = mediaProjectionPermissionData
        if (projectionData != null && _isMediaProjectionEnabled.value) {
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
                capturer.startCapture(720, 1280, 30)
                screenCapturer = capturer
                _webRtcStateText.value = "متاح عالمياً (MediaProjection 30 FPS)"
            } catch (_: Exception) {
                startLiveBitmapVideoFramePump(fps = 15)
            }
        } else {
            startLiveBitmapVideoFramePump(fps = 15)
            _webRtcStateText.value = "متاح عالمياً عبر WebRTC P2P"
        }

        localVideoTrack = factory.createVideoTrack("HOME_NODE_SCREEN_TRACK", videoSource)
        localVideoTrack?.setEnabled(true)
    }

    private fun restartWebRtcCapturerIfNeeded() {
        if (!_isRunning.value) return
        setupLocalVideoTrack()
    }

    private fun startLiveBitmapVideoFramePump(fps: Int = 15) {
        syntheticFramePumpJob?.cancel()
        val intervalMs = (1000L / fps.coerceIn(5, 30))
        syntheticFramePumpJob = serviceScope.launch {
            while (isActive && _isRunning.value && screenCapturer == null) {
                val src = videoSource
                if (src != null && peerConnection != null) {
                    try {
                        val bmp = captureHomeNodeScreenBitmap()
                        val w = (bmp.width / 2) * 2
                        val h = (bmp.height / 2) * 2
                        val nv21 = bitmapToNv21(bmp, w, h)
                        val buffer = NV21Buffer(nv21, w, h, null)
                        val frame = VideoFrame(buffer, 0, SystemClock.elapsedRealtimeNanos())
                        src.capturerObserver.onFrameCaptured(frame)
                        frame.release()
                    } catch (_: Exception) {
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
     * Listens to `users/{uid}/signaling` (and legacy `webrtc_signaling`) in both
     * Firebase Firestore and Firebase Realtime Database.
     */
    private fun listenForAdminWebRtcSignaling(uid: String) {
        val firestore = FirebaseFirestore.getInstance(
            applicationContext.getString(R.string.firestore_database_id)
        )
        val signalingDoc = firestore.collection("users").document(uid)
            .collection("signaling").document("main")
        val legacySignalingDoc = firestore.collection("users").document(uid)
            .collection("webrtc_signaling").document("main")

        fun onSignalingPayload(
            sessionId: String,
            offerSdp: String,
            offerType: String,
            answerSdp: String,
            answerType: String,
            adminCandidatesJson: String
        ) {
            // Case A: Admin sent a fresh SDP Offer (Home Node acts as Callee / Answerer)
            if (offerSdp.isNotBlank() && offerType.equals("offer", ignoreCase = true) && offerSdp != lastHandledOfferSdp) {
                lastHandledOfferSdp = offerSdp
                currentP2pSessionId = sessionId
                handleIncomingWebRtcOffer(uid, sessionId, offerSdp)
            }
            // Case B: Admin answered the Home Node's standby SDP Offer (Home Node acts as Offerer)
            else if (answerSdp.isNotBlank() && answerType.equals("answer", ignoreCase = true) && answerSdp != lastHandledAnswerSdp) {
                val pc = peerConnection
                if (pc != null && pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    lastHandledAnswerSdp = answerSdp
                    pc.setRemoteDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                appendLog(
                                    ServerLog(
                                        method = "WEBRTC-P2P",
                                        endpoint = "users/$uid/signaling",
                                        message = "تم تطبيق SDP Answer من هاتف الآدمن بنجاح"
                                    )
                                )
                            }
                        },
                        SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                    )
                }
            }

            if (adminCandidatesJson.isNotBlank()) {
                processIncomingAdminCandidates(adminCandidatesJson)
            }
        }

        firestoreSignalingRegistration?.remove()
        firestoreSignalingRegistration = signalingDoc.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            onSignalingPayload(
                sessionId = snapshot.getString("sessionId").orEmpty(),
                offerSdp = snapshot.getString("offerSdp").orEmpty(),
                offerType = snapshot.getString("offerType").orEmpty(),
                answerSdp = snapshot.getString("answerSdp").orEmpty(),
                answerType = snapshot.getString("answerType").orEmpty(),
                adminCandidatesJson = snapshot.getString("adminCandidatesJson").orEmpty()
            )
        }

        legacyFirestoreSignalingRegistration?.remove()
        legacyFirestoreSignalingRegistration = legacySignalingDoc.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            onSignalingPayload(
                sessionId = snapshot.getString("sessionId").orEmpty(),
                offerSdp = snapshot.getString("offerSdp").orEmpty(),
                offerType = snapshot.getString("offerType").orEmpty(),
                answerSdp = snapshot.getString("answerSdp").orEmpty(),
                answerType = snapshot.getString("answerType").orEmpty(),
                adminCandidatesJson = snapshot.getString("adminCandidatesJson").orEmpty()
            )
        }

        try {
            val ref = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid)
                .child("signaling")
            rtdbSignalingRef = ref
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    onSignalingPayload(
                        sessionId = snapshot.child("sessionId").getValue(String::class.java).orEmpty(),
                        offerSdp = snapshot.child("offerSdp").getValue(String::class.java).orEmpty(),
                        offerType = snapshot.child("offerType").getValue(String::class.java).orEmpty(),
                        answerSdp = snapshot.child("answerSdp").getValue(String::class.java).orEmpty(),
                        answerType = snapshot.child("answerType").getValue(String::class.java).orEmpty(),
                        adminCandidatesJson = snapshot.child("adminCandidatesJson").getValue(String::class.java).orEmpty()
                    )
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            rtdbOfferListener = listener
            ref.addValueEventListener(listener)
        } catch (_: Exception) {
        }
    }

    private fun buildPeerConnection(uid: String, sessionId: String): PeerConnection? {
        val factory = peerConnectionFactory ?: return null

        try {
            activeDataChannel?.close()
        } catch (_: Exception) {
        }
        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }
        addedRemoteCandidateKeys.clear()
        synchronized(localServerCandidatesArray) {
            while (localServerCandidatesArray.length() > 0) {
                localServerCandidatesArray.remove(0)
            }
        }

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    val stateLabel = when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> "متصل عالمياً عبر P2P (ICE CONNECTED)"
                        PeerConnection.IceConnectionState.CHECKING -> "جاري التفاوض السحابي (ICE CHECKING)..."
                        PeerConnection.IceConnectionState.DISCONNECTED -> "انقطع اتصال P2P مؤقتاً"
                        PeerConnection.IceConnectionState.FAILED -> "فشل مسار ICE المباشر"
                        else -> "WebRTC P2P: ${newState?.name ?: "STANDBY"}"
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
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) return
                    val candidateObj = JSONObject().apply {
                        put("sdpMid", candidate.sdpMid ?: "")
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("sdp", candidate.sdp)
                    }
                    val serialized: String
                    synchronized(localServerCandidatesArray) {
                        localServerCandidatesArray.put(candidateObj)
                        serialized = localServerCandidatesArray.toString()
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

        peerConnection = pc
        localVideoTrack?.let { track ->
            pc?.addTrack(track, listOf("HOME_NODE_STREAM"))
        }
        return pc
    }

    /**
     * Publishes an initial standby Offer + RTCDataChannel on `users/{uid}/signaling`
     * so the Home Node is immediately ready as an Offerer, while also ready to Answer new Admin Offers.
     */
    private fun createAndPublishHomeNodeOffer(uid: String) {
        val sessionId = UUID.randomUUID().toString().take(12)
        currentP2pSessionId = sessionId
        val pc = buildPeerConnection(uid, sessionId) ?: return

        val dcInit = DataChannel.Init().apply { ordered = true }
        val dc = pc.createDataChannel("smart_home_p2p_channel", dcInit)
        registerServerDataChannel(dc)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    lastHandledOfferSdp = desc.description
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                publishServerSignalingFields(
                                    uid = uid,
                                    fields = mapOf(
                                        "userId" to uid,
                                        "sessionId" to sessionId,
                                        "offerSdp" to desc.description,
                                        "offerType" to "offer",
                                        "updatedAt" to System.currentTimeMillis()
                                    )
                                )
                            }
                        },
                        desc
                    )
                }
            },
            constraints
        )
    }

    private fun handleIncomingWebRtcOffer(uid: String, sessionId: String, offerSdp: String) {
        appendLog(
            ServerLog(
                method = "WEBRTC-P2P",
                endpoint = "users/$uid/signaling",
                message = "تم استلام SDP Offer من الآدمن — جاري فتح نفق P2P وإرسال SDP Answer..."
            )
        )

        val pc = buildPeerConnection(uid, sessionId) ?: return

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    }
                    pc.createAnswer(
                        object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                if (desc == null) return
                                lastHandledAnswerSdp = desc.description
                                pc.setLocalDescription(
                                    object : SimpleSdpObserver() {
                                        override fun onSetSuccess() {
                                            val fields = mutableMapOf<String, Any>(
                                                "userId" to uid,
                                                "answerSdp" to desc.description,
                                                "answerType" to "answer",
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
                                                    message = "تم إرسال SDP Answer عبر السحابة بنجاح"
                                                )
                                            )
                                        }
                                    },
                                    desc
                                )
                            }
                        },
                        constraints
                    )
                }
            },
            remoteOffer
        )
    }

    // =========================================================================
    // 3. RTCDATACHANNEL UNIFIED COMMAND HANDLER (/status, /files, /download, /upload, /delete, /screenshot)
    // =========================================================================

    private fun registerServerDataChannel(channel: DataChannel) {
        activeDataChannel = channel
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}

                override fun onStateChange() {
                    val state = channel.state()
                    if (state == DataChannel.State.OPEN) {
                        _webRtcStateText.value = "متصل عالمياً عبر P2P (RTCDataChannel نشط)"
                        appendLog(
                            ServerLog(
                                method = "DATACHANNEL",
                                endpoint = channel.label(),
                                message = "تم فتح قناة البيانات المباشرة RTCDataChannel بنجاح مع هاتف الآدمن"
                            )
                        )
                    } else if (state == DataChannel.State.CLOSED) {
                        _webRtcStateText.value = "بانتظار اتصال P2P جديد"
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (buffer == null) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    if (!buffer.binary) {
                        val rawJson = String(bytes, StandardCharsets.UTF_8)
                        serviceScope.launch {
                            handleDataChannelCommand(channel, rawJson)
                        }
                    }
                }
            }
        )
    }

    private suspend fun handleDataChannelCommand(channel: DataChannel, rawJson: String) {
        val request = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
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

                val rootCanonical = rootDir.canonicalPath
                val currentCanonical = targetDir.canonicalPath
                val parentPath = if (currentCanonical != rootCanonical) {
                    targetDir.parentFile?.canonicalPath.orEmpty()
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
                try {
                    val bitmap = captureHomeNodeScreenBitmap()
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
                } catch (e: Exception) {
                    sendDataChannelJson(
                        channel,
                        JSONObject().apply {
                            put("reqId", reqId)
                            put("success", false)
                            put("error", e.localizedMessage ?: "فشل التقاط الشاشة")
                        }
                    )
                }
            }

            "/delete" -> {
                val path = request.optString("path", "")
                val rootDir = resolveStorageRootDir()
                val targetFile = resolveSafeFile(rootDir, path)
                if (targetFile == null || !targetFile.exists() || targetFile.canonicalPath == rootDir.canonicalPath) {
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
                val deleted = targetFile.deleteRecursively()
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
                            while (channel.bufferedAmount() > 1_048_576L && backpressureWait < 100) {
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
                } catch (e: Exception) {
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
                } catch (e: Exception) {
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
                    } catch (_: Exception) {
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
                    } catch (_: Exception) {
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
        } catch (_: Exception) {
            null
        }
    }

    private fun sendDataChannelJson(channel: DataChannel, json: JSONObject): Boolean {
        return try {
            if (channel.state() != DataChannel.State.OPEN) return false
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (_: Exception) {
            false
        }
    }

    private fun processIncomingAdminCandidates(candidatesJson: String) {
        val pc = peerConnection ?: return
        try {
            val array = JSONArray(candidatesJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sdp = obj.optString("sdp")
                val sdpMid = obj.optString("sdpMid")
                val sdpMLineIndex = obj.optInt("sdpMLineIndex", 0)
                val key = "$sdpMid:$sdpMLineIndex:$sdp"
                if (sdp.isNotBlank() && addedRemoteCandidateKeys.add(key)) {
                    pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                }
            }
        } catch (_: Exception) {
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
        } catch (_: Exception) {
        }

        try {
            rtdbSignalingRef?.updateChildren(fields)
        } catch (_: Exception) {
        }
    }

    private fun releaseWebRtcResources() {
        syntheticFramePumpJob?.cancel()
        firestoreSignalingRegistration?.remove()
        firestoreSignalingRegistration = null
        legacyFirestoreSignalingRegistration?.remove()
        legacyFirestoreSignalingRegistration = null
        try {
            rtdbOfferListener?.let { rtdbSignalingRef?.removeEventListener(it) }
        } catch (_: Exception) {
        }
        try {
            activeDataChannel?.close()
            activeDataChannel?.dispose()
        } catch (_: Exception) {
        }
        activeDataChannel = null
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
        } catch (_: Exception) {
        }
        screenCapturer = null
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (_: Exception) {
        }
        peerConnection = null
        try {
            videoSource?.dispose()
        } catch (_: Exception) {
        }
        videoSource = null
        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null
        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {
        }
        peerConnectionFactory = null
        try {
            eglBase?.release()
        } catch (_: Exception) {
        }
        eglBase = null
    }

    // =========================================================================
    // 4. TELEMETRY HEARTBEAT, WIFI LOCKS & STORAGE MANAGEMENT
    // =========================================================================

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
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val signalingPath = if (uid.isNotBlank()) "users/$uid/signaling" else "users/{uid}/signaling"
        val (battery, freeBytes, totalBytes) = readHardwareStatus()
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val p2pState = if (activeDataChannel?.state() == DataChannel.State.OPEN) {
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
        heartbeatJob?.cancel()
        releaseWebRtcResources()

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
                    p2pState = "OFFLINE",
                    lastUpdated = System.currentTimeMillis()
                )
                _currentNodeInfo.value = offlineNode
                repository.markHomeNodeOffline(offlineNode)
            } catch (_: Exception) {
            } finally {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {
                }
                stopSelf()
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
            .setContentText("متاح عالمياً عبر WebRTC P2P ($signalingPath)")
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_server_action),
                stopPendingIntent
            )
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
