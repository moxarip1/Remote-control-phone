package com.example.webrtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import com.example.R
import com.example.model.HomeNodeInfo
import com.example.model.RemoteFileItem
import com.example.model.TransferProgress
import com.example.repository.SmartCloudRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoTrack
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Global WebRTC P2P Admin Client Manager (`WebRtcP2pClientManager`):
 * Connects to the Home Node from ANY network worldwide (different Wi-Fi, 4G/5G cellular, etc.)
 * using Firebase Signaling (`users/{uid}/signaling`) and Google STUN servers, and executes all commands
 * (`/status`, `/files`, `/download`, `/upload`, `/delete`, `/screenshot`) + 30 FPS Video Stream
 * over a unified encrypted `PeerConnection` + `RTCDataChannel`.
 */
class WebRtcP2pClientManager(
    private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val eglBase: EglBase?
        get() = getSharedEglBase()

    private var peerConnection: PeerConnection? = null
    private var commandDataChannel: DataChannel? = null
    private var negotiationWatchdogJob: Job? = null

    private var firestoreSignalingRegistration: ListenerRegistration? = null
    private var legacyFirestoreSignalingRegistration: ListenerRegistration? = null
    private var rtdbSignalingRef: DatabaseReference? = null
    private var rtdbSignalingListener: ValueEventListener? = null

    private val _isP2pConnected = MutableStateFlow(false)
    val isP2pConnected: StateFlow<Boolean> = _isP2pConnected.asStateFlow()

    private val _p2pConnectionStatus = MutableStateFlow("غير متصل (بانتظار بدء نفق WebRTC P2P)")
    val p2pConnectionStatus: StateFlow<String> = _p2pConnectionStatus.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _latestScreenshotBitmap = MutableStateFlow<Bitmap?>(null)
    val latestScreenshotBitmap: StateFlow<Bitmap?> = _latestScreenshotBitmap.asStateFlow()

    private var activeSessionId: String = ""
    private var connectedUserId: String = ""
    private var activeSecretToken: String = ""
    private var lastAttemptTimestampMs: Long = 0L

    // Pending Request-Response Coroutine Deferreds keyed by reqId
    private val pendingJsonResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    // Active chunked download assemblies keyed by reqId
    private data class IncomingDownloadSession(
        val fileName: String,
        val totalBytes: Long,
        val outputFile: File,
        val outputStream: FileOutputStream,
        var receivedBytes: Long = 0L,
        val onProgress: (TransferProgress) -> Unit,
        val completion: CompletableDeferred<File>
    )

    private val activeDownloads = ConcurrentHashMap<String, IncomingDownloadSession>()

    /**
     * Initiates or refreshes the global WebRTC PeerConnection & RTCDataChannel with the Home Node
     * using Firebase Signaling at `users/{uid}/signaling` and Google public STUN servers.
     */
    @Synchronized
    fun connectToHomeNodeP2p(
        userId: String = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
        secretToken: String = "",
        forceReconnect: Boolean = false
    ) {
        if (userId.isBlank()) return
        if (secretToken.isNotBlank()) {
            activeSecretToken = secretToken
        }

        val currentDcState = try {
            commandDataChannel?.state()
        } catch (_: Throwable) {
            null
        }
        if (!forceReconnect && _isP2pConnected.value && connectedUserId == userId && currentDcState == DataChannel.State.OPEN) {
            return
        }

        // Avoid restarting an in-flight negotiation that started < 4.5 seconds ago unless forced
        val now = System.currentTimeMillis()
        if (!forceReconnect &&
            connectedUserId == userId &&
            peerConnection != null &&
            (now - lastAttemptTimestampMs) < 4_500L
        ) {
            return
        }

        lastAttemptTimestampMs = now
        connectedUserId = userId
        val sessionId = UUID.randomUUID().toString().take(12)
        activeSessionId = sessionId

        cleanupActiveConnectionOnly()
        _p2pConnectionStatus.value = "جاري إنشاء نفق WebRTC P2P العالمي عبر Firebase Signaling..."

        val factory = getSharedPeerConnectionFactory(appContext)
        if (factory == null) {
            _p2pConnectionStatus.value = "وضع التوافق النشط (تعذر تهيئة محرك WebRTC الأصلي على هذا الجهاز)"
            return
        }

        // 1. Configure Google STUN Servers for NAT traversal across different Wi-Fi / 4G / 5G networks
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        val signalingLock = Any()
        val adminCandidatesArray = JSONArray()
        val addedServerCandidates = mutableSetOf<String>()
        val pendingServerCandidates = mutableListOf<IceCandidate>()
        var isRemoteAnswerStarted = false
        var isRemoteDescriptionSet = false
        var localOfferSdpText = ""

        val firestore = try {
            FirebaseFirestore.getInstance(
                appContext.getString(R.string.firestore_database_id)
            )
        } catch (_: Throwable) {
            null
        }
        val signalingDoc = firestore?.collection("users")?.document(userId)
            ?.collection("signaling")?.document("main")
        val legacySignalingDoc = firestore?.collection("users")?.document(userId)
            ?.collection("webrtc_signaling")?.document("main")

        val rtdbRef = try {
            FirebaseDatabase.getInstance()
                .getReference("users")
                .child(userId)
                .child("signaling")
        } catch (_: Throwable) {
            null
        }
        rtdbSignalingRef = rtdbRef

        fun publishSignalingFields(fields: Map<String, Any>) {
            try {
                signalingDoc?.set(fields, SetOptions.merge())
                legacySignalingDoc?.set(fields, SetOptions.merge())
            } catch (_: Throwable) {
            }
            try {
                rtdbRef?.updateChildren(fields)
            } catch (_: Throwable) {
            }
        }

        val pc = try {
            factory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        when (newState) {
                            PeerConnection.IceConnectionState.CHECKING -> {
                                _p2pConnectionStatus.value = "جاري عبور الجدران النارية عبر Google STUN (ICE Checking)..."
                            }
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                _isP2pConnected.value = true
                                _p2pConnectionStatus.value = "متصل عالمياً عبر WebRTC P2P (RTCDataChannel نشط)"
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                _isP2pConnected.value = false
                                _p2pConnectionStatus.value = "انقطع نفق WebRTC P2P مؤقتاً"
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                _isP2pConnected.value = false
                                _p2pConnectionStatus.value = "تعذر إكمال مسار ICE المباشر — جاري إعادة التفاوض التلقائي..."
                            }
                            PeerConnection.IceConnectionState.CLOSED -> {
                                _isP2pConnected.value = false
                                _p2pConnectionStatus.value = "تم إغلاق اتصال P2P"
                            }
                            else -> {}
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                        if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                            val serialized = synchronized(adminCandidatesArray) {
                                adminCandidatesArray.toString()
                            }
                            val fields = mutableMapOf<String, Any>(
                                "userId" to userId,
                                "sessionId" to sessionId,
                                "adminCandidatesJson" to serialized,
                                "updatedAt" to System.currentTimeMillis()
                            )
                            if (localOfferSdpText.isNotBlank()) {
                                fields["offerSdp"] = localOfferSdpText
                                fields["offerType"] = "offer"
                            }
                            publishSignalingFields(fields)
                        }
                    }

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        if (candidate == null) return
                        val obj = JSONObject().apply {
                            put("sdpMid", candidate.sdpMid ?: "")
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("sdp", candidate.sdp)
                        }
                        val serialized = synchronized(adminCandidatesArray) {
                            adminCandidatesArray.put(obj)
                            adminCandidatesArray.toString()
                        }
                        val fields = mutableMapOf<String, Any>(
                            "userId" to userId,
                            "sessionId" to sessionId,
                            "adminCandidatesJson" to serialized,
                            "updatedAt" to System.currentTimeMillis()
                        )
                        if (localOfferSdpText.isNotBlank()) {
                            fields["offerSdp"] = localOfferSdpText
                            fields["offerType"] = "offer"
                        }
                        publishSignalingFields(fields)
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                    override fun onAddStream(stream: MediaStream?) {
                        stream?.videoTracks?.firstOrNull()?.let { track ->
                            _remoteVideoTrack.value = track
                        }
                    }

                    override fun onRemoveStream(stream: MediaStream?) {}

                    override fun onDataChannel(dataChannel: DataChannel?) {
                        if (dataChannel != null) {
                            attachDataChannelObserver(dataChannel)
                        }
                    }

                    override fun onRenegotiationNeeded() {}

                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        val track = receiver?.track() as? VideoTrack
                        if (track != null) {
                            _remoteVideoTrack.value = track
                        }
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track() as? VideoTrack
                        if (track != null) {
                            _remoteVideoTrack.value = track
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            null
        } ?: return

        peerConnection = pc

        try {
            // 1. Create the reliable ordered RTCDataChannel for all P2P Commands & File Transfers
            val dcInit = DataChannel.Init().apply {
                ordered = true
            }
            val dc = pc.createDataChannel("smart_home_p2p_channel", dcInit)
            attachDataChannelObserver(dc)

            // 2. Add Video Transceiver so the same PeerConnection also carries the live screen stream
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        } catch (_: Throwable) {
        }

        fun parseAndEnqueueOrAddServerCandidates(serverCandidatesJson: String) {
            if (serverCandidatesJson.isBlank() || serverCandidatesJson == "[]") return
            val activePc = peerConnection ?: return
            synchronized(signalingLock) {
                try {
                    val arr = JSONArray(serverCandidatesJson)
                    for (i in 0 until arr.length()) {
                        val cObj = arr.getJSONObject(i)
                        val sdp = cObj.optString("sdp")
                        val sdpMid = cObj.optString("sdpMid")
                        val sdpMLineIndex = cObj.optInt("sdpMLineIndex", 0)
                        val key = "$sdpMid:$sdpMLineIndex:$sdp"
                        if (sdp.isNotBlank() && addedServerCandidates.add(key)) {
                            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                            if (isRemoteDescriptionSet) {
                                try {
                                    activePc.addIceCandidate(iceCandidate)
                                } catch (_: Throwable) {
                                }
                            } else {
                                // Queue candidate until setRemoteDescription(ANSWER) succeeds!
                                pendingServerCandidates.add(iceCandidate)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        fun processAnswerAndServerCandidates(
            incomingSessionId: String,
            offerSdpInDoc: String,
            answerSdp: String,
            answerType: String,
            serverCandidatesJson: String
        ) {
            val activePc = peerConnection ?: return

            // If the Home Node just started up and cleared offerSdp while we are still waiting for an answer,
            // immediately re-publish our current SDP Offer & Admin ICE candidates so negotiation never stalls.
            if (!isRemoteAnswerStarted &&
                offerSdpInDoc.isBlank() &&
                answerSdp.isBlank() &&
                localOfferSdpText.isNotBlank()
            ) {
                val currentAdminCandidates = synchronized(adminCandidatesArray) {
                    adminCandidatesArray.toString()
                }
                publishSignalingFields(
                    mapOf(
                        "userId" to userId,
                        "sessionId" to sessionId,
                        "offerSdp" to localOfferSdpText,
                        "offerType" to "offer",
                        "adminCandidatesJson" to currentAdminCandidates,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
                return
            }

            if (incomingSessionId.isNotBlank() && incomingSessionId != sessionId) return

            // Enqueue or apply any server ICE candidates
            parseAndEnqueueOrAddServerCandidates(serverCandidatesJson)

            synchronized(signalingLock) {
                if (!isRemoteAnswerStarted && answerSdp.isNotBlank() && answerType.equals("answer", ignoreCase = true)) {
                    isRemoteAnswerStarted = true
                    try {
                        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                        activePc.setRemoteDescription(
                            object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    synchronized(signalingLock) {
                                        isRemoteDescriptionSet = true
                                        val queued = pendingServerCandidates.toList()
                                        pendingServerCandidates.clear()
                                        for (candidate in queued) {
                                            try {
                                                activePc.addIceCandidate(candidate)
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }
                                    _p2pConnectionStatus.value = "تم استلام SDP Answer وتطبيق مرشحات STUN • جاري فتح DataChannel..."
                                }

                                override fun onSetFailure(error: String?) {
                                    synchronized(signalingLock) {
                                        isRemoteAnswerStarted = false
                                    }
                                }
                            },
                            answerDesc
                        )
                    } catch (_: Throwable) {
                        isRemoteAnswerStarted = false
                    }
                }
            }
        }

        // Listen via Firestore (`users/{uid}/signaling/main`)
        try {
            firestoreSignalingRegistration = signalingDoc?.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                processAnswerAndServerCandidates(
                    incomingSessionId = snapshot.getString("sessionId").orEmpty(),
                    offerSdpInDoc = snapshot.getString("offerSdp").orEmpty(),
                    answerSdp = snapshot.getString("answerSdp").orEmpty(),
                    answerType = snapshot.getString("answerType").orEmpty(),
                    serverCandidatesJson = snapshot.getString("serverCandidatesJson").orEmpty()
                )
            }
        } catch (_: Throwable) {
        }

        // Also listen via legacy Firestore path (`users/{uid}/webrtc_signaling/main`)
        try {
            legacyFirestoreSignalingRegistration = legacySignalingDoc?.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                processAnswerAndServerCandidates(
                    incomingSessionId = snapshot.getString("sessionId").orEmpty(),
                    offerSdpInDoc = snapshot.getString("offerSdp").orEmpty(),
                    answerSdp = snapshot.getString("answerSdp").orEmpty(),
                    answerType = snapshot.getString("answerType").orEmpty(),
                    serverCandidatesJson = snapshot.getString("serverCandidatesJson").orEmpty()
                )
            }
        } catch (_: Throwable) {
        }

        // Also listen via Firebase Realtime Database (`users/{uid}/signaling`)
        if (rtdbRef != null) {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    processAnswerAndServerCandidates(
                        incomingSessionId = snapshot.child("sessionId").getValue(String::class.java).orEmpty(),
                        offerSdpInDoc = snapshot.child("offerSdp").getValue(String::class.java).orEmpty(),
                        answerSdp = snapshot.child("answerSdp").getValue(String::class.java).orEmpty(),
                        answerType = snapshot.child("answerType").getValue(String::class.java).orEmpty(),
                        serverCandidatesJson = snapshot.child("serverCandidatesJson").getValue(String::class.java).orEmpty()
                    )
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            rtdbSignalingListener = listener
            try {
                rtdbRef.addValueEventListener(listener)
            } catch (_: Throwable) {
            }
        }

        // Create SDP Offer and publish to `users/{uid}/signaling` WITHOUT overwriting already-gathered Admin candidates
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
            pc.createOffer(
                object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        localOfferSdpText = desc.description
                        try {
                            pc.setLocalDescription(
                                object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        val currentAdminCandidates = synchronized(adminCandidatesArray) {
                                            adminCandidatesArray.toString()
                                        }
                                        publishSignalingFields(
                                            mapOf(
                                                "userId" to userId,
                                                "sessionId" to sessionId,
                                                "offerSdp" to desc.description,
                                                "offerType" to "offer",
                                                "answerSdp" to "",
                                                "answerType" to "",
                                                "serverCandidatesJson" to "[]",
                                                "adminCandidatesJson" to currentAdminCandidates,
                                                "updatedAt" to System.currentTimeMillis()
                                            )
                                        )
                                        _p2pConnectionStatus.value = "تم إرسال SDP Offer مع خوادم Google STUN • بانتظار رد هاتف المنزل..."
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

        // Watchdog: if DataChannel hasn't opened after 8 seconds, automatically re-negotiate once
        negotiationWatchdogJob = scope.launch {
            delay(8_000L)
            if (isActive && activeSessionId == sessionId && !_isP2pConnected.value) {
                val dcState = try { commandDataChannel?.state() } catch (_: Throwable) { null }
                if (dcState != DataChannel.State.OPEN) {
                    connectToHomeNodeP2p(
                        userId = userId,
                        secretToken = activeSecretToken,
                        forceReconnect = true
                    )
                }
            }
        }
    }

    private fun attachDataChannelObserver(channel: DataChannel) {
        commandDataChannel = channel
        try {
            if (channel.state() == DataChannel.State.OPEN) {
                _isP2pConnected.value = true
                _p2pConnectionStatus.value = "متصل عالمياً عبر WebRTC P2P (RTCDataChannel OPEN)"
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
                        if (state == DataChannel.State.OPEN) {
                            _isP2pConnected.value = true
                            _p2pConnectionStatus.value = "متصل عالمياً عبر WebRTC P2P (RTCDataChannel OPEN)"
                        } else if (state == DataChannel.State.CLOSED) {
                            _isP2pConnected.value = false
                        }
                    }

                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        if (buffer == null) return
                        try {
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            if (!buffer.binary) {
                                val text = String(bytes, StandardCharsets.UTF_8)
                                handleIncomingDataChannelJson(text)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun handleIncomingDataChannelJson(rawText: String) {
        try {
            val json = JSONObject(rawText)
            val reqId = json.optString("reqId")
            val type = json.optString("type")

            when (type) {
                "download_start" -> {
                    val session = activeDownloads[reqId] ?: return
                    val total = json.optLong("totalBytes", session.totalBytes)
                    session.onProgress(
                        TransferProgress(
                            isActive = true,
                            fileName = session.fileName,
                            isUpload = false,
                            bytesTransferred = 0L,
                            totalBytes = total,
                            statusMessage = "جاري سحب الملف عبر نفق WebRTC P2P..."
                        )
                    )
                }

                "download_chunk" -> {
                    val session = activeDownloads[reqId] ?: return
                    val base64Data = json.optString("data")
                    if (base64Data.isNotEmpty()) {
                        val chunkBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        session.outputStream.write(chunkBytes)
                        session.receivedBytes += chunkBytes.size
                        session.onProgress(
                            TransferProgress(
                                isActive = true,
                                fileName = session.fileName,
                                isUpload = false,
                                bytesTransferred = session.receivedBytes,
                                totalBytes = session.totalBytes.coerceAtLeast(session.receivedBytes),
                                statusMessage = "تحميل P2P: ${RemoteFileItem.formatBytes(session.receivedBytes)} / ${RemoteFileItem.formatBytes(session.totalBytes)}"
                            )
                        )
                    }
                }

                "download_end" -> {
                    val session = activeDownloads.remove(reqId) ?: return
                    try {
                        session.outputStream.flush()
                        session.outputStream.close()
                    } catch (_: Throwable) {
                    }
                    session.onProgress(TransferProgress(isActive = false))
                    session.completion.complete(session.outputFile)
                }

                "download_error" -> {
                    val session = activeDownloads.remove(reqId) ?: return
                    try {
                        session.outputStream.close()
                    } catch (_: Throwable) {
                    }
                    session.onProgress(TransferProgress(isActive = false))
                    session.completion.completeExceptionally(
                        IllegalStateException(json.optString("error", "فشل تحميل الملف عبر WebRTC DataChannel"))
                    )
                }

                else -> {
                    if (reqId.isNotBlank()) {
                        pendingJsonResponses.remove(reqId)?.complete(json)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // UNIFIED JSON COMMAND PROTOCOL OVER RTCDATACHANNEL
    // (/status, /files, /download, /upload, /delete, /screenshot)
    // =========================================================================

    private suspend fun sendP2pCommandAndAwait(
        action: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 12_000L
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val dc = commandDataChannel
        if (dc == null || try { dc.state() != DataChannel.State.OPEN } catch (_: Throwable) { true }) {
            return@withContext Result.failure(
                IllegalStateException("قناة WebRTC DataChannel غير متصلة حالياً — جاري التفاوض السحابي...")
            )
        }

        val reqId = UUID.randomUUID().toString().take(12)
        val deferred = CompletableDeferred<JSONObject>()
        pendingJsonResponses[reqId] = deferred

        return@withContext try {
            val payload = JSONObject().apply {
                put("reqId", reqId)
                put("action", action)
                put("token", activeSecretToken)
                for ((k, v) in params) {
                    put(k, v)
                }
            }
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            val sent = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            if (!sent) {
                pendingJsonResponses.remove(reqId)
                return@withContext Result.failure(IllegalStateException("تعذر إرسال حزمة البيانات عبر RTCDataChannel"))
            }

            val response = withTimeout(timeoutMs) {
                deferred.await()
            }
            if (!response.optBoolean("success", true)) {
                Result.failure(IllegalStateException(response.optString("error", "خطأ من هاتف المنزل")))
            } else {
                Result.success(response)
            }
        } catch (e: Exception) {
            pendingJsonResponses.remove(reqId)
            Result.failure(e)
        }
    }

    /**
     * 1. Command `/status` over RTCDataChannel
     */
    suspend fun requestNodeStatusP2p(currentNode: HomeNodeInfo): Result<HomeNodeInfo> {
        return sendP2pCommandAndAwait(action = "/status").map { json ->
            currentNode.copy(
                isOnline = true,
                p2pState = "P2P_CONNECTED",
                batteryLevel = json.optInt("batteryLevel", currentNode.batteryLevel),
                freeStorageBytes = json.optLong("freeStorageBytes", currentNode.freeStorageBytes),
                totalStorageBytes = json.optLong("totalStorageBytes", currentNode.totalStorageBytes),
                lastUpdated = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    /**
     * 2. Command `/files` over RTCDataChannel
     */
    suspend fun listRemoteFilesP2p(dirPath: String = ""): Result<SmartCloudRepository.RemoteDirectoryListing> {
        return sendP2pCommandAndAwait(
            action = "/files",
            params = mapOf("path" to dirPath)
        ).map { json ->
            val currentPath = json.optString("currentPath", dirPath)
            val parentPath = json.optString("parentPath", "")
            val rootPath = json.optString("rootPath", currentPath)
            val filesJson = json.optJSONArray("files") ?: JSONArray()

            val items = buildList {
                for (i in 0 until filesJson.length()) {
                    val obj = filesJson.getJSONObject(i)
                    add(
                        RemoteFileItem(
                            name = obj.optString("name"),
                            path = obj.optString("path"),
                            isDirectory = obj.optBoolean("isDirectory", false),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            lastModified = obj.optLong("lastModified", 0L)
                        )
                    )
                }
            }
            SmartCloudRepository.RemoteDirectoryListing(
                currentPath = currentPath,
                parentPath = parentPath,
                rootPath = rootPath,
                items = items
            )
        }
    }

    /**
     * 3. Command `/screenshot` over RTCDataChannel (Returns live JPEG Bitmap over P2P from anywhere)
     */
    suspend fun requestScreenshotBitmapP2p(quality: Int = 72): Result<Bitmap> {
        return sendP2pCommandAndAwait(
            action = "/screenshot",
            params = mapOf("quality" to quality),
            timeoutMs = 8_000L
        ).mapCatching { json ->
            val base64Jpeg = json.optString("jpegBase64")
            if (base64Jpeg.isBlank()) {
                throw IllegalStateException("بيانات لقطة الشاشة فارغة")
            }
            val bytes = Base64.decode(base64Jpeg, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("تعذر فك تشفير صورة JPEG المستلمة عبر RTCDataChannel")
            _latestScreenshotBitmap.value = bitmap
            bitmap
        }
    }

    /**
     * 4. Command `/delete` over RTCDataChannel
     */
    suspend fun deleteRemoteFileP2p(targetPath: String): Result<Unit> {
        return sendP2pCommandAndAwait(
            action = "/delete",
            params = mapOf("path" to targetPath)
        ).map { }
    }

    /**
     * 5. Command `/download` over RTCDataChannel (Chunked P2P transfer directly to Downloads or Cache)
     */
    suspend fun downloadRemoteFileP2p(
        context: Context,
        fileItem: RemoteFileItem,
        saveToCacheForStreaming: Boolean = false,
        onProgress: (TransferProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val dc = commandDataChannel
        if (dc == null || try { dc.state() != DataChannel.State.OPEN } catch (_: Throwable) { true }) {
            return@withContext Result.failure(
                IllegalStateException("قناة WebRTC DataChannel غير متصلة حالياً")
            )
        }

        val targetDir = if (saveToCacheForStreaming) {
            File(context.cacheDir, "p2p_stream_cache").apply { mkdirs() }
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?.takeIf { it.exists() || it.mkdirs() }
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
        }

        val destFile = File(targetDir, fileItem.name)
        val reqId = UUID.randomUUID().toString().take(12)
        val deferred = CompletableDeferred<File>()

        return@withContext try {
            val fos = FileOutputStream(destFile)
            activeDownloads[reqId] = IncomingDownloadSession(
                fileName = fileItem.name,
                totalBytes = fileItem.sizeBytes,
                outputFile = destFile,
                outputStream = fos,
                onProgress = onProgress,
                completion = deferred
            )

            val requestJson = JSONObject().apply {
                put("reqId", reqId)
                put("action", "/download")
                put("token", activeSecretToken)
                put("path", fileItem.path)
            }
            val bytes = requestJson.toString().toByteArray(StandardCharsets.UTF_8)
            val sent = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            if (!sent) {
                activeDownloads.remove(reqId)?.outputStream?.close()
                return@withContext Result.failure(IllegalStateException("فشل إرسال طلب التحميل عبر DataChannel"))
            }

            val savedFile = withTimeout(180_000L) {
                deferred.await()
            }
            Result.success(savedFile)
        } catch (e: Exception) {
            activeDownloads.remove(reqId)?.let {
                try {
                    it.outputStream.close()
                } catch (_: Throwable) {
                }
            }
            onProgress(TransferProgress(isActive = false))
            Result.failure(e)
        }
    }

    /**
     * 6. Command `/upload` over RTCDataChannel (Chunked P2P file upload from Admin to Home Node)
     */
    suspend fun uploadFileToHomeNodeP2p(
        context: Context,
        targetDirPath: String,
        fileUri: android.net.Uri,
        onProgress: (TransferProgress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val dc = commandDataChannel
        if (dc == null || try { dc.state() != DataChannel.State.OPEN } catch (_: Throwable) { true }) {
            return@withContext Result.failure(
                IllegalStateException("قناة WebRTC DataChannel غير متصلة حالياً")
            )
        }

        try {
            val contentResolver = context.contentResolver
            var displayName = "uploaded_${System.currentTimeMillis()}"
            var fileSize = -1L

            contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }

            val uploadId = UUID.randomUUID().toString().take(12)

            // Step 1: Send upload_start command
            val startRes = sendP2pCommandAndAwait(
                action = "/upload_start",
                params = mapOf(
                    "uploadId" to uploadId,
                    "path" to targetDirPath,
                    "filename" to displayName,
                    "totalBytes" to fileSize.coerceAtLeast(0L)
                )
            )
            if (startRes.isFailure) {
                return@withContext Result.failure(startRes.exceptionOrNull() ?: IllegalStateException("Upload start failed"))
            }

            // Step 2: Stream file in 24KB chunks encoded as Base64 JSON frames over RTCDataChannel
            val inputStream = contentResolver.openInputStream(fileUri)
                ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة الملف المختار"))

            val chunkBuffer = ByteArray(24 * 1024)
            var uploadedBytes = 0L

            inputStream.use { input ->
                while (true) {
                    val read = input.read(chunkBuffer)
                    if (read <= 0) break

                    // Respect DataChannel backpressure
                    var waitLoops = 0
                    while (try { dc.bufferedAmount() > 1_048_576L } catch (_: Throwable) { false } && waitLoops < 100) {
                        delay(25L)
                        waitLoops++
                    }

                    val slice = if (read == chunkBuffer.size) chunkBuffer else chunkBuffer.copyOf(read)
                    val base64Chunk = Base64.encodeToString(slice, Base64.NO_WRAP)

                    val chunkMsg = JSONObject().apply {
                        put("action", "/upload_chunk")
                        put("token", activeSecretToken)
                        put("uploadId", uploadId)
                        put("data", base64Chunk)
                    }
                    val bytes = chunkMsg.toString().toByteArray(StandardCharsets.UTF_8)
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))

                    uploadedBytes += read
                    onProgress(
                        TransferProgress(
                            isActive = true,
                            fileName = displayName,
                            isUpload = true,
                            bytesTransferred = uploadedBytes,
                            totalBytes = fileSize.coerceAtLeast(uploadedBytes),
                            statusMessage = "رفع P2P مباشر: ${RemoteFileItem.formatBytes(uploadedBytes)}"
                        )
                    )
                }
            }

            // Step 3: Commit upload_finish and await confirmation
            val finishRes = sendP2pCommandAndAwait(
                action = "/upload_finish",
                params = mapOf(
                    "uploadId" to uploadId,
                    "filename" to displayName
                ),
                timeoutMs = 20_000L
            )
            onProgress(TransferProgress(isActive = false))
            finishRes.map { displayName }
        } catch (e: Exception) {
            onProgress(TransferProgress(isActive = false))
            Result.failure(e)
        }
    }

    @Synchronized
    private fun cleanupActiveConnectionOnly() {
        negotiationWatchdogJob?.cancel()
        negotiationWatchdogJob = null

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
            rtdbSignalingListener?.let { rtdbSignalingRef?.removeEventListener(it) }
        } catch (_: Throwable) {
        }
        rtdbSignalingListener = null

        try {
            commandDataChannel?.unregisterObserver()
        } catch (_: Throwable) {
        }
        try {
            commandDataChannel?.close()
        } catch (_: Throwable) {
        }
        commandDataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Throwable) {
        }
        peerConnection = null
        _isP2pConnected.value = false
    }

    @Synchronized
    fun disconnectAll() {
        cleanupActiveConnectionOnly()
        _remoteVideoTrack.value = null
        _p2pConnectionStatus.value = "غير متصل"
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: WebRtcP2pClientManager? = null

        @Volatile
        private var sharedEglBase: EglBase? = null

        @Volatile
        private var sharedPeerConnectionFactory: PeerConnectionFactory? = null

        @Volatile
        private var isNativeRuntimeInitialized: Boolean = false

        private val webRtcInitLock = Any()

        fun getInstance(context: Context): WebRtcP2pClientManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebRtcP2pClientManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Returns a single process-wide shared EglBase instance safely, catching any Error/Throwable
         * on older GPUs or emulators.
         */
        fun getSharedEglBase(): EglBase? {
            sharedEglBase?.let { return it }
            synchronized(webRtcInitLock) {
                sharedEglBase?.let { return it }
                val created = try {
                    EglBase.create()
                } catch (_: Throwable) {
                    try {
                        EglBase.create(null, EglBase.CONFIG_PLAIN)
                    } catch (_: Throwable) {
                        null
                    }
                }
                sharedEglBase = created
                return created
            }
        }

        /**
         * Initializes `PeerConnectionFactory` at most once per process and shares it safely between
         * `HomeServerForegroundService` and `WebRtcP2pClientManager`.
         */
        fun getSharedPeerConnectionFactory(context: Context): PeerConnectionFactory? {
            sharedPeerConnectionFactory?.let { return it }
            synchronized(webRtcInitLock) {
                sharedPeerConnectionFactory?.let { return it }
                return try {
                    val appCtx = context.applicationContext
                    if (!isNativeRuntimeInitialized) {
                        PeerConnectionFactory.InitializationOptions.builder(appCtx)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                            .let { PeerConnectionFactory.initialize(it) }
                        isNativeRuntimeInitialized = true
                    }

                    val eglContext = getSharedEglBase()?.eglBaseContext
                    val encoderFactory = try {
                        if (eglContext != null) {
                            DefaultVideoEncoderFactory(eglContext, true, false)
                        } else {
                            SoftwareVideoEncoderFactory()
                        }
                    } catch (_: Throwable) {
                        SoftwareVideoEncoderFactory()
                    }

                    val decoderFactory = try {
                        if (eglContext != null) {
                            DefaultVideoDecoderFactory(eglContext)
                        } else {
                            SoftwareVideoDecoderFactory()
                        }
                    } catch (_: Throwable) {
                        SoftwareVideoDecoderFactory()
                    }

                    PeerConnectionFactory.builder()
                        .setVideoEncoderFactory(encoderFactory)
                        .setVideoDecoderFactory(decoderFactory)
                        .createPeerConnectionFactory()
                        .also { sharedPeerConnectionFactory = it }
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}
