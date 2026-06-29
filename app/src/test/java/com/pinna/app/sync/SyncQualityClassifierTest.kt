package com.pinna.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncQualityClassifierTest {
    @Test
    fun rebufferingActionAlwaysReportsRebuffering() {
        val quality = SyncQualityClassifier.classify(
            roundTripNanos = 5_000_000,
            lastAction = DriftAction.REBUFFER,
            isReady = true,
        )

        assertEquals(SyncQuality.REBUFFERING, quality)
    }

    @Test
    fun lowRoundTripIsExcellent() {
        val quality = SyncQualityClassifier.classify(
            roundTripNanos = 20_000_000,
            lastAction = DriftAction.IGNORE,
            isReady = true,
        )

        assertEquals(SyncQuality.EXCELLENT, quality)
    }

    @Test
    fun moderateRoundTripIsGood() {
        val quality = SyncQualityClassifier.classify(
            roundTripNanos = 60_000_000,
            lastAction = DriftAction.NUDGE_SPEED,
            isReady = true,
        )

        assertEquals(SyncQuality.GOOD, quality)
    }

    @Test
    fun highRoundTripIsUnstable() {
        val quality = SyncQualityClassifier.classify(
            roundTripNanos = 150_000_000,
            lastAction = DriftAction.IGNORE,
            isReady = true,
        )

        assertEquals(SyncQuality.UNSTABLE, quality)
    }

    @Test
    fun missingSamplesAreUnstable() {
        val quality = SyncQualityClassifier.classify(
            roundTripNanos = null,
            lastAction = null,
            isReady = false,
        )

        assertEquals(SyncQuality.UNSTABLE, quality)
    }
}
