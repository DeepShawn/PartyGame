package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

/** 轻量选择类小游戏共用内核：四个选项、五轮、即时计分，适合联机和 AI 补位。 */
class QuickChallengeGame(config: GameConfig, override val id: String) : GameEngine {
    override val players = config.players.coerceIn(2, 3)
    private val scores = IntArray(players)
    private var turn = 0
    private var round = 0
    private var winner: Int? = null
    private var target = (config.seed + id.hashCode()).ushr(1) % 4
    override fun apply(seat: Int, action: GameAction): Boolean {
        if (winner != null || seat != turn || action.type != "choose" || action.values.size != 1 || action.values[0] !in 0..3) return false
        if (action.values[0] == target) scores[seat] += 2 else scores[seat] = (scores[seat] - 1).coerceAtLeast(0)
        round++
        if (round >= 5 * players) winner = scores.indices.maxByOrNull { scores[it] } ?: -1
        else { turn = (turn + 1) % players; target = (target * 3 + round + id.length) % 4 }
        return true
    }
    override fun snapshot(viewer: Int): GameSnapshot { val champion = winner; return GameSnapshot(id, players, turn, if (champion == null) "playing" else "finished", 4, 1, cells = IntArray(4).toList(), legal = if (champion == null && viewer == turn) (0..3).map { GameAction("choose", listOf(it)) } else emptyList(), scores = scores.toList(), labels = (0 until players).map { "${it + 1}号" }, winner = champion, tick = round, message = if (champion == null) "第${round / players + 1}轮：轮到${turn + 1}号" else "${champion + 1}号获胜") }
    override fun botAction(seat: Int, difficulty: Int): GameAction? = if (winner == null && seat == turn) GameAction("choose", listOf((target + difficulty + seat) % 4)) else null
}
