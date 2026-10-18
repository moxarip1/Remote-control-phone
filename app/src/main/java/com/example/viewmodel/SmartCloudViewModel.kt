package com.example.viewmodel

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.R
import com.example.model.AppMode
import com.example.model.HomeNodeInfo
import com.example.model.RemoteFileItem
import com.example.model.ServerLog
import com.example.model.TransferProgress
import com.example.repository.SmartCloudRepository
import com.example.server.LocalFileServer
import com.example.service.HomeServerForegroundService
import com.example.webrtc.WebRtcP2pClientManager
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class SmartCloudViewModel(
    private val repository: SmartCloudRepository,
    private val p2pClientManager: WebRtcP2pClientManager? = null
) : ViewModel() {

    private val _currentUser = MutableStateFlow<FirebaseUser?>(repository.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _selectedMode = MutableStateFlow(AppMode.NONE)
    val selectedMode: StateFlow<AppMode> = _selectedMode.asStateFlow()

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _errorBannerMessage = MutableStateFlow<String?>(null)
    val errorBannerMessage: StateFlow<String?> = _errorBannerMessage.asStateFlow()

    // Home Node Server Flows (delegated from ForegroundService companion state)
    val isServerRunning: StateFlow<Boolean> = HomeServerForegroundService.isRunning
    val localServerNodeInfo: StateFlow<HomeNodeInfo?> = HomeServerForegroundService.currentNodeInfo
    val serverLogs: StateFlow<List<ServerLog>> = HomeServerForegroundService.serverLogs
    val isMediaProjectionEnabled: StateFlow<Boolean> = HomeServerForegroundService.isMediaProjectionEnabled
    val webRtcStateText: StateFlow<String> = HomeServerForegroundService.webRtcStateText

    // Global WebRTC P2P Admin Client Flows
    val isP2pConnected: StateFlow<Boolean> =
        p2pClientManager?.isP2pConnected ?: MutableStateFlow(false).asStateFlow()
    val p2pConnectionStatus: StateFlow<String> =
        p2pClientManager?.p2pConnectionStatus ?: MutableStateFlow("غير متصل").asStateFlow()

    // Admin Controller Discovery & Remote File Browser Flows
    private val _discoveredHomeNode = MutableStateFlow<HomeNodeInfo?>(null)
    val discoveredHomeNode: StateFlow<HomeNodeInfo?> = _discoveredHomeNode.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<RemoteFileItem>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFileItem>> = _remoteFiles.asStateFlow()

    private val _currentRemotePath = MutableStateFlow("")
    val currentRemotePath: StateFlow<String> = _currentRemotePath.asStateFlow()

    private val _parentRemotePath = MutableStateFlow("")
    val parentRemotePath: StateFlow<String> = _parentRemotePath.asStateFlow()

    private val _rootRemotePath = MutableStateFlow("")
    val rootRemotePath: StateFlow<String> = _rootRemotePath.asStateFlow()

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles.asStateFlow()

    private val _transferProgress = MutableStateFlow(TransferProgress(isActive = false))
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    private val _activeStreamMedia = MutableStateFlow<RemoteFileItem?>(null)
    val activeStreamMedia: StateFlow<RemoteFileItem?> = _activeStreamMedia.asStateFlow()

    private val _pendingDeleteItem = MutableStateFlow<RemoteFileItem?>(null)
    val pendingDeleteItem: StateFlow<RemoteFileItem?> = _pendingDeleteItem.asStateFlow()

    private var nodeListenerJob: Job? = null
    private var p2pStateWatcherJob: Job? = null
    private var lastAutoLoadedNodeKey: String? = null

    init {
        viewModelScope.launch {
            repository.observeAuthState()
                .catch { e -> _errorBannerMessage.value = e.localizedMessage }
                .collect { user ->
                    _currentUser.value = user
                    if (user != null) {
                        startObservingHomeNode(user.uid)
                    } else {
                        stopObservingHomeNode()
                        p2pClientManager?.disconnectAll()
                        _selectedMode.value = AppMode.NONE
                    }
                }
        }

        // Automatically reload directory over RTCDataChannel as soon as P2P tunnel transitions to OPEN
        if (p2pClientManager != null) {
            p2pStateWatcherJob = viewModelScope.launch {
                p2pClientManager.isP2pConnected.collect { connected ->
                    if (connected && _selectedMode.value == AppMode.ADMIN_CONTROLLER && _remoteFiles.value.isEmpty()) {
                        loadRemoteDirectory(_currentRemotePath.value)
                    }
                }
            }
        }
    }

    fun tryAutoSignIn(context: Context) {
        if (_currentUser.value != null) return
        viewModelScope.launch {
            _isAuthLoading.value = true
            repository.attemptAutoSignIn(context)
            _isAuthLoading.value = false
        }
    }

    fun signInWithEmail(email: String, password: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            _errorBannerMessage.value = "يرجى إدخال البريد الإلكتروني"
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _errorBannerMessage.value = "يرجى إدخال عنوان بريد إلكتروني صحيح"
            return
        }
        if (password.isBlank()) {
            _errorBannerMessage.value = "يرجى إدخال كلمة المرور"
            return
        }

        viewModelScope.launch {
            _isAuthLoading.value = true
            _errorBannerMessage.value = null
            _statusBannerMessage.value = null
            val result = repository.signInWithEmailAndPassword(trimmedEmail, password)
            _isAuthLoading.value = false
            result.onFailure { error ->
                _errorBannerMessage.value = mapFirebaseAuthErrorToArabic(error, isSignUp = false)
            }
        }
    }

    fun signUpWithEmail(email: String, password: String, confirmPassword: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            _errorBannerMessage.value = "يرجى إدخال البريد الإلكتروني لإنشاء الحساب"
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _errorBannerMessage.value = "يرجى إدخال عنوان بريد إلكتروني صحيح"
            return
        }
        if (password.length < 6) {
            _errorBannerMessage.value = "يجب أن تتكون كلمة المرور من 6 أحرف أو أرقام على الأقل"
            return
        }
        if (confirmPassword.isNotEmpty() && password != confirmPassword) {
            _errorBannerMessage.value = "كلمتا المرور غير متطابقتين، يرجى التأكد من كتابتهما بشكل متطابق"
            return
        }

        viewModelScope.launch {
            _isAuthLoading.value = true
            _errorBannerMessage.value = null
            _statusBannerMessage.value = null
            val result = repository.signUpWithEmailAndPassword(trimmedEmail, password)
            _isAuthLoading.value = false
            result.onFailure { error ->
                _errorBannerMessage.value = mapFirebaseAuthErrorToArabic(error, isSignUp = true)
            }
        }
    }

    fun resetPassword(email: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            _errorBannerMessage.value = "يرجى كتابة بريدك الإلكتروني أولاً في الحقل المخصص ثم الضغط على استعادة كلمة المرور"
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _errorBannerMessage.value = "يرجى إدخال عنوان بريد إلكتروني صحيح لإرسال رابط الاستعادة"
            return
        }

        viewModelScope.launch {
            _isAuthLoading.value = true
            _errorBannerMessage.value = null
            _statusBannerMessage.value = null
            val result = repository.sendPasswordResetEmail(trimmedEmail)
            _isAuthLoading.value = false
            result.onSuccess {
                _statusBannerMessage.value = "تم إرسال رابط إعادة تعيين كلمة المرور إلى $trimmedEmail"
            }.onFailure { error ->
                _errorBannerMessage.value = mapFirebaseAuthErrorToArabic(error, isSignUp = false)
            }
        }
    }

    private fun mapFirebaseAuthErrorToArabic(error: Throwable, isSignUp: Boolean): String {
        val msg = error.message.orEmpty()
        return when {
            msg.contains("ERROR_INVALID_EMAIL", ignoreCase = true) ||
                msg.contains("badly formatted", ignoreCase = true) ->
                "صيغة البريد الإلكتروني غير صحيحة."
            msg.contains("ERROR_WRONG_PASSWORD", ignoreCase = true) ||
                msg.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ||
                msg.contains("invalid credential", ignoreCase = true) ->
                "البريد الإلكتروني أو كلمة المرور غير صحيحة."
            msg.contains("ERROR_USER_NOT_FOUND", ignoreCase = true) ||
                msg.contains("no user record", ignoreCase = true) ->
                "لا يوجد حساب مسجل بهذا البريد الإلكتروني. يمكنك التبديل إلى تبويب «إنشاء حساب جديد»."
            msg.contains("ERROR_EMAIL_ALREADY_IN_USE", ignoreCase = true) ||
                msg.contains("already in use", ignoreCase = true) ->
                "هذا البريد الإلكتروني مسجل مسبقاً. يمكنك تسجيل الدخول مباشرة."
            msg.contains("ERROR_WEAK_PASSWORD", ignoreCase = true) ->
                "كلمة المرور ضعيفة جداً، يجب أن تتكون من 6 أحرف على الأقل."
            msg.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ||
                msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                "مصادقة البريد الإلكتروني غير مفعّلة بعد أو تتطلب التحقق من إعدادات مشروع Firebase."
            msg.contains("network error", ignoreCase = true) ||
                msg.contains("UNAVAILABLE", ignoreCase = true) ->
                "تعذر الاتصال بالشبكة، يرجى التحقق من اتصال الإنترنت والمحاولة مجدداً."
            else -> error.localizedMessage ?: if (isSignUp) {
                "تعذر إنشاء الحساب الجديد، يرجى المحاولة مرة أخرى"
            } else {
                "تعذر تسجيل الدخول، يرجى التحقق من البيانات والمحاولة مرة أخرى"
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            _errorBannerMessage.value = null
            val result = repository.signInWithGoogleInteractive(
                activity = activity,
                onCancelled = {
                    _isAuthLoading.value = false
                }
            )
            _isAuthLoading.value = false
            result.onFailure { error ->
                if (error !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                    _errorBannerMessage.value = error.localizedMessage ?: "تعذر تسجيل الدخول عبر حساب Google"
                }
            }
        }
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            if (isServerRunning.value) {
                stopHomeServerService(context)
            }
            p2pClientManager?.disconnectAll()
            stopObservingHomeNode()
            repository.signOut(context)
            _selectedMode.value = AppMode.NONE
        }
    }

    fun selectMode(mode: AppMode) {
        _selectedMode.value = mode
        _errorBannerMessage.value = null
        _statusBannerMessage.value = null
        if (mode == AppMode.ADMIN_CONTROLLER || mode == AppMode.SCREEN_MIRRORING) {
            val node = _discoveredHomeNode.value
            val uid = _currentUser.value?.uid.orEmpty()
            if (node != null && node.isOnline) {
                p2pClientManager?.connectToHomeNodeP2p(
                    userId = uid.ifBlank { node.userId },
                    secretToken = node.secretToken
                )
                if (mode == AppMode.ADMIN_CONTROLLER) {
                    loadRemoteDirectory(dirPath = _currentRemotePath.value)
                }
            }
        }
    }

    fun clearBanners() {
        _errorBannerMessage.value = null
        _statusBannerMessage.value = null
    }

    // =========================================================================
    // HOME NODE SERVER CONTROLS (With Optional Notification Permission Support)
    // =========================================================================

    fun startHomeServerService(context: Context, port: Int = LocalFileServer.DEFAULT_PORT) {
        val intent = Intent(context, HomeServerForegroundService::class.java).apply {
            action = HomeServerForegroundService.ACTION_START_SERVER
            putExtra(HomeServerForegroundService.EXTRA_PORT, port)
        }

        val hasNotifPermission = try {
            val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasNotifPermission) {
                context.startForegroundService(intent)
            } else {
                // Start as background service silently when POST_NOTIFICATIONS is denied/skipped
                context.startService(intent)
            }
        } catch (_: Throwable) {
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }

        _statusBannerMessage.value = if (hasNotifPermission) {
            "تم بدء تشغيل الخادم المنزلي (WebRTC P2P)"
        } else {
            "تم بدء تشغيل الخادم المنزلي في الوضع الصامت (بدون إشعارات)"
        }
    }

    fun stopHomeServerService(context: Context) {
        val intent = Intent(context, HomeServerForegroundService::class.java).apply {
            action = HomeServerForegroundService.ACTION_STOP_SERVER
        }
        try {
            context.startService(intent)
        } catch (_: Throwable) {
        }
        _statusBannerMessage.value = "تم إيقاف الخادم المنزلي"
    }

    fun clearServerLogs() {
        HomeServerForegroundService.clearLogs()
    }

    fun enableMediaProjectionForWebRtc(context: Context, resultCode: Int, dataIntent: Intent) {
        val intent = Intent(context, HomeServerForegroundService::class.java).apply {
            action = HomeServerForegroundService.ACTION_ENABLE_MEDIA_PROJECTION
            putExtra(HomeServerForegroundService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(HomeServerForegroundService.EXTRA_PROJECTION_DATA_INTENT, dataIntent)
        }
        try {
            context.startService(intent)
        } catch (_: Throwable) {
        }
        _statusBannerMessage.value = "تم تفعيل صلاحية بث الشاشة الكاملة عبر WebRTC P2P (30 FPS)"
    }

    // =========================================================================
    // ADMIN CONTROLLER GLOBAL WEBRTC P2P DISCOVERY & REMOTE ACTIONS
    // =========================================================================

    private fun startObservingHomeNode(uid: String) {
        nodeListenerJob?.cancel()
        nodeListenerJob = viewModelScope.launch {
            repository.observeHomeNode(uid)
                .catch { error ->
                    _errorBannerMessage.value = "تعذر الاستماع لبيانات الخادم المنزلي: ${error.localizedMessage}"
                }
                .collect { nodeInfo ->
                    _discoveredHomeNode.value = nodeInfo
                    if (nodeInfo != null && nodeInfo.isOnline) {
                        p2pClientManager?.connectToHomeNodeP2p(
                            userId = uid,
                            secretToken = nodeInfo.secretToken
                        )
                        val endpointKey = "${nodeInfo.effectiveSignalingPath()}:${nodeInfo.secretToken}"
                        if (lastAutoLoadedNodeKey != endpointKey) {
                            lastAutoLoadedNodeKey = endpointKey
                            delay(350L)
                            loadRemoteDirectory(dirPath = _currentRemotePath.value)
                        }
                    }
                }
        }
    }

    private fun stopObservingHomeNode() {
        nodeListenerJob?.cancel()
        nodeListenerJob = null
        _discoveredHomeNode.value = null
        _remoteFiles.value = emptyList()
        _currentRemotePath.value = ""
        _parentRemotePath.value = ""
        _rootRemotePath.value = ""
        lastAutoLoadedNodeKey = null
    }

    fun refreshAdminConnection() {
        val node = _discoveredHomeNode.value ?: return
        val uid = _currentUser.value?.uid ?: node.userId
        p2pClientManager?.connectToHomeNodeP2p(
            userId = uid,
            secretToken = node.secretToken,
            forceReconnect = !isP2pConnected.value
        )
        viewModelScope.launch {
            repository.pingHomeNodeStatus(node, p2pClientManager).onSuccess { refreshedNode ->
                _discoveredHomeNode.value = refreshedNode
            }
            loadRemoteDirectory(_currentRemotePath.value)
        }
    }

    fun loadRemoteDirectory(dirPath: String = "") {
        val node = _discoveredHomeNode.value
        if (node == null || !node.isOnline) {
            _errorBannerMessage.value = "الخادم المنزلي غير متصل حالياً"
            return
        }

        viewModelScope.launch {
            _isLoadingFiles.value = true
            _errorBannerMessage.value = null
            val result = repository.listRemoteFiles(node, dirPath, p2pClientManager)
            _isLoadingFiles.value = false

            result.onSuccess { listing ->
                _currentRemotePath.value = listing.currentPath
                _parentRemotePath.value = listing.parentPath
                if (_rootRemotePath.value.isBlank() || dirPath.isBlank()) {
                    _rootRemotePath.value = listing.rootPath
                }
                _remoteFiles.value = listing.items
            }.onFailure { error ->
                _errorBannerMessage.value = "جاري إتمام ربط نفق WebRTC P2P السحابي (${node.effectiveSignalingPath()}): ${error.localizedMessage}"
            }
        }
    }

    fun canNavigateUpInRemoteBrowser(): Boolean {
        val current = _currentRemotePath.value
        val root = _rootRemotePath.value
        val parent = _parentRemotePath.value
        return current.isNotBlank() && parent.isNotBlank() && current != root
    }

    fun navigateUpInRemoteBrowser(): Boolean {
        if (!canNavigateUpInRemoteBrowser()) return false
        loadRemoteDirectory(_parentRemotePath.value)
        return true
    }

    fun onRemoteFileClicked(context: Context, item: RemoteFileItem) {
        if (item.isDirectory) {
            loadRemoteDirectory(item.path)
        } else if (item.isStreamableMedia()) {
            _activeStreamMedia.value = item
        } else {
            downloadFileFromHomeNode(context, item)
        }
    }

    fun openStreamPlayer(item: RemoteFileItem) {
        _activeStreamMedia.value = item
    }

    fun closeStreamPlayer() {
        _activeStreamMedia.value = null
    }

    fun getStreamUrlForItem(item: RemoteFileItem): String? {
        val node = _discoveredHomeNode.value ?: return null
        return repository.buildStreamUrl(node, item)
    }

    fun downloadFileFromHomeNode(context: Context, item: RemoteFileItem) {
        val node = _discoveredHomeNode.value ?: return
        viewModelScope.launch {
            _errorBannerMessage.value = null
            val result = repository.downloadRemoteFile(
                context = context,
                node = node,
                fileItem = item,
                onProgress = { progress -> _transferProgress.value = progress }
            )
            result.onSuccess { savedFile ->
                _statusBannerMessage.value = "تم حفظ الملف عبر P2P في التنزيلات: ${savedFile.name}"
            }.onFailure { error ->
                _errorBannerMessage.value = "فشل سحب الملف عبر P2P: ${error.localizedMessage}"
            }
        }
    }

    fun uploadFileToCurrentRemoteDir(context: Context, uri: Uri) {
        val node = _discoveredHomeNode.value ?: return
        val targetDir = _currentRemotePath.value
        viewModelScope.launch {
            _errorBannerMessage.value = null
            val result = repository.uploadFileToHomeNode(
                context = context,
                node = node,
                targetDirPath = targetDir,
                fileUri = uri,
                onProgress = { progress -> _transferProgress.value = progress }
            )
            result.onSuccess { uploadedFileName ->
                _statusBannerMessage.value = "تم رفع الملف عبر P2P بنجاح: $uploadedFileName"
                loadRemoteDirectory(targetDir)
            }.onFailure { error ->
                _errorBannerMessage.value = "فشل رفع الملف عبر P2P: ${error.localizedMessage}"
            }
        }
    }

    fun requestDeleteRemoteFile(item: RemoteFileItem) {
        _pendingDeleteItem.value = item
    }

    fun dismissDeleteConfirmation() {
        _pendingDeleteItem.value = null
    }

    fun confirmDeleteRemoteFile() {
        val item = _pendingDeleteItem.value ?: return
        val node = _discoveredHomeNode.value ?: return
        _pendingDeleteItem.value = null

        viewModelScope.launch {
            _errorBannerMessage.value = null
            val result = repository.deleteRemoteFile(node, item.path, p2pClientManager)
            result.onSuccess {
                _statusBannerMessage.value = "تم حذف ${item.name} من الخادم المنزلي عبر P2P"
                loadRemoteDirectory(_currentRemotePath.value)
            }.onFailure { error ->
                _errorBannerMessage.value = "تعذر حذف الملف: ${error.localizedMessage}"
            }
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val appContext = context.applicationContext
                val databaseId = appContext.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(databaseId)
                val repository = SmartCloudRepository(firestore)
                val p2pManager = WebRtcP2pClientManager.getInstance(appContext)
                return SmartCloudViewModel(repository, p2pManager) as T
            }
        }
    }
}
