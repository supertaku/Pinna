package com.pinna.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkArbiterTest {
    @Test
    fun firstRequesterBecomesTalker() {
        val arbiter = TalkArbiter(talkTimeoutMs = 1_000)

        assertTrue(arbiter.requestTalk("a", nowMs = 0))
        assertEquals("a", arbiter.currentTalker)
        assertTrue(arbiter.isTalking("a"))
    }

    @Test
    fun secondTalkerDeniedWhileFirstActive() {
        val arbiter = TalkArbiter(talkTimeoutMs = 1_000)
        arbiter.requestTalk("a", nowMs = 0)

        assertFalse(arbiter.requestTalk("b", nowMs = 200))
        assertEquals("a", arbiter.currentTalker)
        assertFalse(arbiter.canTalk("b", nowMs = 200))
    }

    @Test
    fun talkerCanKeepTalking() {
        val arbiter = TalkArbiter(talkTimeoutMs = 1_000)
        arbiter.requestTalk("a", nowMs = 0)

        assertTrue(arbiter.requestTalk("a", nowMs = 500))
    }

    @Test
    fun staleTalkerIsPreemptedAfterTimeout() {
        val arbiter = TalkArbiter(talkTimeoutMs = 1_000)
        arbiter.requestTalk("a", nowMs = 0)

        // "a" went silent; after the timeout "b" may take over.
        assertTrue(arbiter.canTalk("b", nowMs = 1_500))
        assertTrue(arbiter.requestTalk("b", nowMs = 1_500))
        assertEquals("b", arbiter.currentTalker)
    }

    @Test
    fun activityKeepsTalkerActive() {
        val arbiter = TalkArbiter(talkTimeoutMs = 1_000)
        arbiter.requestTalk("a", nowMs = 0)
        arbiter.noteActivity("a", nowMs = 900)

        assertFalse(arbiter.canTalk("b", nowMs = 1_500))
    }

    @Test
    fun endTalkClearsOnlyCurrentTalker() {
        val arbiter = TalkArbiter(talkTimeoutMs = 1_000)
        arbiter.requestTalk("a", nowMs = 0)

        arbiter.endTalk("b")
        assertEquals("a", arbiter.currentTalker)

        arbiter.endTalk("a")
        assertNull(arbiter.currentTalker)
    }
}
