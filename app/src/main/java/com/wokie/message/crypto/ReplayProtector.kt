package com.wokie.message.crypto

/**
 * ReplayProtector uses a sliding window sequence number algorithm 
 * (similar to IPsec/DTLS) to securely reject duplicate and out-of-order 
 * messages indefinitely while consuming strictly bounded memory.
 */
class ReplayProtector {
    private var currentSessionId: String = ""
    private var highestSequenceNumber: Long = -1L
    private val outOfOrderWindow = mutableSetOf<Long>()
    private val WINDOW_SIZE = 1024L

    @Synchronized
    fun startNewSession(sessionId: String) {
        currentSessionId = sessionId
        highestSequenceNumber = -1L
        outOfOrderWindow.clear()
    }

    @Synchronized
    fun isMessageValid(sessionId: String, sequenceNumber: Long): Boolean {
        if (currentSessionId.isEmpty()) return false
        if (sessionId != currentSessionId) return false
        if (sequenceNumber < 0) return false
        
        // If it's too old and falls completely out of our window
        if (sequenceNumber <= highestSequenceNumber - WINDOW_SIZE) {
            return false
        }

        // If it's a new high-water mark
        if (sequenceNumber > highestSequenceNumber) {
            highestSequenceNumber = sequenceNumber
            outOfOrderWindow.add(sequenceNumber)
            
            // Prune the set to keep it bounded
            outOfOrderWindow.removeIf { it <= highestSequenceNumber - WINDOW_SIZE }
            return true
        } else {
            // It falls within our valid sliding window. Check for duplicate.
            if (outOfOrderWindow.contains(sequenceNumber)) {
                return false
            }
            outOfOrderWindow.add(sequenceNumber)
            return true
        }
    }
}
