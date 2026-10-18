package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.HomeNodeInfo
import com.example.webrtc.WebRtcP2pClientManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.TimeUnit

/**
 * Global WebRTC P2P Dual-Mode Screen Mirroring & Live View Dashboard (`ScreenMirroringDashboard`):
 * 1. Top Section: Live `/screenshot` Polling every 1000ms over `RTCDataChannel` (with local loopback fallback).
 * 2. Bottom Section: Real-time 30 FPS WebRTC Live Video Stream (`SurfaceViewRenderer` + Firebase `users/{uid}/signaling`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenMirroringDashboard(
    discoveredNode: HomeNodeInfo?,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val p2pManager = remember { WebRtcP2pClientManager.getInstance(context) }

    val isP2pConnected by p2pManager.isP2pConnected.collectAsStateWithLifecycle()
    val p2pConnectionStatus by p2pManager.p2pConnectionStatus.collectAsStateWithLifecycle()
    val remoteVideoTrack by p2pManager.remoteVideoTrack.collectAsStateWithLifecycle()

    val isNodeOnline = discoveredNode?.isOnline == true

    // Automatically ensure the global P2P tunnel is connected when entering the dashboard
    LaunchedEffect(discoveredNode?.userId, discoveredNode?.secretToken, discoveredNode?.isOnline) {
        if (discoveredNode != null && discoveredNode.isOnline) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: discoveredNode.userId
            p2pManager.connectToHomeNodeP2p(
                userId = uid,
                secretToken = discoveredNode.secretToken
            )
        }
    }

    // =========================================================================
    // TOP SECTION STATE: /screenshot Polling (1000ms interval over RTCDataChannel + Fallback)
    // =========================================================================
    var isHttpPollingActive by rememberSaveable { mutableStateOf(true) }
    var latestScreenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var httpFrameCounter by remember { mutableIntStateOf(0) }
    var lastFrameLatencyMs by remember { mutableLongStateOf(0L) }
    var httpPollingError by remember { mutableStateOf<String?>(null) }

    val screenshotHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    LaunchedEffect(discoveredNode?.secretToken, discoveredNode?.isOnline, isHttpPollingActive, isP2pConnected) {
        if (!isHttpPollingActive || discoveredNode == null || !discoveredNode.isOnline) {
            return@LaunchedEffect
        }
        while (isActive && isHttpPollingActive) {
            val startMs = System.currentTimeMillis()

            // 1. Primary path: Request `/screenshot` over Global WebRTC RTCDataChannel
            val p2pResult = if (p2pManager.isP2pConnected.value) {
                p2pManager.requestScreenshotBitmapP2p(quality = 72)
            } else {
                Result.failure(IllegalStateException("P2P negotiating"))
            }

            val finalResult = if (p2pResult.isSuccess) {
                p2pResult
            } else {
                // 2. Local loopback fallback when testing on same device or while ICE is connecting
                withContext(Dispatchers.IO) {
                    try {
                        val host = if (
                            discoveredNode.ipAddress.isBlank() ||
                            discoveredNode.ipAddress.equals("WebRTC-P2P-Global", ignoreCase = true)
                        ) {
                            "127.0.0.1"
                        } else {
                            discoveredNode.ipAddress
                        }
                        val url = "http://$host:${discoveredNode.port}/screenshot?t=$startMs"
                        val req = Request.Builder()
                            .url(url)
                            .header("x-auth-token", discoveredNode.secretToken)
                            .get()
                            .build()

                        screenshotHttpClient.newCall(req).execute().use { response ->
                            if (!response.isSuccessful) {
                                Result.failure(IllegalStateException("بانتظار اكتمال نفق WebRTC P2P..."))
                            } else {
                                val bytes = response.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bmp != null) {
                                        Result.success(bmp)
                                    } else {
                                        Result.failure(IllegalStateException("تعذر فك تشفير صورة JPEG"))
                                    }
                                } else {
                                    Result.failure(IllegalStateException("استجابة فارغة من /screenshot"))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        p2pManager.requestScreenshotBitmapP2p(quality = 72)
                    }
                }
            }

            finalResult.onSuccess { bmp ->
                latestScreenshotBitmap = bmp
                httpFrameCounter++
                lastFrameLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                httpPollingError = null
            }.onFailure { err ->
                httpPollingError = err.localizedMessage ?: "جاري فتح قناة البيانات RTCDataChannel..."
            }

            delay(1000L)
        }
    }

    // =========================================================================
    // BOTTOM SECTION STATE: Real-time WebRTC 30 FPS Streaming + Firebase Signaling
    // =========================================================================
    var isWebRtcActive by rememberSaveable { mutableStateOf(true) }
    var surfaceRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                surfaceRendererRef?.release()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(remoteVideoTrack, surfaceRendererRef, isWebRtcActive) {
        val track = remoteVideoTrack
        val renderer = surfaceRendererRef
        if (isWebRtcActive && track != null && renderer != null) {
            try {
                track.addSink(renderer)
            } catch (_: Exception) {
            }
        }
        onDispose {
            if (track != null && renderer != null) {
                try {
                    track.removeSink(renderer)
                } catch (_: Exception) {
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "بث شاشة الهاتف المزدوجة (Global P2P)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    isP2pConnected -> "متصل عالمياً عبر P2P • ${discoveredNode?.effectiveSignalingPath().orEmpty()}"
                                    isNodeOnline -> "متصل بـ ${discoveredNode?.deviceName} • جاري التفاوض السحابي..."
                                    else -> "هاتف المنزل غير متصل حالياً"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isNodeOnline) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mirroring_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة للوحة تحكم الآدمن"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // =====================================================================
                // 1. TOP SECTION: Screenshot Polling Mode (/screenshot @ 1000ms over RTCDataChannel)
                // =====================================================================
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "١. بث الصور المتكررة (P2P DataChannel /screenshot)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "أمر /screenshot • كل 1 ثانية • إطارات: $httpFrameCounter • ${lastFrameLatencyMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            FilledTonalButton(
                                onClick = { isHttpPollingActive = !isHttpPollingActive },
                                modifier = Modifier.testTag("toggle_http_polling_button")
                            ) {
                                Icon(
                                    imageVector = if (isHttpPollingActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isHttpPollingActive) "إيقاف مؤقت" else "استئناف")
                            }
                        }

                        HorizontalDivider()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF070F1E))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            val currentBitmap = latestScreenshotBitmap
                            when {
                                !isNodeOnline -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "هاتف المنزل غير متصل حالياً لاستقبال لقطات /screenshot",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                currentBitmap != null -> {
                                    Image(
                                        bitmap = currentBitmap.asImageBitmap(),
                                        contentDescription = "لقطة شاشة حية من هاتف المنزل عبر P2P DataChannel",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("http_polling_screenshot_image")
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.Black.copy(alpha = 0.65f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isHttpPollingActive) Color(0xFF10B981) else Color(0xFFF59E0B)
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isHttpPollingActive) "P2P JPEG 1FPS" else "PAUSED",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(30.dp),
                                            strokeWidth = 2.5.dp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = httpPollingError ?: "جاري جلب لقطات الشاشة عبر نفق P2P...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF94A3B8),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // =====================================================================
                // 2. BOTTOM SECTION: Real-Time WebRTC Video Streaming (30 FPS + users/{uid}/signaling)
                // =====================================================================
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "٢. البث الحي الفوري (WebRTC P2P 30 FPS Stream)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = p2pConnectionStatus,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (remoteVideoTrack != null) {
                                            Color(0xFF10B981)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (isWebRtcActive) {
                                        isWebRtcActive = false
                                    } else {
                                        isWebRtcActive = true
                                        val uid = FirebaseAuth.getInstance().currentUser?.uid
                                            ?: discoveredNode?.userId.orEmpty()
                                        p2pManager.connectToHomeNodeP2p(
                                            userId = uid,
                                            secretToken = discoveredNode?.secretToken.orEmpty(),
                                            forceReconnect = true
                                        )
                                    }
                                },
                                enabled = isNodeOnline,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isWebRtcActive) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("toggle_webrtc_stream_button")
                            ) {
                                Icon(
                                    imageVector = if (isWebRtcActive) Icons.Default.Stop else Icons.Default.CastConnected,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isWebRtcActive) "إيقاف WebRTC" else "بدء WebRTC")
                            }
                        }

                        HorizontalDivider()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF050B14))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    SurfaceViewRenderer(ctx).apply {
                                        init(p2pManager.eglBase.eglBaseContext, null)
                                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                        setEnableHardwareScaler(true)
                                        setMirror(false)
                                        surfaceRendererRef = this
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("webrtc_surface_view_renderer")
                            )

                            if (!isWebRtcActive || remoteVideoTrack == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF050B14).copy(alpha = 0.86f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(18.dp)
                                    ) {
                                        if (isWebRtcActive) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(32.dp),
                                                strokeWidth = 2.5.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.ScreenShare,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(40.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (isWebRtcActive) {
                                                p2pConnectionStatus
                                            } else {
                                                "اضغط على «بدء WebRTC» لإنشاء نفق P2P عبر users/{uid}/signaling وعرض البث الحي بسرعة 30 إطاراً/ثانية"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFCBD5E1),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.Black.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFEF4444))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "GLOBAL WEBRTC 30FPS",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
