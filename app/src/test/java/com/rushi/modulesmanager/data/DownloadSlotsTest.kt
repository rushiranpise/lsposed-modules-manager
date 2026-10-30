package com.rushi.modulesmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DownloadSlotsTest {

    @Test
    fun `never runs more transfers than the limit`() = runBlocking {
        val slots = DownloadSlots { 2 }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val finished = Channel<Unit>(Channel.UNLIMITED)

        repeat(6) {
            launch(Dispatchers.Default) {
                slots.withSlot {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    delay(20)
                    active.decrementAndGet()
                }
                finished.send(Unit)
            }
        }
        repeat(6) { finished.receive() }

        assertTrue("peak was ${peak.get()}", peak.get() <= 2)
    }
}
