package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameSnapshot
import kotlin.test.*

class ChineseCheckersGameTest {
    private fun index(x: Int, y: Int) = y * 17 + x
    private fun game(vararg pieces: Pair<Int, Int>, players: Int = 2, turn: Int = 0) = ChineseCheckersGame(
        GameConfig(players = players, options = mapOf(
            "setup" to pieces.joinToString(",") { (cell, value) -> "$cell:$value" },
            "turn" to "$turn"
        ))
    )
    private fun move(g: ChineseCheckersGame, vararg cells: Int) = g.apply(g.snapshot(-1).turn, GameAction("move", cells.toList()))
    private fun camps(s: GameSnapshot) = s.extra.getValue("camps").split(";").map { group -> group.split(",").map { it.toInt() } }

    @Test fun initialStarBoardCampsAndSeating() {
        val g = ChineseCheckersGame(GameConfig())
        val s = g.snapshot(0)
        assertEquals("chinese_checkers", g.id)
        assertEquals(2, g.players)
        assertEquals(17, s.width)
        assertEquals(17, s.height)
        assertEquals(289, s.cells.size)
        assertEquals(168, s.cells.count { it == -1 })
        assertEquals(10, s.cells.count { it == 1 })
        assertEquals(10, s.cells.count { it == 2 })
        assertEquals(listOf(0, 0), s.scores)
        assertEquals(2, s.labels.size)
        val camps = camps(s)
        assertEquals(6, camps.size)
        assertEquals(60, camps.flatten().distinct().size)
        assertTrue(camps.all { it.size == 10 })
        assertTrue(camps.flatten().all { s.cells[it] != -1 })
        assertTrue(camps[0].all { s.cells[it] == 1 })
        assertTrue(camps[3].all { s.cells[it] == 2 })
        assertTrue((listOf(1, 2, 4, 5)).all { k -> camps[k].all { s.cells[it] == 0 } })
        assertTrue(s.extra.getValue("pieceEncoding").contains("-1"))
        assertEquals(11, s.legal.count { it.type == "move" })
        assertTrue(GameAction("resign") in s.legal)
        assertTrue(g.snapshot(1).legal.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())

        val three = ChineseCheckersGame(GameConfig(players = 3))
        val s3 = three.snapshot(0)
        assertEquals(3, three.players)
        assertEquals(3, s3.labels.size)
        val camps3 = camps(s3)
        assertTrue(camps3[0].all { s3.cells[it] == 1 })
        assertTrue(camps3[2].all { s3.cells[it] == 2 })
        assertTrue(camps3[4].all { s3.cells[it] == 3 })
        assertTrue(listOf(1, 3, 5).all { k -> camps3[k].all { s3.cells[it] == 0 } })
        assertTrue(s3.legal.count { it.type == "move" } > 0)
    }

    @Test fun singleStepsCycleTurnsAndRespectTheActingSeat() {
        val g = game(144 to 1, 8 to 2)
        val s = g.snapshot(0)
        val moves = s.legal.filter { it.type == "move" }
        assertEquals(6, moves.size)
        assertTrue(moves.all { it.values.size == 2 && it.values[0] == 144 })
        assertTrue(moves.any { it.values == listOf(144, 145) })
        assertTrue(moves.any { it.values == listOf(144, 128) })
        assertTrue(move(g, 144, 145))
        assertEquals(0, g.snapshot(0).cells[144])
        assertEquals(1, g.snapshot(0).cells[145])
        assertEquals(1, g.snapshot(0).turn)
        assertTrue(g.snapshot(0).legal.isEmpty())
        val reply = g.snapshot(1).legal.first { it.type == "move" && it.values.first() != 145 }
        assertTrue(g.apply(1, reply))
        assertEquals(0, g.snapshot(0).turn)
    }

    @Test fun jumpsSkipPegsWithoutCapturingAndChainsMayStopAnywhere() {
        val g = game(144 to 1, 145 to 2, 147 to 2, 8 to 2)
        val s = g.snapshot(0)
        val from = s.legal.filter { it.type == "move" && it.values.first() == 144 }
        assertEquals(7, from.size)
        assertTrue(GameAction("move", listOf(144, 146)) in from)
        assertTrue(GameAction("move", listOf(144, 146, 148)) in from)
        assertFalse(GameAction("move", listOf(144, 148)) in from)
        assertTrue(from.none { it.values.drop(1).contains(144) })

        val chained = game(144 to 1, 145 to 2, 147 to 2, 8 to 2)
        assertTrue(move(chained, 144, 146, 148))
        val after = chained.snapshot(0)
        assertEquals(1, after.cells[148])
        assertEquals(0, after.cells[144])
        assertEquals(2, after.cells[145])
        assertEquals(2, after.cells[147])
        assertEquals(1, after.turn)

        val stopped = game(144 to 1, 145 to 2, 147 to 2, 8 to 2)
        assertTrue(move(stopped, 144, 146))
        assertEquals(1, stopped.snapshot(0).cells[146])
        assertEquals(2, stopped.snapshot(0).cells[147])

        val diagonal = game(144 to 1, 128 to 2, 8 to 2)
        assertTrue(GameAction("move", listOf(144, 112)) in diagonal.snapshot(0).legal)

        val own = game(144 to 1, 145 to 1, 8 to 2)
        assertTrue(GameAction("move", listOf(144, 146)) in own.snapshot(0).legal)
    }

    @Test fun piecesLockedInTheTargetCampCannotMoveButCanBeJumpedOver() {
        val g = game(139 to 1, 140 to 1, 8 to 2)
        val s = g.snapshot(0)
        assertTrue(139 in camps(s)[3])
        assertTrue(s.legal.none { it.type == "move" && it.values.first() == 139 })
        assertTrue(GameAction("move", listOf(140, 138)) in s.legal)
        assertTrue(GameAction("move", listOf(140, 141)) in s.legal)
        assertTrue(move(g, 140, 141))
        assertEquals(1, g.snapshot(0).cells[141])
        assertEquals(1, g.snapshot(0).cells[139])
    }

    @Test fun fillingTheTargetCampWinsAndScoresTrackHomePieces() {
        val g = game(
            136 to 1, 137 to 1, 138 to 1, 154 to 1, 155 to 1, 156 to 1, 172 to 1, 173 to 1, 190 to 1,
            140 to 1, 8 to 2
        )
        assertTrue(GameAction("move", listOf(140, 139)) in g.snapshot(0).legal)
        assertTrue(move(g, 140, 139))
        val s = g.snapshot(0)
        assertEquals("finished", s.phase)
        assertEquals(0, s.winner)
        assertEquals("race", s.extra["result"])
        assertEquals(listOf(10, 0), s.scores)
        assertTrue(s.message.contains("获胜"))
        assertFalse(g.apply(0, GameAction("resign")))
    }

    @Test fun illegalActionsAreAtomicAndSpectatorsCannotAct() {
        val initial = ChineseCheckersGame(GameConfig())
        assertTrue(initial.apply(0, GameAction("resign")))
        assertEquals(1, initial.snapshot(0).winner)
        assertEquals("resignation", initial.snapshot(0).extra["result"])

        val g = game(144 to 1, 8 to 2)
        val before = g.snapshot(0)
        for (action in listOf(
            GameAction("move"), GameAction("move", listOf(144)), GameAction("move", listOf(144, 0)),
            GameAction("move", listOf(144, 144)), GameAction("move", listOf(999, 144)), GameAction("move", listOf(123, 144)),
            GameAction("move", listOf(144, 145), "x"), GameAction("unknown"), GameAction("resign", listOf(0))
        )) {
            assertFalse(g.apply(0, action))
            assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign")))
        assertFalse(g.apply(2, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("move", listOf(144, 145))))
        assertTrue(move(g, 144, 145))
        val occupied = g.snapshot(0)
        assertFalse(g.apply(1, GameAction("move", listOf(144, 145))))
        assertEquals(occupied, g.snapshot(0))
    }

    @Test fun botsAreDeterministicLegalAndSupportThreePlayers() {
        val g = ChineseCheckersGame(GameConfig(seed = 5))
        for (difficulty in 0..2) {
            val action = assertNotNull(g.botAction(0, difficulty))
            assertTrue(action in g.snapshot(0).legal)
        }
        assertNull(g.botAction(1, 1))
        val twin = ChineseCheckersGame(GameConfig(seed = 9))
        assertEquals(g.botAction(0, 2), twin.botAction(0, 2))

        var plies = 0
        val loop = ChineseCheckersGame(GameConfig(seed = 5))
        while (loop.snapshot(-1).phase != "finished" && plies < 40) {
            val seat = loop.snapshot(-1).turn
            val before = loop.snapshot(seat)
            val action = assertNotNull(loop.botAction(seat, 1))
            assertTrue(action in before.legal)
            assertTrue(loop.apply(seat, action))
            plies++
        }
        if (loop.snapshot(-1).phase != "finished") assertTrue(loop.apply(loop.snapshot(-1).turn, GameAction("resign")))
        assertEquals("finished", loop.snapshot(-1).phase)

        val three = ChineseCheckersGame(GameConfig(players = 3, seed = 2))
        assertEquals(3, three.players)
        val first = assertNotNull(three.botAction(0, 2))
        assertTrue(three.apply(0, first))
        assertEquals(1, three.snapshot(-1).turn)
        val second = assertNotNull(three.botAction(1, 2))
        assertTrue(second in three.snapshot(1).legal)
    }
}
