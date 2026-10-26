package com.example.security

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Constant-time authentication and cryptographic token generation utilities.
 *
 * Security invariants:
 * 1. Rejects immediately if the expected token set is empty or blank.
 * 2. Rejects immediately if the provided token is null or blank.
 * 3. Uses [MessageDigest.isEqual] across all candidate tokens without early return
 *    to prevent timing side-channel attacks.
 * 4. Generates 32-byte (256-bit) cryptographically random tokens via [SecureRandom].
 */
object ConstantTimeAuth {

    private val secureRandom = SecureRandom()

    /**
     * Generates a cryptographically strong 32-byte (256-bit) random token encoded as 64 hex characters.
     */
    fun generateRandom32ByteTokenHex(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(64)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Extracts `x-auth-token` strictly from HTTP headers map (case-insensitive), never from query strings.
     */
    fun extractHeaderToken(headers: Map<String, String>?): String? {
        if (headers.isNullOrEmpty()) return null
        val value = headers.entries.firstOrNull {
            it.key.equals("x-auth-token", ignoreCase = true) ||
                it.key.equals("http_x_auth_token", ignoreCase = true)
        }?.value?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    /**
     * Extracts `x-auth-token` strictly from a DataChannel JSON `headers` object.
     */
    fun extractHeaderTokenFromJson(headersJson: JSONObject?): String? {
        if (headersJson == null) return null
        val keys = headersJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals("x-auth-token", ignoreCase = true)) {
                val value = headersJson.optString(key).trim()
                return if (value.isEmpty()) null else value
            }
        }
        return null
    }

    /**
     * Compares two strings in constant time using [MessageDigest.isEqual].
     */
    fun constantTimeEquals(provided: String, expected: String): Boolean {
        val providedBytes = provided.toByteArray(StandardCharsets.UTF_8)
        val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(providedBytes, expectedBytes)
    }

    /**
     * Verifies [providedToken] against [expectedTokens] in constant time.
     *
     * - Returns `false` if [expectedTokens] contains no non-blank tokens.
     * - Returns `false` if [providedToken] is null or blank.
     * - Evaluates every non-blank token in [expectedTokens] using [MessageDigest.isEqual]
     *   without short-circuiting.
     */
    fun verifyToken(providedToken: String?, expectedTokens: Set<String>): Boolean {
        val candidate = providedToken?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return false
        }

        val sanitizedExpected = expectedTokens
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sanitizedExpected.isEmpty()) {
            return false
        }

        val candidateBytes = candidate.toByteArray(StandardCharsets.UTF_8)
        var matched = 0
        for (expected in sanitizedExpected) {
            val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
            if (MessageDigest.isEqual(candidateBytes, expectedBytes)) {
                matched = matched or 1
            }
        }
        return matched == 1
    }
}
