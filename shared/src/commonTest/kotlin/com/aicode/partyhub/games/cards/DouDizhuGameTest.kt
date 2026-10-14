package com.aicode.partyhub.games.cards

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameJson
import kotlinx.serialization.encodeToString
import kotlin.test.*

class DouDizhuGameTest {
    @Test fun dealsUniqueCardsAndHidesOtherHandsAndBottomUntilBiddingEnds() {
        val g = DouDizhuGame(GameConfig(seed = 938271))
        assertEquals(3, g.players)
        val hands = (0..2).map { g.snapshot(it).hand }
        assertEquals(51, hands.flatten().toSet().size)
        repeat(3) { seat ->
            val s = g.snapshot(seat)
            assertEquals(17, s.hand.size); assertEquals(listOf(17, 17, 17), s.counts)
            assertEquals("bidding", s.phase); assertEquals("", s.extra["publicBottom"])
            assertTrue(s.cells.isEmpty()); assertTrue(s.entities.isEmpty())
            assertFalse(GameJson.encodeToString(s).contains("938271"))
            assertFalse(s.extra.keys.any { it.contains("seed", true) || it.contains("deck", true) || it.contains("hands", true) })
        }
        assertTrue(g.snapshot(-1).hand.isEmpty()); assertTrue(g.snapshot(3).hand.isEmpty())
        assertTrue(g.snapshot(-1).legal.isEmpty())
        assertTrue(g.apply(0, GameAction("bid", listOf(3))))
        val s = g.snapshot(0)
        assertEquals("playing", s.phase); assertEquals(20, s.hand.size)
        assertEquals(54, (s.hand + hands[1] + hands[2]).toSet().size)
        assertEquals(3, s.extra.getValue("publicBottom").split(',').size)
        assertTrue(s.hand.containsAll(s.extra.getValue("publicBottom").split(',').map(String::toInt)))
    }

    @Test fun biddingMustIncreaseAndAllPassRotatesRedealWithFiniteLimit() {
        val g = DouDizhuGame(GameConfig())
        assertTrue(g.apply(0, GameAction("bid", listOf(1))))
        assertFalse(g.apply(1, GameAction("bid", listOf(1))))
        assertTrue(g.apply(1, GameAction("bid", listOf(2))))
        assertTrue(g.apply(2, GameAction("bid", listOf(0))))
        assertEquals(1, g.snapshot(-1).turn)
        assertEquals("1", g.snapshot(-1).extra["landlord"])
        assertEquals("2", g.snapshot(-1).extra["bid"])
        val redeal = DouDizhuGame(GameConfig(seed = 42))
        val first = redeal.snapshot(0).hand
        repeat(3) { assertTrue(redeal.apply(it, GameAction("bid", listOf(0)))) }
        assertEquals("bidding", redeal.snapshot(-1).phase)
        assertEquals(1, redeal.snapshot(-1).turn)
        assertNotEquals(first, redeal.snapshot(0).hand)
        var bids = 3
        while (redeal.snapshot(-1).phase == "bidding" && bids < 12) {
            assertTrue(redeal.apply(redeal.snapshot(-1).turn, GameAction("bid", listOf(0))))
            bids++
        }
        assertEquals("playing", redeal.snapshot(-1).phase)
    }

    @Test fun rejectsIllegalInputAndSpectatorsAndHintDoesNotMutate() {
        val g = DouDizhuGame(GameConfig())
        val before = g.snapshot(0)
        for (a in listOf(GameAction("bid"), GameAction("bid", listOf(-1)), GameAction("bid", listOf(4)),
            GameAction("bid", listOf(1, 2)), GameAction("play", listOf(0)), GameAction("pass"))) {
            assertFalse(g.apply(0, a)); assertEquals(before, g.snapshot(0))
        }
        assertFalse(g.apply(-1, GameAction("resign"))); assertFalse(g.apply(3, GameAction("resign")))
        assertFalse(g.apply(1, GameAction("bid", listOf(3))))
        assertTrue(g.apply(0, GameAction("bid", listOf(3))))
        val playing = g.snapshot(0)
        val own = playing.hand.first()
        val other = g.snapshot(1).hand.first()
        for (a in listOf(GameAction("play"), GameAction("play", listOf(own, own)), GameAction("play", listOf(other)),
            GameAction("play", listOf(-1)), GameAction("play", listOf(54)), GameAction("pass"), GameAction("hint", listOf(1)))) {
            assertFalse(g.apply(0, a)); assertEquals(playing, g.snapshot(0))
        }
        assertTrue(g.apply(0, GameAction("hint")))
        assertEquals(playing, g.snapshot(0))
        assertNotNull(g.botAction(0, 1)); assertEquals(playing, g.snapshot(0))
    }

    @Test fun twoPassesResetLeadAndSpringDoublesMultiplier() {
        val g = DouDizhuGame(GameConfig())
        assertTrue(g.apply(0, GameAction("bid", listOf(3))))
        repeat(20) { index ->
            assertTrue(g.apply(0, GameAction("play", listOf(g.snapshot(0).hand.first()))))
            if (index < 19) {
                assertTrue(g.apply(1, GameAction("pass")))
                assertTrue(g.apply(2, GameAction("pass")))
                assertEquals(0, g.snapshot(-1).turn)
                assertEquals("", g.snapshot(-1).extra["currentPattern"])
                assertEquals("", g.snapshot(-1).extra["lastCards"])
                assertFalse(g.apply(0, GameAction("pass")))
            }
        }
        val s = g.snapshot(-1)
        assertEquals("finished", s.phase); assertEquals(0, s.winner)
        assertEquals("landlord", s.extra["winningSide"])
        assertEquals("6", s.extra["multiplier"]); assertEquals("spring", s.extra["bonus"])
        assertEquals(listOf(12, -6, -6), s.scores)
        assertFalse(g.apply(0, GameAction("play", listOf(0))))
    }

    @Test fun bombsAndRocketDoubleButFourWithWingsDoesNot() {
        var testedBomb = false
        var testedRocket = false
        for (seed in 1..200) {
            val g = DouDizhuGame(GameConfig(seed = seed))
            g.apply(0, GameAction("bid", listOf(3)))
            val hand = g.snapshot(0).hand
            val bomb = hand.groupBy(CardPattern::rank).values.firstOrNull { it.size == 4 }
            val play = if (!testedRocket && hand.containsAll(listOf(52, 53))) listOf(52, 53)
                else if (!testedBomb) bomb else null
            if (play != null) {
                assertTrue(g.apply(0, GameAction("play", play)))
                assertEquals("6", g.snapshot(0).extra["multiplier"])
                if (play.size == 2) testedRocket = true else testedBomb = true
            }
            if (testedBomb && testedRocket) break
        }
        assertTrue(testedBomb); assertTrue(testedRocket)
    }

    @Test fun cancellingDuringBiddingNeverRevealsUndealtBottom() {
        val g = DouDizhuGame(GameConfig(seed = 7))
        assertTrue(g.apply(0, GameAction("bid", listOf(1))))
        assertTrue(g.apply(1, GameAction("resign")))
        assertEquals("finished", g.snapshot(-1).phase)
        assertEquals("", g.snapshot(-1).extra["publicBottom"])
        assertEquals(listOf(17, 17, 17), g.snapshot(-1).counts)
    }

    @Test fun fourWithWingsDoesNotMultiplyAndTeamResignationSettlesBothFarmers() {
        var tested = false
        for (seed in 1..200) {
            val g = DouDizhuGame(GameConfig(seed = seed))
            g.apply(0, GameAction("bid", listOf(3)))
            val hand = g.snapshot(0).hand
            val bomb = hand.groupBy(CardPattern::rank).values.firstOrNull { it.size == 4 } ?: continue
            assertTrue(g.apply(0, GameAction("play", bomb + hand.filter { it !in bomb }.take(2))))
            assertEquals("3", g.snapshot(0).extra["multiplier"])
            assertTrue(g.apply(2, GameAction("resign")))
            assertEquals(0, g.snapshot(-1).winner)
            assertEquals(listOf(6, -3, -3), g.snapshot(-1).scores)
            tested = true
            break
        }
        assertTrue(tested)
    }

    @Test fun snapshotExtrasArePublicAndHintCannotExposeOtherCardsAtAnyPhase() {
        val g = DouDizhuGame(GameConfig(seed = 18))
        val exposed = mutableSetOf<Int>()
        repeat(90) {
            val public = g.snapshot(-1)
            val bottom = public.extra.getValue("publicBottom").split(',').mapNotNull(String::toIntOrNull)
            val played = public.extra.getValue("playedCards").split(',').mapNotNull(String::toIntOrNull)
            val last = public.extra.getValue("lastCards").split(',').mapNotNull(String::toIntOrNull)
            assertEquals(exposed, played.toSet())
            assertTrue(exposed.containsAll(last))
            val inHistory = public.extra.getValue("history").split(';').filter { ":play:" in it }
                .flatMap { it.substringAfter(":play:").split(',').map(String::toInt) }
            assertEquals(exposed, inHistory.toSet())
            if (public.phase == "bidding") assertTrue(bottom.isEmpty())
            else assertEquals(3, bottom.size)
            assertEquals(setOf("viewer", "landlord", "bid", "currentPattern", "lastCards", "lastPlayer", "publicBottom",
                "multiplier", "history", "playedCards", "winningSide", "bonus", "result", "rules"), public.extra.keys)
            repeat(3) { seat ->
                val own = g.snapshot(seat)
                assertEquals(public.extra - "viewer", own.extra - "viewer")
                for (a in own.legal.filter { it.type == "play" }) assertTrue(own.hand.containsAll(a.values))
                if (seat != public.turn) {
                    assertTrue(own.legal.isEmpty())
                    assertNull(g.botAction(seat, 2))
                }
            }
            if (public.phase == "finished") return@repeat
            val action = assertNotNull(g.botAction(public.turn, 2))
            if (action.type == "play") exposed.addAll(action.values)
            assertTrue(g.apply(public.turn, action))
        }
    }

    @Test fun antiSpringAndFarmerTeamWin() {
        val g = DouDizhuGame(GameConfig(seed = 9))
        g.apply(0, GameAction("bid", listOf(1))); g.apply(1, GameAction("bid", listOf(0))); g.apply(2, GameAction("bid", listOf(0)))
        g.apply(0, GameAction("play", listOf(g.snapshot(0).hand.min())))
        val response = g.snapshot(1).hand.first { CardPattern.rank(it) > CardPattern.rank(g.snapshot(0).extra.getValue("lastCards").toInt()) }
        assertTrue(g.apply(1, GameAction("play", listOf(response))))
        while (g.snapshot(-1).phase != "finished") {
            val turn = g.snapshot(-1).turn
            assertTrue(g.apply(turn, if (turn == 1) GameAction("play", listOf(g.snapshot(1).hand.first())) else GameAction("pass")))
        }
        assertEquals("farmers", g.snapshot(-1).extra["winningSide"])
        assertEquals("anti_spring", g.snapshot(-1).extra["bonus"])
        assertEquals(listOf(-4, 2, 2), g.snapshot(-1).scores)
    }

    @Test fun manySeededGamesFinishWithOnlyLegalOwnedActionsAndConservedCards() {
        repeat(36) { seed ->
            val g = DouDizhuGame(GameConfig(seed = seed))
            val played = mutableSetOf<Int>()
            var actions = 0
            while (g.snapshot(-1).phase != "finished" && actions < 400) {
                val s = g.snapshot(-1)
                val own = g.snapshot(s.turn)
                val action = assertNotNull(g.botAction(s.turn, seed % 3))
                assertEquals(action, DouDizhuGame.chooseAction(own, seed % 3))
                assertTrue(own.legal.size <= 260)
                if (action.type == "play") {
                    assertTrue(own.hand.containsAll(action.values))
                    assertTrue(action.values.none { it in played })
                    played.addAll(action.values)
                }
                assertTrue(g.apply(s.turn, action), "$seed $action")
                val after = g.snapshot(-1)
                if (after.phase != "bidding") assertEquals(54, after.counts.sum() + played.size)
                assertTrue(after.hand.isEmpty()); assertTrue(after.legal.isEmpty())
                actions++
            }
            assertEquals("finished", g.snapshot(-1).phase, "seed=$seed actions=$actions")
            assertEquals(0, g.snapshot(-1).scores.sum())
        }
    }
}
