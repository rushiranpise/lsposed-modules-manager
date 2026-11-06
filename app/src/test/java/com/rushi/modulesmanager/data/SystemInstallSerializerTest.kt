package com.rushi.modulesmanager.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInstallSerializerTest {

    @Test
    fun onlyTheHolderReleasesTheSlot() = runBlocking {
        val serializer = SystemInstallSerializer()
        serializer.awaitFreeAndMarkPending("com.example.a")
        serializer.markCompleted("com.example.b")
        assertTrue(serializer.isHeldBy("com.example.a"))

        serializer.markCompleted("com.example.a")
        assertFalse(serializer.isHeldBy("com.example.a"))
    }

    @Test
    fun theNextHolderWaitsForTheCurrentOne() = runBlocking {
        val serializer = SystemInstallSerializer()
        serializer.awaitFreeAndMarkPending("com.example.a")
        launch {
            serializer.awaitFreeAndMarkPending("com.example.b")
        }
        delay(50)
        assertTrue(serializer.isHeldBy("com.example.a"))

        serializer.markCompleted("com.example.a")
        withTimeout(2_000) {
            while (!serializer.isHeldBy("com.example.b")) delay(10)
        }
    }

    @Test
    fun aTimedOutHandOffDoesNotDeadlockTheNextOne() = runBlocking {
        val serializer = SystemInstallSerializer()
        serializer.awaitFreeAndMarkPending("com.example.a", timeoutMs = 20)
        withTimeout(2_000) {
            serializer.awaitFreeAndMarkPending("com.example.b", timeoutMs = 1_000)
        }
        assertTrue(serializer.isHeldBy("com.example.b"))
    }
}
