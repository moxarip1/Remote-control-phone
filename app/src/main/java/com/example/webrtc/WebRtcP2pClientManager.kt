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
import com.example.repository.AuthTokenManager
import com.example.repository.SmartCloudRepository.RemoteDirectoryListing
import com.google.android.gms.tasks.Tasks
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
import kotlinx.coroutines.withTimeoutOrNull
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
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Global WebRTC P2P Client Manager for the Admin Controller & Screen Mirroring Dashboard.
 *
 * Implements:
 * 1. Public Google STUN + OpenRelay Free TURN servers (`iceServers`) for global NAT traversal across 4G/5G/Wi-Fi.
 * 2. Complete cleanup and deletion of the previous Firebase `signaling` node before starting any new Offer.
 * 3. Calling `pc.createDataChannel("dataChannel", ...)` strictly BEFORE `pc.createOffer()`.
 * 4. Candidate Queue (`candidateQueue`) so `addIceCandidate` is only invoked after `setRemoteDescription` succeeds.
 * 5. Real-time listeners for `onIceConnectionChange` and `onDataChannel` with live state (`connecting`, `open`, `failed`).
 */
class WebRtcP2pClientManager private constructor(
    private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var peerConnection: PeerConnection? = null
    private var commandDataChannel: DataChannel? = null

    private var rtdbSignalingRef: DatabaseReference? = null
    private var rtdbSignalingListener: ValueEventListener? = null
    private var firestoreSignalingRegistration: ListenerRegistration? = null
    private var legacyFirestoreSignalingRegistration: ListenerRegistration? = null

    private var negotiationJob: Job? = null
    private var negotiationWatchdogJob: Job? = null
    private var connectedUserId: String? = null
    private var activeSessionId: String? = null
    private var activeSecretToken: String = ""
    private var lastAttemptTimestampMs: Long = 0L

    private val _isP2pConnected = MutableStateFlow(false)
    val isP2pConnected: StateFlow<Boolean> = _isP2pConnected.asStateFlow()

    /**
     * Real-time connection state code: "connecting", "open", or "failed".
     */
    private val _p2pRealtimeState = MutableStateFlow("connecting")
    val p2pRealtimeState: StateFlow<String> = _p2pRealtimeState.asStateFlow()

    private val _p2pConnectionStatus = MutableStateFlow("غير متصل — بانتظار بدء التفاوض السحابي")
    val p2pConnectionStatus: StateFlow<String> = _p2pConnectionStatus.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _liveMirrorBitmap = MutableStateFlow<Bitmap?>(null)
    val liveMirrorBitmap: StateFlow<Bitmap?> = _liveMirrorBitmap.asStateFlow()

    val eglBase: EglBase?
        get() = getSharedEglBase()

    // Pending Request-Response Coroutine Map keyed by `requestId`
    private val pendingCommandRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private data class IncomingDownloadAccumulator(
        val requestId: String,
        val totalSize: Long,
        val fileName: String,
        val outputStream: FileOutputStream,
        val targetFile: File,
        var receivedBytes: Long = 0L,
        val onProgress: (TransferProgress) -> Unit,
        val completion: CompletableDeferred<Result<File>>
    )

    private val activeDownloads = ConcurrentHashMap<String, IncomingDownloadAccumulator>()

    private data class IncomingScreenshotAccumulator(
        val requestId: String,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        val completion: CompletableDeferred<ByteArray?>
    )

    private val activeScreenshots = ConcurrentHashMap<String, IncomingScreenshotAccumulator>()

    /**
     * Initiates or maintains the global WebRTC P2P connection to the Home Node via `users/{uid}/signaling`.
     */
    @Synchronized
    fun connectToHomeNodeP2p(
        userId: String,
        secretToken: String = "",
        forceReconnect: Boolean = false
    ) {
        if (userId.isBlank()) return
        if (secretToken.isNotBlank()) {
            activeSecretToken = secretToken
        }

        val currentChannelState = try {
            commandDataChannel?.state()
        } catch (_: Throwable) {
            null
        }
        val currentIceState = try {
            peerConnection?.iceConnectionState()
        } catch (_: Throwable) {
            null
        }

        if (!forceReconnect &&
            connectedUserId == userId &&
            peerConnection != null &&
            currentChannelState == DataChannel.State.OPEN &&
            (currentIceState == PeerConnection.IceConnectionState.CONNECTED ||
                currentIceState == PeerConnection.IceConnectionState.COMPLETED)
        ) {
            _isP2pConnected.value = true
            _p2pRealtimeState.value = "open"
            return
        }

        val now = System.currentTimeMillis()
        if (!forceReconnect &&
            connectedUserId == userId &&
            peerConnection != null &&
            (now - lastAttemptTimestampMs) < 4_500L &&
            currentIceState != PeerConnection.IceConnectionState.FAILED &&
            currentIceState != PeerConnection.IceConnectionState.DISCONNECTED &&
            currentIceState != PeerConnection.IceConnectionState.CLOSED
        ) {
            return
        }

        lastAttemptTimestampMs = now
        connectedUserId = userId
        val sessionId = UUID.randomUUID().toString().take(12)
        activeSessionId = sessionId

        negotiationJob?.cancel()
        negotiationJob = scope.launch {
            startNewWebRtcNegotiationSuspend(userId = userId, sessionId = sessionId)
        }
    }

    /**
     * Builds the public STUN + TURN server list using Google STUN and OpenRelay Public TURN servers.
     */
    fun buildPublicStunTurnIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            // Google Public STUN Servers
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            // OpenRelay Free Public STUN & TURN Servers (UDP 80, UDP 443, TCP 443, TLS 443)
            PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
    }

    private suspend fun startNewWebRtcNegotiationSuspend(userId: String, sessionId: String) {
        cleanupActiveConnectionOnly()
        _isP2pConnected.value = false
        _p2pRealtimeState.value = "connecting"
        _p2pConnectionStatus.value = "الحالة اللحظية: connecting • جاري تنظيف عقدة signaling السابقة في Firebase..."

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

        // 1. Completely delete and clean previous signaling node in Firebase before starting a new Offer
        // to prevent stale ICE candidates or old SDP answers from interfering.
        withContext(Dispatchers.IO) {
            try {
                signalingDoc?.let { Tasks.await(it.delete(), 2500, TimeUnit.MILLISECONDS) }
            } catch (_: Throwable) {
            }
            try {
                legacySignalingDoc?.let { Tasks.await(it.delete(), 1500, TimeUnit.MILLISECONDS) }
            } catch (_: Throwable) {
            }
            try {
                rtdbRef?.let { Tasks.await(it.removeValue(), 1500, TimeUnit.MILLISECONDS) }
            } catch (_: Throwable) {
            }
        }

        if (activeSessionId != sessionId) return

        val factory = getSharedPeerConnectionFactory(appContext)
        if (factory == null) {
            _p2pRealtimeState.value = "failed"
            _p2pConnectionStatus.value = "الحالة اللحظية: failed • تعذر تهيئة محرك WebRTC الأصلي"
            return
        }

        // 2. Configure STUN/TURN iceServers (stun.l.google.com + OpenRelay public TURN servers)
        val iceServers = buildPublicStunTurnIceServers()
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
        // 4. Candidate Queue: holds incoming remote ICE candidates until setRemoteDescription succeeds
        val candidateQueue = mutableListOf<IceCandidate>()
        var isRemoteAnswerStarted = false
        var isRemoteDescriptionSet = false
        var isOfferPublished = false
        var localOfferSdpText = ""

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

                    // 5. Listener for oniceconnectionstatechange with real-time state updates (connecting, open, failed)
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        when (newState) {
                            PeerConnection.IceConnectionState.NEW,
                            PeerConnection.IceConnectionState.CHECKING -> {
                                if (!_isP2pConnected.value) {
                                    _p2pRealtimeState.value = "connecting"
                                    _p2pConnectionStatus.value =
                                        "الحالة اللحظية: connecting • جاري فحص مسارات STUN/TURN (ICE Checking)..."
                                }
                            }
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                _isP2pConnected.value = true
                                _p2pRealtimeState.value = "open"
                                _p2pConnectionStatus.value =
                                    "الحالة اللحظية: open • متصل عالمياً عبر WebRTC P2P (ICE & DataChannel Active)"
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                _isP2pConnected.value = false
                                _p2pRealtimeState.value = "connecting"
                                _p2pConnectionStatus.value =
                                    "الحالة اللحظية: connecting • انقطع الاتصال مؤقتاً وجاري استعادته..."
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                _isP2pConnected.value = false
                                _p2pRealtimeState.value = "failed"
                                _p2pConnectionStatus.value =
                                    "الحالة اللحظية: failed • تعذر مسار ICE، جاري إعادة التفاوض عبر TURN..."
                            }
                            PeerConnection.IceConnectionState.CLOSED -> {
                                _isP2pConnected.value = false
                                _p2pRealtimeState.value = "failed"
                                _p2pConnectionStatus.value =
                                    "الحالة اللحظية: failed • تم إغلاق قناة الاتصال"
                            }
                            else -> {}
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                        if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                            val shouldPublish = synchronized(signalingLock) { isOfferPublished }
                            if (!shouldPublish) return
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
                        val shouldPublish = synchronized(signalingLock) { isOfferPublished }
                        if (!shouldPublish) return

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

                    // 5. Listener for ondatachannel with real-time state updates (connecting, open, failed)
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

        // 3. Call pc.createDataChannel("dataChannel") strictly BEFORE pc.createOffer()
        try {
            val dcInit = DataChannel.Init().apply {
                ordered = true
            }
            val dc = pc.createDataChannel("dataChannel", dcInit)
            attachDataChannelObserver(dc)

            // Add Video Transceiver so live screen mirroring also flows over the same PeerConnection
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        } catch (_: Throwable) {
        }

        // 4. Candidate Queue helper: queues remote candidates until setRemoteDescription succeeds
        fun enqueueOrAddRemoteCandidates(serverCandidatesJson: String) {
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
                                candidateQueue.add(iceCandidate)
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

            if (!isRemoteAnswerStarted &&
                isOfferPublished &&
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

            // Enqueue or add incoming Server ICE candidates via Candidate Queue
            enqueueOrAddRemoteCandidates(serverCandidatesJson)

            synchronized(signalingLock) {
                if (!isRemoteAnswerStarted && answerSdp.isNotBlank() && answerType.equals("answer", ignoreCase = true)) {
                    isRemoteAnswerStarted = true
                    try {
                        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                        activePc.setRemoteDescription(
                            object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    // Flush the Candidate Queue ONLY after setRemoteDescription succeeds!
                                    synchronized(signalingLock) {
                                        isRemoteDescriptionSet = true
                                        val queued = candidateQueue.toList()
                                        candidateQueue.clear()
                                        for (candidate in queued) {
                                            try {
                                                activePc.addIceCandidate(candidate)
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }
                                    if (!_isP2pConnected.value) {
                                        _p2pRealtimeState.value = "connecting"
                                        _p2pConnectionStatus.value =
                                            "الحالة اللحظية: connecting • تم ضبط Answer وتفريغ طابور المرشحات، جاري فتح DataChannel..."
                                    }
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

        // Attach Firestore & RTDB listeners before publishing the fresh Offer
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

        // Now call pc.createOffer() (after pc.createDataChannel("dataChannel") was already called above)
        try {
            val constraints = MediaConstraints()
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
                                        synchronized(signalingLock) {
                                            isOfferPublished = true
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
                                        if (!_isP2pConnected.value) {
                                            _p2pRealtimeState.value = "connecting"
                                            _p2pConnectionStatus.value =
                                                "الحالة اللحظية: connecting • تم إرسال Offer عبر STUN/TURN وبانتظار Answer..."
                                        }
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

        // Watchdog: if DataChannel hasn't opened after 10 seconds, automatically re-negotiate
        negotiationWatchdogJob = scope.launch {
            delay(10_000L)
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
            when (channel.state()) {
                DataChannel.State.CONNECTING -> {
                    if (!_isP2pConnected.value) {
                        _p2pRealtimeState.value = "connecting"
                        _p2pConnectionStatus.value = "الحالة اللحظية: connecting • جاري فتح قناة البيانات (dataChannel)..."
                    }
                }
                DataChannel.State.OPEN -> {
                    _isP2pConnected.value = true
                    _p2pRealtimeState.value = "open"
                    _p2pConnectionStatus.value = "الحالة اللحظية: open • قناة البيانات (dataChannel) مفتوحة ونشطة"
                }
                DataChannel.State.CLOSED -> {
                    _isP2pConnected.value = false
                    _p2pRealtimeState.value = "failed"
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
                                if (!_isP2pConnected.value) {
                                    _p2pRealtimeState.value = "connecting"
                                    _p2pConnectionStatus.value =
                                        "الحالة اللحظية: connecting • جاري فتح قناة البيانات (dataChannel)..."
                                }
                            }
                            DataChannel.State.OPEN -> {
                                _isP2pConnected.value = true
                                _p2pRealtimeState.value = "open"
                                _p2pConnectionStatus.value =
                                    "الحالة اللحظية: open • قناة البيانات (dataChannel) مفتوحة ونشطة"
                            }
                            DataChannel.State.CLOSED -> {
                                _isP2pConnected.value = false
                                _p2pRealtimeState.value = "failed"
                                _p2pConnectionStatus.value =
                                    "الحالة اللحظية: failed • تم إغلاق قناة البيانات (dataChannel)"
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
        val json = try {
            JSONObject(rawText)
        } catch (_: Throwable) {
            return
        }

        val reqId = json.optString("reqId").ifBlank { json.optString("requestId") }
        val msgType = json.optString("type").ifBlank { json.optString("kind") }

        when (msgType.lowercase()) {
            "download_start" -> {
                val acc = activeDownloads[reqId] ?: return
                val totalBytes = json.optLong("totalBytes", json.optLong("sizeBytes", acc.totalSize))
                acc.onProgress(
                    TransferProgress(
                        isActive = true,
                        fileName = acc.fileName,
                        bytesTransferred = 0L,
                        totalBytes = totalBytes,
                        isUpload = false,
                        statusMessage = "جاري السحب المباشر عبر WebRTC DataChannel..."
                    )
                )
            }

            "download_chunk" -> {
                val acc = activeDownloads[reqId] ?: return
                val dataBase64 = json.optString("data").ifBlank { json.optString("dataBase64") }
                if (dataBase64.isNotEmpty()) {
                    try {
                        val decoded = Base64.decode(dataBase64, Base64.NO_WRAP)
                        acc.outputStream.write(decoded)
                        acc.receivedBytes += decoded.size
                        acc.onProgress(
                            TransferProgress(
                                isActive = true,
                                fileName = acc.fileName,
                                bytesTransferred = acc.receivedBytes,
                                totalBytes = acc.totalSize.coerceAtLeast(acc.receivedBytes),
                                isUpload = false,
                                statusMessage = "تم سحب ${RemoteFileItem.formatBytes(acc.receivedBytes)} عبر P2P"
                            )
                        )
                    } catch (e: Throwable) {
                        activeDownloads.remove(reqId)
                        try {
                            acc.outputStream.close()
                        } catch (_: Throwable) {
                        }
                        acc.completion.complete(Result.failure(e))
                    }
                }
            }

            "download_end" -> {
                val acc = activeDownloads.remove(reqId) ?: return
                try {
                    acc.outputStream.flush()
                    acc.outputStream.close()
                    acc.onProgress(TransferProgress(isActive = false))
                    acc.completion.complete(Result.success(acc.targetFile))
                } catch (e: Throwable) {
                    acc.onProgress(TransferProgress(isActive = false))
                    acc.completion.complete(Result.failure(e))
                }
            }

            "download_error" -> {
                val acc = activeDownloads.remove(reqId) ?: return
                try {
                    acc.outputStream.close()
                } catch (_: Throwable) {
                }
                acc.onProgress(TransferProgress(isActive = false))
                val msg = json.optString("error", "تعذر سحب الملف عبر قناة WebRTC P2P")
                acc.completion.complete(Result.failure(IllegalStateException(msg)))
            }

            else -> {
                if (reqId.isNotBlank()) {
                    pendingCommandRequests.remove(reqId)?.complete(json)
                }
            }
        }
    }

    // =========================================================================
    // UNIFIED P2P DATACHANNEL COMMANDS (/status, /files, /download, /upload, /screenshot, /delete)
    // =========================================================================

    fun updateAuthToken(token: String) {
        activeSecretToken = token.trim()
    }

    private fun resolveEffectiveToken(explicitToken: String = ""): String {
        val candidate = explicitToken.trim()
            .ifBlank { AuthTokenManager.getSavedAuthToken(appContext) }
            .ifBlank { activeSecretToken.trim() }
        if (candidate.isNotBlank()) {
            activeSecretToken = candidate
        }
        return candidate
    }

    private fun buildHeadersJson(authToken: String): JSONObject {
        return JSONObject().apply {
            put("Content-Type", "application/json")
            put("x-auth-token", authToken)
        }
    }

    private suspend fun ensureDataChannelReady(
        timeoutMs: Long = 8_000L
    ): DataChannel? {
        val uid = connectedUserId
        if (!uid.isNullOrBlank()) {
            connectToHomeNodeP2p(
                userId = uid,
                secretToken = activeSecretToken,
                forceReconnect = false
            )
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dc = commandDataChannel
            if (dc != null && try { dc.state() == DataChannel.State.OPEN } catch (_: Throwable) { false }) {
                _isP2pConnected.value = true
                _p2pRealtimeState.value = "open"
                return dc
            }
            delay(120L)
        }
        return null
    }

    private suspend fun sendJsonCommandOverDataChannel(
        action: String,
        extraFields: JSONObject = JSONObject(),
        authToken: String = "",
        timeoutMs: Long = 10_000L
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل إرسال الطلبات")
            )
        }

        val dc = ensureDataChannelReady(timeoutMs = 6_000L)
            ?: return@withContext Result.failure(
                IllegalStateException("جاري التفاوض السحابي عبر WebRTC P2P (STUN/TURN)...")
            )

        val reqId = UUID.randomUUID().toString().take(12)
        val payload = JSONObject().apply {
            val keys = extraFields.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, extraFields.get(k))
            }
            put("reqId", reqId)
            put("action", action)
            put("headers", buildHeadersJson(resolvedToken))
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingCommandRequests[reqId] = deferred

        try {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            val sent = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            if (!sent) {
                pendingCommandRequests.remove(reqId)
                return@withContext Result.failure(
                    IllegalStateException("تعذر إرسال الأمر عبر قناة RTCDataChannel")
                )
            }

            val response = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
            pendingCommandRequests.remove(reqId)

            if (response == null) {
                return@withContext Result.failure(
                    IllegalStateException("انتهت مهلة انتظار الرد عبر قناة WebRTC P2P")
                )
            }

            val success = response.optBoolean("success", true)
            if (success) {
                Result.success(response)
            } else {
                val errMsg = response.optString("error", "خطأ من الخادم المنزلي")
                Result.failure(IllegalStateException(errMsg))
            }
        } catch (e: Throwable) {
            pendingCommandRequests.remove(reqId)
            Result.failure(e)
        }
    }

    suspend fun requestNodeStatusP2p(
        node: HomeNodeInfo,
        authToken: String = ""
    ): Result<HomeNodeInfo> {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل إرسال الطلبات")
            )
        }
        if (node.userId.isNotBlank()) {
            connectToHomeNodeP2p(node.userId, resolvedToken, forceReconnect = false)
        }
        return sendJsonCommandOverDataChannel(
            action = "/status",
            authToken = resolvedToken,
            timeoutMs = 8_000L
        ).map { res ->
            node.copy(
                isOnline = true,
                p2pState = "P2P_CONNECTED",
                batteryLevel = res.optInt("batteryLevel", node.batteryLevel),
                freeStorageBytes = res.optLong("freeStorageBytes", node.freeStorageBytes),
                totalStorageBytes = res.optLong("totalStorageBytes", node.totalStorageBytes),
                lastUpdated = res.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    suspend fun listRemoteFilesP2p(
        dirPath: String,
        authToken: String = ""
    ): Result<RemoteDirectoryListing> {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل استعراض المجلدات والملفات")
            )
        }
        val extra = JSONObject().apply {
            put("path", dirPath)
        }
        return sendJsonCommandOverDataChannel(
            action = "/files",
            extraFields = extra,
            authToken = resolvedToken,
            timeoutMs = 12_000L
        ).map { json ->
            val filesArray = json.optJSONArray("files") ?: json.optJSONArray("items") ?: JSONArray()
            val parsedItems = mutableListOf<RemoteFileItem>()
            for (i in 0 until filesArray.length()) {
                val obj = filesArray.getJSONObject(i)
                parsedItems.add(
                    RemoteFileItem(
                        name = obj.optString("name"),
                        path = obj.optString("path"),
                        isDirectory = obj.optBoolean("isDirectory", false),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        lastModified = obj.optLong("lastModified", 0L),
                        mimeType = obj.optString("mimeType", "")
                    )
                )
            }
            RemoteDirectoryListing(
                currentPath = json.optString("currentPath", dirPath),
                parentPath = json.optString("parentPath", ""),
                rootPath = json.optString("rootPath", ""),
                items = parsedItems
            )
        }
    }

    suspend fun deleteRemoteFileP2p(
        targetPath: String,
        authToken: String = ""
    ): Result<Unit> {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل حذف الملفات")
            )
        }
        val extra = JSONObject().apply {
            put("path", targetPath)
        }
        return sendJsonCommandOverDataChannel(
            action = "/delete",
            extraFields = extra,
            authToken = resolvedToken,
            timeoutMs = 10_000L
        ).map { }
    }

    suspend fun downloadRemoteFileP2p(
        context: Context,
        fileItem: RemoteFileItem,
        saveToCacheForStreaming: Boolean = false,
        authToken: String = "",
        onProgress: (TransferProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل تحميل الملفات")
            )
        }

        val dc = ensureDataChannelReady(timeoutMs = 8_000L)
            ?: return@withContext Result.failure(
                IllegalStateException("قناة WebRTC DataChannel غير متصلة بعد")
            )

        val reqId = UUID.randomUUID().toString().take(12)
        val targetDir = if (saveToCacheForStreaming) {
            File(context.cacheDir, "p2p_stream_cache").apply { mkdirs() }
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.takeIf { it.exists() || it.mkdirs() }
                ?: File(context.filesDir, "downloads").apply { mkdirs() }
        }
        val safeLocalName = fileItem.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "downloaded_file" }
        val targetFile = File(targetDir, safeLocalName)
        val outputStream = try {
            FileOutputStream(targetFile)
        } catch (e: Throwable) {
            return@withContext Result.failure(e)
        }

        val completion = CompletableDeferred<Result<File>>()
        val accumulator = IncomingDownloadAccumulator(
            requestId = reqId,
            totalSize = fileItem.sizeBytes,
            fileName = fileItem.name,
            outputStream = outputStream,
            targetFile = targetFile,
            onProgress = onProgress,
            completion = completion
        )
        activeDownloads[reqId] = accumulator

        val cmd = JSONObject().apply {
            put("reqId", reqId)
            put("action", "/download")
            put("path", fileItem.path)
            put("headers", buildHeadersJson(resolvedToken))
        }

        val bytes = cmd.toString().toByteArray(StandardCharsets.UTF_8)
        val sent = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        if (!sent) {
            activeDownloads.remove(reqId)
            try {
                outputStream.close()
            } catch (_: Throwable) {
            }
            return@withContext Result.failure(IllegalStateException("فشل إرسال طلب السحب عبر P2P"))
        }

        val result = withTimeoutOrNull(180_000L) {
            completion.await()
        }
        activeDownloads.remove(reqId)

        result ?: Result.failure(IllegalStateException("انتهت مهلة تحميل الملف عبر P2P"))
    }

    suspend fun uploadFileToHomeNodeP2p(
        context: Context,
        targetDirPath: String,
        fileUri: android.net.Uri,
        authToken: String = "",
        onProgress: (TransferProgress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل رفع الملفات")
            )
        }

        val dc = ensureDataChannelReady(timeoutMs = 8_000L)
            ?: return@withContext Result.failure(
                IllegalStateException("قناة WebRTC DataChannel غير متصلة بعد")
            )

        val resolver = context.contentResolver
        var fileName = "uploaded_${System.currentTimeMillis()}.bin"
        var totalSize = -1L

        resolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) {
                    val resolvedName = cursor.getString(nameIdx)
                    if (!resolvedName.isNullOrBlank()) fileName = resolvedName
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    totalSize = cursor.getLong(sizeIdx)
                }
            }
        }

        val uploadId = UUID.randomUUID().toString().take(12)
        val startExtra = JSONObject().apply {
            put("uploadId", uploadId)
            put("path", targetDirPath)
            put("filename", fileName)
        }
        val startResult = sendJsonCommandOverDataChannel(
            action = "/upload_start",
            extraFields = startExtra,
            authToken = resolvedToken,
            timeoutMs = 10_000L
        )
        if (startResult.isFailure) {
            return@withContext Result.failure(
                startResult.exceptionOrNull() ?: IllegalStateException("تعذر بدء الرفع عبر P2P")
            )
        }

        onProgress(
            TransferProgress(
                isActive = true,
                fileName = fileName,
                bytesTransferred = 0L,
                totalBytes = totalSize.coerceAtLeast(1L),
                isUpload = true,
                statusMessage = "بدء رفع الملف عبر WebRTC DataChannel..."
            )
        )

        var uploadedBytes = 0L
        val chunkBuffer = ByteArray(P2P_BINARY_CHUNK_BYTES)

        try {
            resolver.openInputStream(fileUri)?.use { input ->
                var read = input.read(chunkBuffer)
                while (read != -1) {
                    while (try { dc.bufferedAmount() > 512 * 1024L } catch (_: Throwable) { false }) {
                        delay(20L)
                    }
                    val slice = if (read == chunkBuffer.size) chunkBuffer else chunkBuffer.copyOf(read)
                    val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)

                    val chunkMsg = JSONObject().apply {
                        put("action", "/upload_chunk")
                        put("uploadId", uploadId)
                        put("data", b64)
                        put("headers", buildHeadersJson(resolvedToken))
                    }
                    val rawBytes = chunkMsg.toString().toByteArray(StandardCharsets.UTF_8)
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(rawBytes), false))

                    uploadedBytes += read
                    onProgress(
                        TransferProgress(
                            isActive = true,
                            fileName = fileName,
                            bytesTransferred = uploadedBytes,
                            totalBytes = totalSize.coerceAtLeast(uploadedBytes),
                            isUpload = true,
                            statusMessage = "جاري الرفع عبر P2P: ${RemoteFileItem.formatBytes(uploadedBytes)}"
                        )
                    )
                    read = input.read(chunkBuffer)
                }
            } ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة الملف المختار"))

            val finishExtra = JSONObject().apply {
                put("uploadId", uploadId)
            }
            val endResult = sendJsonCommandOverDataChannel(
                action = "/upload_finish",
                extraFields = finishExtra,
                authToken = resolvedToken,
                timeoutMs = 15_000L
            )
            onProgress(TransferProgress(isActive = false))

            endResult.map { fileName }
        } catch (e: Throwable) {
            onProgress(TransferProgress(isActive = false))
            Result.failure(e)
        }
    }

    suspend fun requestScreenshotBitmapP2p(
        quality: Int = 72,
        authToken: String = ""
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveToken(authToken)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً")
            )
        }
        val extra = JSONObject().apply {
            put("quality", quality)
        }
        sendJsonCommandOverDataChannel(
            action = "/screenshot",
            extraFields = extra,
            authToken = resolvedToken,
            timeoutMs = 8_000L
        ).mapCatching { res ->
            val base64Jpeg = res.optString("jpegBase64")
            if (base64Jpeg.isBlank()) {
                throw IllegalStateException("استجابة لقطة الشاشة فارغة")
            }
            val bytes = Base64.decode(base64Jpeg, Base64.NO_WRAP)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("تعذر فك تشفير صورة الشاشة عبر P2P")
            _liveMirrorBitmap.value = bmp
            bmp
        }
    }

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

        _remoteVideoTrack.value = null
        _isP2pConnected.value = false
    }

    @Synchronized
    fun disconnectAll() {
        negotiationJob?.cancel()
        negotiationJob = null
        cleanupActiveConnectionOnly()
        connectedUserId = null
        activeSessionId = null
        _p2pRealtimeState.value = "connecting"
        _p2pConnectionStatus.value = "غير متصل"
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val P2P_BINARY_CHUNK_BYTES = 16 * 1024

        @Volatile
        private var instance: WebRtcP2pClientManager? = null

        @Volatile
        private var sharedEglBase: EglBase? = null

        @Volatile
        private var sharedPeerConnectionFactory: PeerConnectionFactory? = null

        fun getInstance(context: Context): WebRtcP2pClientManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcP2pClientManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @Synchronized
        fun getSharedEglBase(): EglBase? {
            if (sharedEglBase == null) {
                try {
                    sharedEglBase = EglBase.create()
                } catch (_: Throwable) {
                }
            }
            return sharedEglBase
        }

        @Synchronized
        fun getSharedPeerConnectionFactory(context: Context): PeerConnectionFactory? {
            if (sharedPeerConnectionFactory != null) {
                return sharedPeerConnectionFactory
            }
            return try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                val eglContext = getSharedEglBase()?.eglBaseContext
                val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
                val decoderFactory = DefaultVideoDecoderFactory(eglContext)

                sharedPeerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
                sharedPeerConnectionFactory
            } catch (_: Throwable) {
                null
            }
        }
    }
}
