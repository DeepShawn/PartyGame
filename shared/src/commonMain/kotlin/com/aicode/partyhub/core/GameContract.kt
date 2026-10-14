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
    fun botAction(seat: Int, difficulty: Int): GameAction? = null
}

val GameJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
