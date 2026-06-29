package com.pinna.app.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockModelTest {
    @Test
    fun symmetricLatency_calculatesExpectedOffset() {
        val sample = ClockSyncSample(
            t1ClientNanos = 1_000,
            t2HostNanos = 1_100,
            t3HostNanos = 1_120,
            t4ClientNanos = 1_040,
        )

        val result = sample.toMeasurement(maxRoundTripNanos = 1_000)

        assertEquals(90, result!!.offsetNanos)
        assertEquals(20, result.roundTripNanos)
    }

    @Test
    fun highRoundTripSample_isRejected() {
        val sample = ClockSyncSample(
            t1ClientNanos = 1_000,
            t2HostNanos = 1_020,
            t3HostNanos = 1_030,
            t4ClientNanos = 20_000,
        )

        assertEquals(null, sample.toMeasurement(maxRoundTripNanos = 500))
    }

    @Test
    fun modelPrefersLowestRoundTripSamplesAndSmoothsOutlier() {
        val model = SyncClockModel(maxSamples = 3, outlierThresholdNanos = 200)

        model.add(ClockSyncMeasurement(offsetNanos = 100, roundTripNanos = 50))
        model.add(ClockSyncMeasurement(offsetNanos = 110, roundTripNanos = 40))
        model.add(ClockSyncMeasurement(offsetNanos = 500, roundTripNanos = 10))
        model.add(ClockSyncMeasurement(offsetNanos = 90, roundTripNanos = 45))

        assertTrue(model.isReady)
        assertEquals(100, model.estimatedOffsetNanos)
        assertEquals(1_100, model.clientTimeToHostTimeNanos(1_000))
        assertFalse(model.wasOutlierAccepted)
    }
}
