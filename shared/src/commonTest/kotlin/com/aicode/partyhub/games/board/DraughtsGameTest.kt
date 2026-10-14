package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.math.abs
import kotlin.test.*

class DraughtsGameTest {
    private fun index(x: Int, y: Int) = y * 10 + x
    private fun position(vararg pieces: Pair<Char, Pair<Int, Int>>, turn: Char = 'w'): DraughtsGame {
        val grid = Array(10) { CharArray(10) { '.' } }
        pieces.forEach { (ch, at) -> grid[at.second][at.first] = ch }
        return DraughtsGame(GameConfig(options = mapOf("setup" to grid.joinToString("/") { it.concatToString() }, "turn" to turn.toString())))
    }
    private fun play(g: DraughtsGame, vararg cells: Int) = g.apply(g.snapshot(-1).turn, GameAction("move", cells.toList()))

    @Test fun initialPositionOpensWithCompulsoryCaptureRulesInPlace() {
        val g = DraughtsGame(GameConfig(players = 3))
        val s = g.snapshot(0)
        assertEquals("draughts", g.id)
        assertEquals(2, g.players)
        assertEquals(10, s.width)
        assertEquals(10, s.height)
        assertEquals(100, s.cells.size)
        assertEquals(20, s.cells.count { it == 1 })
        assertEquals(20, s.cells.count { it == -1 })
        assertEquals(0, s.cells.count { abs(it) == 2 })
        assertEquals(listOf(20, 20), s.scores)
        assertTrue(s.extra.getValue("pieceEncoding").contains("王"))
        assertTrue(s.extra.getValue("pieceEncoding").contains("-2"))
        val moves = s.legal.filter { it.type == "move" }
        assertEquals(9, moves.size)
        assertTrue(moves.all { it.values.size == 2 && it.values[0] / 10 == 6 })
        assertTrue(GameAction("move", listOf(index(1, 6), index(0, 5))) in moves)
        assertTrue(GameAction("move", listOf(index(9, 6), index(8, 5))) in moves)
        assertTrue(GameAction("resign") in s.legal)
        assertTrue(g.snapshot(1).legal.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())
        assertTrue(play(g, index(1, 6), index(0, 5)))
        assertEquals(1, g.snapshot(0).cells[index(0, 5)])
        assertEquals(0, g.snapshot(0).cells[index(1, 6)])
        assertEquals(1, g.snapshot(-1).turn)
    }

    @Test fun mustChooseTheLongestCaptureAndRunItToTheEnd() {
        val g = position(
            'w' to (1 to 6), 'w' to (8 to 5),
            'b' to (2 to 5), 'b' to (4 to 3), 'b' to (6 to 1), 'b' to (7 to 4), 'b' to (0 to 1)
        )
        val moves = g.snapshot(0).legal.filter { it.type == "move" }
        assertEquals(1, moves.size)
        assertEquals(listOf(index(1, 6), index(3, 4), index(5, 2), index(7, 0)), moves.single().values)
        assertTrue(moves.none { it.values.first() == index(8, 5) })
        assertTrue(play(g, index(1, 6), index(3, 4), index(5, 2), index(7, 0)))
        val s = g.snapshot(0)
        assertEquals(0, s.cells[index(1, 6)])
        assertEquals(2, s.cells[index(7, 0)])
        assertEquals(0, s.cells[index(2, 5)])
        assertEquals(0, s.cells[index(4, 3)])
        assertEquals(0, s.cells[index(6, 1)])
        assertEquals(-1, s.cells[index(7, 4)])
        assertEquals(1, s.cells[index(8, 5)])
        assertEquals(-1, s.cells[index(0, 1)])
        assertEquals(1, s.turn)
        assertTrue(g.snapshot(1).legal.any { it.type == "move" })
    }

    @Test fun capturedPiecesStayUntilTheMoveEndsAndCannotBeTakenTwice() {
        val g = position(
            'w' to (2 to 3), 'b' to (1 to 4), 'b' to (1 to 6), 'b' to (9 to 2)
        )
        val moves = g.snapshot(0).legal.filter { it.type == "move" }
        assertEquals(1, moves.size)
        assertEquals(listOf(index(2, 3), index(0, 5), index(2, 7)), moves.single().values)
        assertTrue(moves.none { it.values.size > 3 })
        assertTrue(play(g, index(2, 3), index(0, 5), index(2, 7)))
        val s = g.snapshot(0)
        assertEquals(0, s.cells[index(2, 3)])
        assertEquals(1, s.cells[index(2, 7)])
        assertEquals(0, s.cells[index(1, 4)])
        assertEquals(0, s.cells[index(1, 6)])
        assertEquals(-1, s.cells[index(9, 2)])
        assertEquals(1, s.turn)
    }

    @Test fun flyingKingChoosesAnyLandingAndMustContinueTheWholeCapture() {
        val g = position(
            'W' to (0 to 9), 'b' to (2 to 7), 'b' to (6 to 3), 'b' to (6 to 7)
        )
        val busy = g.snapshot(0).legal.filter { it.type == "move" && it.values.first() == index(0, 9) }
        assertEquals(11, busy.size)
        assertTrue(busy.all { it.values.size == 3 })
        assertTrue(GameAction("move", listOf(index(0, 9), index(4, 5), index(9, 0))) in busy)
        assertTrue(GameAction("move", listOf(index(0, 9), index(4, 5), index(7, 8))) in busy)
        assertTrue(busy.none { it.values.size == 2 })
        assertTrue(play(g, index(0, 9), index(4, 5), index(9, 0)))
        val s = g.snapshot(0)
        assertEquals(2, s.cells[index(9, 0)])
        assertEquals(0, s.cells[index(0, 9)])
        assertEquals(0, s.cells[index(2, 7)])
        assertEquals(0, s.cells[index(6, 3)])
        assertEquals(-1, s.cells[index(6, 7)])
    }

    @Test fun flyingKingCannotJumpTwoAdjacentPiecesOrPassOverOne() {
        val blocked = position('W' to (0 to 9), 'b' to (3 to 6), 'b' to (4 to 5), 'b' to (6 to 7))
        val actions = blocked.snapshot(0).legal.filter { it.type == "move" && it.values.first() == index(0, 9) }
        assertEquals(2, actions.size)
        assertTrue(GameAction("move", listOf(index(0, 9), index(1, 8))) in actions)
        assertTrue(actions.none { it.values.size > 2 })

        val single = position('W' to (0 to 9), 'b' to (3 to 6), 'b' to (6 to 7))
        val actions2 = single.snapshot(0).legal.filter { it.type == "move" }
        assertEquals(2, actions2.size)
        assertTrue(GameAction("move", listOf(index(0, 9), index(4, 5), index(7, 8))) in actions2)
        assertTrue(GameAction("move", listOf(index(0, 9), index(4, 5), index(8, 9))) in actions2)
        assertTrue(GameAction("move", listOf(index(0, 9), index(1, 8))) !in actions2)
        assertTrue(GameAction("move", listOf(index(0, 9), index(5, 4))) !in actions2)
    }

    @Test fun manReachesLastRowDuringCaptureAndPromotesOnlyWhenItStopsThere() {
        val g = position('w' to (5 to 2), 'b' to (6 to 1), 'b' to (8 to 1), 'b' to (2 to 7))
        val moves = g.snapshot(0).legal.filter { it.type == "move" }
        assertEquals(1, moves.size)
        assertEquals(listOf(index(5, 2), index(7, 0), index(9, 2)), moves.single().values)
        assertTrue(moves.none { it.values == listOf(index(5, 2), index(7, 0)) })
        assertTrue(play(g, index(5, 2), index(7, 0), index(9, 2)))
        val s = g.snapshot(0)
        assertEquals(1, s.cells[index(9, 2)])
        assertEquals(0, s.cells[index(6, 1)])
        assertEquals(0, s.cells[index(8, 1)])

        val promoting = position('w' to (4 to 1), 'b' to (8 to 7))
        assertTrue(play(promoting, index(4, 1), index(3, 0)))
        assertEquals(2, promoting.snapshot(0).cells[index(3, 0)])
        assertEquals(1, promoting.snapshot(0).turn)
    }

    @Test fun menCaptureForwardsAndBackwardsAndBothMaximumPathsAreAllowed() {
        val backward = position('b' to (4 to 3), 'w' to (3 to 2), 'w' to (8 to 7), turn = 'b')
        assertTrue(GameAction("move", listOf(index(4, 3), index(2, 1))) in backward.snapshot(1).legal)
        assertTrue(play(backward, index(4, 3), index(2, 1)))
        val afterBackward = backward.snapshot(0)
        assertEquals(0, afterBackward.cells[index(3, 2)])
        assertEquals(-1, afterBackward.cells[index(2, 1)])
        assertEquals(0, afterBackward.turn)
        assertTrue(afterBackward.legal.any { it.type == "move" })

        val fork = position('w' to (4 to 3), 'b' to (3 to 4), 'b' to (5 to 4), 'b' to (0 to 1))
        val options = fork.snapshot(0).legal.filter { it.type == "move" }
        assertEquals(2, options.size)
        assertTrue(GameAction("move", listOf(index(4, 3), index(2, 5))) in options)
        assertTrue(GameAction("move", listOf(index(4, 3), index(6, 5))) in options)
        assertTrue(play(fork, index(4, 3), index(2, 5)))
        assertEquals(0, fork.snapshot(0).cells[index(3, 4)])
        assertEquals(1, fork.snapshot(0).cells[index(2, 5)])
    }

    @Test fun blockedPlayerLosesImmediatelyAndCapturingAllPiecesWins() {
        val blocked = position('b' to (1 to 0), 'w' to (0 to 1), 'w' to (2 to 1), 'w' to (3 to 2), turn = 'b')
        val blockedSnapshot = blocked.snapshot(0)
        assertEquals("finished", blockedSnapshot.phase)
        assertEquals(0, blockedSnapshot.winner)
        assertEquals("blocked", blockedSnapshot.extra["result"])

        val sweep = position('w' to (1 to 6), 'b' to (2 to 5), 'b' to (4 to 3), 'b' to (6 to 1))
        assertTrue(play(sweep, index(1, 6), index(3, 4), index(5, 2), index(7, 0)))
        val s = sweep.snapshot(0)
        assertEquals("finished", s.phase)
        assertEquals(0, s.winner)
        assertEquals("no_pieces", s.extra["result"])
    }

    @Test fun threefoldRepetitionDraws() {
        val g = position('W' to (1 to 0), 'B' to (8 to 1))
        val moves = listOf(
            index(1, 0) to index(0, 1), index(8, 1) to index(9, 0),
            index(0, 1) to index(1, 0), index(9, 0) to index(8, 1)
        )
        repeat(1) {
            moves.forEachIndexed { ply, (from, to) ->
                assertTrue(g.apply(ply % 2, GameAction("move", listOf(from, to))))
            }
        }
        assertEquals("playing", g.snapshot(0).phase)
        moves.forEachIndexed { ply, (from, to) ->
            assertTrue(g.apply(ply % 2, GameAction("move", listOf(from, to))))
        }
        val s = g.snapshot(0)
        assertEquals("finished", s.phase)
        assertEquals(-1, s.winner)
        assertEquals("threefold_repetition", s.extra["result"])
    }

    @Test fun onlyKingMovesForFiftyPliesDraws() {
        val g = position('W' to (4 to 1), 'B' to (0 to 1))
        val cycle = listOf(index(4, 1), index(5, 0), index(6, 1), index(7, 0), index(8, 1), index(9, 0), index(6, 3))
        fun moveAt(ply: Int): Pair<Int, Int> = if (ply % 2 == 0) {
            val k = ply / 2
            cycle[k % 7] to cycle[(k + 1) % 7]
        } else {
            val k = ply / 2
            if (k % 2 == 0) index(0, 1) to index(1, 0) else index(1, 0) to index(0, 1)
        }
        for (ply in 0 until 49) {
            val (from, to) = moveAt(ply)
            assertTrue(g.apply(ply % 2, GameAction("move", listOf(from, to))), "ply $ply")
        }
        assertEquals("playing", g.snapshot(0).phase)
        val (from, to) = moveAt(49)
        assertTrue(g.apply(1, GameAction("move", listOf(from, to))))
        val s = g.snapshot(0)
        assertEquals("finished", s.phase)
        assertEquals(-1, s.winner)
        assertEquals("king_moves", s.extra["result"])
    }

    @Test fun malformedSetupRejectedAtBoundary() {
        val nine = (0 until 9).joinToString("/") { ".".repeat(10) }
        assertFailsWith<IllegalArgumentException> { DraughtsGame(GameConfig(options = mapOf("setup" to nine))) }
        val badRow = (0 until 10).joinToString("/") { if (it == 5) ".".repeat(9) else ".".repeat(10) }
        assertFailsWith<IllegalArgumentException> { DraughtsGame(GameConfig(options = mapOf("setup" to badRow))) }
        val badChar = (0 until 10).joinToString("/") { if (it == 5) "x".repeat(10) else ".".repeat(10) }
        assertFailsWith<IllegalArgumentException> { DraughtsGame(GameConfig(options = mapOf("setup" to badChar))) }
        val light = (0 until 10).joinToString("/") { if (it == 0) "w" + ".".repeat(9) else ".".repeat(10) }
        assertFailsWith<IllegalArgumentException> { DraughtsGame(GameConfig(options = mapOf("setup" to light))) }
        assertFailsWith<IllegalArgumentException> {
            DraughtsGame(GameConfig(options = mapOf("setup" to (0 until 10).joinToString("/") { ".".repeat(10) }, "turn" to "x")))
        }
    }

    @Test fun illegalActionsAreAtomicAndSpectatorsCannotAct() {
        val g = DraughtsGame(GameConfig())
        val before = g.snapshot(0)
        for (action in listOf(
            GameAction("move"), GameAction("move", listOf(index(1, 6))), GameAction("move", listOf(999, 0)),
            GameAction("move", listOf(index(1, 6), index(0, 5), index(1, 6))), GameAction("move", listOf(index(0, 1), index(0, 5))),
            GameAction("move", listOf(index(1, 6), index(0, 6))), GameAction("unknown"), GameAction("resign", listOf(0))
        )) {
            assertFalse(g.apply(0, action))
            assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("move", listOf(index(1, 6), index(0, 5)))))
        assertTrue(play(g, index(1, 6), index(0, 5)))
        val occupied = g.snapshot(0)
        assertFalse(g.apply(1, GameAction("move", listOf(index(1, 6), index(0, 5)))))
        assertEquals(occupied, g.snapshot(0))
        assertTrue(g.apply(1, GameAction("resign")))
        assertEquals(0, g.snapshot(0).winner)
        assertEquals("resignation", g.snapshot(0).extra["result"])
    }

    @Test fun botsAreDeterministicLegalAndTakeForcedCaptures() {
        val forced = position(
            'w' to (1 to 6), 'w' to (8 to 5),
            'b' to (2 to 5), 'b' to (4 to 3), 'b' to (6 to 1), 'b' to (7 to 4), 'b' to (0 to 1)
        )
        for (difficulty in 0..2) {
            assertEquals(GameAction("move", listOf(index(1, 6), index(3, 4), index(5, 2), index(7, 0))), forced.botAction(0, difficulty))
        }
        assertNull(forced.botAction(1, 2))

        val g = DraughtsGame(GameConfig(seed = 4))
        val twin = DraughtsGame(GameConfig(seed = 88))
        assertEquals(g.botAction(0, 1), twin.botAction(0, 1))
        var plies = 0
        while (g.snapshot(-1).phase != "finished" && plies < 60) {
            val seat = g.snapshot(-1).turn
            val before = g.snapshot(seat)
            val action = assertNotNull(g.botAction(seat, 2))
            assertTrue(action in before.legal)
            assertTrue(g.apply(seat, action))
            plies++
        }
        if (g.snapshot(-1).phase != "finished") assertTrue(g.apply(g.snapshot(-1).turn, GameAction("resign")))
        assertEquals("finished", g.snapshot(-1).phase)
    }
}
