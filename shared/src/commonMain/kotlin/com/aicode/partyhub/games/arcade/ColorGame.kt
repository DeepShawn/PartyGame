package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

class ColorGame(config: GameConfig) : GameEngine {
    override val id = "color"
    override val players = arcadePlayers(config.players)
    private val random = ArcadeRandom(config.seed)
    private val palette = listOf("红色", "蓝色", "绿色", "黄色")
    private val scores = IntArray(players)
    private val answered = BooleanArray(players)
    private var ink = random.nextInt(4)
    private var word = (ink + 1 + random.nextInt(3)) % 4
    private var round = 1
    private var remaining = 6000
    private var elapsed = 0
    private var phase = "playing"
    private var winner: Int? = null

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || phase != "playing" || answered[seat]) return false
        if (!action.isIndex("answer", 0..3)) return false
        answered[seat] = true
        scores[seat] += if (action.values[0] == ink) 2 else -1
        if (answered.all { it }) finishQuestion()
        return true
    }

    private fun finishQuestion() {
        if (round == 12) {
            phase = "finished"
            winner = scoreWinner(scores)
            remaining = 0
        } else {
            phase = "result"
            remaining = 1000
        }
    }

    override fun tick(elapsedMs: Int) {
        if (elapsedMs <= 0 || phase == "finished") return
        var budget = elapsedMs
        while (budget > 0 && phase != "finished") {
            val step = minOf(budget, remaining)
            budget -= step
            remaining -= step
            elapsed += step
            if (remaining == 0) {
                if (phase == "playing") finishQuestion() else {
                    round++
                    ink = random.nextInt(4)
                    word = (ink + 1 + random.nextInt(3)) % 4
                    answered.fill(false)
                    phase = "playing"
                    remaining = 6000
                }
            }
        }
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = phase,
        scores = scores.toList(), labels = palette.toList(), winner = winner, tick = elapsed,
        legal = if (viewer in 0 until players && !answered[viewer] && phase == "playing") (0..3).map { GameAction("answer", listOf(it)) } else emptyList(),
        extra = mapOf(
            "round" to "$round", "totalRounds" to "12", "countdown" to "$remaining",
            "question" to palette[word], "colorIndex" to "$ink", "answered" to answered.csv(),
            "palette" to "#E53935,#1E88E5,#43A047,#FDD835"
        ),
        message = when (phase) {
            "playing" -> "第${round}/12题：选择文字的实际颜色，不是字义；答对+2，答错−1，每人一次。"
            "result" -> "本题结束，即将显示下一题。"
            else -> if (winner == -1) "十二题结束，总分并列，平局。" else "十二题结束，${winner!! + 1}号玩家获胜。"
        }
    )
}
