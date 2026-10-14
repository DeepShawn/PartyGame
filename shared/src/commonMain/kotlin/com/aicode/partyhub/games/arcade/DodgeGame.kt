package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameEntity
import com.aicode.partyhub.core.GameSnapshot

class DodgeGame(config: GameConfig) : GameEngine {
    override val id = "dodge"
    override val players = arcadePlayers(config.players)
    private val width = 15
    private val height = 15
    private val cells = arenaCells(width, height)
    private val clock = ArcadeClock(50)
    private val random = ArcadeRandom(config.seed)
    private val x = intArrayOf(3000, 11000, 7000).copyOf(players)
    private val y = IntArray(players) { 11000 }
    private val alive = BooleanArray(players) { true }
    private val scores = IntArray(players)
    private val directions = IntArray(players) { -1 }
    private val inputUsed = BooleanArray(players)
    private val obstacles = mutableListOf<Obstacle>()
    private val columns = mutableListOf<Int>()
    private var nextSpawn = 1500
    private var nextId = 100
    private var winner: Int? = null
    private var timedOut = false
    private val speed get() = (2000 + clock.elapsed / 10).coerceAtMost(7000)

    private data class Obstacle(val id: Int, val x: Int, var y: Int)

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || winner != null || !alive[seat] || inputUsed[seat]) return false
        if (!action.isIndex("direction", -1..3)) return false
        directions[seat] = action.values[0]
        inputUsed[seat] = true
        return true
    }

    override fun tick(elapsedMs: Int) = clock.advance(elapsedMs, { winner == null }) { step() }

    private fun step() {
        inputUsed.fill(false)
        val oldX = x.copyOf()
        val oldY = y.copyOf()
        for (seat in 0 until players) if (alive[seat]) {
            when (directions[seat]) {
                0 -> y[seat] -= 250
                1 -> x[seat] += 250
                2 -> y[seat] += 250
                3 -> x[seat] -= 250
            }
            x[seat] = x[seat].coerceIn(1000, (width - 2) * 1000)
            y[seat] = y[seat].coerceIn(1000, (height - 2) * 1000)
            scores[seat] = clock.elapsed / 100
        }
        if (clock.elapsed >= nextSpawn) {
            if (columns.isEmpty()) {
                columns.addAll(1..7)
                random.shuffle(columns)
            }
            val column = columns.removeAt(columns.lastIndex)
            obstacles.add(Obstacle(nextId++, column * 1000, 1000))
            if (column != 7) obstacles.add(Obstacle(nextId++, (width - 1 - column) * 1000, 1000))
            nextSpawn = clock.elapsed + (850 - clock.elapsed / 100).coerceAtLeast(300)
        }
        val dead = BooleanArray(players)
        for (obstacle in obstacles) {
            val previousY = obstacle.y
            obstacle.y += speed / 20
            for (seat in 0 until players) if (alive[seat] && intersects(oldX[seat], oldY[seat], x[seat], y[seat], obstacle, previousY)) dead[seat] = true
        }
        for (seat in 0 until players) if (dead[seat]) alive[seat] = false
        obstacles.removeAll { it.y >= (height - 1) * 1000 }
        val survivors = alive.seats()
        if (survivors.size <= 1) winner = survivors.singleOrNull() ?: -1
        if (winner == null && clock.elapsed >= 180000) {
            winner = -1
            timedOut = true
        }
    }

    private fun intersects(oldX: Int, oldY: Int, newX: Int, newY: Int, obstacle: Obstacle, obstacleOldY: Int): Boolean {
        var enter = 0.0
        var leave = 1.0
        fun axis(position: Int, delta: Int): Boolean {
            if (delta == 0) return position > -800 && position < 1000
            val first = (-800.0 - position) / delta
            val second = (1000.0 - position) / delta
            enter = maxOf(enter, minOf(first, second))
            leave = minOf(leave, maxOf(first, second))
            return enter < leave
        }
        return axis(oldX - obstacle.x, newX - oldX) &&
            axis(oldY - obstacleOldY, newY - oldY - (obstacle.y - obstacleOldY))
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = if (winner == null) "playing" else "finished",
        width = width, height = height, cells = cells.toList(), scores = scores.toList(), winner = winner, tick = clock.elapsed,
        entities = buildList {
            for (seat in 0 until players) if (alive[seat]) add(GameEntity(seat, "player", seat, x[seat] / 1000f, y[seat] / 1000f, width = 0.8f, height = 0.8f))
            obstacles.forEach { add(GameEntity(it.id, "obstacle", x = it.x / 1000f, y = it.y / 1000f)) }
        },
        legal = if (viewer in 0 until players && alive[viewer] && winner == null && !inputUsed[viewer]) (-1..3).map { GameAction("direction", listOf(it)) } else emptyList(),
        extra = mapOf(
            "alive" to alive.csv(), "directions" to directions.joinToString(","),
            "speed" to "$speed", "speedScale" to "1000", "stepMs" to "50",
            "countdown" to "${(180000 - clock.elapsed).coerceAtLeast(0)}", "warmup" to "${(1500 - clock.elapsed).coerceAtLeast(0)}"
        ),
        message = when {
            winner == null -> "方向键移动，停止键停下；玩家可穿过彼此，避开下落障碍。障碍逐渐加速加密，每存活0.1秒得1分，最后存活者胜；三分钟未决平局。"
            timedOut -> "三分钟到，多名玩家仍存活，平局。"
            winner == -1 -> "同一步全部撞上障碍，平局。"
            else -> "${winner!! + 1}号玩家最后存活，获胜。"
        }
    )
}
