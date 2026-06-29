package com.pinna.app.network

sealed interface ControlStreamState {
    data object Disconnected : ControlStreamState
    data object Connecting : ControlStreamState
    data object Connected : ControlStreamState
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ControlStreamState
    data class Failed(val message: String) : ControlStreamState
}
