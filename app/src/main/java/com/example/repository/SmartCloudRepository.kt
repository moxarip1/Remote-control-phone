package com.example.repository

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.model.HomeNodeInfo
import com.example.model.RemoteFileItem
import com.example.model.TransferProgress
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

class SmartCloudRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        FirebaseAuth.getInstance()
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before accessing Smart Home Cloud.")
    }

    fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /**
     * Silent auto sign-in via Jetpack Credential Manager using GetGoogleIdOption.
     */
    suspend fun attemptAutoSignIn(context: Context): Result<FirebaseUser> {
        auth.currentUser?.let { return Result.success(it) }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val user = checkNotNull(authResult.user) { "Authenticated user is null" }
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Unexpected credential type"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Interactive Google Sign-In via Jetpack Credential Manager using GetSignInWithGoogleOption.
     */
    suspend fun signInWithGoogleInteractive(
        activity: Activity,
        onCancelled: () -> Unit = {}
    ): Result<FirebaseUser> {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("default_web_client_id missing", e))
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val credentialManager = CredentialManager.create(activity)
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val user = checkNotNull(authResult.user) { "Authenticated user is null" }
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Unexpected credential type"))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
            onCancelled()
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sign in with Email and Password via Firebase Authentication.
     */
    suspend fun signInWithEmailAndPassword(email: String, password: String): Result<FirebaseUser> {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال البريد الإلكتروني وكلمة المرور"))
        }
        return try {
            val authResult = auth.signInWithEmailAndPassword(trimmedEmail, password).await()
            val user = checkNotNull(authResult.user) { "تعذر العثور على بيانات المستخدم بعد تسجيل الدخول" }
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Email/Password Sign-In failed", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new account with Email and Password via Firebase Authentication.
     */
    suspend fun signUpWithEmailAndPassword(email: String, password: String): Result<FirebaseUser> {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال البريد الإلكتروني وكلمة المرور"))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("يجب أن تتكون كلمة المرور من 6 أحرف على الأقل"))
        }
        return try {
            val authResult = auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
            val user = checkNotNull(authResult.user) { "تعذر إنشاء الحساب الجديد" }
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Email/Password Sign-Up failed", e)
            Result.failure(e)
        }
    }

    /**
     * Send a password reset email via Firebase Authentication.
     */
    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال البريد الإلكتروني أولاً لإرسال رابط استعادة كلمة المرور"))
        }
        return try {
            auth.sendPasswordResetEmail(trimmedEmail).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear CredentialManager state", e)
        }
    }

    /**
     * Publishes HomeNodeInfo to `/users/{uid}/home_node/main` in Firestore Enterprise
     * AND mirrors to Realtime Database `users/{uid}/home_node` with `onDisconnect()` hook if configured.
     */
    suspend fun publishHomeNodeInfo(
        nodeInfo: HomeNodeInfo,
        registerOnDisconnect: Boolean = true
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val docRef = firestore.collection("users").document(uid)
            .collection("home_node").document("main")

        return try {
            try {
                docRef.update(nodeInfo.toFirestoreUpdateMap()).await()
            } catch (notFound: FirebaseFirestoreException) {
                if (notFound.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                    docRef.set(nodeInfo.toFirestoreCreateMap(uid)).await()
                } else {
                    throw notFound
                }
            }

            // Mirror to Firebase Realtime Database with onDisconnect() when RTDB is available
            mirrorToRealtimeDatabaseSafely(uid, nodeInfo, registerOnDisconnect)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun markHomeNodeOffline(lastNodeInfo: HomeNodeInfo): Result<Unit> {
        return publishHomeNodeInfo(
            lastNodeInfo.copy(isOnline = false, lastUpdated = System.currentTimeMillis()),
            registerOnDisconnect = false
        )
    }

    suspend fun getHomeNodeInfo(userId: String = requireUserId()): Result<HomeNodeInfo?> {
        val docRef = firestore.collection("users").document(userId)
            .collection("home_node").document("main")
        return try {
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                Result.success(null)
            } else {
                Result.success(
                    snapshot.toObject(
                        HomeNodeInfo::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Real-time observation of `/users/{userId}/home_node/main` for Admin Controller auto-discovery.
     */
    fun observeHomeNode(userId: String = requireUserId()): Flow<HomeNodeInfo?> {
        val docRef = firestore.collection("users").document(userId)
            .collection("home_node").document("main")
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        HomeNodeInfo::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.GET, docRef.path)
                }
                throw error
            }
    }

    private fun mirrorToRealtimeDatabaseSafely(
        uid: String,
        nodeInfo: HomeNodeInfo,
        registerOnDisconnect: Boolean
    ) {
        try {
            val rtdbRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid)
                .child("home_node")
            if (registerOnDisconnect) {
                rtdbRef.onDisconnect().updateChildren(mapOf("isOnline" to false))
            }
            rtdbRef.setValue(nodeInfo.toRealtimeDbMap())
        } catch (_: Exception) {
            // Realtime Database URL may not be configured in google-services.json when Firestore Enterprise is primary
        }
    }

    // =========================================================================
    // ADMIN CONTROLLER GLOBAL WEBRTC P2P OPERATIONS (RTCDataChannel + Local Loopback Fallback)
    // =========================================================================

    private fun resolveLoopbackBaseUrl(node: HomeNodeInfo): String {
        val host = if (
            node.ipAddress.isBlank() ||
            node.ipAddress.equals("WebRTC-P2P-Global", ignoreCase = true)
        ) {
            "127.0.0.1"
        } else {
            node.ipAddress
        }
        return "http://$host:${node.port}"
    }

    private fun resolveEffectiveAuthToken(explicitToken: String, node: HomeNodeInfo): String {
        return explicitToken.trim()
            .ifBlank { AuthTokenManager.getSavedAuthToken() }
    }

    suspend fun pingHomeNodeStatus(
        node: HomeNodeInfo,
        p2pManager: com.example.webrtc.WebRtcP2pClientManager? = null,
        authToken: String = ""
    ): Result<HomeNodeInfo> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveAuthToken(authToken, node)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل إرسال الطلبات")
            )
        }

        if (p2pManager != null && (p2pManager.isP2pConnected.value || !AuthTokenManager.isLanDebugHttpEnabled())) {
            val p2pRes = p2pManager.requestNodeStatusP2p(node, resolvedToken)
            if (p2pRes.isSuccess || !AuthTokenManager.isLanDebugHttpEnabled()) {
                return@withContext p2pRes
            }
        }

        try {
            val url = "${resolveLoopbackBaseUrl(node)}/status"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("x-auth-token", resolvedToken)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (p2pManager != null) {
                        val p2pFallback = p2pManager.requestNodeStatusP2p(node, resolvedToken)
                        if (p2pFallback.isSuccess) return@withContext p2pFallback
                    }
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: $bodyStr")
                    )
                }
                val json = JSONObject(bodyStr)
                val updated = node.copy(
                    isOnline = true,
                    p2pState = "P2P_CONNECTED",
                    batteryLevel = json.optInt("batteryLevel", node.batteryLevel),
                    freeStorageBytes = json.optLong("freeStorageBytes", node.freeStorageBytes),
                    totalStorageBytes = json.optLong("totalStorageBytes", node.totalStorageBytes),
                    lastUpdated = json.optLong("timestamp", System.currentTimeMillis())
                )
                Result.success(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (p2pManager != null) {
                return@withContext p2pManager.requestNodeStatusP2p(node, resolvedToken)
            }
            Result.failure(e)
        }
    }

    data class RemoteDirectoryListing(
        val currentPath: String,
        val parentPath: String,
        val rootPath: String,
        val items: List<RemoteFileItem>
    )

    suspend fun listRemoteFiles(
        node: HomeNodeInfo,
        dirPath: String = "",
        p2pManager: com.example.webrtc.WebRtcP2pClientManager? = null,
        authToken: String = ""
    ): Result<RemoteDirectoryListing> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveAuthToken(authToken, node)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل استعراض المجلدات والملفات")
            )
        }

        // 1. Primary path: Encrypted WebRTC P2P RTCDataChannel (DTLS)
        if (p2pManager != null && (p2pManager.isP2pConnected.value || !AuthTokenManager.isLanDebugHttpEnabled())) {
            val p2pResult = p2pManager.listRemoteFilesP2p(dirPath, resolvedToken)
            if (p2pResult.isSuccess || !AuthTokenManager.isLanDebugHttpEnabled()) {
                return@withContext p2pResult
            }
        }

        // 2. Optional LAN HTTP Debug fast-path (only active when user explicitly enabled LAN HTTP Debug Mode)
        try {
            val encodedPath = URLEncoder.encode(dirPath, "UTF-8")
            val url = "${resolveLoopbackBaseUrl(node)}/files?path=$encodedPath"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("x-auth-token", resolvedToken)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (p2pManager != null) {
                        val p2pFallback = p2pManager.listRemoteFilesP2p(dirPath, resolvedToken)
                        if (p2pFallback.isSuccess) return@withContext p2pFallback
                    }
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: $bodyStr")
                    )
                }
                val json = JSONObject(bodyStr)
                val currentPath = json.optString("currentPath", dirPath)
                val parentPath = json.optString("parentPath", "")
                val rootPath = json.optString("rootPath", "")
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
                Result.success(
                    RemoteDirectoryListing(
                        currentPath = currentPath,
                        parentPath = parentPath,
                        rootPath = rootPath,
                        items = items
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (p2pManager != null) {
                return@withContext p2pManager.listRemoteFilesP2p(dirPath, resolvedToken)
            }
            Result.failure(e)
        }
    }

    suspend fun downloadRemoteFile(
        context: Context,
        node: HomeNodeInfo,
        fileItem: RemoteFileItem,
        authToken: String = "",
        onProgress: (TransferProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveAuthToken(authToken, node)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل تحميل الملفات")
            )
        }

        val p2pManager = com.example.webrtc.WebRtcP2pClientManager.getInstance(context)
        if (p2pManager.isP2pConnected.value || !AuthTokenManager.isLanDebugHttpEnabled(context)) {
            val p2pRes = p2pManager.downloadRemoteFileP2p(
                context = context,
                fileItem = fileItem,
                saveToCacheForStreaming = false,
                authToken = resolvedToken,
                onProgress = onProgress
            )
            if (p2pRes.isSuccess || !AuthTokenManager.isLanDebugHttpEnabled(context)) {
                return@withContext p2pRes
            }
        }

        try {
            val encodedPath = URLEncoder.encode(fileItem.path, "UTF-8")
            val url = "${resolveLoopbackBaseUrl(node)}/download?path=$encodedPath"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("x-auth-token", resolvedToken)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val p2pFallback = p2pManager.downloadRemoteFileP2p(
                        context = context,
                        fileItem = fileItem,
                        saveToCacheForStreaming = false,
                        authToken = resolvedToken,
                        onProgress = onProgress
                    )
                    if (p2pFallback.isSuccess) return@withContext p2pFallback
                    return@withContext Result.failure(
                        IllegalStateException("فشل التحميل (HTTP ${response.code})")
                    )
                }

                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("Empty response body"))

                val totalBytes = response.header("Content-Length")?.toLongOrNull()
                    ?: body.contentLength().takeIf { it > 0 }
                    ?: fileItem.sizeBytes

                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?.takeIf { it.exists() || it.mkdirs() }
                    ?: context.filesDir

                val safeName = fileItem.name.replace("/", "_").replace("\\", "_")
                val destFile = File(downloadsDir, safeName)
                var transferred = 0L
                val buffer = ByteArray(64 * 1024)

                onProgress(
                    TransferProgress(
                        isActive = true,
                        fileName = safeName,
                        isUpload = false,
                        bytesTransferred = 0L,
                        totalBytes = totalBytes,
                        statusMessage = "جاري سحب الملف من الخادم المنزلي..."
                    )
                )

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            transferred += read
                            onProgress(
                                TransferProgress(
                                    isActive = true,
                                    fileName = safeName,
                                    isUpload = false,
                                    bytesTransferred = transferred,
                                    totalBytes = totalBytes,
                                    statusMessage = "جاري التحميل: ${RemoteFileItem.formatBytes(transferred)} / ${RemoteFileItem.formatBytes(totalBytes)}"
                                )
                            )
                        }
                        output.flush()
                    }
                }

                onProgress(TransferProgress(isActive = false))
                Result.success(destFile)
            }
        } catch (e: CancellationException) {
            onProgress(TransferProgress(isActive = false))
            throw e
        } catch (e: Exception) {
            onProgress(TransferProgress(isActive = false))
            p2pManager.downloadRemoteFileP2p(
                context = context,
                fileItem = fileItem,
                saveToCacheForStreaming = false,
                authToken = resolvedToken,
                onProgress = onProgress
            )
        }
    }

    suspend fun uploadFileToHomeNode(
        context: Context,
        node: HomeNodeInfo,
        targetDirPath: String,
        fileUri: Uri,
        authToken: String = "",
        onProgress: (TransferProgress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveAuthToken(authToken, node)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل رفع الملفات")
            )
        }

        val p2pManager = com.example.webrtc.WebRtcP2pClientManager.getInstance(context)
        if (p2pManager.isP2pConnected.value || !AuthTokenManager.isLanDebugHttpEnabled(context)) {
            val p2pRes = p2pManager.uploadFileToHomeNodeP2p(
                context = context,
                targetDirPath = targetDirPath,
                fileUri = fileUri,
                authToken = resolvedToken,
                onProgress = onProgress
            )
            if (p2pRes.isSuccess || !AuthTokenManager.isLanDebugHttpEnabled(context)) {
                return@withContext p2pRes
            }
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

            val encodedDir = URLEncoder.encode(targetDirPath, "UTF-8")
            val encodedName = URLEncoder.encode(displayName, "UTF-8")
            val url = "${resolveLoopbackBaseUrl(node)}/upload?path=$encodedDir&filename=$encodedName"

            val streamingBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

                override fun contentLength(): Long = fileSize

                override fun writeTo(sink: BufferedSink) {
                    val inputStream = contentResolver.openInputStream(fileUri)
                        ?: throw IllegalStateException("تعذر فتح الملف المختار للرفع")
                    val buffer = ByteArray(64 * 1024)
                    var uploaded = 0L

                    inputStream.use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            uploaded += read
                            onProgress(
                                TransferProgress(
                                    isActive = true,
                                    fileName = displayName,
                                    isUpload = true,
                                    bytesTransferred = uploaded,
                                    totalBytes = fileSize.coerceAtLeast(uploaded),
                                    statusMessage = "جاري الرفع: ${RemoteFileItem.formatBytes(uploaded)}"
                                )
                            )
                        }
                    }
                }
            }

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("x-auth-token", resolvedToken)
                .post(streamingBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                onProgress(TransferProgress(isActive = false))
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val p2pFallback = p2pManager.uploadFileToHomeNodeP2p(
                        context = context,
                        targetDirPath = targetDirPath,
                        fileUri = fileUri,
                        authToken = resolvedToken,
                        onProgress = onProgress
                    )
                    if (p2pFallback.isSuccess) return@withContext p2pFallback
                    return@withContext Result.failure(
                        IllegalStateException("فشل رفع الملف (HTTP ${response.code}): $bodyStr")
                    )
                }
                Result.success(displayName)
            }
        } catch (e: CancellationException) {
            onProgress(TransferProgress(isActive = false))
            throw e
        } catch (e: Exception) {
            onProgress(TransferProgress(isActive = false))
            p2pManager.uploadFileToHomeNodeP2p(
                context = context,
                targetDirPath = targetDirPath,
                fileUri = fileUri,
                authToken = resolvedToken,
                onProgress = onProgress
            )
        }
    }

    suspend fun deleteRemoteFile(
        node: HomeNodeInfo,
        targetPath: String,
        p2pManager: com.example.webrtc.WebRtcP2pClientManager? = null,
        authToken: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val resolvedToken = resolveEffectiveAuthToken(authToken, node)
        if (resolvedToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("يرجى إدخال رمز المصادقة (x-auth-token) أولاً قبل حذف الملفات")
            )
        }

        if (p2pManager != null && (p2pManager.isP2pConnected.value || !AuthTokenManager.isLanDebugHttpEnabled())) {
            val p2pRes = p2pManager.deleteRemoteFileP2p(targetPath, resolvedToken)
            if (p2pRes.isSuccess || !AuthTokenManager.isLanDebugHttpEnabled()) {
                return@withContext p2pRes
            }
        }

        try {
            val encodedPath = URLEncoder.encode(targetPath, "UTF-8")
            val url = "${resolveLoopbackBaseUrl(node)}/delete?path=$encodedPath"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("x-auth-token", resolvedToken)
                .delete()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (p2pManager != null) {
                        val p2pFallback = p2pManager.deleteRemoteFileP2p(targetPath, resolvedToken)
                        if (p2pFallback.isSuccess) return@withContext p2pFallback
                    }
                    return@withContext Result.failure(
                        IllegalStateException("فشل الحذف (HTTP ${response.code}): $bodyStr")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (p2pManager != null) {
                return@withContext p2pManager.deleteRemoteFileP2p(targetPath, resolvedToken)
            }
            Result.failure(e)
        }
    }

    fun buildStreamUrl(
        node: HomeNodeInfo,
        fileItem: RemoteFileItem
    ): String {
        val encodedPath = URLEncoder.encode(fileItem.path, "UTF-8")
        return "${resolveLoopbackBaseUrl(node)}/stream?path=$encodedPath"
    }

    private companion object {
        const val TAG = "SmartCloudRepository"
    }
}
