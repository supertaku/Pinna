package com.pinna.app.connectivity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface LocalHotspotReservationHandle {
    fun close()
}

internal class LocalHotspotStartCoordinator(
    initialState: LocalHotspotState,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initialState)
    private var generation = 0L
    private var reservation: LocalHotspotReservationHandle? = null
    private var pending: PendingStart? = null

    val state: StateFlow<LocalHotspotState> = _state.asStateFlow()

    fun activeSession(): LocalHotspotSession? =
        (state.value as? LocalHotspotState.Active)?.session

    fun beginStart(): PendingStart {
        synchronized(lock) {
            generation += 1
            val next = PendingStart(generation, CompletableDeferred())
            pending = next
            _state.value = LocalHotspotState.Starting
            return next
        }
    }

    fun onStarted(
        token: Long,
        session: LocalHotspotSession?,
        handle: LocalHotspotReservationHandle,
    ) {
        synchronized(lock) {
            if (token != generation || pending?.token != token) {
                handle.close()
                return
            }
            if (session == null) {
                handle.close()
                pending?.deferred?.complete(Result.failure(IllegalStateException("Local hotspot credentials are unavailable.")))
                pending = null
                _state.value = LocalHotspotState.Failed("Local hotspot credentials are unavailable.")
                return
            }

            reservation = handle
            pending?.deferred?.complete(Result.success(session))
            pending = null
            _state.value = LocalHotspotState.Active(session)
        }
    }

    fun onStopped(token: Long) {
        synchronized(lock) {
            reservation = null
            if (token == generation && pending?.token == token) {
                pending?.deferred?.complete(Result.failure(IllegalStateException("Local hotspot stopped before it was ready.")))
                pending = null
            }
            _state.value = LocalHotspotState.Stopped
        }
    }

    fun onFailed(token: Long, message: String) {
        synchronized(lock) {
            if (token != generation || pending?.token != token) return
            reservation = null
            pending?.deferred?.complete(Result.failure(IllegalStateException(message)))
            pending = null
            _state.value = LocalHotspotState.Failed(message)
        }
    }

    fun stop() {
        synchronized(lock) {
            generation += 1
            _state.value = LocalHotspotState.Stopping
            pending?.deferred?.complete(Result.failure(IllegalStateException("Local hotspot start was cancelled.")))
            pending = null
            reservation?.close()
            reservation = null
            _state.value = LocalHotspotState.Stopped
        }
    }

    data class PendingStart(
        val token: Long,
        val deferred: CompletableDeferred<Result<LocalHotspotSession>>,
    )
}
