package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.test.*

class ReversiGameTest {
    private fun index(x: Int, y: Int) = y * 8 + x
    private fun place(g: ReversiGame, index: Int) = g.apply(g.snapshot(-1).turn, GameAction("place", listOf(index)))
    private fun position(vararg pieces: Pair<Char, Pair<Int, Int>>, turn: Char = 'b'): ReversiGame {
        val grid = Array(8) { CharArray(8) { '.' } }
        pieces.forEach { (ch, at) -> grid[at.second][at.first] = ch }
        return ReversiGame(GameConfig(options = mapOf("setup" to grid.joinToString("/") { it.concatToString() }, "turn" to turn.toString())))
    }

    @Test fun initialBoardLegalMovesAndPerSeatViews() {
        val g = ReversiGame(GameConfig(players = 3))
        val s = g.snapshot(0)
        assertEquals("reversi", g.id)
        assertEquals(2, g.players)
        assertEquals(8, s.width)
        assertEquals(8, s.height)
        assertEquals(64, s.cells.size)
        assertEquals(1, s.cells[index(3, 3)])
        assertEquals(2, s.cells[index(4, 3)])
        assertEquals(2, s.cells[index(3, 4)])
        assertEquals(1, s.cells[index(4, 4)])
        assertEquals(listOf(2, 2), s.scores)
        val moves = s.legal.filter { it.type == "place" }.map { it.values.single() }.toSet()
        assertEquals(setOf(index(5, 3), index(4, 2), index(3, 5), index(2, 4)), moves)
        assertTrue(GameAction("resign") in s.legal)
        assertTrue(g.snapshot(1).legal.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())
    }

    @Test fun placementFlipsAtLeastOneStoneInEveryDirection() {
        val g = ReversiGame(GameConfig())
        assertTrue(place(g, index(3, 5)))
        val s = g.snapshot(0)
        assertEquals(1, s.cells[index(3, 5)])
        assertEquals(1, s.cells[index(3, 4)])
        assertEquals(1, s.cells[index(3, 3)])
        assertEquals(2, s.cells[index(4, 3)])
        assertEquals(1, s.turn)
        assertEquals(listOf(4, 1), s.scores)
        assertEquals(3, g.snapshot(1).legal.count { it.type == "place" })
    }

    @Test fun illegalPlacementsAreAtomicAndSpectatorsCannotAct() {
        val g = ReversiGame(GameConfig())
        val before = g.snapshot(0)
        for (action in listOf(
            GameAction("place"), GameAction("place", listOf(-1)), GameAction("place", listOf(64)),
            GameAction("place", listOf(0)), GameAction("place", listOf(1, 2)),
            GameAction("place", listOf(0), "x"), GameAction("unknown")
        )) {
            assertFalse(g.apply(0, action))
            assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("place", listOf(index(3, 5)))))
        assertTrue(place(g, index(3, 5)))
        val occupied = g.snapshot(0)
        assertFalse(g.apply(1, GameAction("place", listOf(index(3, 5)))))
        assertEquals(occupied, g.snapshot(0))
    }

    @Test fun autoPassSkipsAndBothBlockedEndsGameByCount() {
        val g = position('b' to (1 to 1), 'w' to (0 to 0))
        val s = g.snapshot(0)
        assertEquals(1, s.turn)
        assertTrue(s.message.contains("跳过"))
        assertTrue(s.legal.isEmpty())
        assertTrue(g.snapshot(1).legal.any { it.type == "place" && it.values == listOf(index(2, 2)) })
        assertFalse(g.apply(0, GameAction("place", listOf(index(2, 2)))))
        assertTrue(g.apply(1, GameAction("place", listOf(index(2, 2)))))
        assertEquals(2, g.snapshot(0).cells[index(1, 1)])
        val finished = g.snapshot(0)
        assertEquals("finished", finished.phase)
        assertEquals(1, finished.winner)
        assertEquals("count", finished.extra["result"])
        assertEquals(listOf(0, 3), finished.scores)
        assertTrue(finished.message.contains("无子可下"))
    }

    @Test fun malformedSetupRejectedAtBoundary() {
        for (setup in listOf("8/8", "........ ........ ........", "1" + ".".repeat(64))) {
            assertFailsWith<IllegalArgumentException> {
                ReversiGame(GameConfig(options = mapOf("setup" to setup)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ReversiGame(GameConfig(options = mapOf("setup" to ".".repeat(63) + "X")))
        }
    }

    @Test fun botPrefersCornersAndIsDeterministicAndLegal() {
        val g = position(
            'w' to (0 to 1), 'b' to (0 to 2),
            'w' to (3 to 3), 'w' to (2 to 2), 'b' to (1 to 1)
        )
        val legal = g.snapshot(0).legal.filter { it.type == "place" }.map { it.values.single() }.toSet()
        assertEquals(setOf(index(0, 0), index(4, 4)), legal)
        for (difficulty in 0..2) {
            assertEquals(GameAction("place", listOf(index(0, 0))), g.botAction(0, difficulty))
        }
        val initial = ReversiGame(GameConfig(seed = 3))
        val twin = ReversiGame(GameConfig(seed = 3))
        assertEquals(initial.botAction(0, 1), twin.botAction(0, 1))
        assertTrue(initial.botAction(0, 1)!! in initial.snapshot(0).legal)
        assertNull(initial.botAction(1, 1))
    }

    @Test fun botsAtEveryDifficultyFinishLegalGames() {
        for (difficulty in 0..2) {
            val g = ReversiGame(GameConfig(difficulty = difficulty))
            var plies = 0
            while (g.snapshot(-1).phase != "finished" && plies < 200) {
                val seat = g.snapshot(-1).turn
                val before = g.snapshot(seat)
                val action = assertNotNull(g.botAction(seat, difficulty))
                assertTrue(action in before.legal)
                assertTrue(g.apply(seat, action))
                plies++
            }
            if (g.snapshot(-1).phase != "finished") assertTrue(g.apply(g.snapshot(-1).turn, GameAction("resign")))
            assertEquals("finished", g.snapshot(-1).phase)
        }
    }
}
