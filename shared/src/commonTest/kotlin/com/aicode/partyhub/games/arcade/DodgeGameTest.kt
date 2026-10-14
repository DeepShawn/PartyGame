package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DodgeGameTest {
    @Test fun mirroredStationaryPlayersHitTogetherDrawRegardlessOfScoreProcessingOrder() {
        val game = DodgeGame(GameConfig(seed = 17))
        repeat(1200) { if (game.snapshot(0).phase != "finished") game.tick(50) }
        val end = game.snapshot(0)
        assertEquals("finished", end.phase)
        assertEquals(-1, end.winner)
        assertEquals("0,0", end.extra["alive"])
        assertEquals(end.scores[0], end.scores[1])
        assertFalse(direction(game, 0, 1))
    }

    @Test fun movementIsClampedInsideWallsAndStopPreservesPosition() {
        val game = DodgeGame(GameConfig(seed = 45))
        assertTrue(direction(game, 0, 3))
        game.tick(1000)
        assertEquals(1f, player(game.snapshot(0), 0).x)
        assertTrue(direction(game, 0, -1))
        val before = player(game.snapshot(0), 0)
        game.tick(400)
        val after = player(game.snapshot(0), 0)
        assertEquals(before.x, after.x)
        assertEquals(before.y, after.y)
        assertTrue(game.snapshot(0).entities.none { it.kind == "obstacle" })
    }
}
