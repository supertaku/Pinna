package com.pinna.app.core.time

interface MonotonicClock {
    fun nowNanos(): Long
}

object ElapsedRealtimeClock : MonotonicClock {
    override fun nowNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}
