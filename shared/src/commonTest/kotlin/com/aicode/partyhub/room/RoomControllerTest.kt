package com.aicode.partyhub.room

import com.aicode.partyhub.core.*
import kotlin.test.*

class RoomControllerTest {
    private class Game : GameEngine {
        override val id = "gomoku"
        override val players = 2
        var actions = 0
        override fun apply(seat: Int, action: GameAction): Boolean { actions++; return true }
        override fun snapshot(viewer: Int) = GameSnapshot(id, players, hand = if (viewer >= 0) listOf(viewer) else emptyList())
    }
    @Test fun unauthenticatedAndSpectatorCannotPlay() {
        val game = Game()
        val room = RoomController("ABCD1234", "gomoku", 2)
        room.addMember("host", "房主", true)
        room.addMember("guest", "朋友")
        room.addMember("watch", "观战")
        room.setReady("guest", true)
        assertTrue(room.start("host", game))
        assertFalse(room.apply("unknown", 1, 0, GameAction("place", listOf(1))))
        assertFalse(room.apply("watch", 1, 0, GameAction("place", listOf(1))))
        assertTrue(room.apply("guest", 1, 0, GameAction("place", listOf(1))))
        assertEquals(1, game.actions)
    }
    @Test fun rejectsDuplicateAndOldMatchCommands() {
        val game = Game()
        val room = RoomController("ABCD1234", "gomoku", 2)
        room.addMember("h", "H", true)
        assertTrue(room.start("h", game))
        assertTrue(room.apply("h", 1, 0, GameAction("place", listOf(2))))
        assertFalse(room.apply("h", 1, 0, GameAction("place", listOf(3))))
        assertFalse(room.apply("h", 0, 1, GameAction("place", listOf(3))))
    }
    @Test fun snapshotsArePerViewerAndBotsFillEmptySeats() {
        val room = RoomController("ABCD1234", "gomoku", 2)
        room.addMember("h", "H", true)
        assertTrue(room.start("h", Game()))
        assertEquals(listOf(1), room.botSeats())
        assertEquals(listOf(0), room.snapshot("h").game!!.hand)
        assertNull(room.snapshot("intruder").game)
    }
    @Test fun cannotExceedThreeHumanMembers() {
        val room = RoomController("A", "snake", 3)
        assertTrue(room.addMember("h", "H", true))
        assertTrue(room.addMember("1", "One"))
        assertTrue(room.addMember("2", "Two"))
        assertFalse(room.addMember("3", "Four"))
    }
}
