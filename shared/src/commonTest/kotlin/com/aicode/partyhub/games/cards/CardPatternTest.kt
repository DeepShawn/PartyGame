package com.aicode.partyhub.games.cards

import kotlin.random.Random
import kotlin.test.*

class CardPatternTest {
    private fun cards(vararg ranks: Int): List<Int> {
        val used = mutableMapOf<Int, Int>()
        return ranks.map { rank ->
            if (rank >= 16) rank + 36 else (rank - 3) * 4 + (used[rank] ?: 0).also { used[rank] = it + 1 }
        }
    }
    private fun kind(expected: CardPattern.Kind, vararg ranks: Int) {
        assertEquals(expected, assertNotNull(CardPattern.choose(cards(*ranks))).kind)
    }

    @Test fun ranksAndEveryClassicPattern() {
        assertEquals((3..15).flatMap { rank -> List(4) { rank } } + listOf(16, 17), (0..53).map(CardPattern::rank))
        kind(CardPattern.Kind.SINGLE, 17)
        kind(CardPattern.Kind.PAIR, 15, 15)
        kind(CardPattern.Kind.TRIPLE, 3, 3, 3)
        kind(CardPattern.Kind.TRIPLE_SINGLE, 4, 4, 4, 3)
        kind(CardPattern.Kind.TRIPLE_PAIR, 4, 4, 4, 3, 3)
        kind(CardPattern.Kind.STRAIGHT, 10, 11, 12, 13, 14)
        kind(CardPattern.Kind.PAIR_STRAIGHT, 3, 3, 4, 4, 5, 5)
        kind(CardPattern.Kind.AIRPLANE, 3, 3, 3, 4, 4, 4)
        kind(CardPattern.Kind.AIRPLANE_SINGLE, 3, 3, 3, 4, 4, 4, 5, 5)
        kind(CardPattern.Kind.AIRPLANE_PAIR, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6)
        kind(CardPattern.Kind.FOUR_SINGLE, 3, 3, 3, 3, 4, 4)
        kind(CardPattern.Kind.FOUR_PAIR, 3, 3, 3, 3, 4, 4, 5, 5)
        kind(CardPattern.Kind.BOMB, 15, 15, 15, 15)
        kind(CardPattern.Kind.ROCKET, 16, 17)
    }

    @Test fun rejectsDuplicateIdsInvalidIdsIllegalRunsAndIncorrectWings() {
        for (ids in listOf(emptyList(), listOf(0, 0), listOf(-1), listOf(54),
            cards(11, 12, 13, 14, 15), cards(13, 14, 15, 16, 17), cards(3, 3, 4, 4),
            cards(3, 3, 3, 4, 4, 4, 5), cards(3, 3, 3, 5, 5, 5),
            cards(3, 3, 3, 4, 4, 4, 5, 5, 5, 5), cards(3, 3, 3, 3, 4, 4, 4, 4))) {
            assertNull(CardPattern.choose(ids), ids.toString())
        }
    }

    @Test fun comparesShapeLengthAndBombHierarchy() {
        val low = assertNotNull(CardPattern.choose(cards(3, 3)))
        assertTrue(assertNotNull(CardPattern.choose(cards(4, 4))).beats(low))
        assertFalse(low.beats(low))
        assertFalse(assertNotNull(CardPattern.choose(cards(4))).beats(low))
        val bomb = assertNotNull(CardPattern.choose(cards(3, 3, 3, 3)))
        assertTrue(bomb.beats(low))
        val rocket = assertNotNull(CardPattern.choose(cards(16, 17)))
        assertTrue(rocket.beats(bomb)); assertFalse(bomb.beats(rocket)); assertFalse(rocket.beats(rocket))
        val five = assertNotNull(CardPattern.choose(cards(3, 4, 5, 6, 7)))
        assertFalse(assertNotNull(CardPattern.choose(cards(4, 5, 6, 7, 8, 9))).beats(five))
    }

    @Test fun ambiguousAirplaneMatchesPreviousPattern() {
        val opponent = assertNotNull(CardPattern.choose(cards(3, 3, 3, 4, 4, 4, 5, 5, 5, 9, 10, 11)))
        val ambiguous = cards(4, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 7)
        assertEquals(CardPattern.Kind.AIRPLANE, CardPattern.choose(ambiguous)?.kind)
        assertEquals(CardPattern.Kind.AIRPLANE_SINGLE, CardPattern.choose(ambiguous, opponent)?.kind)
        assertTrue(assertNotNull(CardPattern.choose(ambiguous, opponent)).beats(opponent))
    }

    @Test fun pairWingsCannotReuseBodyAndLargeAirplanesHaveExactWingCount() {
        kind(CardPattern.Kind.AIRPLANE_PAIR, 10, 10, 10, 11, 11, 11, 12, 12, 12, 3, 3, 4, 4, 15, 15)
        kind(CardPattern.Kind.AIRPLANE_SINGLE, 3, 3, 3, 4, 4, 4, 16, 17)
        assertNull(CardPattern.choose(cards(3, 3, 3, 3, 4, 4, 4, 5)))
        assertNull(CardPattern.choose(cards(14, 14, 14, 15, 15, 15)))
        for (ids in listOf(cards(3), cards(5, 5), cards(3, 3, 3, 4, 4, 4, 5, 5))) {
            val pattern = assertNotNull(CardPattern.choose(ids))
            assertEquals(pattern, CardPattern.decode(pattern.encode()))
        }
    }

    @Test fun candidateExistenceMatchesExhaustiveSmallHands() {
        repeat(24) { seed ->
            val hand = (0..53).shuffled(Random(seed)).take(11)
            val patterns = mutableSetOf<CardPattern>()
            for (mask in 1 until (1 shl hand.size)) {
                val cards = hand.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                patterns.addAll(CardPattern.recognize(cards))
            }
            val targets = listOf(cards(3), cards(8, 8), cards(4, 4, 4), cards(3, 3, 3, 4),
                cards(3, 3, 3, 4, 4), cards(3, 4, 5, 6, 7), cards(3, 3, 4, 4, 5, 5), cards(3, 3, 3, 3))
            for (cards in targets) {
                val target = assertNotNull(CardPattern.choose(cards))
                assertEquals(patterns.any { it.beats(target) }, CardPattern.candidates(hand, target).isNotEmpty(), "$seed $target")
            }
        }
    }

    @Test fun generatedCandidatesAreBoundedOwnedAndActuallyBeatTarget() {
        val hand = cards(3, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 7, 7, 8, 9, 10, 15, 16, 17)
        val target = CardPattern.choose(cards(3, 3, 3, 4))
        for (against in listOf(null, target)) {
            val candidates = CardPattern.candidates(hand, against)
            assertTrue(candidates.isNotEmpty())
            assertTrue(candidates.size <= 256)
            for (play in candidates) {
                assertTrue(hand.containsAll(play))
                assertNotNull(CardPattern.choose(play, against))
            }
        }
    }
}
