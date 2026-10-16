package com.aicode.partyhub.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GameAction(val type: String, val values: List<Int> = emptyList(), val text: String = "")

@Serializable
data class GameEntity(
    val id: Int,
    val kind: String,
    val owner: Int = -1,
    val x: Float,
    val y: Float,
    val width: Float = 1f,
    val height: Float = 1f,
    val value: Int = 0,
    val angle: Float = 0f
)

@Serializable
data class GameSnapshot(
    val gameId: String,
    val players: Int,
    val turn: Int = 0,
    val phase: String = "playing",
    val width: Int = 0,
    val height: Int = 0,
    val cells: List<Int> = emptyList(),
    val entities: List<GameEntity> = emptyList(),
    val scores: List<Int> = emptyList(),
    val hand: List<Int> = emptyList(),
    val counts: List<Int> = emptyList(),
    val legal: List<GameAction> = emptyList(),
    val labels: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
    val message: String = "",
    val winner: Int? = null,
    val tick: Int = 0
)

@Serializable
data class GameConfig(
    val players: Int = 2,
    val seed: Int = 1,
    val difficulty: Int = 1,
    val options: Map<String, String> = emptyMap()
)

interface GameEngine {
    val id: String
    val players: Int
    fun apply(seat: Int, action: GameAction): Boolean
    fun snapshot(viewer: Int): GameSnapshot
    fun tick(elapsedMs: Int) {}
    /** 通用离线 AI：街机游戏没有专用搜索器时，根据局面选择安全/得分动作。 */
    fun botAction(seat: Int, difficulty: Int): GameAction? {
        val view = snapshot(seat)
        if (view.winner != null || view.legal.isEmpty()) return null
        if (id == "reaction" && view.phase != "signal") return null
        val target = when (id) {
            "color" -> view.extra["colorIndex"]?.toIntOrNull()
            "math" -> view.extra["question"]?.let { question -> view.labels.indexOf(question).takeIf { it >= 0 } }
            else -> null
        }
        if (target != null) view.legal.firstOrNull { it.values.firstOrNull() == target }?.let { return it }
        val preferred = view.legal.sortedWith(compareBy<GameAction> { if (it.type == "answer" || it.type == "choose") 0 else if (it.type == "tap") 1 else if (it.type == "direction") 2 else 3 })
        val index = ((view.tick / 100) + seat + difficulty.coerceIn(0, 2)) % preferred.size
        return preferred[index]
    }
}

val GameJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
