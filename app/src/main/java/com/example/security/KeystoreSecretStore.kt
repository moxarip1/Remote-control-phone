package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when the hardware/OS `AndroidKeyStore` fails or is unavailable.
 * Production code MUST fail closed (clear tokens and require re-pairing) and NEVER fall back
 * to an ephemeral or plaintext key.
 */
class KeystoreUnavailableException(
    message: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

/**
 * Strategy interface for supplying the AES-256-GCM master [SecretKey].
 * - Production always uses [AndroidKeystoreKeyProvider] (strictly backed by `AndroidKeyStore`, no fallback).
 * - JVM unit tests explicitly inject an in-memory test provider via [KeystoreSecretStore.setKeyProviderForTesting].
 */
fun interface KeyProvider {
    @Throws(KeystoreUnavailableException::class)
    fun getOrCreateSecretKey(): SecretKey
}

/**
 * Production [KeyProvider] backed exclusively by Android's hardware/TEE `AndroidKeyStore`.
 * Never falls back to an in-memory key; throws [KeystoreUnavailableException] on any failure.
 */
object AndroidKeystoreKeyProvider : KeyProvider {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "SmartHomeCloud_MasterSecretKey_v1"

    @Throws(KeystoreUnavailableException::class)
    override fun getOrCreateSecretKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existingEntry != null) {
                return existingEntry.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } catch (t: Throwable) {
            throw KeystoreUnavailableException(
                "فشل الوصول إلى Android Keystore الآمن (Fail-Closed): يتطلب الجهاز إعادة الإقران (${t.javaClass.simpleName})",
                t
            )
        }
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM secret storage written to [Context.getNoBackupFilesDir].
 *
 * Security invariants:
 * - Uses `AES/GCM/NoPadding` with a 128-bit authentication tag and 12-byte IV.
 * - Uses [AndroidKeystoreKeyProvider] in production with ZERO in-memory fallback.
 * - If [KeyProvider.getOrCreateSecretKey] throws [KeystoreUnavailableException], fails closed immediately,
 *   records the error in [keystoreErrorFlow], and requires re-pairing.
 */
object KeystoreSecretStore {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SECRET_FILE_NAME = "keystore_secrets_v1.enc"

    private val lock = Any()

    @Volatile
    private var keyProvider: KeyProvider = AndroidKeystoreKeyProvider

    private val _keystoreErrorFlow = MutableStateFlow<String?>(null)
    val keystoreErrorFlow: StateFlow<String?> = _keystoreErrorFlow.asStateFlow()

    private val _isFailedClosedFlow = MutableStateFlow(false)
    val isFailedClosedFlow: StateFlow<Boolean> = _isFailedClosedFlow.asStateFlow()

    /**
     * Injects a custom [KeyProvider] (used by JVM unit tests where `AndroidKeyStore` is not mounted).
     */
    fun setKeyProviderForTesting(provider: KeyProvider) {
        synchronized(lock) {
            keyProvider = provider
            _keystoreErrorFlow.value = null
            _isFailedClosedFlow.value = false
        }
    }

    /**
     * Restores the strict production [AndroidKeystoreKeyProvider].
     */
    fun resetKeyProviderToProduction() {
        synchronized(lock) {
            keyProvider = AndroidKeystoreKeyProvider
            _keystoreErrorFlow.value = null
            _isFailedClosedFlow.value = false
        }
    }

    private fun recordFailClosed(error: KeystoreUnavailableException): Nothing {
        _isFailedClosedFlow.value = true
        _keystoreErrorFlow.value = error.message
            ?: "فشل الوصول إلى Android Keystore (Fail-Closed): يرجى إعادة الإقران."
        throw error
    }

    private fun obtainSecretKeyOrFailClosed(): SecretKey {
        return try {
            val key = keyProvider.getOrCreateSecretKey()
            _isFailedClosedFlow.value = false
            _keystoreErrorFlow.value = null
            key
        } catch (e: KeystoreUnavailableException) {
            recordFailClosed(e)
        } catch (t: Throwable) {
            recordFailClosed(
                KeystoreUnavailableException(
                    "فشل الوصول إلى Android Keystore (Fail-Closed): يتطلب إعادة الإقران (${t.javaClass.simpleName})",
                    t
                )
            )
        }
    }

    private fun resolveEncryptedStoreFile(context: Context): File {
        val dir = context.applicationContext.noBackupFilesDir ?: context.applicationContext.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, SECRET_FILE_NAME)
    }

    @Throws(KeystoreUnavailableException::class)
    private fun encryptBytes(plaintext: ByteArray): ByteArray {
        val key = obtainSecretKeyOrFailClosed()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            val buffer = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            buffer.putInt(iv.size)
            buffer.put(iv)
            buffer.put(ciphertext)
            buffer.array()
        } catch (t: Throwable) {
            recordFailClosed(
                KeystoreUnavailableException(
                    "فشل تشفير بيانات الاعتماد عبر Android Keystore (Fail-Closed): يرجى إعادة الإقران",
                    t
                )
            )
        }
    }

    @Throws(KeystoreUnavailableException::class)
    private fun decryptBytes(context: Context, blob: ByteArray): ByteArray {
        val key = obtainSecretKeyOrFailClosed()
        try {
            if (blob.size < 16) {
                throw KeystoreUnavailableException("بيانات Keystore المشفرة تالفة؛ يرجى إعادة الإقران")
            }
            val buffer = ByteBuffer.wrap(blob)
            val ivLength = buffer.int
            if (ivLength !in 12..16 || blob.size < 4 + ivLength) {
                throw KeystoreUnavailableException("ترويسة IV غير صالحة في تخزين Keystore؛ يرجى إعادة الإقران")
            }
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (e: KeystoreUnavailableException) {
            clearCorruptedFile(context)
            recordFailClosed(e)
        } catch (t: Throwable) {
            clearCorruptedFile(context)
            recordFailClosed(
                KeystoreUnavailableException(
                    "تعذر فك تشفير مفاتيح الجلسة من Android Keystore (تم إبطال المفتاح أو تغييره) — تم الإغلاق الآمن (Fail-Closed)، يرجى إعادة الإقران",
                    t
                )
            )
        }
    }

    private fun clearCorruptedFile(context: Context) {
        try {
            val file = resolveEncryptedStoreFile(context)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }

    @Throws(KeystoreUnavailableException::class)
    private fun readSecretsJson(context: Context): JSONObject {
        synchronized(lock) {
            // Verify Keystore availability upfront even if the file does not exist yet
            obtainSecretKeyOrFailClosed()
            val file = resolveEncryptedStoreFile(context)
            if (!file.exists() || file.length() == 0L) {
                return JSONObject()
            }
            val rawBlob = try {
                file.readBytes()
            } catch (t: Throwable) {
                recordFailClosed(
                    KeystoreUnavailableException("تعذر قراءة ملف الأسرار المشفر؛ يرجى إعادة الإقران", t)
                )
            }
            val decrypted = decryptBytes(context, rawBlob)
            return try {
                JSONObject(String(decrypted, StandardCharsets.UTF_8))
            } catch (t: Throwable) {
                clearCorruptedFile(context)
                recordFailClosed(
                    KeystoreUnavailableException("محتوى ملف الأسرار المشفر غير صالح؛ يرجى إعادة الإقران", t)
                )
            }
        }
    }

    @Throws(KeystoreUnavailableException::class)
    private fun writeSecretsJson(context: Context, json: JSONObject) {
        synchronized(lock) {
            val file = resolveEncryptedStoreFile(context)
            val plaintext = json.toString().toByteArray(StandardCharsets.UTF_8)
            val encrypted = encryptBytes(plaintext)
            val tempFile = File(file.parentFile, "$SECRET_FILE_NAME.tmp")
            tempFile.writeBytes(encrypted)
            if (!tempFile.renameTo(file)) {
                file.writeBytes(encrypted)
                tempFile.delete()
            }
        }
    }

    @Throws(KeystoreUnavailableException::class)
    fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        val json = readSecretsJson(context)
        return if (json.has(key) && !json.isNull(key)) {
            json.optString(key, defaultValue ?: "")
        } else {
            defaultValue
        }
    }

    @Throws(KeystoreUnavailableException::class)
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        val json = readSecretsJson(context)
        return if (json.has(key)) json.optBoolean(key, defaultValue) else defaultValue
    }

    @Throws(KeystoreUnavailableException::class)
    fun putValues(context: Context, updates: Map<String, Any?>) {
        synchronized(lock) {
            val json = readSecretsJson(context)
            for ((k, v) in updates) {
                if (v == null) {
                    json.remove(k)
                } else {
                    json.put(k, v)
                }
            }
            writeSecretsJson(context, json)
        }
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            clearCorruptedFile(context)
        }
    }
}
