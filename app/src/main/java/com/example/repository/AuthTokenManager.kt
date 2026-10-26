package com.example.repository

import android.content.Context
import com.example.security.ConstantTimeAuth
import com.example.security.KeystoreSecretStore
import com.example.security.KeystoreUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hardware-backed Android Keystore (AES-256-GCM) manager for authentication tokens.
 *
 * Security hardening (Phase 1):
 * - Never stores tokens in plaintext SharedPreferences or Firestore.
 * - Generates 32-byte (256-bit) cryptographically random tokens via [ConstantTimeAuth.generateRandom32ByteTokenHex].
 * - Automatically wipes any legacy plaintext SharedPreferences file on startup.
 * - Fails closed (`""`) if [KeystoreSecretStore] throws [KeystoreUnavailableException], requiring re-pairing.
 */
object AuthTokenManager {
    private const val LEGACY_PLAIN_PREFS_NAME = "smart_home_cloud_auth_local_storage"
    private const val KEY_AUTH_TOKEN = "x_auth_token"
    private const val KEY_SERVER_DEFAULT_TOKEN = "server_persistent_token"
    private const val KEY_MANUAL_OVERRIDE = "is_manual_override"
    private const val KEY_LAN_DEBUG_ENABLED = "is_lan_debug_http_enabled"

    private val _authTokenFlow = MutableStateFlow("")
    val authTokenFlow: StateFlow<String> = _authTokenFlow.asStateFlow()

    private val _lanDebugSessionTokenFlow = MutableStateFlow("")
    val lanDebugSessionTokenFlow: StateFlow<String> = _lanDebugSessionTokenFlow.asStateFlow()

    private val _isLanDebugHttpEnabledFlow = MutableStateFlow(false)
    val isLanDebugHttpEnabledFlow: StateFlow<Boolean> = _isLanDebugHttpEnabledFlow.asStateFlow()

    val keystoreErrorFlow: StateFlow<String?> = KeystoreSecretStore.keystoreErrorFlow
    val isKeystoreFailedClosedFlow: StateFlow<Boolean> = KeystoreSecretStore.isFailedClosedFlow

    @Volatile
    private var isInitialized = false

    private fun wipeLegacyPlaintextSharedPreferences(context: Context) {
        try {
            val legacyPrefs = context.applicationContext.getSharedPreferences(LEGACY_PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isNotEmpty()) {
                legacyPrefs.edit().clear().commit()
            }
            context.applicationContext.deleteSharedPreferences(LEGACY_PLAIN_PREFS_NAME)
        } catch (_: Throwable) {
        }
    }

    private fun failClosedOnKeystoreError(): String {
        _authTokenFlow.value = ""
        _lanDebugSessionTokenFlow.value = ""
        _isLanDebugHttpEnabledFlow.value = false
        isInitialized = false
        return ""
    }

    fun init(context: Context): String {
        wipeLegacyPlaintextSharedPreferences(context)

        return try {
            val saved = KeystoreSecretStore.getString(context, KEY_AUTH_TOKEN, null)
            val isManual = KeystoreSecretStore.getBoolean(context, KEY_MANUAL_OVERRIDE, false)
            val lanDebugEnabled = KeystoreSecretStore.getBoolean(context, KEY_LAN_DEBUG_ENABLED, false)
            _isLanDebugHttpEnabledFlow.value = lanDebugEnabled

            val effective = when {
                !saved.isNullOrBlank() -> saved.trim()
                !isManual -> {
                    val serverDefault = ensureServerPersistentToken(context)
                    if (serverDefault.isNotBlank()) {
                        KeystoreSecretStore.putValues(context, mapOf(KEY_AUTH_TOKEN to serverDefault))
                    }
                    serverDefault
                }
                else -> ""
            }
            _authTokenFlow.value = effective
            isInitialized = true
            effective
        } catch (_: KeystoreUnavailableException) {
            failClosedOnKeystoreError()
        }
    }

    /**
     * Reads the currently saved `x-auth-token` from Android Keystore AES-GCM storage.
     * Fails closed (`""`) if Android Keystore is unavailable or corrupted.
     */
    fun getSavedAuthToken(context: Context? = null): String {
        if (KeystoreSecretStore.isFailedClosedFlow.value && context == null) {
            return ""
        }
        if (context != null && !isInitialized) {
            return init(context)
        }
        if (context != null) {
            return try {
                val stored = KeystoreSecretStore.getString(context, KEY_AUTH_TOKEN, _authTokenFlow.value)
                    ?: _authTokenFlow.value
                val trimmed = stored.trim()
                _authTokenFlow.value = trimmed
                trimmed
            } catch (_: KeystoreUnavailableException) {
                failClosedOnKeystoreError()
            }
        }
        return _authTokenFlow.value.trim()
    }

    /**
     * Saves the `x-auth-token` into Android Keystore AES-GCM encrypted storage.
     * If Keystore fails on device, fails closed (`_authTokenFlow.value = ""`) and never keeps an ephemeral key.
     */
    fun saveAuthToken(context: Context?, token: String, isManualOverride: Boolean = true) {
        val trimmed = token.trim()
        if (context != null) {
            wipeLegacyPlaintextSharedPreferences(context)
            val updates = mutableMapOf<String, Any?>(
                KEY_AUTH_TOKEN to trimmed,
                KEY_MANUAL_OVERRIDE to isManualOverride
            )
            if (trimmed.isNotBlank()) {
                updates[KEY_SERVER_DEFAULT_TOKEN] = trimmed
            }
            try {
                KeystoreSecretStore.putValues(context, updates)
                _authTokenFlow.value = trimmed
                isInitialized = true
            } catch (_: KeystoreUnavailableException) {
                failClosedOnKeystoreError()
            }
        } else {
            if (KeystoreSecretStore.isFailedClosedFlow.value) {
                failClosedOnKeystoreError()
            } else {
                _authTokenFlow.value = trimmed
                isInitialized = true
            }
        }
    }

    /**
     * Generates or loads the 32-byte (64-hex-char) persistent server token stored in Keystore-wrapped storage.
     * Fails closed (`""`) if AndroidKeyStore fails—never uses an unpersisted ephemeral token.
     */
    fun ensureServerPersistentToken(context: Context): String {
        wipeLegacyPlaintextSharedPreferences(context)

        return try {
            val currentAuth = KeystoreSecretStore.getString(context, KEY_AUTH_TOKEN, null)?.trim().orEmpty()
            if (currentAuth.length >= 32) {
                KeystoreSecretStore.putValues(context, mapOf(KEY_SERVER_DEFAULT_TOKEN to currentAuth))
                _authTokenFlow.value = currentAuth
                isInitialized = true
                return currentAuth
            }

            val existingServerToken = KeystoreSecretStore.getString(context, KEY_SERVER_DEFAULT_TOKEN, null)?.trim().orEmpty()
            if (existingServerToken.length >= 32) {
                val isManual = KeystoreSecretStore.getBoolean(context, KEY_MANUAL_OVERRIDE, false)
                if (!isManual) {
                    KeystoreSecretStore.putValues(context, mapOf(KEY_AUTH_TOKEN to existingServerToken))
                    _authTokenFlow.value = existingServerToken
                }
                isInitialized = true
                return existingServerToken
            }

            // Generate a fresh 32-byte (256-bit) cryptographically random hex token and persist in Keystore
            val generated = ConstantTimeAuth.generateRandom32ByteTokenHex()
            KeystoreSecretStore.putValues(
                context,
                mapOf(
                    KEY_SERVER_DEFAULT_TOKEN to generated,
                    KEY_AUTH_TOKEN to generated,
                    KEY_MANUAL_OVERRIDE to false
                )
            )
            _authTokenFlow.value = generated
            isInitialized = true
            generated
        } catch (_: KeystoreUnavailableException) {
            failClosedOnKeystoreError()
        }
    }

    /**
     * Rotates the per-session 32-byte random token used when LAN HTTP Debug Mode is explicitly enabled.
     */
    fun rotateLanDebugSessionToken(): String {
        if (KeystoreSecretStore.isFailedClosedFlow.value) {
            _lanDebugSessionTokenFlow.value = ""
            return ""
        }
        val token = ConstantTimeAuth.generateRandom32ByteTokenHex()
        _lanDebugSessionTokenFlow.value = token
        return token
    }

    fun isLanDebugHttpEnabled(context: Context? = null): Boolean {
        if (context != null) {
            return try {
                val enabled = KeystoreSecretStore.getBoolean(context, KEY_LAN_DEBUG_ENABLED, false)
                _isLanDebugHttpEnabledFlow.value = enabled
                if (enabled && _lanDebugSessionTokenFlow.value.isBlank()) {
                    rotateLanDebugSessionToken()
                }
                enabled
            } catch (_: KeystoreUnavailableException) {
                failClosedOnKeystoreError()
                false
            }
        }
        return _isLanDebugHttpEnabledFlow.value
    }

    fun setLanDebugHttpEnabled(context: Context?, enabled: Boolean) {
        if (context != null) {
            try {
                KeystoreSecretStore.putValues(context, mapOf(KEY_LAN_DEBUG_ENABLED to enabled))
            } catch (_: KeystoreUnavailableException) {
                failClosedOnKeystoreError()
                return
            }
        }
        _isLanDebugHttpEnabledFlow.value = enabled
        if (enabled) {
            rotateLanDebugSessionToken()
        } else {
            _lanDebugSessionTokenFlow.value = ""
        }
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
