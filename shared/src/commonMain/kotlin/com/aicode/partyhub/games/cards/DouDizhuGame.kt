package com.aicode.partyhub.games.cards

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.random.Random

class DouDizhuGame(private val config: GameConfig) : GameEngine {
    override val id = "doudizhu"
    override val players = 3
    private val random = Random(config.seed)
    private val hands = List(3) { mutableListOf<Int>() }
    private var bottom = emptyList<Int>()
    private var publicBottom = emptyList<Int>()
    private var phase = "bidding"
    private var turn = 0
    private var starter = 0
    private var redeals = 0
    private var bidsMade = 0
    private var bid = 0
    private var landlord = -1
    private var lastPlayer = -1
    private var lastCards = emptyList<Int>()
    private var pattern: CardPattern? = null
    private var passes = 0
    private var multiplier = 1
    private val playCounts = IntArray(3)
    private val history = mutableListOf<String>()
    private val playedCards = mutableListOf<Int>()
    private var winner: Int? = null
    private var winningSide = ""
    private var bonus = ""
    private var result = ""
    private var scores = listOf(0, 0, 0)
    private var revision = 0

    init { deal() }

    private fun deal() {
        val deck = (0..53).shuffled(random)
        for (seat in 0..2) {
            hands[seat].clear()
            hands[seat].addAll(deck.subList(seat * 17, seat * 17 + 17).sorted())
        }
        bottom = deck.takeLast(3).sorted()
        turn = starter; bidsMade = 0; bid = 0; landlord = -1
    }

    private fun startPlaying() {
        publicBottom = bottom
        hands[landlord].addAll(bottom)
        hands[landlord].sort()
        phase = "playing"; turn = landlord; multiplier = bid
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0..2 || phase == "finished" || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            history.add("$seat:resign")
            if (landlord < 0 || phase == "bidding") {
                winner = -1; phase = "finished"; result = "cancelled_before_bid"
            } else finish(if (seat == landlord) (landlord + 1) % 3 else landlord, false)
            revision++
            return true
        }
        if (seat != turn) return false
        if (action.type == "hint") return phase == "playing" && action.values.isEmpty()
        if (phase == "bidding") {
            if (action.type != "bid" || action.values.size != 1) return false
            val value = action.values[0]
            if (value !in 0..3 || value != 0 && value <= bid) return false
            history.add("$seat:bid:$value")
            bidsMade++
            if (value > 0) { bid = value; landlord = seat }
            if (value == 3 || bidsMade == 3 && bid > 0) startPlaying()
            else if (bidsMade == 3) {
                if (redeals < 2) {
                    redeals++; starter = (starter + 1) % 3
                    history.add("redeal:$redeals")
                    deal()
                } else {
                    landlord = starter; bid = 1
                    history.add("forced:$landlord:1")
                    startPlaying()
                }
            } else turn = (turn + 1) % 3
            revision++
            return true
        }
        when (action.type) {
            "pass" -> {
                if (action.values.isNotEmpty() || pattern == null) return false
                history.add("$seat:pass")
                passes++
                if (passes == 2) {
                    turn = lastPlayer; pattern = null; lastCards = emptyList(); passes = 0
                } else turn = (turn + 1) % 3
            }
            "play" -> {
                val cards = action.values
                if (cards.isEmpty() || cards.size > hands[seat].size || !hands[seat].containsAll(cards)) return false
                val chosen = CardPattern.choose(cards, pattern) ?: return false
                hands[seat].removeAll(cards.toSet())
                pattern = chosen; lastCards = cards.sorted(); lastPlayer = seat; passes = 0
                history.add("$seat:play:${lastCards.joinToString(",")}")
                playedCards.addAll(lastCards)
                playCounts[seat]++
                if (chosen.kind == CardPattern.Kind.BOMB || chosen.kind == CardPattern.Kind.ROCKET) multiplier *= 2
                if (hands[seat].isEmpty()) finish(seat, true) else turn = (turn + 1) % 3
            }
            else -> return false
        }
        revision++
        return true
    }

    private fun finish(seat: Int, checkSpring: Boolean) {
        val landlordWon = seat == landlord
        if (checkSpring) {
            if (landlordWon && (0..2).filter { it != landlord }.all { playCounts[it] == 0 }) bonus = "spring"
            else if (!landlordWon && playCounts[landlord] == 1) bonus = "anti_spring"
            if (bonus.isNotEmpty()) multiplier *= 2
        }
        winner = seat; winningSide = if (landlordWon) "landlord" else "farmers"
        scores = (0..2).map { (if (it == landlord) 2 else -1) * multiplier * if (landlordWon) 1 else -1 }
        phase = "finished"; result = if (checkSpring) "empty_hand" else "resignation"
    }

    private fun visible(viewer: Int) = GameSnapshot(
        id, players, turn, phase, hand = if (viewer in 0..2) hands[viewer].toList() else emptyList(),
        counts = hands.map { it.size }, scores = scores, labels = listOf("席位1", "席位2", "席位3"), winner = winner, tick = revision,
        extra = mapOf(
            "viewer" to viewer.toString(), "landlord" to landlord.toString(), "bid" to bid.toString(),
            "currentPattern" to (pattern?.encode() ?: ""), "lastCards" to lastCards.joinToString(","),
            "lastPlayer" to lastPlayer.toString(), "publicBottom" to publicBottom.joinToString(","),
            "multiplier" to multiplier.toString(), "history" to history.joinToString(";"),
            "playedCards" to playedCards.joinToString(","), "winningSide" to winningSide, "bonus" to bonus, "result" to result,
            "rules" to "每席叫分一次且须高于前叫，3分立即结束。连续三轮全不叫，末轮起叫者强制1分地主。单翼可同点数（允许拆对子），不可与飞机主体同点；对翼须不同点数且不得拆四张作两对。顺子、连对、飞机主体不含2或王。两次pass重置；领出不可pass。炸弹/王炸各翻倍，春天/反春天翻倍；乘数含叫分，地主结算±2倍、农民各∓1倍。叫分前认输取消该局，确定地主后认输代表全队认输。提示为有限候选，仍可手选任意合法组合。"
        ),
        message = if (phase == "finished") result else if (phase == "bidding") "轮到席位${turn + 1}叫分" else "轮到席位${turn + 1}出牌"
    )

    override fun snapshot(viewer: Int): GameSnapshot {
        val base = visible(viewer)
        if (viewer != turn || viewer !in 0..2 || phase == "finished") return base
        val legal = if (phase == "bidding") (listOf(0) + ((bid + 1)..3).toList()).map { GameAction("bid", listOf(it)) }
        else buildList {
            val hint = chooseAction(base, config.difficulty)
            if (hint != null && hint.type == "play") add(hint)
            if (pattern != null) add(GameAction("pass"))
            add(GameAction("hint"))
        }
        return base.copy(legal = legal)
    }

    override fun botAction(seat: Int, difficulty: Int): GameAction? =
        if (seat in 0..2 && seat == turn && phase != "finished") chooseAction(visible(seat), difficulty) else null

    companion object {
        fun chooseAction(view: GameSnapshot, difficulty: Int): GameAction? {
            val seat = view.extra["viewer"]?.toIntOrNull() ?: return null
            if (view.gameId != "doudizhu" || view.phase == "finished" || seat !in 0..2 || seat != view.turn || view.hand.isEmpty()) return null
            val level = difficulty.coerceIn(0, 2)
            val hand = view.hand
            val groups = hand.groupBy(CardPattern::rank)
            if (view.phase == "bidding") {
                val strength = hand.map { when (CardPattern.rank(it)) { 17 -> 5; 16 -> 3; 15 -> 2; 14 -> 1; else -> 0 } }.sum() + groups.values.count { it.size == 4 } * 5
                val wanted = when { strength >= 13 - level -> 3; strength >= 9 - level -> 2; strength >= 5 - level -> 1; else -> 0 }
                val current = view.extra.getValue("bid").toInt()
                return GameAction("bid", listOf(if (wanted > current) wanted else 0))
            }
            if (view.phase != "playing") return null
            val against = CardPattern.decode(view.extra.getValue("currentPattern"))
            val candidates = CardPattern.candidates(hand, against)
            if (candidates.isEmpty()) return if (against != null) GameAction("pass") else null
            candidates.firstOrNull { it.size == hand.size }?.let { return GameAction("play", it) }
            val landlord = view.extra.getValue("landlord").toInt()
            val lastPlayer = view.extra.getValue("lastPlayer").toInt()
            if (level > 0 && against != null && seat != landlord && lastPlayer != landlord && lastPlayer != seat) return GameAction("pass")
            val enemies = (0..2).filter { it != seat && (seat == landlord || it == landlord) }
            val threat = enemies.any { view.counts[it] <= 2 }
            val publicPlayed = view.extra.getValue("playedCards").split(',').mapNotNull(String::toIntOrNull)
            val known = (hand + publicPlayed).toSet()
            val remainingRanks = (0..53).filter { it !in known }.groupingBy(CardPattern::rank).eachCount()
            val selected = candidates.minBy { cards ->
                val p = CardPattern.choose(cards, against)!!
                val bomb = p.kind == CardPattern.Kind.BOMB || p.kind == CardPattern.Kind.ROCKET
                val left = hand.filter { it !in cards }
                val leftGroups = left.groupBy(CardPattern::rank)
                val splitCost = groups.entries.map { (rank, all) ->
                    val remaining = leftGroups[rank]?.size ?: 0
                    if (remaining in 1 until all.size) (if (all.size == 4) 35 else if (all.size == 3) 12 else 5) else 0
                }.sum()
                when (level) {
                    0 -> p.high * 2 - cards.size * (if (against == null) 3 else 0) + if (bomb) 100 else 0
                    1 -> left.size * 5 + leftGroups.size * 4 + splitCost + p.high + if (bomb && !threat) 100 else 0
                    else -> {
                        val singles = leftGroups.values.count { it.size == 1 }
                        val beaten = if (p.kind in listOf(CardPattern.Kind.SINGLE, CardPattern.Kind.PAIR, CardPattern.Kind.TRIPLE))
                            remainingRanks.any { (rank, count) -> rank > p.high && count >= p.size } else false
                        left.size * 6 + singles * 10 + leftGroups.size * 5 + splitCost + p.high +
                            (if (bomb && !threat) 120 else 0) + (if (threat && beaten) 35 else 0)
                    }
                }
            }
            return GameAction("play", selected)
        }
    }
}
