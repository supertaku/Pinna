package com.pinna.app.core.time

import kotlin.math.abs

data class ClockSyncSample(
    val t1ClientNanos: Long,
    val t2HostNanos: Long,
    val t3HostNanos: Long,
    val t4ClientNanos: Long,
) {
    fun toMeasurement(maxRoundTripNanos: Long): ClockSyncMeasurement? {
        if (t4ClientNanos < t1ClientNanos || t3HostNanos < t2HostNanos) return null
        val roundTrip = (t4ClientNanos - t1ClientNanos) - (t3HostNanos - t2HostNanos)
        if (roundTrip < 0 || roundTrip > maxRoundTripNanos) return null
        val offset = ((t2HostNanos - t1ClientNanos) + (t3HostNanos - t4ClientNanos)) / 2
        return ClockSyncMeasurement(offsetNanos = offset, roundTripNanos = roundTrip)
    }
}

data class ClockSyncMeasurement(
    val offsetNanos: Long,
    val roundTripNanos: Long,
)

class SyncClockModel(
    private val maxSamples: Int = 5,
    private val outlierThresholdNanos: Long = 200_000_000,
) {
    private val samples = mutableListOf<ClockSyncMeasurement>()

    var wasOutlierAccepted: Boolean = true
        private set

    val isReady: Boolean
        get() = samples.isNotEmpty()

    val estimatedOffsetNanos: Long
        get() = if (samples.isEmpty()) 0 else samples.map { it.offsetNanos }.sorted()[samples.size / 2]

    fun add(measurement: ClockSyncMeasurement) {
        if (samples.isNotEmpty() && abs(measurement.offsetNanos - estimatedOffsetNanos) > outlierThresholdNanos) {
            wasOutlierAccepted = false
            return
        }
        samples += measurement
        samples.sortBy { it.roundTripNanos }
        while (samples.size > maxSamples) {
            samples.removeAt(samples.lastIndex)
        }
    }

    fun clientTimeToHostTimeNanos(clientTimeNanos: Long): Long = clientTimeNanos + estimatedOffsetNanos
}
