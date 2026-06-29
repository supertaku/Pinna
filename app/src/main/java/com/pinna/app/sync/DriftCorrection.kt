package com.pinna.app.sync

import kotlin.math.abs

enum class DriftAction {
    IGNORE,
    NUDGE_SPEED,
    SEEK,
    REBUFFER,
}

object DriftCorrectionPolicy {
    fun classify(driftMs: Long): DriftAction {
        val absolute = abs(driftMs)
        return when {
            absolute < 20 -> DriftAction.IGNORE
            absolute < 80 -> DriftAction.NUDGE_SPEED
            absolute < 250 -> DriftAction.SEEK
            else -> DriftAction.REBUFFER
        }
    }
}
