package com.wokie.message.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ReplayProtectorTests {

    @Test
    fun `accept first message`() {
        val protector = ReplayProtector()
        protector.startNewSession("SESSION_A")
        assertTrue(protector.isMessageValid("SESSION_A", 1L))
    }

    @Test
    fun `reject duplicate message ID`() {
        val protector = ReplayProtector()
        protector.startNewSession("SESSION_A")
        assertTrue(protector.isMessageValid("SESSION_A", 1L))
        assertFalse(protector.isMessageValid("SESSION_A", 1L)) // Replay rejected
    }

    @Test
    fun `reject old session ID`() {
        val protector = ReplayProtector()
        protector.startNewSession("SESSION_A")
        assertTrue(protector.isMessageValid("SESSION_A", 1L))
        
        protector.startNewSession("SESSION_B")
        assertFalse(protector.isMessageValid("SESSION_A", 2L)) // Old session rejected
    }

    @Test
    fun `reject message outside sliding window`() {
        val protector = ReplayProtector()
        protector.startNewSession("SESSION_A")
        assertTrue(protector.isMessageValid("SESSION_A", 2000L)) // Fast forward
        assertFalse(protector.isMessageValid("SESSION_A", 500L)) // Too old, out of window
    }

    @Test
    fun `accept out of order message within window`() {
        val protector = ReplayProtector()
        protector.startNewSession("SESSION_A")
        assertTrue(protector.isMessageValid("SESSION_A", 100L))
        assertTrue(protector.isMessageValid("SESSION_A", 99L)) // Slightly old, but valid
    }

    @Test
    fun `concurrency test - simultaneous sends of same sequence number`() {
        val protector = ReplayProtector()
        protector.startNewSession("SESSION_A")
        
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        var successCount = 0
        
        for (i in 0 until threadCount) {
            executor.submit {
                if (protector.isMessageValid("SESSION_A", 42L)) {
                    synchronized(this) { successCount++ }
                }
                latch.countDown()
            }
        }
        
        latch.await()
        assertEquals(1, successCount) // Only one thread should succeed
    }
}
