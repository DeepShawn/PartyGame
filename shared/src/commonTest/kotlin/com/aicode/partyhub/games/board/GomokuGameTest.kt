package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.test.*

class GomokuGameTest {
    private fun index(x: Int, y: Int) = y * 15 + x
    private fun place(g: GomokuGame, index: Int) = g.apply(g.snapshot(-1).turn, GameAction("place", listOf(index)))
    private fun fiveInLine(vararg line: Int, filler: IntArray = intArrayOf(0, 1, 2, 3)): GomokuGame {
        val g = GomokuGame(GameConfig())
        line.dropLast(1).forEachIndexed { i, cell ->
            assertTrue(place(g, cell))
            assertTrue(place(g, filler[i]))
        }
        assertEquals(0, g.snapshot(-1).turn)
        assertTrue(place(g, line.last()))
        return g
    }

    @Test fun initialBoardAndTurnOrder() {
        val g = GomokuGame(GameConfig(players = 3))
        val s = g.snapshot(0)
        assertEquals("gomoku", g.id)
        assertEquals(2, g.players)
        assertEquals(15, s.width)
        assertEquals(15, s.height)
        assertEquals(225, s.cells.size)
        assertTrue(s.cells.all { it == 0 })
        assertEquals("playing", s.phase)
        assertEquals(225, s.legal.count { it.type == "place" })
        assertTrue(GameAction("resign") in s.legal)
        assertTrue(g.snapshot(1).legal.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())
        assertTrue(place(g, index(7, 7)))
        assertEquals(1, g.snapshot(0).cells[index(7, 7)])
        assertEquals(1, g.snapshot(-1).turn)
        assertEquals(224, g.snapshot(1).legal.count { it.type == "place" })
        assertTrue(g.snapshot(0).legal.isEmpty())
    }

    @Test fun fiveInAnyDirectionWinsIncludingOverline() {
        assertEquals(0, fiveInLine(index(3, 7), index(4, 7), index(5, 7), index(6, 7), index(7, 7)).snapshot(0).winner)
        assertEquals(0, fiveInLine(index(7, 3), index(7, 4), index(7, 5), index(7, 6), index(7, 7)).snapshot(0).winner)
        assertEquals(0, fiveInLine(index(3, 3), index(4, 4), index(5, 5), index(6, 6), index(7, 7)).snapshot(0).winner)
        assertEquals(0, fiveInLine(index(3, 7), index(4, 6), index(5, 5), index(6, 4), index(7, 3)).snapshot(0).winner)
        val overline = GomokuGame(GameConfig())
        listOf(index(3, 5), index(4, 5), index(5, 5), index(6, 5), index(8, 5)).forEachIndexed { i, cell ->
            assertTrue(place(overline, cell))
            assertTrue(place(overline, intArrayOf(index(0, 0), index(1, 2), index(2, 4), index(3, 6), index(4, 8))[i]))
        }
        assertTrue(place(overline, index(7, 5)))
        val s = overline.snapshot(0)
        assertEquals(0, s.winner)
        assertEquals("finished", s.phase)
        assertEquals("five_in_row", s.extra["result"])
        assertTrue(s.message.contains("五连"))
        assertTrue(s.legal.isEmpty())
        assertFalse(place(overline, index(10, 10)))
    }

    @Test fun sixInARowWinsAndWinIsCheckedFromLastStone() {
        val g = GomokuGame(GameConfig())
        listOf(index(3, 9), index(4, 9), index(5, 9), index(6, 9), index(8, 9)).forEachIndexed { i, cell ->
            assertTrue(place(g, cell))
            assertTrue(place(g, intArrayOf(index(14, 14), index(13, 12), index(12, 10), index(11, 8), index(10, 6))[i]))
        }
        assertTrue(place(g, index(7, 9)))
        assertEquals(0, g.snapshot(0).winner)
    }

    @Test fun illegalActionsAreAtomicAndSpectatorsCannotAct() {
        val g = GomokuGame(GameConfig())
        val before = g.snapshot(0)
        for (action in listOf(
            GameAction("place"), GameAction("place", listOf(-1)), GameAction("place", listOf(225)),
            GameAction("place", listOf(1, 2)), GameAction("place", listOf(0), "note"),
            GameAction("unknown", listOf(0)), GameAction("resign", listOf(0))
        )) {
            assertFalse(g.apply(0, action))
            assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("place", listOf(5))))
        assertTrue(place(g, 5))
        val occupied = g.snapshot(0)
        assertFalse(g.apply(1, GameAction("place", listOf(5))))
        assertEquals(occupied, g.snapshot(0))
    }

    @Test fun resignEndsTheGameForTheOpponent() {
        val g = GomokuGame(GameConfig())
        assertTrue(g.apply(0, GameAction("resign")))
        val s = g.snapshot(-1)
        assertEquals("finished", s.phase)
        assertEquals(1, s.winner)
        assertEquals("resignation", s.extra["result"])
        assertFalse(g.apply(1, GameAction("resign")))
    }

    @Test fun botWinsWhenFourInARowAndBlocksOpponentFour() {
        val win = GomokuGame(GameConfig())
        listOf(index(7, 3), index(7, 4), index(7, 5), index(7, 6)).forEachIndexed { i, cell ->
            assertTrue(place(win, cell))
            assertTrue(place(win, intArrayOf(index(0, 0), index(0, 1), index(0, 2), index(0, 3))[i]))
        }
        for (difficulty in 0..2) {
            val action = assertNotNull(win.botAction(0, difficulty))
            assertTrue(action.values[0] in listOf(index(7, 2), index(7, 7)))
        }
        assertTrue(win.apply(0, assertNotNull(win.botAction(0, 1))))
        assertEquals(0, win.snapshot(0).winner)

        for (difficulty in 0..2) {
            val block = GomokuGame(GameConfig())
            listOf(index(7, 2), index(7, 3), index(7, 4), index(7, 5)).forEachIndexed { i, cell ->
                assertTrue(place(block, intArrayOf(0, index(2, 1), index(4, 2), index(6, 3))[i]))
                assertTrue(place(block, cell))
            }
            val action = assertNotNull(block.botAction(0, difficulty))
            assertTrue(action.values[0] in listOf(index(7, 1), index(7, 6)), "difficulty $difficulty should block")
        }
    }

    @Test fun botsAtEveryDifficultyAreDeterministicAndAlwaysLegal() {
        for (difficulty in 0..2) {
            val g = GomokuGame(GameConfig(seed = 7))
            val twin = GomokuGame(GameConfig(seed = 77))
            var plies = 0
            while (g.snapshot(-1).phase != "finished" && plies < 80) {
                val seat = g.snapshot(-1).turn
                val before = g.snapshot(seat)
                assertNull(g.botAction(1 - seat, difficulty))
                val action = assertNotNull(g.botAction(seat, difficulty))
                assertEquals(g.botAction(seat, difficulty), twin.botAction(twin.snapshot(-1).turn, difficulty))
                assertTrue(action in before.legal)
                assertEquals(before, g.snapshot(seat))
                assertTrue(g.apply(seat, action))
                assertTrue(twin.apply(seat, action))
                plies++
            }
            if (g.snapshot(-1).phase != "finished") assertTrue(g.apply(g.snapshot(-1).turn, GameAction("resign")))
            assertEquals("finished", g.snapshot(-1).phase)
        }
    }
}
