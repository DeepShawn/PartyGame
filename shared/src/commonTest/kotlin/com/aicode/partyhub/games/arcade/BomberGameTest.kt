package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BomberGameTest {
    @Test fun blastsChainEarlyButDoNotPassWallsOrCrates() {
        val game = BomberGame(GameConfig(players = 3, seed = 6))
        assertTrue(game.apply(0, GameAction("bomb")))
        direction(game, 0, 1)
        game.tick(400)
        direction(game, 0, -1)
        assertTrue(game.apply(0, GameAction("bomb")))
        assertFalse(game.apply(0, GameAction("bomb")))
        game.tick(200)
        direction(game, 0, 2)
        game.tick(200)
        direction(game, 0, -1)
        val before = game.snapshot(0)
        val bombs = before.entities.filter { it.kind == "bomb" }
        assertEquals(2, bombs.size)
        assertEquals(listOf(1200, 1600), bombs.map { it.value })
        game.tick(1200)
        val after = game.snapshot(0)
        assertTrue(after.entities.none { it.kind == "bomb" })
        val blasts = after.entities.filter { it.kind == "blast" }
        assertTrue(blasts.isNotEmpty())
        assertTrue(blasts.all { before.cells[it.y.toInt() * before.width + it.x.toInt()] != -1 })
        for (bomb in bombs) {
            for ((dx, dy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                var blocked = false
                for (distance in 1..3) {
                    val x = bomb.x.toInt() + dx * distance
                    val y = bomb.y.toInt() + dy * distance
                    if (x !in 0 until before.width || y !in 0 until before.height) break
                    if (blocked) {
                        val reachedByOtherBomb = bombs.any { other ->
                            other != bomb && (other.x.toInt() == x || other.y.toInt() == y)
                        }
                        if (!reachedByOtherBomb) assertTrue(blasts.none { it.x.toInt() == x && it.y.toInt() == y })
                    }
                    if (before.cells[y * before.width + x] != 0) blocked = true
                }
            }
        }
        val destroyed = before.cells.indices.count { before.cells[it] == -2 && after.cells[it] == 0 }
        assertTrue(destroyed > 0)
        assertEquals(destroyed, after.scores[0])
    }

    @Test fun onlyLastSurvivorWinsEvenWhenAnotherSeatHasMorePoints() {
        val game = BomberGame(GameConfig(players = 3, seed = 6))
        assertTrue(game.apply(0, GameAction("bomb")))
        assertTrue(game.apply(1, GameAction("bomb")))
        game.tick(2000)
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals(2, game.snapshot(0).winner)
        assertFalse(direction(game, 0, 1))
        assertFalse(game.apply(2, GameAction("bomb")))
    }
}
