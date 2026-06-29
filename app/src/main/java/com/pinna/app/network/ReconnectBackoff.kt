package com.pinna.app.network

/**
 * Exponential backoff (in milliseconds) for listener control-stream reconnect attempts, capped at a
 * ceiling. Pure so it can be unit tested; callers add random jitter on top.
 */
object ReconnectBackoff {
    fun delayMs(attempt: Int, baseMs: Long = 500, maxMs: Long = 8_000): Long {
        if (attempt <= 1) return baseMs.coerceAtMost(maxMs)
        val exponent = (attempt - 1).coerceIn(0, 20)
        val shifted = baseMs shl exponent
        return shifted.coerceIn(baseMs, maxMs)
    }
}
