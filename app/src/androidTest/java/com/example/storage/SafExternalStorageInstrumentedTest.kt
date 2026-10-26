package com.example.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.security.AndroidKeystoreKeyProvider
import com.example.security.KeystoreSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Instrumented test (`androidTest`) exercising [SafTreeSharedRootBackend] and [StorageGateway]
 * against a real `com.android.externalstorage.documents` (`ExternalStorageProvider`) tree URI
 * and real hardware-backed `AndroidKeyStore` on a physical Android device.
 *
 * How to run on a physical phone:
 * 1. Open the app on the phone and grant at least one SAF shared folder via `ACTION_OPEN_DOCUMENT_TREE`
 *    (or pass `-e treeUri content://com.android.externalstorage.documents/tree/...` to instrumentation).
 * 2. Run:
 *    `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.storage.SafExternalStorageInstrumentedTest`
 */
@RunWith(AndroidJUnit4::class)
class SafExternalStorageInstrumentedTest {

    private fun resolveGrantedExternalStorageTreeUri(context: Context): Uri? {
        val args = InstrumentationRegistry.getArguments()
        val argUri = args.getString("treeUri")?.trim()
        if (!argUri.isNullOrEmpty()) {
            return Uri.parse(argUri)
        }
        return context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission }
            ?.uri
    }

    @Test
    fun safTreeSharedRootBackend_nestedFolder_traversalAndDeleteBoundaries_againstExternalStorageProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseTreeUri = resolveGrantedExternalStorageTreeUri(context)
        assumeTrue(
            "Requires a persisted SAF tree URI from ExternalStorageProvider (grant a folder in the app UI or pass -e treeUri <uri>)",
            baseTreeUri != null
        )
        val treeUri = baseTreeUri!!
        assertEquals("com.android.externalstorage.documents", treeUri.authority)

        // Create an isolated test subtree inside the granted ExternalStorageProvider tree
        val baseDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val baseDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, baseDocId)
        val sandboxFolderName = "saf_test_${UUID.randomUUID().toString().take(8)}"
        val sandboxDirUri = DocumentsContract.createDocument(
            context.contentResolver,
            baseDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            sandboxFolderName
        )
        assertNotNull("Failed to create test sandbox folder in ExternalStorageProvider", sandboxDirUri)

        try {
            // Create a nested folder inside the sandbox
            val nestedDirUri = DocumentsContract.createDocument(
                context.contentResolver,
                sandboxDirUri!!,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "nested_2026"
            )
            assertNotNull("Failed to create nested folder", nestedDirUri)

            val rootId = "root_ext_test"
            val backend = SafTreeSharedRootBackend(
                context = context,
                rootId = rootId,
                displayName = "ExternalStorageTest",
                treeUri = treeUri,
                allowUploads = true
            )
            val gateway = StorageGateway { listOf(backend) }

            // 1. Write a file into the nested folder via StorageGateway -> SafTreeSharedRootBackend
            val nestedOpaqueDir = "$rootId/$sandboxFolderName/nested_2026"
            val writtenStat = gateway.writeStream(
                rawDirPath = nestedOpaqueDir,
                rawFileName = "hello.txt",
                inputStream = ByteArrayInputStream("real-saf-content".toByteArray())
            )
            assertEquals("$nestedOpaqueDir/hello.txt", writtenStat.opaquePath)

            // 2. Read the file back via StorageGateway.openRead
            val (readStat, readStream) = gateway.openRead("$nestedOpaqueDir/hello.txt")
            assertEquals("hello.txt", readStat.name)
            assertEquals("real-saf-content", readStream.bufferedReader().use { it.readText() })

            // 3. Verify path traversal attempts are rejected with StorageAccessDeniedException (403)
            val traversalPayloads = listOf(
                "$rootId/$sandboxFolderName/../../secret.txt",
                "$rootId/../outside",
                "/storage/emulated/0/Download",
                "$rootId/$sandboxFolderName/nested_2026/%2e%2e/hello.txt"
            )
            for (payload in traversalPayloads) {
                try {
                    gateway.openRead(payload)
                    fail("Expected StorageAccessDeniedException for traversal payload: $payload")
                } catch (_: StorageAccessDeniedException) {
                    // Expected 403
                }
            }

            // 4. Verify deleting top-level shared root is rejected with StorageAccessDeniedException (403)
            try {
                gateway.delete(rootId)
                fail("Expected deleting top-level shared root to throw StorageAccessDeniedException")
            } catch (_: StorageAccessDeniedException) {
                // Expected 403
            }

            // 5. Verify deleting the child file inside the nested folder succeeds
            assertTrue(gateway.delete("$nestedOpaqueDir/hello.txt"))
            val afterDeleteListing = gateway.list(nestedOpaqueDir)
            assertFalse(afterDeleteListing.entries.any { it.name == "hello.txt" })
        } finally {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, sandboxDirUri!!)
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun realAndroidKeystore_encryptsAndDecryptsWithoutFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        KeystoreSecretStore.resetKeyProviderToProduction()
        val secretKey = AndroidKeystoreKeyProvider.getOrCreateSecretKey()
        assertNotNull(secretKey)

        KeystoreSecretStore.putValues(context, mapOf("instr_test_token" to "token_abc_123"))
        assertEquals("token_abc_123", KeystoreSecretStore.getString(context, "instr_test_token"))
        assertFalse(KeystoreSecretStore.isFailedClosedFlow.value)
    }
}
