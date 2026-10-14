package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun direction(game: GameEngine, seat: Int, value: Int) = game.apply(seat, GameAction("direction", listOf(value)))
internal fun player(state: GameSnapshot, seat: Int) = state.entities.first { it.kind == "player" && it.owner == seat }

class RealtimeGamesTest {
    @Test fun realtimeMapsHaveSafeStartsAndRejectInputFlooding() {
        for (factory in arcadeFactories.drop(4)) {
            val game = factory(GameConfig(players = 3, seed = 8))
            val snapshot = game.snapshot(0)
            assertEquals(snapshot.width * snapshot.height, snapshot.cells.size)
            assertTrue(snapshot.cells.all { it in -2..0 })
            assertEquals(3, snapshot.entities.count { it.kind == "player" })
            for (seat in 0..2) {
                val entity = player(snapshot, seat)
                val x = entity.x.toInt()
                val y = entity.y.toInt()
                assertEquals(0, snapshot.cells[y * snapshot.width + x])
                assertTrue(direction(game, seat, if (game.id == "snake") 0 else 1))
                assertFalse(direction(game, seat, 2))
            }
        }
    }

    @Test fun snakeCannotReverseAndResolvesHeadOnAndSwapsSimultaneously() {
        val game = SnakeGame(GameConfig(seed = 3))
        assertFalse(direction(game, 0, 3))
        assertFalse(direction(game, 1, 1))
        val first = game.snapshot(0)
        val distance = (player(first, 1).x - player(first, 0).x).toInt()
        game.tick(180 * ((distance + 1) / 2))
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals(-1, game.snapshot(0).winner)
        assertEquals("0,0", game.snapshot(0).extra["alive"])
        val stopped = SnakeGame(GameConfig(seed = 3))
        assertTrue(direction(stopped, 0, -1))
        assertTrue(direction(stopped, 1, -1))
        stopped.tick(180)
        assertTrue(direction(stopped, 0, 0))
        stopped.tick(180 * player(first, 0).y.toInt())
        assertEquals(1, stopped.snapshot(0).winner)
        val end = stopped.snapshot(0)
        assertFalse(direction(stopped, 1, 0))
        stopped.tick(1000)
        assertEquals(end, stopped.snapshot(0))
    }

    @Test fun threeUnsteeredSnakesReachTheCenterInTheSameStep() {
        val game = SnakeGame(GameConfig(players = 3, seed = 3))
        game.tick(4 * 180)
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals("0,0,0", game.snapshot(0).extra["alive"])
        assertEquals(-1, game.snapshot(0).winner)
    }

    @Test fun threeSnakesStartWithEqualLengthsAndHeadSwapsEliminateBoth() {
        val three = SnakeGame(GameConfig(players = 3, seed = 9)).snapshot(0)
        assertEquals(listOf(3, 3, 3), (0..2).map { seat -> three.entities.count { it.owner == seat && it.kind in listOf("player", "snake") } })
        val game = SnakeGame(GameConfig(seed = 3))
        direction(game, 0, -1)
        game.tick(180)
        direction(game, 0, 1)
        game.tick(180 * 4)
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals("0,0", game.snapshot(0).extra["alive"])
        assertEquals(-1, game.snapshot(0).winner)
    }

    @Test fun snakeEatingGrowsBodyAndAwardsPoints() {
        val game = SnakeGame(GameConfig(seed = 32))
        direction(game, 1, -1)
        val before = game.snapshot(0)
        val head = player(before, 0)
        val food = before.entities.filter { it.kind == "food" }.minBy { kotlin.math.abs(it.x - head.x) + kotlin.math.abs(it.y - head.y) }
        val vertical = if (food.y < head.y) 0 else 2
        if (food.y != head.y) {
            assertTrue(direction(game, 0, vertical))
            game.tick(180 * kotlin.math.abs(food.y - head.y).toInt())
        }
        if (food.x != head.x) {
            assertTrue(direction(game, 0, if (food.x > head.x) 1 else 3))
            game.tick(180 * kotlin.math.abs(food.x - head.x).toInt())
        }
        assertTrue(game.snapshot(0).scores[0] > 0)
        assertTrue(game.snapshot(0).entities.count { it.kind == "snake" && it.owner == 0 } > before.entities.count { it.kind == "snake" && it.owner == 0 })
    }

    @Test fun anEarlierEliminatedSnakeCannotWinWhenFinalTwoCrashTogether() {
        val game = SnakeGame(GameConfig(players = 3, seed = 12865))
        direction(game, 1, -1)
        direction(game, 2, -1)
        direction(game, 0, 0)
        game.tick(180 * 4)
        direction(game, 0, 3)
        game.tick(180)
        assertEquals(1, game.snapshot(0).scores[0])
        game.tick(180 * 2)
        assertEquals("0,1,1", game.snapshot(0).extra["alive"])
        direction(game, 2, 1)
        game.tick(180)
        direction(game, 1, 0)
        game.tick(180 * 6)
        val end = game.snapshot(0)
        assertEquals("0,0,0", end.extra["alive"])
        val expected = when {
            end.scores[1] > end.scores[2] -> 1
            end.scores[2] > end.scores[1] -> 2
            else -> -1
        }
        assertEquals(expected, end.winner)
    }

    @Test fun tanksHaveThreeHpAndMutualLethalShotsDraw() {
        val game = TankGame(GameConfig(seed = 53))
        assertEquals("3,3", game.snapshot(0).extra["hp"])
        repeat(3) { shot ->
            assertTrue(game.apply(1, GameAction("fire")))
            assertTrue(game.apply(0, GameAction("fire")))
            assertFalse(game.apply(0, GameAction("fire")))
            game.tick(1500)
            assertEquals("${2 - shot},${2 - shot}", game.snapshot(0).extra["hp"])
        }
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals(-1, game.snapshot(0).winner)
        assertEquals(listOf(3, 3), game.snapshot(0).scores)
    }

    @Test fun tankMovingAwayFromAnIncomingBulletDoesNotReceiveAPhantomHit() {
        val game = TankGame(GameConfig(seed = 7))
        assertTrue(game.apply(0, GameAction("fire")))
        game.tick(900)
        assertTrue(direction(game, 1, 1))
        game.tick(100)
        assertEquals(13f, player(game.snapshot(0), 1).x)
        assertEquals("3,3", game.snapshot(0).extra["hp"])
        game.tick(100)
        assertEquals("3,2", game.snapshot(0).extra["hp"])
    }

    @Test fun tankWallsAbsorbShotsAndDirectionStopsMotion() {
        val game = TankGame(GameConfig(seed = 7))
        val start = player(game.snapshot(0), 0)
        direction(game, 0, 0)
        game.tick(200)
        direction(game, 0, -1)
        val stopped = player(game.snapshot(0), 0)
        assertTrue(stopped.y < start.y)
        game.tick(200)
        assertEquals(stopped.x, player(game.snapshot(0), 0).x)
        assertEquals(stopped.y, player(game.snapshot(0), 0).y)
        assertTrue(game.apply(0, GameAction("fire")))
        game.tick(1800)
        assertEquals("3,3", game.snapshot(0).extra["hp"])
        assertTrue(game.snapshot(0).entities.none { it.kind == "bullet" })
    }

    @Test fun bombFuseKillsAllExposedPlayersInOneStep() {
        val game = BomberGame(GameConfig(seed = 11))
        assertTrue(game.apply(0, GameAction("bomb")))
        assertTrue(game.apply(1, GameAction("bomb")))
        assertFalse(game.apply(0, GameAction("bomb")))
        game.tick(1950)
        assertEquals("playing", game.snapshot(0).phase)
        assertEquals(2, game.snapshot(0).entities.count { it.kind == "bomb" })
        game.tick(50)
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals(-1, game.snapshot(0).winner)
        assertEquals("0,0", game.snapshot(0).extra["alive"])
        assertTrue(game.snapshot(0).entities.any { it.kind == "blast" })
        assertFalse(game.apply(0, GameAction("bomb")))
    }

    @Test fun bombOwnerCanEscapeAndCannotWalkBackThroughBomb() {
        val game = BomberGame(GameConfig(players = 3, seed = 2))
        game.apply(0, GameAction("bomb"))
        direction(game, 0, 1)
        game.tick(200)
        val escaped = player(game.snapshot(0), 0)
        direction(game, 0, 3)
        game.tick(200)
        assertEquals(escaped.x, player(game.snapshot(0), 0).x)
        direction(game, 0, 1)
        game.tick(200)
        direction(game, 0, 2)
        game.tick(200)
        direction(game, 0, -1)
        game.tick(1200)
        assertEquals("1,1,1", game.snapshot(0).extra["alive"])
    }

    @Test fun dodgeAcceleratesAndStationaryPlayersAreNotImmortal() {
        val game = DodgeGame(GameConfig(players = 3, seed = 16))
        val speed = game.snapshot(0).extra.getValue("speed").toInt()
        game.tick(1000)
        assertEquals("1,1,1", game.snapshot(0).extra["alive"])
        assertTrue(game.snapshot(0).extra.getValue("speed").toInt() > speed)
        repeat(1200) { if (game.snapshot(0).phase != "finished") game.tick(50) }
        assertEquals("finished", game.snapshot(0).phase)
        assertTrue(game.snapshot(0).scores.all { it > 0 })
        val end = game.snapshot(0)
        game.tick(10000)
        assertEquals(end, game.snapshot(0))
    }
}
