package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryGameTest {
    @Test fun hiddenCardsStayHiddenAndMismatchesWaitBeforeChangingTurn() {
        val game = MemoryGame(GameConfig(players = 3, seed = 198))
        val initial = game.snapshot(-1)
        assertEquals(List(24) { -1 }, initial.cells)
        assertEquals(initial, MemoryGame(GameConfig(players = 3, seed = 991)).snapshot(-1))
        assertFalse(game.apply(1, GameAction("flip", listOf(0))))
        assertTrue(game.apply(0, GameAction("flip", listOf(0))))
        assertFalse(game.apply(0, GameAction("flip", listOf(0))))
        val shown = game.snapshot(2)
        assertEquals(23, shown.cells.count { it == -1 })
        val deck = discover(198)
        val different = deck.indices.first { deck[it] != deck[0] }
        assertTrue(game.apply(0, GameAction("flip", listOf(different))))
        assertEquals("reveal", game.snapshot(0).phase)
        assertFalse(game.apply(0, GameAction("flip", listOf(3))))
        game.tick(899)
        assertEquals(0, game.snapshot(0).turn)
        assertEquals(22, game.snapshot(0).cells.count { it == -1 })
        game.tick(1)
        assertEquals(1, game.snapshot(0).turn)
        assertEquals(List(24) { -1 }, game.snapshot(-1).cells)
        assertEquals(List(3) { 0 }, game.snapshot(0).scores)
    }

    @Test fun matchingKeepsTurnTracksOwnersAndFinishesAllTwelvePairs() {
        val deck = discover(198)
        val game = MemoryGame(GameConfig(players = 3, seed = 198))
        deck.indices.groupBy { deck[it] }.values.forEachIndexed { pair, indices ->
            assertEquals(2, indices.size)
            assertTrue(game.apply(0, GameAction("flip", listOf(indices[0]))))
            assertTrue(game.apply(0, GameAction("flip", listOf(indices[1]))))
            assertEquals(listOf(pair + 1, 0, 0), game.snapshot(0).scores)
            assertFalse(game.apply(0, GameAction("flip", listOf(indices[0]))))
            if (pair < 11) assertEquals(0, game.snapshot(0).turn)
        }
        val end = game.snapshot(0)
        assertEquals("finished", end.phase)
        assertEquals(0, end.winner)
        assertEquals(List(24) { "0" }, end.extra.getValue("owners").split(","))
        game.tick(3000)
        assertFalse(game.apply(0, GameAction("flip", listOf(0))))
        assertEquals(end, game.snapshot(0))
    }

    private fun discover(seed: Int): List<Int> {
        val game = MemoryGame(GameConfig(players = 3, seed = seed))
        val observed = MutableList(24) { -1 }
        for (i in 0 until 24 step 2) {
            val seat = game.snapshot(-1).turn
            assertTrue(game.apply(seat, GameAction("flip", listOf(i))))
            observed[i] = game.snapshot(-1).cells[i]
            assertTrue(game.apply(seat, GameAction("flip", listOf(i + 1))))
            observed[i + 1] = game.snapshot(-1).cells[i + 1]
            game.tick(900)
        }
        return observed
    }
}
