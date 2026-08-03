package com.pinna.app.network

import com.pinna.app.protocol.RoomControlMessage
import com.pinna.app.room.RoomState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class LocalRoomEndpoint(
    val host: String,
    val port: Int,
    val roomId: String,
)

interface LocalRoomServer {
    val endpoint: LocalRoomEndpoint?
    suspend fun start(roomState: RoomState, token: String, tracks: Map<String, String>): LocalRoomEndpoint
    suspend fun stop()
    suspend fun broadcast(message: RoomControlMessage)
}

interface LocalRoomClient {
    val controlMessages: Flow<RoomControlMessage>
    val controlStreamState: StateFlow<ControlStreamState>
    suspend fun connect(endpoint: LocalRoomEndpoint, token: String): Result<RoomState>
    suspend fun openControlStream(endpoint: LocalRoomEndpoint, token: String): Result<Unit>
    suspend fun send(message: RoomControlMessage): Result<Unit>
    suspend fun disconnect()
    suspend fun shutdown() = disconnect()
}
