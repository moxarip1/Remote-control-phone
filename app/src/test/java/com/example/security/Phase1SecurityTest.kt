package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.HomeNodeInfo
import com.example.repository.AuthTokenManager
import com.example.server.FileCommandHandler
import com.example.storage.DirectorySharedRootBackend
import com.example.storage.SharedRootEntry
import com.example.storage.StorageAccessDeniedException
import com.example.storage.StorageGateway
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1SecurityTest {

    private lateinit var tempSandbox: File
    private lateinit var sharedDirPhotos: File
    private lateinit var sharedDirReadOnly: File
    private lateinit var outsideSecretDir: File
    private lateinit var outsideSecretFile: File
    private lateinit var storageGateway: StorageGateway

    @Before
    fun setUp() {
        tempSandbox = Files.createTempDirectory("phase1_sec_test_").toFile()
        sharedDirPhotos = File(tempSandbox, "shared_photos").apply { mkdirs() }
        sharedDirReadOnly = File(tempSandbox, "shared_readonly").apply { mkdirs() }
        outsideSecretDir = File(tempSandbox, "outside_private").apply { mkdirs() }

        File(sharedDirPhotos, "vacation.jpg").writeText("jpeg-image-bytes")
        val subFolder = File(sharedDirPhotos, "2026").apply { mkdirs() }
        File(subFolder, "notes.txt").writeText("family-notes-content")
        File(sharedDirReadOnly, "manual.pdf").writeText("readonly-pdf-content")
        outsideSecretFile = File(outsideSecretDir, "private_keys.pem").apply {
            writeText("TOP_SECRET_PRIVATE_KEY")
        }

        val backends = listOf(
            DirectorySharedRootBackend(
                rootId = "root_photos",
                displayName = "Photos",
                rootDirectory = sharedDirPhotos,
                allowUploads = true
            ),
            // Uses default allowUploads = false
            DirectorySharedRootBackend(
                rootId = "root_readonly",
                displayName = "ReadOnlyDocs",
                rootDirectory = sharedDirReadOnly
            )
        )
        storageGateway = StorageGateway { backends }
    }

    @After
    fun tearDown() {
        KeystoreSecretStore.resetKeyProviderToProduction()
        tempSandbox.deleteRecursively()
    }

    // =========================================================================
    // 1. Constant-Time Auth & Empty-Token Set Rejection Tests (Phase 1.4 & 1.9)
    // =========================================================================

    @Test
    fun verifyToken_rejectsWhenExpectedSetIsEmpty() {
        // Audit Finding (c) regression test: non-blank token MUST be rejected when expectedTokens is empty
        assertFalse(ConstantTimeAuth.verifyToken("any-non-blank-token", emptySet()))
        assertFalse(ConstantTimeAuth.verifyToken("any-non-blank-token", setOf("", "   ")))
    }

    @Test
    fun verifyToken_rejectsBlankOrNullProvidedToken() {
        val validToken = ConstantTimeAuth.generateRandom32ByteTokenHex()
        assertFalse(ConstantTimeAuth.verifyToken(null, setOf(validToken)))
        assertFalse(ConstantTimeAuth.verifyToken("", setOf(validToken)))
        assertFalse(ConstantTimeAuth.verifyToken("    ", setOf(validToken)))
    }

    @Test
    fun verifyToken_constantTimeCompare_acceptsExactMatchAndRejectsNearMisses() {
        val token1 = ConstantTimeAuth.generateRandom32ByteTokenHex()
        val token2 = ConstantTimeAuth.generateRandom32ByteTokenHex()
        assertEquals(64, token1.length)
        assertEquals(64, token2.length)

        val expected = setOf(token1, token2)
        assertTrue(ConstantTimeAuth.verifyToken(token1, expected))
        assertTrue(ConstantTimeAuth.verifyToken(token2, expected))

        // Near-miss with single bit/char flipped of identical length
        val flippedLastChar = token1.dropLast(1) + if (token1.last() == 'a') 'b' else 'a'
        assertFalse(ConstantTimeAuth.verifyToken(flippedLastChar, expected))

        // Prefix / suffix attacks
        assertFalse(ConstantTimeAuth.verifyToken(token1.dropLast(2), expected))
        assertFalse(ConstantTimeAuth.verifyToken(token1 + "00", expected))
    }

    // =========================================================================
    // 2. Path Traversal, Absolute Path & Symlink Escape Tests (Phase 1.1, 1.2, 1.9)
    // =========================================================================

    @Test
    fun storageGateway_rejectsAllTraversalAndAbsolutePathPayloadsWith403() {
        val maliciousPayloads = listOf(
            "..",
            "../outside_private/private_keys.pem",
            "root_photos/../outside_private/private_keys.pem",
            "root_photos/2026/../../outside_private/private_keys.pem",
            "root_photos/./vacation.jpg",
            "root_photos/%2e%2e/outside_private/private_keys.pem",
            "root_photos/%2E%2E%2foutside_private",
            "root_photos\\2026\\notes.txt",
            "root_photos/vacation.jpg\u0000.txt",
            "/storage/emulated/0/Download",
            "/sdcard/DCIM",
            "/etc/passwd",
            "/data/data/com.example/files/keystore_secrets_v1.enc",
            "file:///sdcard/Download/secret.txt",
            "C:\\Users\\Admin\\Desktop",
            outsideSecretFile.absolutePath
        )

        for (payload in maliciousPayloads) {
            try {
                storageGateway.stat(payload)
                fail("Expected StorageAccessDeniedException for stat('$payload')")
            } catch (expected: StorageAccessDeniedException) {
                // Expected 403
            }

            try {
                storageGateway.list(payload)
                fail("Expected StorageAccessDeniedException for list('$payload')")
            } catch (expected: StorageAccessDeniedException) {
                // Expected 403
            }

            try {
                storageGateway.openRead(payload)
                fail("Expected StorageAccessDeniedException for openRead('$payload')")
            } catch (expected: StorageAccessDeniedException) {
                // Expected 403
            }

            try {
                storageGateway.delete(payload)
                fail("Expected StorageAccessDeniedException for delete('$payload')")
            } catch (expected: StorageAccessDeniedException) {
                // Expected 403
            }
        }
    }

    @Test
    fun storageGateway_rejectsSymlinkEscapeOutsideSharedRoot() {
        val symlinkFile = File(sharedDirPhotos, "escaped_link.pem").toPath()
        val createdSymlink = try {
            Files.createSymbolicLink(symlinkFile, outsideSecretFile.toPath())
            true
        } catch (_: Throwable) {
            // In case the host filesystem does not permit symlink creation
            false
        }

        if (createdSymlink) {
            try {
                storageGateway.openRead("root_photos/escaped_link.pem")
                fail("Expected symlink escape to be rejected with StorageAccessDeniedException")
            } catch (expected: StorageAccessDeniedException) {
                // Expected 403
            }

            // Listing the directory should also skip symlinks
            val listing = storageGateway.list("root_photos")
            assertFalse(listing.entries.any { it.name == "escaped_link.pem" })
        }
    }

    // =========================================================================
    // 3. Shared-Root Enforcement & Default Read-Only (allowUploads = false)
    // =========================================================================

    @Test
    fun storageGateway_enforcesSharedRootBoundariesAndDefaultReadOnlyUploads() {
        // Verify SharedRootEntry defaults to allowUploads = false
        val defaultEntry = SharedRootEntry(
            rootId = "root_default",
            uriString = "content://com.android.externalstorage.documents/tree/primary%3ADocs",
            displayName = "Docs"
        )
        assertFalse("Newly added shared roots must default to allowUploads = false", defaultEntry.allowUploads)
        val parsedFromJsonWithoutFlag = SharedRootEntry.fromJson(
            JSONObject().put("rootId", "root_default").put("uriString", "content://test").put("displayName", "Docs")
        )
        assertNotNull(parsedFromJsonWithoutFlag)
        assertFalse(parsedFromJsonWithoutFlag!!.allowUploads)

        // 1. Listing virtual root ("") returns only the registered shared roots with opaque IDs
        val rootListing = storageGateway.list("")
        assertEquals("", rootListing.currentPath)
        assertEquals(2, rootListing.entries.size)
        assertTrue(rootListing.entries.any { it.opaquePath == "root_photos" && it.isDirectory })
        assertTrue(rootListing.entries.any { it.opaquePath == "root_readonly" && it.isDirectory })

        // 2. Accessing an unregistered rootId fails with 403
        try {
            storageGateway.list("root_unregistered/sub")
            fail("Expected unregistered rootId to fail with StorageAccessDeniedException")
        } catch (expected: StorageAccessDeniedException) {
            // Expected 403
        }

        // 3. Reading inside a valid shared root succeeds using opaque path
        val (_, readStream) = storageGateway.openRead("root_photos/2026/notes.txt")
        val content = readStream.bufferedReader().use { it.readText() }
        assertEquals("family-notes-content", content)

        // 4. Uploading to a default read-only shared root (allowUploads = false) fails with 403
        try {
            storageGateway.writeStream(
                rawDirPath = "root_readonly",
                rawFileName = "injected.txt",
                inputStream = ByteArrayInputStream("blocked".toByteArray())
            )
            fail("Expected upload to read-only root to fail with StorageAccessDeniedException")
        } catch (expected: StorageAccessDeniedException) {
            // Expected 403
        }

        // 5. Uploading to an upload-enabled shared root succeeds
        val uploadedItem = storageGateway.writeStream(
            rawDirPath = "root_photos/2026",
            rawFileName = "new_photo.jpg",
            inputStream = ByteArrayInputStream("new-photo-bytes".toByteArray())
        )
        assertEquals("root_photos/2026/new_photo.jpg", uploadedItem.opaquePath)
        val (_, verifyStream) = storageGateway.openRead("root_photos/2026/new_photo.jpg")
        val readBack = verifyStream.bufferedReader().use { it.readText() }
        assertEquals("new-photo-bytes", readBack)

        // 6. Deleting a top-level shared root itself is forbidden (403)
        try {
            storageGateway.delete("root_photos")
            fail("Expected deleting top-level shared root to fail with StorageAccessDeniedException")
        } catch (expected: StorageAccessDeniedException) {
            // Expected 403
        }
    }

    // =========================================================================
    // 4. KeystoreSecretStore Fail-Closed vs. Injected Test KeyProvider
    // =========================================================================

    @Test
    fun keystoreSecretStore_failsClosedWithoutEphemeralFallbackWhenKeystoreUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Production AndroidKeystoreKeyProvider has NO in-memory fallback and must throw KeystoreUnavailableException on JVM
        KeystoreSecretStore.resetKeyProviderToProduction()
        try {
            KeystoreSecretStore.putValues(context, mapOf("test_key" to "secret_val"))
            fail("Expected KeystoreUnavailableException when AndroidKeyStore is absent on JVM")
        } catch (expected: KeystoreUnavailableException) {
            assertTrue(KeystoreSecretStore.isFailedClosedFlow.value)
            assertNotNull(KeystoreSecretStore.keystoreErrorFlow.value)
        }

        // AuthTokenManager also fails closed (returns empty token "", never generates an ephemeral fallback token)
        val tokenWhenKeystoreFailed = AuthTokenManager.init(context)
        assertEquals("", tokenWhenKeystoreFailed)
        assertEquals("", AuthTokenManager.getSavedAuthToken(context))
        assertTrue(AuthTokenManager.isKeystoreFailedClosedFlow.value)

        // Now explicitly inject a test KeyProvider for JVM unit testing and verify AES-GCM round-trip works
        val testAesKey = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        KeystoreSecretStore.setKeyProviderForTesting { testAesKey }
        KeystoreSecretStore.clearAll(context)

        val generatedToken = AuthTokenManager.ensureServerPersistentToken(context)
        assertEquals(64, generatedToken.length)
        assertEquals(generatedToken, AuthTokenManager.getSavedAuthToken(context))
        assertFalse(KeystoreSecretStore.isFailedClosedFlow.value)
    }

    // =========================================================================
    // 5. Unified FileCommandHandler Header-Only Auth & Secret Exclusion (Phase 1.3, 1.4, 1.6)
    // =========================================================================

    @Test
    fun fileCommandHandler_enforcesHeaderOnlyTokenAndSharesSecurityAcrossTransports() {
        val secretToken = ConstantTimeAuth.generateRandom32ByteTokenHex()
        val loggedMessages = mutableListOf<String>()
        val handler = FileCommandHandler(
            storageGateway = storageGateway,
            validTokensProvider = { setOf(secretToken) },
            statusProvider = { Triple(85, 1000L, 2000L) },
            screenshotBitmapProvider = { null },
            onLog = { log -> loggedMessages.add(log.message) }
        )

        // Query string tokens are never extracted by extractHeaderToken
        assertNull(ConstantTimeAuth.extractHeaderToken(emptyMap()))
        assertEquals(secretToken, ConstantTimeAuth.extractHeaderToken(mapOf("X-Auth-Token" to secretToken)))

        // Request with missing/null header token MUST be rejected with 401
        val topLevelOnlyCmd = JSONObject().apply {
            put("reqId", "req1")
            put("action", "/files")
            put("path", "root_photos")
            put("token", secretToken)
            put("x-auth-token", secretToken)
        }
        val extractedFromMissingHeaders = ConstantTimeAuth.extractHeaderTokenFromJson(topLevelOnlyCmd.optJSONObject("headers"))
        assertNull(extractedFromMissingHeaders)
        assertFalse(handler.isAuthorizedHeaderToken(extractedFromMissingHeaders, "P2P", "/files", "RTCDataChannel"))
        val unauthorizedResult = handler.unauthorizedResult("req1")
        assertEquals(401, unauthorizedResult.statusCode)
        assertFalse(unauthorizedResult.body.getBoolean("success"))

        // Request with valid x-auth-token inside headers object succeeds
        val validHeaderCmd = JSONObject().apply {
            put("reqId", "req2")
            put("action", "/files")
            put("path", "root_photos")
            put("headers", JSONObject().put("x-auth-token", secretToken))
        }
        val extractedValidToken = ConstantTimeAuth.extractHeaderTokenFromJson(validHeaderCmd.optJSONObject("headers"))
        assertEquals(secretToken, extractedValidToken)
        assertTrue(handler.isAuthorizedHeaderToken(extractedValidToken, "P2P", "/files", "RTCDataChannel"))
        val listResult = handler.handleListFiles("root_photos", reqId = "req2")
        assertEquals(200, listResult.statusCode)
        assertTrue(listResult.body.getBoolean("success"))
        assertEquals("root_photos", listResult.body.getString("currentPath"))

        // Command with traversal path fails with 403
        val traversalResult = handler.handleListFiles("root_photos/../outside_private", reqId = "req3")
        assertEquals(403, traversalResult.statusCode)
        assertFalse(traversalResult.body.getBoolean("success"))

        // Verify logs never contain the secret token or full private filesystem path
        for (log in loggedMessages) {
            assertFalse("Log leaked secretToken: $log", log.contains(secretToken))
            assertFalse("Log leaked absolute path: $log", log.contains(tempSandbox.absolutePath))
        }
    }

    @Test
    fun homeNodeInfo_firestoreMapsNeverContainSecretToken() {
        val node = HomeNodeInfo(
            userId = "user_123",
            deviceName = "Pixel Host",
            secretToken = "super-secret-32-byte-token"
        )
        val createMap = node.toFirestoreCreateMap("user_123")
        val updateMap = node.toFirestoreUpdateMap("user_123")
        val rtdbMap = node.toRealtimeDbMap("user_123")

        assertFalse(createMap.containsKey("secretToken"))
        assertFalse(updateMap.containsKey("secretToken"))
        assertFalse(rtdbMap.containsKey("secretToken"))
    }
}
