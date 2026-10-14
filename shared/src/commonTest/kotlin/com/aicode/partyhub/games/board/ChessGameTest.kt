package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import kotlin.random.Random
import kotlin.test.*

class ChessGameTest {
    private fun game(fen: String) = ChessGame(GameConfig(options = mapOf("fen" to fen)))
    private fun sq(name: String) = (8 - name[1].digitToInt()) * 8 + (name[0] - 'a')
    private fun move(game: ChessGame, from: String, to: String, promotion: Int? = null): Boolean =
        game.apply(game.snapshot(-1).turn, GameAction("move", listOf(sq(from), sq(to)) + listOfNotNull(promotion)))
    private fun perft(game: ChessGame, depth: Int): Int {
        if (depth == 0) return 1
        val snapshot = game.snapshot(-1)
        return game.snapshot(snapshot.turn).legal.filter { it.type == "move" }.sumOf { action ->
            val next = game(snapshot.extra.getValue("fen"))
            assertTrue(next.apply(snapshot.turn, action))
            perft(next, depth - 1)
        }
    }

    @Test fun initialFenLegalMovesAndPerft() {
        val g = ChessGame(GameConfig(players = 3))
        val s = g.snapshot(0)
        assertEquals(2, g.players)
        assertEquals(8, s.width)
        assertEquals(64, s.cells.size)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", s.extra["fen"])
        assertEquals(20, s.legal.count { it.type == "move" })
        assertTrue(g.snapshot(1).legal.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())
        assertNull(g.botAction(0, 2))
        assertEquals(400, perft(g, 2))
        assertEquals(8902, perft(g, 3))
    }

    @Test fun castlingPerftReference() {
        val g = game("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(g, 1))
        assertEquals(2039, perft(g, 2))
    }

    @Test fun illegalActionsAreAtomicAndSpectatorsCannotAct() {
        val g = ChessGame(GameConfig())
        val before = g.snapshot(0)
        for (a in listOf(GameAction("move"), GameAction("move", listOf(-1, 4)),
            GameAction("move", listOf(52, 36, 5)), GameAction("move", listOf(52, 20)),
            GameAction("move", listOf(52, 36, 5, 9)), GameAction("resign", listOf(1)), GameAction("unknown"))) {
            assertFalse(g.apply(0, a))
            assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("move", listOf(12, 28))))
    }

    @Test fun foolsMateAndUciHistory() {
        val g = ChessGame(GameConfig())
        assertTrue(move(g, "f2", "f3"))
        assertTrue(move(g, "e7", "e5"))
        assertTrue(move(g, "g2", "g4"))
        assertTrue(move(g, "d8", "h4"))
        val s = g.snapshot(0)
        assertEquals("finished", s.phase)
        assertEquals(1, s.winner)
        assertEquals("checkmate", s.extra["result"])
        assertEquals("f2f3 e7e5 g2g4 d8h4", s.extra["uciHistory"])
        assertEquals(s.extra["uciHistory"], s.extra["history"])
        assertFalse(g.apply(0, GameAction("resign")))
    }

    @Test fun castleThroughCheckAndLostRights() {
        val g = game("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertTrue(move(g, "e1", "g1"))
        assertEquals(4, g.snapshot(0).cells[sq("f1")])
        assertEquals("kq", g.snapshot(0).extra.getValue("fen").split(' ')[2])
        val checked = game("4kr2/8/8/8/8/8/8/4K2R w K - 0 1")
        assertFalse(move(checked, "e1", "g1"))
        val inCheck = game("k3r3/8/8/8/8/8/8/4K2R w K - 0 1")
        assertFalse(move(inCheck, "e1", "g1"))
        val captured = game("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertTrue(move(captured, "a1", "a8"))
        assertEquals("Kk", captured.snapshot(0).extra.getValue("fen").split(' ')[2])
    }

    @Test fun enPassantOnlyImmediatelyAndNotIfExposingKing() {
        val g = game("4k3/3p4/8/4P3/8/8/8/4K3 b - - 0 1")
        assertTrue(move(g, "d7", "d5"))
        assertTrue(move(g, "e5", "d6"))
        assertEquals(0, g.snapshot(0).cells[sq("d5")])
        assertEquals(1, g.snapshot(0).cells[sq("d6")])
        val pinned = game("k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        assertFalse(move(pinned, "e5", "d6"))
        val delayed = game("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        assertTrue(move(delayed, "e1", "f1"))
        assertTrue(move(delayed, "e8", "f8"))
        assertFalse(move(delayed, "e5", "d6"))
    }

    @Test fun promotionEnumeratesFourChoicesAndDefaultsToQueen() {
        for (piece in 2..5) {
            val g = game("7k/P7/8/8/8/8/8/4K3 w - - 0 1")
            assertEquals(4, g.snapshot(0).legal.count { it.type == "move" && it.values.take(2) == listOf(8, 0) })
            assertTrue(move(g, "a7", "a8", piece))
            assertEquals(piece, g.snapshot(0).cells[0])
        }
        val g = game("7k/P7/8/8/8/8/8/4K3 w - - 0 1")
        assertFalse(move(g, "a7", "a8", 6))
        assertTrue(move(g, "a7", "a8"))
        assertEquals(5, g.snapshot(0).cells[0])
    }

    @Test fun stalemateDeadMaterialAndNotMerelyUnforceableMate() {
        assertEquals("stalemate", game("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").snapshot(1).extra["result"])
        for (fen in listOf("7k/8/8/8/8/8/8/K7", "7k/8/8/8/8/8/8/KN6", "7k/8/8/8/8/8/8/KB6",
            "7k/8/8/8/5b2/8/8/K1B5")) {
            assertEquals("insufficient_material", game("$fen w - - 0 1").snapshot(0).extra["result"])
        }
        assertEquals("playing", game("7k/8/8/8/8/8/8/KNN5 w - - 0 1").snapshot(0).phase)
    }

    @Test fun threefoldClaimAndFivefoldAutomaticDraw() {
        val g = ChessGame(GameConfig())
        fun cycle() {
            assertTrue(move(g, "g1", "f3")); assertTrue(move(g, "g8", "f6"))
            assertTrue(move(g, "f3", "g1")); assertTrue(move(g, "f6", "g8"))
        }
        cycle(); cycle()
        assertEquals("playing", g.snapshot(0).phase)
        assertTrue(GameAction("claim_draw") in g.snapshot(0).legal)
        assertTrue(g.apply(0, GameAction("claim_draw")))
        assertEquals(-1, g.snapshot(0).winner)
        val auto = ChessGame(GameConfig())
        repeat(4) {
            move(auto, "g1", "f3"); move(auto, "g8", "f6"); move(auto, "f3", "g1"); move(auto, "f6", "g8")
        }
        assertEquals("fivefold_repetition", auto.snapshot(0).extra["result"])
    }

    @Test fun prospectiveClaimDoesNotExecuteMove() {
        val g = ChessGame(GameConfig())
        repeat(2) {
            move(g, "g1", "f3"); move(g, "g8", "f6"); move(g, "f3", "g1")
            if (it == 0) move(g, "f6", "g8")
        }
        val before = g.snapshot(1).cells
        assertTrue(g.apply(1, GameAction("claim_draw", listOf(sq("f6"), sq("g8")))))
        assertEquals(before, g.snapshot(1).cells)
        assertEquals(-1, g.snapshot(1).winner)
    }

    @Test fun fiftyAndSeventyFiveMoveRulesAndMatePrecedence() {
        val fifty = game("7k/8/8/8/8/8/8/KR6 w - - 99 50")
        assertTrue(fifty.apply(0, GameAction("claim_draw", listOf(sq("b1"), sq("b2")))))
        assertEquals("fifty_move", fifty.snapshot(0).extra["result"])
        val auto = game("7k/8/8/8/8/8/8/KR6 w - - 149 75")
        assertTrue(move(auto, "b1", "b2"))
        assertEquals("seventy_five_move", auto.snapshot(0).extra["result"])
        val mate = game("7k/5Q2/6K1/8/8/8/8/8 w - - 149 75")
        assertTrue(move(mate, "f7", "g7"))
        assertEquals("checkmate", mate.snapshot(0).extra["result"])
    }

    @Test fun multipleRealGamesAlwaysEmitApplicableMovesAndRoundTripFen() {
        repeat(6) { seed ->
            val g = ChessGame(GameConfig())
            val random = Random(seed)
            var plies = 0
            while (g.snapshot(-1).phase != "finished" && plies < 160) {
                val s = g.snapshot(-1)
                val action = g.snapshot(s.turn).legal.filter { it.type == "move" }.random(random)
                assertTrue(g.apply(s.turn, action))
                val after = g.snapshot(-1)
                assertEquals(after.cells, game(after.extra.getValue("fen")).snapshot(-1).cells)
                plies++
            }
            if (g.snapshot(-1).phase != "finished") assertTrue(g.apply(g.snapshot(-1).turn, GameAction("resign")))
            assertEquals("finished", g.snapshot(-1).phase)
        }
    }

    @Test fun repetitionIgnoresUnusableEnPassantButIncludesCastlingRights() {
        for (fen in listOf("4k1n1/8/8/4p3/8/8/8/4K1N1 w - e6 0 2",
            "k3r1n1/8/8/3pP3/8/8/8/4K1N1 w - d6 0 2")) {
            val g = game(fen)
            repeat(2) {
                assertTrue(move(g, "g1", "f3")); assertTrue(move(g, "g8", "f6"))
                assertTrue(move(g, "f3", "g1")); assertTrue(move(g, "f6", "g8"))
            }
            assertTrue(g.apply(0, GameAction("claim_draw")))
        }
        val rights = game("4k1n1/8/8/8/8/8/8/4K2R w K - 0 1")
        repeat(2) {
            assertTrue(move(rights, "h1", "h2")); assertTrue(move(rights, "g8", "f6"))
            assertTrue(move(rights, "h2", "h1")); assertTrue(move(rights, "f6", "g8"))
        }
        assertFalse(rights.apply(0, GameAction("claim_draw")))
    }

    @Test fun blackPromotionAndPawnMovesResetHalfmoveClock() {
        val black = game("4k3/8/8/8/8/8/7p/K7 b - - 149 75")
        assertTrue(move(black, "h2", "h1", 4))
        assertEquals(-4, black.snapshot(0).cells[sq("h1")])
        assertEquals("h2h1r", black.snapshot(0).extra["uciHistory"])
        assertEquals("0", black.snapshot(0).extra.getValue("fen").split(' ')[4])
        assertEquals("playing", black.snapshot(0).phase)
    }

    @Test fun enPassantAndPromotionPerftReferences() {
        val endgame = game("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, perft(endgame, 1)); assertEquals(191, perft(endgame, 2)); assertEquals(2812, perft(endgame, 3))
        val promotion = game("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, perft(promotion, 1)); assertEquals(264, perft(promotion, 2))
    }

    @Test fun malformedFenRejectedAtBoundary() {
        for (fen in listOf("bad", "8/8/8/8/8/8/8/8 w - - 0 1", "8/8/8/8/8/8/8/K6k w Z - 0 1")) {
            assertFailsWith<IllegalArgumentException> { game(fen) }
        }
    }
}
