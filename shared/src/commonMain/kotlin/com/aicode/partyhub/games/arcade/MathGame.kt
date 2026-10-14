package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

class MathGame(config: GameConfig) : GameEngine {
    override val id = "math"
    override val players = arcadePlayers(config.players)
    private val random = ArcadeRandom(config.seed)
    private val difficulty = config.difficulty.coerceIn(0, 2)
    private val scores = IntArray(players)
    private val answered = BooleanArray(players)
    private var round = 1
    private var remaining = 8000
    private var elapsed = 0
    private var phase = "playing"
    private var winner: Int? = null
    private var question = ""
    private var choices = emptyList<Int>()
    private var result = 0

    init { nextQuestion() }

    private fun nextQuestion() {
        val range = 10 + difficulty * 15
        val a = 1 + random.nextInt(range)
        val b = 1 + random.nextInt(if (difficulty == 0) 10 else 12)
        val op = random.nextInt(if (difficulty == 0) 2 else 4)
        when (op) {
            0 -> { question = "$a + $b = ?"; result = a + b }
            1 -> { question = "$a − $b = ?"; result = a - b }
            2 -> { question = "${a % 13 + 1} × $b = ?"; result = (a % 13 + 1) * b }
            3 -> { question = "${a * b} ÷ $b = ?"; result = a }
        }
        val offsets = (1..12).flatMap { listOf(it, -it) }.toMutableList()
        random.shuffle(offsets)
        val options = (listOf(result) + offsets.take(3).map { result + it }).toMutableList()
        random.shuffle(options)
        choices = options
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || phase != "playing" || answered[seat]) return false
        if (!action.isIndex("answer", 0..3)) return false
        answered[seat] = true
        scores[seat] += if (choices[action.values[0]] == result) 2 else -1
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
                    nextQuestion()
                    answered.fill(false)
                    phase = "playing"
                    remaining = 8000
                }
            }
        }
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = phase,
        scores = scores.toList(), labels = choices.map { "$it" }, winner = winner, tick = elapsed,
        legal = if (viewer in 0 until players && !answered[viewer] && phase == "playing") (0..3).map { GameAction("answer", listOf(it)) } else emptyList(),
        extra = mapOf(
            "round" to "$round", "totalRounds" to "12", "countdown" to "$remaining",
            "question" to question, "answered" to answered.csv()
        ),
        message = when (phase) {
            "playing" -> "第${round}/12题：选择计算结果；答对+2，答错−1，每人只能答一次。"
            "result" -> "本题结束，即将显示下一题。"
            else -> if (winner == -1) "十二题结束，总分并列，平局。" else "十二题结束，${winner!! + 1}号玩家获胜。"
        }
    )
}
