package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppMode
import com.example.ui.AdminControllerScreen
import com.example.ui.AuthScreen
import com.example.ui.HomeNodeServerScreen
import com.example.ui.ModeSelectorScreen
import com.example.ui.SystemPermissionsState
import com.example.ui.theme.SmartHomeCloudTheme
import com.example.viewmodel.SmartCloudViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartHomeCloudTheme {
                SmartHomeCloudRootApp(activity = this)
            }
        }
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

    val discoveredNode by viewModel.discoveredHomeNode.collectAsStateWithLifecycle()
    val remoteFiles by viewModel.remoteFiles.collectAsStateWithLifecycle()
    val currentRemotePath by viewModel.currentRemotePath.collectAsStateWithLifecycle()
    val rootRemotePath by viewModel.rootRemotePath.collectAsStateWithLifecycle()
    val isLoadingFiles by viewModel.isLoadingFiles.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val activeStreamMedia by viewModel.activeStreamMedia.collectAsStateWithLifecycle()
    val pendingDeleteItem by viewModel.pendingDeleteItem.collectAsStateWithLifecycle()

    var permissionsState by remember {
        mutableStateOf(evaluateSystemPermissions(context))
    }

    // Refresh permissions state whenever Activity resumes from system settings screens
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsState = evaluateSystemPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsState = evaluateSystemPermissions(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionsState = evaluateSystemPermissions(context)
    }

    val settingsIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        permissionsState = evaluateSystemPermissions(context)
    }

    // Attempt silent auto sign-in on startup if not already signed in
    LaunchedEffect(Unit) {
        viewModel.tryAutoSignIn(context)
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
                    discoveredHomeNode = discoveredNode,
                    isLocalServerRunning = isServerRunning,
                    onRequestStoragePermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                settingsIntentLauncher.launch(intent)
                            } catch (_: Exception) {
                                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                settingsIntentLauncher.launch(fallback)
                            }
                        } else {
                            legacyStorageLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        }
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
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
                    onStartServer = { viewModel.startHomeServerService(context) },
                    onStopServer = { viewModel.stopHomeServerService(context) },
                    onClearLogs = { viewModel.clearServerLogs() },
                    onBackToModeSelector = { viewModel.selectMode(AppMode.NONE) }
                )
            }

            AppMode.ADMIN_CONTROLLER -> {
                AdminControllerScreen(
                    discoveredNode = discoveredNode,
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
                    onDismissBanners = { viewModel.clearBanners() }
                )
            }
        }
    }
}

private fun evaluateSystemPermissions(context: Context): SystemPermissionsState {
    val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

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
        hasStorageAccess = hasStorage,
        hasNotificationPermission = hasNotifications,
        isIgnoringBatteryOptimizations = ignoringBatteryOpt
    )
}
