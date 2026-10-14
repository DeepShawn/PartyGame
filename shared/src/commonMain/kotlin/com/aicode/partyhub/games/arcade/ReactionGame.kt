package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

class ReactionGame(config: GameConfig) : GameEngine {
    override val id = "reaction"
    override val players = arcadePlayers(config.players)
    private val random = ArcadeRandom(config.seed)
    private val scores = IntArray(players)
    private val responses = IntArray(players) { -1 }
    private var phase = "waiting"
    private var round = 1
    private var remaining = 1200 + random.nextInt(2001)
    private var elapsed = 0
    private var winner: Int? = null

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || !action.isButton("tap")) return false
        if (phase !in listOf("waiting", "signal") || responses[seat] != -1) return false
        responses[seat] = if (phase == "waiting") -2 else 2500 - remaining
        if (responses.none { it == -1 }) finishRound()
        return true
    }

    private fun finishRound() {
        for (seat in 0 until players) if (responses[seat] >= 0) {
            scores[seat] += players - responses.count { it >= 0 && it < responses[seat] }
        }
        if (round == 10) {
            phase = "finished"
            remaining = 0
            winner = scoreWinner(scores)
        } else {
            phase = "result"
            remaining = 800
        }
    }

    override fun tick(elapsedMs: Int) {
        if (elapsedMs <= 0 || phase == "finished") return
        var budget = elapsedMs
        while (budget > 0 && phase != "finished") {
            val step = minOf(remaining, budget)
            remaining -= step
            elapsed += step
            budget -= step
            if (remaining == 0) when (phase) {
                "waiting" -> {
                    phase = "signal"
                    remaining = 2500
                }
                "signal" -> finishRound()
                "result" -> {
                    round++
                    responses.fill(-1)
                    phase = "waiting"
                    remaining = 1200 + random.nextInt(2001)
                }
            }
        }
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = phase,
        scores = scores.toList(), winner = winner, tick = elapsed,
        legal = if (viewer in 0 until players && responses[viewer] == -1 && phase in listOf("waiting", "signal")) listOf(GameAction("tap")) else emptyList(),
        extra = mapOf(
            "round" to "$round", "totalRounds" to "10",
            "countdown" to if (phase == "waiting") "" else "$remaining",
            "signal" to if (phase == "signal") "1" else "0",
            "responses" to responses.joinToString(",")
        ),
        message = when (phase) {
            "waiting" -> "第${round}/10轮：等待信号，提前抢按本轮不得分。"
            "signal" -> "现在抢按！同一房主时间按下并列计分。"
            "result" -> "本轮按反应时间排名计分，准备下一轮。"
            else -> if (winner == -1) "十轮结束，总分并列，平局。" else "十轮结束，${winner!! + 1}号玩家总分最高。"
        }
    )
}
