package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.math.abs
import kotlin.test.*

class XiangqiGameTest {
    private fun position(vararg pieces: Pair<Int, Int>, turn: Int = 0): XiangqiGame {
        val board = IntArray(90)
        board[4] = -7; board[85] = 7; board[49] = 1
        pieces.forEach { (square, piece) -> board[square] = piece }
        val symbols = " PNBRCAK"
        val fen = (0..9).joinToString("/") { row ->
            buildString {
                var empty = 0
                for (col in 0..8) {
                    val p = board[row * 9 + col]
                    if (p == 0) empty++ else {
                        if (empty > 0) append(empty)
                        empty = 0
                        val ch = symbols[abs(p)]
                        append(if (p > 0) ch else ch.lowercaseChar())
                    }
                }
                if (empty > 0) append(empty)
            }
        }
        return XiangqiGame(GameConfig(options = mapOf("fen" to "$fen ${if (turn == 0) "w" else "b"}")))
    }
    private fun move(g: XiangqiGame, from: Int, to: Int) = g.apply(g.snapshot(-1).turn, GameAction("move", listOf(from, to)))

    @Test fun initialBoardAndLegalMoveCounts() {
        val g = XiangqiGame(GameConfig(players = 3))
        val s = g.snapshot(0)
        assertEquals(2, g.players)
        assertEquals(9, s.width); assertEquals(10, s.height)
        assertEquals(90, s.cells.size)
        assertEquals(44, s.legal.count { it.type == "move" })
        assertEquals(7, s.cells[85]); assertEquals(-7, s.cells[4])
        assertTrue(g.snapshot(-1).legal.isEmpty()); assertTrue(g.snapshot(1).legal.isEmpty())
    }

    @Test fun secondPlyMatchesReferenceAndEachGeneratedMoveIsApplicable() {
        val initialMoves = XiangqiGame(GameConfig()).snapshot(0).legal
        var replies = 0
        for (action in initialMoves) {
            val g = XiangqiGame(GameConfig())
            assertTrue(g.apply(0, action))
            replies += g.snapshot(1).legal.size
        }
        assertEquals(1920, replies)
    }

    @Test fun checkmateIsLossAndBotsAreDeterministic() {
        val g = position(85 to 0, 84 to 7, 49 to 0, 66 to -4, 77 to -4)
        assertEquals(1, g.snapshot(0).winner)
        assertEquals("checkmate", g.snapshot(0).extra["result"])
        for (difficulty in 0..2) {
            val a = XiangqiGame(GameConfig(seed = 3))
            val b = XiangqiGame(GameConfig(seed = 99))
            assertEquals(a.botAction(0, difficulty), b.botAction(0, difficulty))
        }
    }

    @Test fun horseLegAndElephantEyeAndRiver() {
        assertFalse(move(position(82 to 2, 73 to 1), 82, 65))
        assertTrue(move(position(82 to 2), 82, 65))
        assertFalse(move(position(83 to 3, 75 to 1), 83, 67))
        assertTrue(move(position(83 to 3), 83, 67))
        assertFalse(move(position(47 to 3), 47, 31))
    }

    @Test fun palacePawnDirectionAndCrossing() {
        assertFalse(move(position(84 to 6), 84, 74))
        assertTrue(move(position(84 to 6), 84, 76))
        assertFalse(move(position(85 to 0, 66 to 7), 66, 65))
        assertFalse(move(position(54 to 1), 54, 55))
        assertFalse(move(position(54 to 1), 54, 63))
        assertTrue(move(position(36 to 1), 36, 37))
        assertTrue(move(position(54 to 1), 54, 45))
    }

    @Test fun cannonNeedsExactlyOneScreenToCapture() {
        assertFalse(move(position(81 to 5, 0 to -4), 81, 0))
        assertTrue(move(position(81 to 5, 0 to -4, 45 to 1), 81, 0))
        assertFalse(move(position(81 to 5, 0 to -4, 45 to 1, 27 to -1), 81, 0))
        assertFalse(move(position(81 to 5, 45 to 1), 81, 27))
        assertTrue(move(position(81 to 5), 81, 27))
    }

    @Test fun flyingGeneralsAndPinnedPieceCannotMoveAway() {
        val g = position(49 to 5)
        assertFalse(move(g, 49, 48))
        assertFalse(move(position(49 to 0, 76 to 4, 40 to -4, 4 to 0, 3 to -7), 76, 75))
        assertTrue(move(position(49 to 5), 49, 40))
    }

    @Test fun stalemateIsLossNotDraw() {
        val g = position(85 to 0, 84 to 7, 49 to 0, 67 to -4, 77 to -4, turn = 0)
        assertEquals("finished", g.snapshot(0).phase)
        assertEquals(1, g.snapshot(0).winner)
        assertEquals("stalemate", g.snapshot(0).extra["result"])
    }

    @Test fun repeatedQuietPositionDraws() {
        val g = XiangqiGame(GameConfig())
        repeat(2) {
            assertTrue(move(g, 82, 65)); assertTrue(move(g, 1, 20))
            assertTrue(move(g, 65, 82)); assertTrue(move(g, 20, 1))
        }
        assertEquals(-1, g.snapshot(0).winner)
        assertEquals("threefold_repetition", g.snapshot(0).extra["result"])
    }

    @Test fun unilateralPerpetualCheckLosesInsteadOfClaimingDraw() {
        val g = position(12 to 4)
        assertTrue(move(g, 12, 13))
        repeat(2) {
            assertTrue(move(g, 4, 5)); assertTrue(move(g, 13, 14))
            assertTrue(move(g, 5, 4)); assertTrue(move(g, 14, 13))
        }
        assertEquals(1, g.snapshot(0).winner)
        assertEquals("perpetual_check", g.snapshot(0).extra["result"])
        assertTrue(g.snapshot(0).extra.getValue("rules").contains("娱乐"))
    }

    @Test fun rejectsMalformedAndOutOfTurnActionsWithoutMutation() {
        val g = XiangqiGame(GameConfig())
        val before = g.snapshot(0)
        for (a in listOf(GameAction("move"), GameAction("move", listOf(90, 0)), GameAction("move", listOf(82, 65, 5)),
            GameAction("move", listOf(85, 4)), GameAction("resign", listOf(0)))) {
            assertFalse(g.apply(0, a)); assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("move", listOf(1, 20))))
        assertNull(g.botAction(1, 2))
    }

    @Test fun botsAtEveryDifficultyPlayLegalDeterministicGamesWithinNodeBudget() {
        repeat(3) { difficulty ->
            val g = XiangqiGame(GameConfig(difficulty = difficulty))
            var plies = 0
            while (g.snapshot(-1).phase != "finished" && plies < 100) {
                val s = g.snapshot(-1)
                val before = g.snapshot(s.turn)
                val action = assertNotNull(g.botAction(s.turn, difficulty))
                assertEquals(before, g.snapshot(s.turn))
                assertTrue(action in before.legal)
                assertTrue(g.apply(s.turn, action))
                plies++
            }
            if (g.snapshot(-1).phase != "finished") assertTrue(g.apply(g.snapshot(-1).turn, GameAction("resign")))
            assertEquals("finished", g.snapshot(-1).phase)
        }
    }
}
