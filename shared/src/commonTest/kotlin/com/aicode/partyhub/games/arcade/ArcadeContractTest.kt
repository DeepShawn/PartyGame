package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal val arcadeFactories: List<(GameConfig) -> GameEngine> = listOf(
    ::ReactionGame, ::ColorGame, ::MathGame, ::MemoryGame,
    ::SnakeGame, ::TankGame, ::BomberGame, ::DodgeGame
)

class ArcadeContractTest {
    @Test fun allEnginesValidateSeatsShapesAndPlayerCounts() {
        for (factory in arcadeFactories) {
            assertFailsWith<IllegalArgumentException> { factory(GameConfig(players = 1)) }
            assertFailsWith<IllegalArgumentException> { factory(GameConfig(players = 4)) }
            for (count in 2..3) {
                val game = factory(GameConfig(players = count, seed = 9281))
                val before = game.snapshot(-1)
                assertEquals(count, before.scores.size)
                assertTrue(before.message.isNotBlank(), game.id)
                assertEquals(if (game.id == "memory") 0 else -1, before.turn)
                for (seat in listOf(-1, count, Int.MAX_VALUE)) {
                    for (type in listOf("tap", "answer", "flip", "direction", "fire", "bomb")) {
                        assertFalse(game.apply(seat, GameAction(type, listOf(0))), game.id)
                        assertFalse(game.apply(seat, GameAction(type)), game.id)
                    }
                }
                for (action in listOf(
                    GameAction("unknown"), GameAction("direction", listOf(4)),
                    GameAction("direction", listOf(-2)), GameAction("answer", listOf(4)),
                    GameAction("flip", listOf(24)), GameAction("tap", listOf(0)),
                    GameAction("fire", listOf(1)), GameAction("bomb", text = "injected"),
                    GameAction("flip", listOf(1, 2)), GameAction("direction", listOf(1), "1")
                )) assertFalse(game.apply(0, action), "${game.id}: $action")
                game.tick(0)
                game.tick(-20)
                assertEquals(before, game.snapshot(-1), game.id)
                assertTrue(before.extra.keys.none { it.contains("seed", true) || it.contains("correct", true) || it == "answer" })
            }
        }
    }

    @Test fun fixedSeedsAndElapsedTimePartitionsProduceIdenticalSnapshots() {
        for (factory in arcadeFactories) {
            val first = factory(GameConfig(players = 3, seed = 772))
            val second = factory(GameConfig(players = 3, seed = 772))
            first.tick(5203)
            repeat(52) { second.tick(100) }
            second.tick(3)
            assertEquals(first.snapshot(-1), second.snapshot(-1), first.id)
            for (seat in 0..2) assertEquals(first.snapshot(-1).cells, first.snapshot(seat).cells)
        }
    }

    @Test fun reversedSeatArrivalOrderDoesNotChangeSimultaneousResults() {
        for (factory in arcadeFactories.drop(4)) {
            val first = factory(GameConfig(players = 3, seed = 87))
            val second = factory(GameConfig(players = 3, seed = 87))
            repeat(100) { frame ->
                for (seat in 0..2) {
                    first.apply(seat, GameAction("direction", listOf((frame + seat) % 5 - 1)))
                    first.apply(seat, GameAction("fire"))
                    first.apply(seat, GameAction("bomb"))
                }
                for (seat in 2 downTo 0) {
                    second.apply(seat, GameAction("direction", listOf((frame + seat) % 5 - 1)))
                    second.apply(seat, GameAction("fire"))
                    second.apply(seat, GameAction("bomb"))
                }
                first.tick(200)
                second.tick(200)
                assertEquals(first.snapshot(-1), second.snapshot(-1), "${first.id} frame $frame")
            }
        }
    }

    @Test fun repeatedLegalActionsKeepEntityBoundsAndTerminalStateConsistent() {
        for (seed in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE)) for (factory in arcadeFactories) {
            val game = factory(GameConfig(players = 3, seed = seed))
            repeat(400) { frame ->
                for (seat in 0..2) {
                    val legal = game.snapshot(seat).legal
                    if (legal.isNotEmpty()) assertTrue(game.apply(seat, legal[(frame + seat) % legal.size]), game.id)
                }
                game.tick(137)
                val state = game.snapshot(-1)
                assertEquals(3, state.scores.size)
                assertTrue(state.entities.map { it.id }.distinct().size == state.entities.size, game.id)
                assertTrue(state.entities.all { it.x >= 0 && it.y >= 0 && it.x < state.width && it.y < state.height }, game.id)
                assertTrue(state.extra.keys.none { it.contains("seed", true) || it.contains("correct", true) || it == "answer" }, game.id)
                if (state.phase == "finished") {
                    assertTrue(state.winner in -1..2)
                    assertTrue((0..2).all { game.snapshot(it).legal.isEmpty() })
                    game.tick(1)
                    assertEquals(state, game.snapshot(-1), game.id)
                } else assertEquals(null, state.winner)
            }
        }
    }

    @Test fun veryLargeTicksRespectLimitsAndNeverOverflowOrReviveFinishedGames() {
        for (factory in arcadeFactories.filterIndexed { index, _ -> index != 3 }) {
            val game = factory(GameConfig(players = 3, seed = 99))
            if (game.id == "snake") for (seat in 0..2) direction(game, seat, -1)
            game.tick(Int.MAX_VALUE)
            val end = game.snapshot(-1)
            assertEquals("finished", end.phase, game.id)
            assertTrue(end.tick in 1..180000, game.id)
            assertTrue(end.winner in -1..2, game.id)
            game.tick(Int.MAX_VALUE)
            assertEquals(end, game.snapshot(-1), game.id)
        }
    }

    @Test fun snapshotsAreDetachedFromSubsequentChanges() {
        for (factory in arcadeFactories) {
            val game = factory(GameConfig(seed = 55))
            val before = game.snapshot(0)
            val scores = before.scores.toList()
            val cells = before.cells.toList()
            val extra = before.extra.toMap()
            val entities = before.entities.toList()
            game.apply(0, GameAction("tap"))
            game.apply(0, GameAction("answer", listOf(0)))
            game.apply(0, GameAction("flip", listOf(0)))
            game.apply(0, GameAction("fire"))
            game.apply(0, GameAction("bomb"))
            game.tick(10000)
            assertEquals(scores, before.scores, game.id)
            assertEquals(cells, before.cells, game.id)
            assertEquals(extra, before.extra, game.id)
            assertEquals(entities, before.entities, game.id)
        }
    }
}
