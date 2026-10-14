package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuizGamesTest {
    @Test fun reactionRejectsEarlyAndDuplicateTapsAndRanksEqualHostTimesFairly() {
        val game = ReactionGame(GameConfig(players = 3, seed = 83))
        assertEquals("waiting", game.snapshot(0).phase)
        assertEquals("", game.snapshot(0).extra["countdown"])
        assertTrue(game.apply(2, GameAction("tap")))
        assertFalse(game.apply(2, GameAction("tap")))
        repeat(600) { if (game.snapshot(0).phase == "waiting") game.tick(10) }
        assertEquals("signal", game.snapshot(0).phase)
        assertTrue(game.apply(1, GameAction("tap")))
        assertTrue(game.apply(0, GameAction("tap")))
        assertEquals(listOf(3, 3, 0), game.snapshot(0).scores)
        assertEquals("result", game.snapshot(0).phase)
        assertFalse(game.apply(0, GameAction("tap")))
    }

    @Test fun reactionCompletesTenRoundsAndFreezesAtFinish() {
        val game = ReactionGame(GameConfig(seed = 9))
        repeat(10) { round ->
            assertEquals((round + 1).toString(), game.snapshot(0).extra["round"])
            repeat(600) { if (game.snapshot(0).phase == "waiting") game.tick(10) }
            game.apply(0, GameAction("tap"))
            game.tick(100)
            game.apply(1, GameAction("tap"))
            assertEquals(listOf((round + 1) * 2, round + 1), game.snapshot(0).scores)
            if (round < 9) game.tick(800)
        }
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals(0, game.snapshot(0).winner)
        val end = game.snapshot(0)
        assertFalse(game.apply(0, GameAction("tap")))
        game.tick(Int.MAX_VALUE)
        assertEquals(end, game.snapshot(0))
        val unattended = ReactionGame(GameConfig())
        unattended.tick(200000)
        assertEquals("finished", unattended.snapshot(0).phase)
        assertEquals(-1, unattended.snapshot(0).winner)
    }

    @Test fun colorRequiresInkNotWordAndOnlyOneAnswerPerQuestion() {
        playQuiz(ColorGame(GameConfig(players = 3, seed = 449))) { snapshot ->
            val ink = snapshot.extra.getValue("colorIndex").toInt()
            assertTrue(snapshot.labels[ink] != snapshot.extra.getValue("question"))
            ink
        }
    }

    @Test fun mathAnswersAreUniqueAndComputedFromVisibleQuestion() {
        playQuiz(MathGame(GameConfig(players = 3, seed = 449))) { snapshot ->
            assertEquals(4, snapshot.labels.toSet().size)
            val parts = snapshot.extra.getValue("question").split(" ")
            val a = parts[0].toInt()
            val b = parts[2].toInt()
            val result = when (parts[1]) {
                "+" -> a + b
                "−" -> a - b
                "×" -> a * b
                "÷" -> a / b
                else -> error("Unexpected operator")
            }
            snapshot.labels.indexOf(result.toString()).also { assertTrue(it in 0..3) }
        }
    }

    private fun playQuiz(game: GameEngine, correctIndex: (GameSnapshot) -> Int) {
        repeat(12) { round ->
            val state = game.snapshot(0)
            assertEquals((round + 1).toString(), state.extra["round"])
            val right = correctIndex(state)
            assertFalse(game.apply(0, GameAction("answer")))
            assertTrue(game.apply(0, GameAction("answer", listOf(right))))
            assertFalse(game.apply(0, GameAction("answer", listOf(right))))
            assertTrue(game.apply(1, GameAction("answer", listOf((right + 1) % 4))))
            assertTrue(game.apply(2, GameAction("answer", listOf(right))))
            assertEquals(listOf((round + 1) * 2, -(round + 1), (round + 1) * 2), game.snapshot(0).scores)
            if (round < 11) {
                assertEquals("result", game.snapshot(0).phase)
                assertFalse(game.apply(0, GameAction("answer", listOf(right))))
                game.tick(1000)
            }
        }
        assertEquals("finished", game.snapshot(0).phase)
        assertEquals(-1, game.snapshot(0).winner)
        val end = game.snapshot(0)
        game.tick(9000)
        assertEquals(end, game.snapshot(0))
    }

    @Test fun unansweredQuizzesTimeoutWithoutInventingScores() {
        for (game in listOf(ColorGame(GameConfig()), MathGame(GameConfig()))) {
            game.tick(200000)
            assertEquals("finished", game.snapshot(0).phase)
            assertEquals(listOf(0, 0), game.snapshot(0).scores)
            assertEquals(-1, game.snapshot(0).winner)
        }
    }
}
