package com.aicode.partyhub.room

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameSnapshot
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1

@Serializable
data class RoomMember(
    val id: String,
    val name: String,
    val seat: Int,
    val host: Boolean = false,
    val ready: Boolean = false,
    val connected: Boolean = true
)

@Serializable
data class RoomSnapshot(
    val roomId: String,
    val gameId: String,
    val capacity: Int,
    val members: List<RoomMember>,
    val phase: String,
    val matchId: Int,
    val yourSeat: Int = -1,
    val bots: List<Int> = emptyList(),
    val game: GameSnapshot? = null,
    val message: String = ""
)

@Serializable
data class RoomMessage(
    val type: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val roomId: String = "",
    val matchId: Int = 0,
    val sequence: Int = 0,
    val name: String = "",
    val gameId: String = "",
    val ready: Boolean = false,
    val action: GameAction? = null,
    val room: RoomSnapshot? = null,
    val message: String = ""
)
