package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

class MemoryGame(config: GameConfig) : GameEngine {
    override val id = "memory"
    override val players = arcadePlayers(config.players)
    private val deck = (0 until 12).flatMap { listOf(it, it) }.toMutableList()
    private val owners = IntArray(24) { -1 }
    private val scores = IntArray(players)
    private val revealed = mutableListOf<Int>()
    private var turn = 0
    private var phase = "playing"
    private var remaining = 0
    private var elapsed = 0L
    private var winner: Int? = null

    init { ArcadeRandom(config.seed).shuffle(deck) }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat != turn || seat !in 0 until players || phase != "playing") return false
        if (!action.isIndex("flip", 0..23)) return false
        val index = action.values[0]
        if (owners[index] >= 0 || index in revealed) return false
        revealed.add(index)
        if (revealed.size == 2) {
            if (deck[revealed[0]] == deck[revealed[1]]) {
                revealed.forEach { owners[it] = seat }
                scores[seat]++
                revealed.clear()
                if (scores.sum() == 12) {
                    phase = "finished"
                    winner = scoreWinner(scores)
                }
            } else {
                phase = "reveal"
                remaining = 900
            }
        }
        return true
    }

    override fun tick(elapsedMs: Int) {
        if (elapsedMs <= 0 || phase == "finished") return
        elapsed += elapsedMs.toLong()
        if (phase == "reveal") {
            remaining = (remaining - elapsedMs).coerceAtLeast(0)
            if (remaining == 0) {
                revealed.clear()
                turn = (turn + 1) % players
                phase = "playing"
            }
        }
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = turn, phase = phase, width = 6, height = 4,
        cells = deck.indices.map { if (owners[it] >= 0 || it in revealed) deck[it] else -1 },
        scores = scores.toList(), winner = winner, tick = elapsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        legal = if (viewer == turn && phase == "playing") deck.indices.filter { owners[it] < 0 && it !in revealed }.map { GameAction("flip", listOf(it)) } else emptyList(),
        extra = mapOf(
            "owners" to owners.joinToString(","), "revealed" to revealed.joinToString(","),
            "countdown" to "$remaining", "pairsLeft" to "${12 - scores.sum()}"
        ),
        message = when (phase) {
            "reveal" -> "两张不同，记住它们；展示结束后轮到下一位。"
            "playing" -> "轮到${turn + 1}号玩家，翻开${if (revealed.isEmpty()) "第一" else "第二"}张；配对得1分并继续。"
            else -> if (winner == -1) "十二对全部完成，总分并列，平局。" else "十二对全部完成，${winner!! + 1}号玩家获胜。"
        }
    )
}
