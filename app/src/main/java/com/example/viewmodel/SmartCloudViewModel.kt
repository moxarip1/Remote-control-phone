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
import com.example.receiver.BootCompletedReceiver
import com.example.repository.AuthTokenManager
import com.example.repository.SmartCloudRepository
import com.example.security.ConstantTimeAuth
import com.example.server.LocalFileServer
import com.example.service.HomeServerForegroundService
import com.example.storage.SharedRootEntry
import com.example.storage.SharedRootsRepository
import com.example.webrtc.WebRtcP2pClientManager
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SmartCloudViewModel(
    private val repository: SmartCloudRepository,
    private val p2pClientManager: WebRtcP2pClientManager? = null,
    private val appContext: Context? = null
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

    // SAF Shared Roots & LAN Debug Mode Flows (Phase 1.1 & 1.5)
    val sharedRoots: StateFlow<List<SharedRootEntry>> = SharedRootsRepository.sharedRootsFlow
    val isLanDebugHttpEnabled: StateFlow<Boolean> = AuthTokenManager.isLanDebugHttpEnabledFlow

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

    // Keystore-backed Auth Token (x-auth-token)
    val savedAuthToken: StateFlow<String> = AuthTokenManager.authTokenFlow

    private val _isAuthTokenDialogOpen = MutableStateFlow(false)
    val isAuthTokenDialogOpen: StateFlow<Boolean> = _isAuthTokenDialogOpen.asStateFlow()

    private var nodeListenerJob: Job? = null
    private var p2pStateWatcherJob: Job? = null
    private var alwaysOnServerWatchdogJob: Job? = null
    private var lastAutoLoadedNodeKey: String? = null

    init {
        if (appContext != null) {
            SharedRootsRepository.validateAndLoadRoots(appContext)
            val initialToken = AuthTokenManager.init(appContext)
            if (initialToken.isNotBlank()) {
                p2pClientManager?.updateAuthToken(initialToken)
            }
        }

        // Observe Keystore fail-closed state and immediately surface clear error requiring re-pairing
        viewModelScope.launch {
            AuthTokenManager.keystoreErrorFlow.collect { keystoreErr ->
                if (!keystoreErr.isNullOrBlank()) {
                    p2pClientManager?.updateAuthToken("")
                    _errorBannerMessage.value = keystoreErr
                }
            }
        }

        viewModelScope.launch {
            repository.observeAuthState()
                .catch { e -> _errorBannerMessage.value = e.localizedMessage }
                .collect { user ->
                    _currentUser.value = user
                    if (user != null) {
                        startObservingHomeNode(user.uid)
                        appContext?.let { ensureHomeServerAlwaysRunning(it) }
                    } else {
                        stopObservingHomeNode()
                        p2pClientManager?.disconnectAll()
                        _selectedMode.value = AppMode.NONE
                    }
                }
        }

        // Always-On Server Watchdog: keeps HomeServerForegroundService running automatically and continuously
        if (appContext != null) {
            alwaysOnServerWatchdogJob = viewModelScope.launch {
                while (isActive) {
                    if (_currentUser.value != null && !isServerRunning.value) {
                        ensureHomeServerAlwaysRunning(appContext)
                    }
                    delay(4_000L)
                }
            }
        }

        // Automatically reload directory & status over RTCDataChannel as soon as P2P tunnel transitions to OPEN
        if (p2pClientManager != null) {
            p2pStateWatcherJob = viewModelScope.launch {
                p2pClientManager.isP2pConnected.collect { connected ->
                    if (connected) {
                        val token = AuthTokenManager.getSavedAuthToken(appContext)
                        val node = _discoveredHomeNode.value
                        if (node != null && token.isNotBlank()) {
                            repository.pingHomeNodeStatus(node, p2pClientManager, token).onSuccess { refreshed ->
                                _discoveredHomeNode.value = refreshed
                            }
                        }
                        if (_selectedMode.value == AppMode.ADMIN_CONTROLLER && token.isNotBlank()) {
                            loadRemoteDirectory(_currentRemotePath.value)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // SAF SHARED ROOTS & LAN DEBUG MODE (Phase 1.1 & 1.5)
    // =========================================================================

    fun refreshSharedRoots(context: Context) {
        SharedRootsRepository.validateAndLoadRoots(context)
    }

    fun addSharedRootUri(context: Context, treeUri: Uri, allowUploads: Boolean = false) {
        val entry = SharedRootsRepository.addSharedRootUri(context, treeUri, allowUploads)
        if (entry != null) {
            _errorBannerMessage.value = null
            _statusBannerMessage.value =
                "تمت إضافة المجلد المشترك عبر SAF بنجاح (قراءة فقط افتراضياً): ${entry.displayName} (${entry.rootId})"
        } else {
            _errorBannerMessage.value =
                "تعذر إضافة المجلد المشترك (SAF): تأكد من منح صلاحية الوصول للمجلد"
        }
    }

    fun removeSharedRoot(context: Context, rootId: String) {
        SharedRootsRepository.removeSharedRoot(context, rootId)
        _statusBannerMessage.value = "تم إلغاء مشاركة المجلد ($rootId) وإزالة صلاحية الوصول"
    }

    fun setRootAllowUploads(context: Context, rootId: String, allowUploads: Boolean) {
        SharedRootsRepository.setAllowUploads(context, rootId, allowUploads)
    }

    fun setLanDebugHttpEnabled(context: Context, enabled: Boolean) {
        AuthTokenManager.setLanDebugHttpEnabled(context, enabled)
        val appCtx = context.applicationContext
        try {
            val intent = Intent(appCtx, HomeServerForegroundService::class.java).apply {
                action = HomeServerForegroundService.ACTION_SET_LAN_DEBUG_MODE
                putExtra(HomeServerForegroundService.EXTRA_LAN_DEBUG_ENABLED, enabled)
            }
            appCtx.startService(intent)
        } catch (_: Throwable) {
        }
        _statusBannerMessage.value = if (enabled) {
            "تحذير أمني: تم تفعيل وضع تصحيح الشبكة المحلية (LAN HTTP Cleartext Debug Mode) برمز جلسة عشوائي مؤقت"
        } else {
            "تم إيقاف خادم LAN HTTP المحلي؛ جميع الاتصالات تتم حصرياً عبر WebRTC DataChannel المشفر (DTLS)"
        }
    }

    fun generateAndSaveNew32ByteToken() {
        val newToken = ConstantTimeAuth.generateRandom32ByteTokenHex()
        updateAuthToken(newToken, closeDialog = false)
    }

    fun openAuthTokenDialog() {
        _isAuthTokenDialogOpen.value = true
    }

    fun closeAuthTokenDialog() {
        _isAuthTokenDialogOpen.value = false
    }

    /**
     * Saves the Auth Token (`x-auth-token`) in Android Keystore-encrypted storage (`KeystoreSecretStore`),
     * updates the active WebRTC/HTTP headers, and syncs with the Home Node Server if running.
     */
    fun updateAuthToken(newToken: String, closeDialog: Boolean = false) {
        val trimmed = newToken.trim()
        AuthTokenManager.saveAuthToken(appContext, trimmed, isManualOverride = true)
        p2pClientManager?.updateAuthToken(trimmed)

        if (closeDialog) {
            _isAuthTokenDialogOpen.value = false
        }

        if (trimmed.isBlank()) {
            _errorBannerMessage.value =
                "تنبيه المصادقة: رمز المصادقة (x-auth-token) فارغ! لن يتم إرسال أي طلبات حتى يتم إدخال التوكن."
            return
        }

        // If Home Node Server is running on this device, update its expected x-auth-token immediately
        if (appContext != null && isServerRunning.value) {
            try {
                val intent = Intent(appContext.applicationContext, HomeServerForegroundService::class.java).apply {
                    action = HomeServerForegroundService.ACTION_UPDATE_AUTH_TOKEN
                    putExtra(HomeServerForegroundService.EXTRA_AUTH_TOKEN, trimmed)
                }
                appContext.applicationContext.startService(intent)
            } catch (_: Throwable) {
            }
        }

        _errorBannerMessage.value = null
        _statusBannerMessage.value = "تم تشفير وحفظ رمز المصادقة (x-auth-token) في Android Keystore وتفعيله لجميع الطلبات"

        val node = _discoveredHomeNode.value
        if (node != null && node.isOnline &&
            (_selectedMode.value == AppMode.ADMIN_CONTROLLER || _selectedMode.value == AppMode.SCREEN_MIRRORING)
        ) {
            val uid = _currentUser.value?.uid ?: node.userId
            p2pClientManager?.connectToHomeNodeP2p(
                userId = uid,
                secretToken = trimmed,
                forceReconnect = false
            )
            if (_selectedMode.value == AppMode.ADMIN_CONTROLLER) {
                loadRemoteDirectory(_currentRemotePath.value)
            }
        }
    }

    /**
     * Copies the local server token if running on the same device, or reminds the user that
     * `secretToken` is no longer published in Firestore (Phase 1.6 security hardening).
     */
    fun syncAuthTokenFromCloudNode() {
        val localServerToken = localServerNodeInfo.value?.secretToken?.trim()
            ?.ifBlank { appContext?.let { AuthTokenManager.ensureServerPersistentToken(it) }.orEmpty() }
            .orEmpty()
        if (localServerToken.isBlank()) {
            _errorBannerMessage.value =
                "حماية Phase 1.6: لم يعد يتم نشر secretToken في Firestore. يرجى نسخ رمز المصادقة (32-byte) من شاشة الخادم المنزلي."
            return
        }
        AuthTokenManager.saveAuthToken(appContext, localServerToken, isManualOverride = false)
        p2pClientManager?.updateAuthToken(localServerToken)
        _isAuthTokenDialogOpen.value = false
        _errorBannerMessage.value = null
        _statusBannerMessage.value = "تم استرداد رمز المصادقة المشفر من Keystore المحلي وتفعيله"
        if (_selectedMode.value == AppMode.ADMIN_CONTROLLER) {
            loadRemoteDirectory(_currentRemotePath.value)
        }
    }

    /**
     * Blocks any outgoing request if the Auth Token (`x-auth-token`) is empty,
     * showing a prominent alert asking the user to enter the token first.
     */
    private fun requireValidAuthTokenOrAlert(): String? {
        val currentToken = AuthTokenManager.getSavedAuthToken(appContext).trim()
        if (currentToken.isBlank()) {
            _errorBannerMessage.value =
                "تنبيه المصادقة: رمز المصادقة (x-auth-token) فارغ! يرجى إدخال رمز المصادقة أولاً لإتمام الطلب."
            _isAuthTokenDialogOpen.value = true
            return null
        }
        return currentToken
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
        val localDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

        when (mode) {
            AppMode.HOME_NODE_SERVER -> {
                // Persist role as Home Server and ensure Admin P2P client does not collide locally
                saveDeviceRole(BootCompletedReceiver.ROLE_HOME_SERVER)
                p2pClientManager?.disconnectAll()
                if (appContext != null) {
                    SharedRootsRepository.validateAndLoadRoots(appContext)
                    if (!isServerRunning.value) {
                        startHomeServerService(appContext, silent = true)
                    }
                }
            }
            AppMode.ADMIN_CONTROLLER, AppMode.SCREEN_MIRRORING -> {
                val node = _discoveredHomeNode.value
                val uid = _currentUser.value?.uid.orEmpty()
                val isAnotherRemoteDeviceOnline = node != null &&
                    node.isOnline &&
                    node.deviceName.isNotBlank() &&
                    !node.deviceName.equals(localDeviceName, ignoreCase = true)

                if (isAnotherRemoteDeviceOnline) {
                    saveDeviceRole(BootCompletedReceiver.ROLE_ADMIN_ONLY)
                    if (isServerRunning.value && appContext != null) {
                        stopHomeServerService(appContext)
                    }
                } else if (appContext != null && !isServerRunning.value) {
                    startHomeServerService(appContext, silent = true)
                }

                if (node != null && node.isOnline) {
                    val savedToken = AuthTokenManager.getSavedAuthToken(appContext)
                    if (savedToken.isNotBlank()) {
                        p2pClientManager?.connectToHomeNodeP2p(
                            userId = uid.ifBlank { node.userId },
                            secretToken = savedToken
                        )
                    }
                    if (mode == AppMode.ADMIN_CONTROLLER) {
                        loadRemoteDirectory(dirPath = _currentRemotePath.value)
                    }
                }
            }
            AppMode.NONE -> {
                if (appContext != null) {
                    SharedRootsRepository.validateAndLoadRoots(appContext)
                    ensureHomeServerAlwaysRunning(appContext)
                }
            }
        }
    }

    private fun saveDeviceRole(role: String) {
        try {
            appContext?.getSharedPreferences(BootCompletedReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(BootCompletedReceiver.KEY_DEVICE_ROLE, role)
                ?.apply()
        } catch (_: Throwable) {
        }
    }

    private fun readSavedDeviceRole(context: Context): String {
        return try {
            context.applicationContext
                .getSharedPreferences(BootCompletedReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(BootCompletedReceiver.KEY_DEVICE_ROLE, BootCompletedReceiver.ROLE_HOME_SERVER)
                ?: BootCompletedReceiver.ROLE_HOME_SERVER
        } catch (_: Throwable) {
            BootCompletedReceiver.ROLE_HOME_SERVER
        }
    }

    fun ensureHomeServerAlwaysRunning(context: Context) {
        if (_currentUser.value == null) return
        if (isServerRunning.value) return

        val mode = _selectedMode.value
        if (mode == AppMode.HOME_NODE_SERVER) {
            startHomeServerService(context, silent = true)
            return
        }

        val localDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val remoteNode = _discoveredHomeNode.value
        val isAnotherRemoteDeviceOnline = remoteNode != null &&
            remoteNode.isOnline &&
            remoteNode.deviceName.isNotBlank() &&
            !remoteNode.deviceName.equals(localDeviceName, ignoreCase = true) &&
            (System.currentTimeMillis() - remoteNode.lastUpdated) < 90_000L

        if (mode == AppMode.ADMIN_CONTROLLER || mode == AppMode.SCREEN_MIRRORING) {
            if (!isAnotherRemoteDeviceOnline) {
                startHomeServerService(context, silent = true)
            }
            return
        }

        val savedRole = readSavedDeviceRole(context)
        if (!isAnotherRemoteDeviceOnline || savedRole == BootCompletedReceiver.ROLE_HOME_SERVER) {
            startHomeServerService(context, silent = true)
        }
    }

    fun clearBanners() {
        _errorBannerMessage.value = null
        _statusBannerMessage.value = null
    }

    // =========================================================================
    // HOME NODE SERVER CONTROLS (Always-On Automatic Server + Optional Notifications)
    // =========================================================================

    fun startHomeServerService(
        context: Context,
        port: Int = LocalFileServer.DEFAULT_PORT,
        silent: Boolean = false
    ) {
        if (_selectedMode.value == AppMode.HOME_NODE_SERVER) {
            p2pClientManager?.disconnectAll()
        }

        val appCtx = context.applicationContext
        val intent = Intent(appCtx, HomeServerForegroundService::class.java).apply {
            action = HomeServerForegroundService.ACTION_START_SERVER
            putExtra(HomeServerForegroundService.EXTRA_PORT, port)
        }

        val hasNotifPermission = try {
            val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    appCtx,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            runtimeGranted && NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }

        try {
            appCtx.startService(intent)
        } catch (_: Throwable) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasNotifPermission) {
                    appCtx.startForegroundService(intent)
                }
            } catch (e: Throwable) {
                if (!silent) {
                    _errorBannerMessage.value = "تعذر تشغيل الخادم: ${e.localizedMessage}"
                }
                return
            }
        }

        if (!silent) {
            _statusBannerMessage.value = if (hasNotifPermission) {
                "الخادم المنزلي يعمل تلقائياً ودائماً (WebRTC P2P)"
            } else {
                "الخادم المنزلي يعمل تلقائياً ودائماً في الوضع الصامت (بدون إشعارات)"
            }
        }
    }

    fun stopHomeServerService(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, HomeServerForegroundService::class.java).apply {
            action = HomeServerForegroundService.ACTION_STOP_SERVER
        }
        try {
            appContext.startService(intent)
        } catch (_: Throwable) {
            try {
                appContext.stopService(Intent(appContext, HomeServerForegroundService::class.java))
            } catch (_: Throwable) {
            }
        }
        _statusBannerMessage.value = "تم إيقاف الخادم المنزلي"
    }

    fun clearServerLogs() {
        HomeServerForegroundService.clearLogs()
    }

    fun enableMediaProjectionForWebRtc(context: Context, resultCode: Int, dataIntent: Intent) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, HomeServerForegroundService::class.java).apply {
            action = HomeServerForegroundService.ACTION_ENABLE_MEDIA_PROJECTION
            putExtra(HomeServerForegroundService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(HomeServerForegroundService.EXTRA_PROJECTION_DATA_INTENT, dataIntent)
        }
        try {
            appContext.startService(intent)
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
                    val effectiveToken = AuthTokenManager.getSavedAuthToken(appContext)
                    if (effectiveToken.isNotBlank()) {
                        p2pClientManager?.updateAuthToken(effectiveToken)
                    }

                    val currentMode = _selectedMode.value
                    val isAdminMode = currentMode == AppMode.ADMIN_CONTROLLER || currentMode == AppMode.SCREEN_MIRRORING
                    if (nodeInfo != null && nodeInfo.isOnline && isAdminMode && effectiveToken.isNotBlank()) {
                        p2pClientManager?.connectToHomeNodeP2p(
                            userId = uid,
                            secretToken = effectiveToken
                        )
                        val endpointKey = "${nodeInfo.effectiveSignalingPath()}:$effectiveToken"
                        if (lastAutoLoadedNodeKey != endpointKey && currentMode == AppMode.ADMIN_CONTROLLER) {
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
        val token = requireValidAuthTokenOrAlert() ?: return
        val node = _discoveredHomeNode.value ?: return
        val uid = _currentUser.value?.uid ?: node.userId
        p2pClientManager?.connectToHomeNodeP2p(
            userId = uid,
            secretToken = token,
            forceReconnect = !isP2pConnected.value
        )
        viewModelScope.launch {
            repository.pingHomeNodeStatus(node, p2pClientManager, token).onSuccess { refreshedNode ->
                _discoveredHomeNode.value = refreshedNode
            }
            loadRemoteDirectory(_currentRemotePath.value)
        }
    }

    fun loadRemoteDirectory(dirPath: String = "") {
        val token = requireValidAuthTokenOrAlert() ?: return
        val node = _discoveredHomeNode.value
        if (node == null || !node.isOnline) {
            _errorBannerMessage.value = "الخادم المنزلي غير متصل حالياً"
            return
        }

        viewModelScope.launch {
            _isLoadingFiles.value = true
            _errorBannerMessage.value = null
            val result = repository.listRemoteFiles(
                node = node,
                dirPath = dirPath,
                p2pManager = p2pClientManager,
                authToken = token
            )
            _isLoadingFiles.value = false

            result.onSuccess { listing ->
                _currentRemotePath.value = listing.currentPath
                _parentRemotePath.value = listing.parentPath
                if (_rootRemotePath.value.isBlank() || dirPath.isBlank()) {
                    _rootRemotePath.value = listing.rootPath
                }
                _remoteFiles.value = listing.items
            }.onFailure { error ->
                _errorBannerMessage.value = "تعذر جلب المجلد (${node.effectiveSignalingPath()}): ${error.localizedMessage}"
            }
        }
    }

    fun canNavigateUpInRemoteBrowser(): Boolean {
        val current = _currentRemotePath.value
        val root = _rootRemotePath.value
        return current.isNotBlank() && current != root
    }

    fun navigateUpInRemoteBrowser(): Boolean {
        if (!canNavigateUpInRemoteBrowser()) return false
        loadRemoteDirectory(_parentRemotePath.value)
        return true
    }

    fun onRemoteFileClicked(context: Context, item: RemoteFileItem) {
        if (requireValidAuthTokenOrAlert() == null) return
        if (item.isDirectory) {
            loadRemoteDirectory(item.path)
        } else if (item.isStreamableMedia()) {
            _activeStreamMedia.value = item
        } else {
            downloadFileFromHomeNode(context, item)
        }
    }

    fun openStreamPlayer(item: RemoteFileItem) {
        if (requireValidAuthTokenOrAlert() == null) return
        _activeStreamMedia.value = item
    }

    fun closeStreamPlayer() {
        _activeStreamMedia.value = null
    }

    fun getStreamUrlForItem(item: RemoteFileItem): String? {
        requireValidAuthTokenOrAlert() ?: return null
        val node = _discoveredHomeNode.value ?: return null
        return repository.buildStreamUrl(node, item)
    }

    fun downloadFileFromHomeNode(context: Context, item: RemoteFileItem) {
        val token = requireValidAuthTokenOrAlert() ?: return
        val node = _discoveredHomeNode.value ?: return
        viewModelScope.launch {
            _errorBannerMessage.value = null
            val result = repository.downloadRemoteFile(
                context = context,
                node = node,
                fileItem = item,
                authToken = token,
                onProgress = { progress -> _transferProgress.value = progress }
            )
            result.onSuccess { savedFile ->
                _statusBannerMessage.value = "تم حفظ الملف عبر P2P في مجلد تنزيلات التطبيق: ${savedFile.name}"
            }.onFailure { error ->
                _errorBannerMessage.value = "فشل سحب الملف عبر P2P: ${error.localizedMessage}"
            }
        }
    }

    fun uploadFileToCurrentRemoteDir(context: Context, uri: Uri) {
        val token = requireValidAuthTokenOrAlert() ?: return
        val node = _discoveredHomeNode.value ?: return
        val targetDir = _currentRemotePath.value
        if (targetDir.isBlank()) {
            _errorBannerMessage.value = "يرجى فتح أحد المجلدات المشتركة (Shared Root) أولاً قبل رفع ملف إليه"
            return
        }
        viewModelScope.launch {
            _errorBannerMessage.value = null
            val result = repository.uploadFileToHomeNode(
                context = context,
                node = node,
                targetDirPath = targetDir,
                fileUri = uri,
                authToken = token,
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
        if (requireValidAuthTokenOrAlert() == null) return
        _pendingDeleteItem.value = item
    }

    fun dismissDeleteConfirmation() {
        _pendingDeleteItem.value = null
    }

    fun confirmDeleteRemoteFile() {
        val token = requireValidAuthTokenOrAlert() ?: return
        val item = _pendingDeleteItem.value ?: return
        val node = _discoveredHomeNode.value ?: return
        _pendingDeleteItem.value = null

        viewModelScope.launch {
            _errorBannerMessage.value = null
            val result = repository.deleteRemoteFile(
                node = node,
                targetPath = item.path,
                p2pManager = p2pClientManager,
                authToken = token
            )
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
                return SmartCloudViewModel(repository, p2pManager, appContext) as T
            }
        }
    }
}
