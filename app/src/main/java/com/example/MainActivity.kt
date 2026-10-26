package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppMode
import com.example.service.HomeServerForegroundService
import com.example.ui.AdminControllerScreen
import com.example.ui.AuthScreen
import com.example.ui.HomeNodeServerScreen
import com.example.ui.ModeSelectorScreen
import com.example.ui.ScreenMirroringDashboard
import com.example.ui.SystemPermissionsState
import com.example.ui.theme.SmartHomeCloudTheme
import com.example.viewmodel.SmartCloudViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeServerForegroundService.registerActiveActivity(this)
        enableEdgeToEdge()
        setContent {
            SmartHomeCloudTheme {
                SmartHomeCloudRootApp(activity = this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        HomeServerForegroundService.registerActiveActivity(this)
    }

    override fun onDestroy() {
        HomeServerForegroundService.unregisterActiveActivity(this)
        super.onDestroy()
    }
}

@SuppressLint("BatteryLife")
@Composable
fun SmartHomeCloudRootApp(
    activity: ComponentActivity,
    viewModel: SmartCloudViewModel = viewModel(
        factory = SmartCloudViewModel.provideFactory(LocalContext.current)
    )
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val isAuthLoading by viewModel.isAuthLoading.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val errorBanner by viewModel.errorBannerMessage.collectAsStateWithLifecycle()

    val isServerRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val localServerNodeInfo by viewModel.localServerNodeInfo.collectAsStateWithLifecycle()
    val serverLogs by viewModel.serverLogs.collectAsStateWithLifecycle()
    val isMediaProjectionEnabled by viewModel.isMediaProjectionEnabled.collectAsStateWithLifecycle()
    val webRtcStateText by viewModel.webRtcStateText.collectAsStateWithLifecycle()

    val sharedRoots by viewModel.sharedRoots.collectAsStateWithLifecycle()
    val isLanDebugHttpEnabled by viewModel.isLanDebugHttpEnabled.collectAsStateWithLifecycle()

    val isP2pConnected by viewModel.isP2pConnected.collectAsStateWithLifecycle()
    val p2pConnectionStatus by viewModel.p2pConnectionStatus.collectAsStateWithLifecycle()

    val discoveredNode by viewModel.discoveredHomeNode.collectAsStateWithLifecycle()
    val remoteFiles by viewModel.remoteFiles.collectAsStateWithLifecycle()
    val currentRemotePath by viewModel.currentRemotePath.collectAsStateWithLifecycle()
    val rootRemotePath by viewModel.rootRemotePath.collectAsStateWithLifecycle()
    val isLoadingFiles by viewModel.isLoadingFiles.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val activeStreamMedia by viewModel.activeStreamMedia.collectAsStateWithLifecycle()
    val pendingDeleteItem by viewModel.pendingDeleteItem.collectAsStateWithLifecycle()
    val savedAuthToken by viewModel.savedAuthToken.collectAsStateWithLifecycle()
    val isAuthTokenDialogOpen by viewModel.isAuthTokenDialogOpen.collectAsStateWithLifecycle()

    var isNotificationSkipped by rememberSaveable { mutableStateOf(false) }

    var permissionsState by remember(isNotificationSkipped, sharedRoots) {
        mutableStateOf(
            evaluateSystemPermissions(
                context = context,
                hasSafSharedRoots = sharedRoots.isNotEmpty(),
                isNotificationSkipped = isNotificationSkipped
            )
        )
    }

    // Refresh permissions & SAF persisted roots whenever Activity resumes
    DisposableEffect(lifecycleOwner, isNotificationSkipped, sharedRoots) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSharedRoots(context)
                permissionsState = evaluateSystemPermissions(
                    context = context,
                    hasSafSharedRoots = viewModel.sharedRoots.value.isNotEmpty(),
                    isNotificationSkipped = isNotificationSkipped
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE) folder picker launcher (Rule 0.3 & Phase 1.1)
    val safFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.addSharedRootUri(context, treeUri, allowUploads = false)
            permissionsState = evaluateSystemPermissions(
                context = context,
                hasSafSharedRoots = viewModel.sharedRoots.value.isNotEmpty(),
                isNotificationSkipped = isNotificationSkipped
            )
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            isNotificationSkipped = true
        }
        permissionsState = evaluateSystemPermissions(
            context = context,
            hasSafSharedRoots = sharedRoots.isNotEmpty(),
            isNotificationSkipped = isNotificationSkipped
        )
    }

    val settingsIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        permissionsState = evaluateSystemPermissions(
            context = context,
            hasSafSharedRoots = sharedRoots.isNotEmpty(),
            isNotificationSkipped = isNotificationSkipped
        )
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.enableMediaProjectionForWebRtc(
                context = context,
                resultCode = result.resultCode,
                dataIntent = result.data!!
            )
        }
    }

    // Attempt silent auto sign-in on startup if not already signed in
    LaunchedEffect(Unit) {
        viewModel.tryAutoSignIn(context)
    }

    // Automatically ensure the Home Node Server is running whenever signed in
    LaunchedEffect(currentUser?.uid, selectedMode, isServerRunning) {
        if (currentUser != null) {
            viewModel.ensureHomeServerAlwaysRunning(context)
        }
    }

    if (currentUser == null) {
        AuthScreen(
            isLoading = isAuthLoading,
            statusMessage = statusBanner,
            errorMessage = errorBanner,
            onEmailSignInClick = { email, password -> viewModel.signInWithEmail(email, password) },
            onEmailSignUpClick = { email, password, confirmPassword ->
                viewModel.signUpWithEmail(email, password, confirmPassword)
            },
            onResetPasswordClick = { email -> viewModel.resetPassword(email) },
            onGoogleSignInClick = { viewModel.signInWithGoogle(activity) },
            onDismissError = { viewModel.clearBanners() }
        )
    } else {
        when (selectedMode) {
            AppMode.NONE -> {
                ModeSelectorScreen(
                    userEmail = currentUser?.email ?: currentUser?.displayName ?: "حساب السحابة المنزلية",
                    permissionsState = permissionsState,
                    sharedRootsCount = sharedRoots.size,
                    discoveredHomeNode = discoveredNode,
                    isLocalServerRunning = isServerRunning,
                    onRequestStoragePermission = {
                        safFolderPickerLauncher.launch(null)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            isNotificationSkipped = true
                            permissionsState = evaluateSystemPermissions(
                                context = context,
                                hasSafSharedRoots = sharedRoots.isNotEmpty(),
                                isNotificationSkipped = isNotificationSkipped
                            )
                        }
                    },
                    onSkipNotificationPermission = {
                        isNotificationSkipped = true
                        permissionsState = evaluateSystemPermissions(
                            context = context,
                            hasSafSharedRoots = sharedRoots.isNotEmpty(),
                            isNotificationSkipped = true
                        )
                    },
                    onRequestBatteryOptimizationExemption = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            settingsIntentLauncher.launch(intent)
                        } catch (_: Exception) {
                        }
                    },
                    onSelectMode = { mode -> viewModel.selectMode(mode) },
                    onSignOut = { viewModel.signOut(context) }
                )
            }

            AppMode.HOME_NODE_SERVER -> {
                HomeNodeServerScreen(
                    isRunning = isServerRunning,
                    nodeInfo = localServerNodeInfo,
                    logs = serverLogs,
                    statusMessage = statusBanner,
                    isMediaProjectionEnabled = isMediaProjectionEnabled,
                    webRtcStateText = webRtcStateText,
                    authToken = savedAuthToken,
                    isAuthTokenDialogOpen = isAuthTokenDialogOpen,
                    sharedRoots = sharedRoots,
                    isLanDebugHttpEnabled = isLanDebugHttpEnabled,
                    onAddSharedRoot = { safFolderPickerLauncher.launch(null) },
                    onRemoveSharedRoot = { rootId -> viewModel.removeSharedRoot(context, rootId) },
                    onToggleRootAllowUploads = { rootId, allow ->
                        viewModel.setRootAllowUploads(context, rootId, allow)
                    },
                    onToggleLanDebugHttp = { enabled ->
                        viewModel.setLanDebugHttpEnabled(context, enabled)
                    },
                    onRotateAuthToken = { viewModel.generateAndSaveNew32ByteToken() },
                    onUpdateAuthToken = { newToken, closeDialog ->
                        viewModel.updateAuthToken(newToken, closeDialog)
                    },
                    onOpenAuthTokenDialog = { viewModel.openAuthTokenDialog() },
                    onCloseAuthTokenDialog = { viewModel.closeAuthTokenDialog() },
                    onStartServer = { viewModel.startHomeServerService(context, silent = true) },
                    onRequestMediaProjection = {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                        if (mpm != null) {
                            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onClearLogs = { viewModel.clearServerLogs() },
                    onBackToModeSelector = { viewModel.selectMode(AppMode.NONE) }
                )
            }

            AppMode.ADMIN_CONTROLLER -> {
                AdminControllerScreen(
                    discoveredNode = discoveredNode,
                    isP2pConnected = isP2pConnected,
                    p2pConnectionStatus = p2pConnectionStatus,
                    remoteFiles = remoteFiles,
                    currentPath = currentRemotePath,
                    rootPath = rootRemotePath,
                    isLoadingFiles = isLoadingFiles,
                    transferProgress = transferProgress,
                    activeStreamMedia = activeStreamMedia,
                    pendingDeleteItem = pendingDeleteItem,
                    statusMessage = statusBanner,
                    errorMessage = errorBanner,
                    canNavigateUp = viewModel.canNavigateUpInRemoteBrowser(),
                    authToken = savedAuthToken,
                    isAuthTokenDialogOpen = isAuthTokenDialogOpen,
                    onUpdateAuthToken = { newToken, closeDialog ->
                        viewModel.updateAuthToken(newToken, closeDialog)
                    },
                    onSyncAuthTokenFromCloud = { viewModel.syncAuthTokenFromCloudNode() },
                    onOpenAuthTokenDialog = { viewModel.openAuthTokenDialog() },
                    onCloseAuthTokenDialog = { viewModel.closeAuthTokenDialog() },
                    onNavigateUp = { viewModel.navigateUpInRemoteBrowser() },
                    onBackToModeSelector = { viewModel.selectMode(AppMode.NONE) },
                    onRefreshConnection = { viewModel.refreshAdminConnection() },
                    onNavigateToDirectory = { path -> viewModel.loadRemoteDirectory(path) },
                    onFileItemClick = { item -> viewModel.onRemoteFileClicked(context, item) },
                    onStreamFileClick = { item -> viewModel.openStreamPlayer(item) },
                    onDownloadFileClick = { item -> viewModel.downloadFileFromHomeNode(context, item) },
                    onDeleteFileClick = { item -> viewModel.requestDeleteRemoteFile(item) },
                    onConfirmDelete = { viewModel.confirmDeleteRemoteFile() },
                    onDismissDeleteDialog = { viewModel.dismissDeleteConfirmation() },
                    onUploadFileSelected = { uri -> viewModel.uploadFileToCurrentRemoteDir(context, uri) },
                    onCloseStreamPlayer = { viewModel.closeStreamPlayer() },
                    buildStreamUrl = { item -> viewModel.getStreamUrlForItem(item) },
                    onOpenScreenMirroring = { viewModel.selectMode(AppMode.SCREEN_MIRRORING) },
                    onDismissBanners = { viewModel.clearBanners() }
                )
            }

            AppMode.SCREEN_MIRRORING -> {
                ScreenMirroringDashboard(
                    discoveredNode = discoveredNode,
                    onBack = { viewModel.selectMode(AppMode.ADMIN_CONTROLLER) }
                )
            }
        }
    }
}

private fun evaluateSystemPermissions(
    context: Context,
    hasSafSharedRoots: Boolean,
    isNotificationSkipped: Boolean = false
): SystemPermissionsState {
    val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoringBatteryOpt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    return SystemPermissionsState(
        hasStorageAccess = hasSafSharedRoots,
        hasNotificationPermission = hasNotifications,
        isIgnoringBatteryOptimizations = ignoringBatteryOpt,
        isNotificationSkipped = isNotificationSkipped
    )
}
