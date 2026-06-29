package com.pinna.app.connectivity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHotspotStartCoordinatorTest {
    private val session = LocalHotspotSession(ssid = "Pinna", passphrase = "secret-pass")

    @Test
    fun lateStartedCallbackAfterStopClosesReservationAndDoesNotBecomeActive() = runBlocking {
        val coordinator = LocalHotspotStartCoordinator(LocalHotspotState.Stopped)
        val pending = coordinator.beginStart()
        val handle = FakeReservationHandle()

        coordinator.stop()
        coordinator.onStarted(pending.token, session, handle)

        assertTrue(handle.closed)
        assertEquals(LocalHotspotState.Stopped, coordinator.state.value)
        assertTrue(pending.deferred.await().isFailure)
    }

    @Test
    fun successfulStartPublishesActiveSession() = runBlocking {
        val coordinator = LocalHotspotStartCoordinator(LocalHotspotState.Stopped)
        val pending = coordinator.beginStart()
        val handle = FakeReservationHandle()

        coordinator.onStarted(pending.token, session, handle)

        assertEquals(LocalHotspotState.Active(session), coordinator.state.value)
        assertEquals(Result.success(session), pending.deferred.await())
    }

    private class FakeReservationHandle : LocalHotspotReservationHandle {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }
}
