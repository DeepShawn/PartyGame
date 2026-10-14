package com.aicode.partyhub.games.cards

data class CardPattern(val kind: Kind, val high: Int, val length: Int, val size: Int) {
    enum class Kind {
        SINGLE, PAIR, TRIPLE, TRIPLE_SINGLE, TRIPLE_PAIR, STRAIGHT, PAIR_STRAIGHT,
        AIRPLANE, AIRPLANE_SINGLE, AIRPLANE_PAIR, FOUR_SINGLE, FOUR_PAIR, BOMB, ROCKET
    }

    fun beats(other: CardPattern): Boolean = when {
        other.kind == Kind.ROCKET -> false
        kind == Kind.ROCKET -> true
        kind == Kind.BOMB && other.kind != Kind.BOMB -> true
        kind != other.kind || length != other.length || size != other.size -> false
        else -> high > other.high
    }

    fun encode() = "${kind.name}:$high:$length:$size"

    companion object {
        fun rank(card: Int): Int {
            require(card in 0..53)
            return if (card >= 52) card - 36 else card / 4 + 3
        }

        fun decode(value: String): CardPattern? {
            val parts = value.split(':')
            if (parts.size != 4) return null
            val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
            val high = parts[1].toIntOrNull() ?: return null
            val length = parts[2].toIntOrNull() ?: return null
            val size = parts[3].toIntOrNull() ?: return null
            if (high !in 3..17 || length !in 1..12 || size !in 1..20) return null
            return CardPattern(kind, high, length, size)
        }

        fun choose(cards: List<Int>, against: CardPattern? = null): CardPattern? {
            val patterns = recognize(cards)
            if (against == null) return patterns.firstOrNull()
            return patterns.filter { it.beats(against) }.minWithOrNull(
                compareBy<CardPattern> { if (it.kind == against.kind) 0 else if (it.kind == Kind.BOMB) 1 else 2 }.thenBy { it.high }
            )
        }

        fun recognize(cards: List<Int>): List<CardPattern> {
            if (cards.isEmpty() || cards.size > 20 || cards.any { it !in 0..53 } || cards.toSet().size != cards.size) return emptyList()
            val counts = IntArray(18)
            cards.forEach { counts[rank(it)]++ }
            val ranks = (3..17).filter { counts[it] > 0 }
            val n = cards.size
            val result = mutableListOf<CardPattern>()
            fun add(kind: Kind, high: Int, length: Int = 1) { result.add(CardPattern(kind, high, length, n)) }
            if (n == 2 && counts[16] == 1 && counts[17] == 1) add(Kind.ROCKET, 17)
            if (ranks.size == 1) when (n) {
                1 -> add(Kind.SINGLE, ranks[0])
                2 -> add(Kind.PAIR, ranks[0])
                3 -> add(Kind.TRIPLE, ranks[0])
                4 -> add(Kind.BOMB, ranks[0])
            }
            val triple = ranks.firstOrNull { counts[it] == 3 }
            if (triple != null && ranks.size == 2) {
                if (n == 4) add(Kind.TRIPLE_SINGLE, triple)
                if (n == 5) add(Kind.TRIPLE_PAIR, triple)
            }
            fun run(multiplicity: Int, minimum: Int, kind: Kind) {
                if (ranks.size >= minimum && ranks.last() <= 14 && ranks.last() - ranks.first() + 1 == ranks.size && ranks.all { counts[it] == multiplicity })
                    add(kind, ranks.last(), ranks.size)
            }
            run(1, 5, Kind.STRAIGHT)
            run(2, 3, Kind.PAIR_STRAIGHT)
            run(3, 2, Kind.AIRPLANE)
            for ((kind, unit) in listOf(Kind.AIRPLANE_SINGLE to 4, Kind.AIRPLANE_PAIR to 5)) {
                if (n % unit != 0 || n / unit < 2) continue
                val length = n / unit
                for (start in 3..(15 - length)) {
                    val body = start until start + length
                    if (body.any { counts[it] != 3 }) continue
                    val wings = ranks.filter { it !in body }
                    val valid = if (unit == 4) wings.sumOf { counts[it] } == length
                        else wings.size == length && wings.all { counts[it] == 2 }
                    if (valid) add(kind, body.last, length)
                }
            }
            val four = ranks.firstOrNull { counts[it] == 4 }
            if (four != null) {
                if (n == 6) add(Kind.FOUR_SINGLE, four)
                if (n == 8 && ranks.size == 3 && ranks.filter { it != four }.all { counts[it] == 2 }) add(Kind.FOUR_PAIR, four)
            }
            return result
        }

        fun candidates(hand: List<Int>, against: CardPattern? = null): List<List<Int>> {
            if (hand.isEmpty() || hand.size > 20 || hand.any { it !in 0..53 } || hand.distinct().size != hand.size) return emptyList()
            val grouped = hand.sorted().groupBy(::rank)
            val result = linkedSetOf<List<Int>>()
            fun add(cards: List<Int>) {
                val sorted = cards.sorted()
                if (choose(sorted, against) != null) result.add(sorted)
            }
            fun wings(body: List<Int>, count: Int, pairs: Boolean) {
                val bodyRanks = body.map(::rank).toSet()
                val remaining = grouped.filterKeys { it !in bodyRanks }
                for (reverse in listOf(false, true)) {
                    val ranks = if (reverse) remaining.keys.sortedDescending() else remaining.keys.sorted()
                    val extra = if (pairs) ranks.filter { remaining.getValue(it).size >= 2 }.take(count).flatMap { remaining.getValue(it).take(2) }
                        else ranks.flatMap { remaining.getValue(it) }.take(count)
                    if (extra.size == count * if (pairs) 2 else 1) add(body + extra)
                }
            }
            add(hand)
            if (52 in hand && 53 in hand) add(listOf(52, 53))
            for ((_, cards) in grouped) {
                for (n in 1..cards.size) add(cards.take(n))
            }
            for (multiplicity in 1..3) {
                val minimum = if (multiplicity == 1) 5 else if (multiplicity == 2) 3 else 2
                for (start in 3..14) {
                    val body = mutableListOf<Int>()
                    for (end in start..14) {
                        val cards = grouped[end] ?: break
                        if (cards.size < multiplicity) break
                        body.addAll(cards.take(multiplicity))
                        val length = end - start + 1
                        if (length < minimum || body.size > hand.size) continue
                        add(body.toList())
                        if (multiplicity == 3) {
                            wings(body, length, false)
                            wings(body, length, true)
                        }
                    }
                }
            }
            for ((_, cards) in grouped) {
                if (cards.size >= 3) {
                    wings(cards.take(3), 1, false)
                    wings(cards.take(3), 1, true)
                }
                if (cards.size == 4) {
                    wings(cards, 2, false)
                    wings(cards, 2, true)
                }
            }
            return result.take(256)
        }
    }
}
