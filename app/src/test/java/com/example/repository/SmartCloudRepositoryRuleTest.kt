package com.example.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.model.HomeNodeInfo
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCloudRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun sampleHomeNode(): HomeNodeInfo = HomeNodeInfo(
        id = "main",
        deviceName = "Pixel 7 Pro Home Server",
        ipAddress = "192.168.1.50",
        port = 8080,
        isOnline = true,
        secretToken = "token-secret-uuid-999",
        batteryLevel = 92,
        freeStorageBytes = 42_000_000_000L,
        totalStorageBytes = 128_000_000_000L,
        lastUpdated = System.currentTimeMillis()
    )

    @Test
    fun publishAndGetHomeNode_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SmartCloudRepository(firestore, auth)
        val node = sampleHomeNode()

        val publishResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.publishHomeNodeInfo(node, registerOnDisconnect = false)
        }
        assertTrue("Expected publishHomeNodeInfo to succeed: ${publishResult.exceptionOrNull()}", publishResult.isSuccess)

        val fetchedResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getHomeNodeInfo(aliceUid)
        }
        assertTrue(fetchedResult.isSuccess)
        val fetched = fetchedResult.getOrNull()
        assertNotNull(fetched)
        assertEquals("192.168.1.50", fetched?.ipAddress)
        assertEquals(true, fetched?.isOnline)

        val offlineResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.markHomeNodeOffline(node)
        }
        assertTrue(offlineResult.isSuccess)
    }

    @Test
    fun observeHomeNode_authenticatedOwner_emitsRealtimeNodeInfo() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SmartCloudRepository(firestore, auth)
        val node = sampleHomeNode()

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.publishHomeNodeInfo(node, registerOnDisconnect = false).getOrThrow()
        }

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeHomeNode(aliceUid).first { it != null && it.ipAddress == "192.168.1.50" }
        }
        assertNotNull(observed)
        assertEquals("token-secret-uuid-999", observed?.secretToken)
    }

    @Test
    fun getHomeNode_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SmartCloudRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.publishHomeNodeInfo(sampleHomeNode(), registerOnDisconnect = false).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = SmartCloudRepository(firestore, auth)
        val crossReadResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getHomeNodeInfo(aliceUid)
        }
        assertTrue(crossReadResult.isFailure)
        val exception = crossReadResult.exceptionOrNull() as? FirebaseFirestoreException
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception?.code)
    }

    @Test
    fun observeHomeNode_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = SmartCloudRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.publishHomeNodeInfo(sampleHomeNode(), registerOnDisconnect = false).getOrThrow()
        }

        auth.signOut()

        var thrown: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeHomeNode(aliceUid).first()
            }
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        val fsEx = thrown as? FirebaseFirestoreException
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice@smarthomecloud.test"
        const val BOB_EMAIL = "bob@smarthomecloud.test"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
