package com.pinna.app.sync

enum class SyncQuality {
    EXCELLENT,
    GOOD,
    UNSTABLE,
    REBUFFERING,
}

/**
 * Maps the current round-trip sample quality and the most recent drift correction action into a
 * coarse, user-facing sync quality chip. The classifier is pure so it can be unit tested without
 * Android or networking dependencies.
 */
object SyncQualityClassifier {
    private const val EXCELLENT_RTT_NANOS = 30_000_000L
    private const val GOOD_RTT_NANOS = 80_000_000L

    fun classify(roundTripNanos: Long?, lastAction: DriftAction?, isReady: Boolean): SyncQuality {
        if (lastAction == DriftAction.REBUFFER) return SyncQuality.REBUFFERING
        if (!isReady || roundTripNanos == null) return SyncQuality.UNSTABLE
        return when {
            roundTripNanos <= EXCELLENT_RTT_NANOS -> SyncQuality.EXCELLENT
            roundTripNanos <= GOOD_RTT_NANOS -> SyncQuality.GOOD
            else -> SyncQuality.UNSTABLE
        }
    }
}
