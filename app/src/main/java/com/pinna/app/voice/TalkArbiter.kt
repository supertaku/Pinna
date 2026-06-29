package com.pinna.app.voice

/**
 * Half-duplex push-to-talk arbitration: at most one talker at a time. A talker holds the floor until
 * they end talking or go silent past [talkTimeoutMs] (so a talker who drops off the network can't block
 * the room forever). Pure and unit tested; the same logic backs both UI gating and host enforcement.
 */
class TalkArbiter(private val talkTimeoutMs: Long = 1_500) {
    var currentTalker: String? = null
        private set

    private var lastActivityMs: Long = 0

    fun canTalk(deviceId: String, nowMs: Long): Boolean {
        val current = currentTalker
        return current == null || current == deviceId || nowMs - lastActivityMs > talkTimeoutMs
    }

    fun requestTalk(deviceId: String, nowMs: Long): Boolean {
        if (!canTalk(deviceId, nowMs)) return false
        currentTalker = deviceId
        lastActivityMs = nowMs
        return true
    }

    fun noteActivity(deviceId: String, nowMs: Long) {
        if (currentTalker == deviceId) lastActivityMs = nowMs
    }

    fun endTalk(deviceId: String) {
        if (currentTalker == deviceId) currentTalker = null
    }

    fun isTalking(deviceId: String): Boolean = currentTalker == deviceId
}
