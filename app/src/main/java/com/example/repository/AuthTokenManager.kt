package com.example.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Persistent local storage manager (localStorage equivalent via SharedPreferences)
 * for the `x-auth-token` authentication header used across all HTTP and WebRTC P2P
 * requests between the Admin Controller and the Home Node Server.
 */
object AuthTokenManager {
    private const val PREFS_NAME = "smart_home_cloud_auth_local_storage"
    private const val KEY_AUTH_TOKEN = "x_auth_token"
    private const val KEY_SERVER_DEFAULT_TOKEN = "server_persistent_token"
    private const val KEY_MANUAL_OVERRIDE = "is_manual_override"

    private val _authTokenFlow = MutableStateFlow("")
    val authTokenFlow: StateFlow<String> = _authTokenFlow.asStateFlow()

    @Volatile
    private var isInitialized = false

    fun init(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_AUTH_TOKEN, null)
        val isManual = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)

        val effective = when {
            saved != null -> saved.trim()
            !isManual -> {
                val serverDefault = ensureServerPersistentToken(context)
                prefs.edit().putString(KEY_AUTH_TOKEN, serverDefault).apply()
                serverDefault
            }
            else -> ""
        }
        _authTokenFlow.value = effective
        isInitialized = true
        return effective
    }

    /**
     * Reads the currently saved `x-auth-token` from localStorage (SharedPreferences).
     */
    fun getSavedAuthToken(context: Context? = null): String {
        if (context != null && !isInitialized) {
            return init(context)
        }
        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_AUTH_TOKEN, _authTokenFlow.value) ?: _authTokenFlow.value
            _authTokenFlow.value = stored.trim()
        }
        return _authTokenFlow.value.trim()
    }

    /**
     * Saves the `x-auth-token` automatically to localStorage (SharedPreferences)
     * and updates the reactive StateFlow.
     */
    fun saveAuthToken(context: Context?, token: String, isManualOverride: Boolean = true) {
        val trimmed = token.trim()
        _authTokenFlow.value = trimmed
        isInitialized = true
        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putString(KEY_AUTH_TOKEN, trimmed)
                .putBoolean(KEY_MANUAL_OVERRIDE, isManualOverride)
            if (trimmed.isNotBlank()) {
                editor.putString(KEY_SERVER_DEFAULT_TOKEN, trimmed)
            }
            editor.apply()
        }
    }

    /**
     * Ensures the Home Node Server uses a stable, persistent token across restarts
     * rather than generating a random UUID that invalidates active Admin sessions.
     */
    fun ensureServerPersistentToken(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentAuth = prefs.getString(KEY_AUTH_TOKEN, null)?.trim().orEmpty()
        if (currentAuth.isNotBlank()) {
            prefs.edit().putString(KEY_SERVER_DEFAULT_TOKEN, currentAuth).apply()
            _authTokenFlow.value = currentAuth
            isInitialized = true
            return currentAuth
        }

        val existingServerToken = prefs.getString(KEY_SERVER_DEFAULT_TOKEN, null)?.trim().orEmpty()
        if (existingServerToken.isNotBlank()) {
            val isManual = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)
            if (!isManual) {
                prefs.edit().putString(KEY_AUTH_TOKEN, existingServerToken).apply()
                _authTokenFlow.value = existingServerToken
            }
            isInitialized = true
            return existingServerToken
        }

        val generated = "shc-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        prefs.edit()
            .putString(KEY_SERVER_DEFAULT_TOKEN, generated)
            .putString(KEY_AUTH_TOKEN, generated)
            .putBoolean(KEY_MANUAL_OVERRIDE, false)
            .apply()
        _authTokenFlow.value = generated
        isInitialized = true
        return generated
    }

    /**
     * Synchronizes the localStorage token with the Home Node's published token in Firebase
     * unless the user explicitly cleared or overrode the token manually.
     */
    fun syncWithDiscoveredNodeToken(context: Context?, remoteNodeToken: String): String {
        val remoteTrimmed = remoteNodeToken.trim()
        if (remoteTrimmed.isBlank()) {
            return getSavedAuthToken(context)
        }
        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isManual = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)
            val currentSaved = prefs.getString(KEY_AUTH_TOKEN, "")?.trim().orEmpty()
            if (!isManual || (currentSaved.isNotBlank() && currentSaved != remoteTrimmed)) {
                if (!isManual) {
                    saveAuthToken(context, remoteTrimmed, isManualOverride = false)
                    return remoteTrimmed
                }
            }
            return currentSaved
        }
        if (_authTokenFlow.value.isBlank()) {
            _authTokenFlow.value = remoteTrimmed
        }
        return _authTokenFlow.value
    }

    /**
     * Builds the standard authentication headers map required for all requests:
     * headers: {
     *   'Content-Type': 'application/json',
     *   'x-auth-token': authToken
     * }
     */
    fun buildAuthHeaders(authToken: String): Map<String, String> {
        return mapOf(
            "Content-Type" to "application/json",
            "x-auth-token" to authToken.trim()
        )
    }
}
