package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.test.*

class GoGameTest {
    private fun index(x: Int, y: Int, size: Int = 9) = y * size + x
    private fun go(vararg options: Pair<String, String>) = GoGame(GameConfig(options = options.toMap()))
    private fun place(g: GoGame, index: Int) = g.apply(g.snapshot(-1).turn, GameAction("place", listOf(index)))
    private fun pass(g: GoGame) = g.apply(g.snapshot(-1).turn, GameAction("pass"))

    @Test fun initialBoardSizesPlayersAndTurnOrder() {
        val g = GoGame(GameConfig(players = 3))
        val s = g.snapshot(0)
        assertEquals("go", g.id)
        assertEquals(2, g.players)
        assertEquals(9, s.width)
        assertEquals(9, s.height)
        assertEquals(81, s.cells.size)
        assertTrue(s.cells.all { it == 0 })
        assertEquals(81, s.legal.count { it.type == "place" })
        assertTrue(GameAction("pass") in s.legal)
        assertTrue(GameAction("resign") in s.legal)
        assertEquals("9", s.extra["boardSize"])
        assertEquals("7.5", s.extra["komi"])
        assertEquals("", s.extra["gtpMoves"])
        assertTrue(g.snapshot(1).legal.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())

        val big = go("boardSize" to "19")
        assertEquals(19, big.snapshot(0).width)
        assertEquals(361, big.snapshot(0).cells.size)
        assertEquals("19", big.snapshot(0).extra["boardSize"])
        assertEquals(9, go("boardSize" to "17").snapshot(0).width)
    }

    @Test fun gtpHistoryUsesColumnLettersSkippingIAndRowsFromBottom() {
        val g = go("boardSize" to "19")
        assertTrue(place(g, index(15, 3, 19)))
        assertEquals("B Q16", g.snapshot(-1).extra["gtpMoves"])
        assertTrue(place(g, index(0, 0, 19)))
        assertEquals("B Q16 W A19", g.snapshot(-1).extra["gtpMoves"])
        assertTrue(pass(g))
        assertEquals("B Q16 W A19 B pass", g.snapshot(-1).extra["gtpMoves"])

        val small = go()
        assertTrue(place(small, index(3, 5)))
        assertTrue(pass(small))
        assertEquals("B D4 W pass", small.snapshot(-1).extra["gtpMoves"])
    }

    @Test fun captureRemovesStonesAndSuicideIsRejected() {
        val g = go()
        assertTrue(place(g, index(0, 1)))
        assertTrue(place(g, index(0, 0)))
        assertTrue(place(g, index(1, 0)))
        val s = g.snapshot(0)
        assertEquals(0, s.cells[index(0, 0)])
        assertEquals(1, s.cells[index(0, 1)])
        assertEquals(1, s.cells[index(1, 0)])
        assertEquals(1, s.turn)
        val before = g.snapshot(1)
        assertFalse(g.apply(1, GameAction("place", listOf(index(0, 0)))))
        assertEquals(before, g.snapshot(1))
        assertTrue(place(g, index(7, 7)))
    }

    @Test fun suicideThatCapturesIsLegal() {
        val g = go()
        assertTrue(place(g, index(0, 1)))
        assertTrue(place(g, index(0, 0)))
        assertTrue(place(g, index(2, 1)))
        assertTrue(place(g, index(1, 1)))
        assertTrue(place(g, index(1, 2)))
        assertTrue(place(g, index(2, 0)))
        assertTrue(place(g, index(1, 0)))
        assertEquals(1, g.snapshot(-1).cells[index(1, 0)])
        assertEquals(0, g.snapshot(-1).cells[index(1, 1)])
        assertEquals(0, g.snapshot(-1).cells[index(0, 0)])
        val before = g.snapshot(1)
        assertFalse(g.apply(1, GameAction("place", listOf(index(1, 1)))))
        assertEquals(before, g.snapshot(1))
    }

    @Test fun positionalSuperkoRejectsKoRecapture() {
        val g = go()
        assertTrue(place(g, index(0, 1)))
        assertTrue(place(g, index(0, 2)))
        assertTrue(place(g, index(2, 1)))
        assertTrue(place(g, index(2, 2)))
        assertTrue(place(g, index(1, 0)))
        assertTrue(place(g, index(1, 3)))
        assertTrue(place(g, index(8, 8)))
        assertTrue(place(g, index(1, 1)))
        assertTrue(place(g, index(1, 2)))
        assertEquals(0, g.snapshot(-1).cells[index(1, 1)])
        assertFalse(g.apply(1, GameAction("place", listOf(index(1, 1)))))
        assertTrue(place(g, index(7, 7)))
    }

    @Test fun twoPassesEnterScoringAndDoubleAcceptFinalizesWithKomi() {
        val g = go()
        assertTrue(place(g, index(0, 0)))
        assertTrue(place(g, index(8, 8)))
        assertTrue(pass(g))
        assertTrue(pass(g))
        val scoring = g.snapshot(0)
        assertEquals("scoring", scoring.phase)
        assertTrue(scoring.legal.none { it.type == "place" || it.type == "pass" })
        assertTrue(GameAction("accept_score") in scoring.legal)
        assertTrue(GameAction("resume") in scoring.legal)
        assertTrue(scoring.legal.any { it.type == "dead" && it.values == listOf(index(0, 0)) })
        assertTrue(g.snapshot(-1).legal.isEmpty())

        assertTrue(g.apply(0, GameAction("accept_score")))
        assertTrue(g.apply(1, GameAction("accept_score")))
        val s = g.snapshot(0)
        assertEquals("finished", s.phase)
        assertEquals(1, s.winner)
        assertEquals("score", s.extra["result"])
        assertEquals("W+7.5", s.extra["margin"])
        assertEquals(listOf(1, 1), s.scores)
    }

    @Test fun deadStonesRequireBothConfirmationsAndResumeClearsMarking() {
        val marked = go("komi" to "0")
        assertTrue(place(marked, index(4, 4)))
        assertTrue(place(marked, index(8, 8)))
        assertTrue(pass(marked))
        assertTrue(pass(marked))
        val dead = index(4, 4)
        assertTrue(marked.apply(1, GameAction("dead", listOf(dead))))
        assertEquals("$dead", marked.snapshot(0).extra["dead"])
        assertTrue(marked.apply(0, GameAction("accept_score")))
        assertEquals("scoring", marked.snapshot(0).phase)
        assertFalse(marked.apply(1, GameAction("accept_score", listOf(1))))
        assertTrue(marked.apply(1, GameAction("accept_score")))
        val finished = marked.snapshot(0)
        assertEquals("finished", finished.phase)
        assertEquals(1, finished.winner)
        assertEquals("W+81", finished.extra["margin"])
        assertEquals(listOf(0, 81), finished.scores)
        assertEquals(0, finished.cells[dead])

        val resumed = go("komi" to "0")
        assertTrue(place(resumed, index(4, 4)))
        assertTrue(place(resumed, index(8, 8)))
        assertTrue(pass(resumed))
        assertTrue(pass(resumed))
        assertTrue(resumed.apply(0, GameAction("dead", listOf(index(4, 4)))))
        assertTrue(resumed.apply(1, GameAction("resume")))
        assertEquals("playing", resumed.snapshot(0).phase)
        assertEquals(1, resumed.snapshot(0).turn)
        assertEquals("", resumed.snapshot(0).extra["dead"])
        assertTrue(resumed.apply(1, GameAction("place", listOf(index(0, 0)))))
    }

    @Test fun markingChangeResetsEarlierAcceptances() {
        val g = go("komi" to "0")
        assertTrue(place(g, index(4, 4)))
        assertTrue(place(g, index(8, 8)))
        assertTrue(pass(g))
        assertTrue(pass(g))
        assertTrue(g.apply(0, GameAction("accept_score")))
        assertTrue(g.apply(1, GameAction("dead", listOf(index(8, 8)))))
        assertEquals("${index(8, 8)}", g.snapshot(0).extra["dead"])
        assertTrue(g.apply(1, GameAction("accept_score")))
        assertEquals("scoring", g.snapshot(0).phase)
        assertTrue(g.apply(0, GameAction("accept_score")))
        assertEquals("finished", g.snapshot(0).phase)
        assertEquals(0, g.snapshot(0).winner)
        assertEquals("B+81", g.snapshot(0).extra["margin"])
        assertEquals(0, g.snapshot(0).cells[index(8, 8)])
    }

    @Test fun illegalActionsAreAtomicAndSpectatorsCannotAct() {
        val g = go()
        val before = g.snapshot(0)
        for (action in listOf(
            GameAction("place"), GameAction("place", listOf(-1)), GameAction("place", listOf(81)),
            GameAction("place", listOf(1, 2)), GameAction("place", listOf(0), "x"),
            GameAction("pass", listOf(0)), GameAction("resign", listOf(0)), GameAction("unknown")
        )) {
            assertFalse(g.apply(0, action))
            assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("place", listOf(0))))
        assertTrue(place(g, 0))
        val occupied = g.snapshot(0)
        assertFalse(g.apply(1, GameAction("place", listOf(0))))
        assertEquals(occupied, g.snapshot(0))
    }

    @Test fun resignEndsTheGameAndBotsAreUnavailable() {
        val g = go()
        assertNull(g.botAction(0, 0))
        assertNull(g.botAction(1, 2))
        assertTrue(place(g, index(4, 4)))
        assertNull(g.botAction(1, 1))
        assertTrue(g.apply(0, GameAction("resign")))
        val s = g.snapshot(-1)
        assertEquals("finished", s.phase)
        assertEquals(1, s.winner)
        assertEquals("resignation", s.extra["result"])
        assertFalse(g.apply(1, GameAction("resign")))
    }
}
