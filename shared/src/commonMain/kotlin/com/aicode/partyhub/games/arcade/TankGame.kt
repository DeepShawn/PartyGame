package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameEntity
import com.aicode.partyhub.core.GameSnapshot

class TankGame(config: GameConfig) : GameEngine {
    override val id = "tank"
    override val players = arcadePlayers(config.players)
    private val width = 15
    private val height = 11
    private val cells = arenaCells(width, height)
    private val clock = ArcadeClock(50)
    private var positions = listOf(ArcadeCell(2, 5), ArcadeCell(12, 5), ArcadeCell(7, 8)).take(players)
    private val hp = IntArray(players) { 3 }
    private val scores = IntArray(players)
    private val directions = IntArray(players) { -1 }
    private val facing = intArrayOf(1, 3, 0).copyOf(players)
    private val inputUsed = BooleanArray(players)
    private val fireQueued = BooleanArray(players)
    private val nextFire = IntArray(players)
    private val bullets = mutableListOf<Bullet>()
    private var nextId = 100
    private var winner: Int? = null
    private var timedOut = false

    private data class Bullet(val id: Int, val owner: Int, var cell: ArcadeCell, val direction: Int)

    init {
        val random = ArcadeRandom(config.seed)
        for (y in listOf(2, 3, 7, 8)) for (x in 4..5) {
            if (random.nextInt(3) == 0) {
                cells[y * width + x] = -1
                cells[y * width + width - 1 - x] = -1
            }
        }
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || winner != null || hp[seat] <= 0) return false
        if (action.isIndex("direction", -1..3)) {
            if (inputUsed[seat]) return false
            val direction = action.values[0]
            directions[seat] = direction
            if (direction >= 0) facing[seat] = direction
            inputUsed[seat] = true
            return true
        }
        if (action.isButton("fire")) {
            if (fireQueued[seat] || clock.elapsed < nextFire[seat]) return false
            fireQueued[seat] = true
            nextFire[seat] = clock.elapsed + 500
            return true
        }
        return false
    }

    override fun tick(elapsedMs: Int) = clock.advance(elapsedMs, { winner == null }) { step() }

    private fun step() {
        val alive = BooleanArray(players) { hp[it] > 0 }
        val old = positions
        if (clock.elapsed % 200 == 0) {
            val targets = positions.mapIndexed { seat, cell ->
                val target = cell.move(directions[seat])
                if (alive[seat] && cells[target.index(width)] == 0) target else cell
            }
            positions = blockedMoves(positions, targets, alive)
            inputUsed.fill(false)
        }
        for (seat in 0 until players) if (fireQueued[seat]) {
            if (alive[seat]) bullets.add(Bullet(nextId++, seat, positions[seat], facing[seat]))
            fireQueued[seat] = false
        }
        val damage = IntArray(players)
        val remaining = mutableListOf<Bullet>()
        for (bullet in bullets) {
            var hit = (0 until players).firstOrNull { alive[it] && it != bullet.owner && (positions[it] == bullet.cell || old[it] == bullet.cell) }
            var blocked = false
            if (hit == null && clock.elapsed % 100 == 0) {
                bullet.cell = bullet.cell.move(bullet.direction)
                blocked = cells[bullet.cell.index(width)] == -1
                if (!blocked) hit = (0 until players).firstOrNull { alive[it] && it != bullet.owner && positions[it] == bullet.cell }
            }
            if (hit != null) {
                damage[hit]++
                scores[bullet.owner]++
            } else if (!blocked) remaining.add(bullet)
        }
        bullets.clear()
        bullets.addAll(remaining)
        for (seat in 0 until players) hp[seat] = (hp[seat] - damage[seat]).coerceAtLeast(0)
        val survivors = (0 until players).filter { hp[it] > 0 }
        if (survivors.size <= 1) winner = survivors.singleOrNull() ?: -1
        if (winner == null && clock.elapsed >= 180000) {
            winner = -1
            timedOut = true
        }
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = if (winner == null) "playing" else "finished",
        width = width, height = height, cells = cells.toList(), scores = scores.toList(), winner = winner, tick = clock.elapsed,
        entities = buildList {
            for (seat in 0 until players) if (hp[seat] > 0) add(GameEntity(seat, "player", seat, positions[seat].x.toFloat(), positions[seat].y.toFloat(), value = hp[seat], angle = facing[seat] * 90f))
            bullets.forEach { add(GameEntity(it.id, "bullet", it.owner, it.cell.x.toFloat(), it.cell.y.toFloat(), width = 0.25f, height = 0.25f, angle = it.direction * 90f)) }
        },
        legal = buildList {
            if (viewer in 0 until players && hp[viewer] > 0 && winner == null) {
                if (!inputUsed[viewer]) for (direction in -1..3) add(GameAction("direction", listOf(direction)))
                if (!fireQueued[viewer] && clock.elapsed >= nextFire[viewer]) add(GameAction("fire"))
            }
        },
        extra = mapOf(
            "hp" to hp.joinToString(","), "alive" to BooleanArray(players) { hp[it] > 0 }.csv(),
            "directions" to directions.joinToString(","), "fireCooldown" to nextFire.joinToString(",") { "${(it - clock.elapsed).coerceAtLeast(0)}" },
            "stepMs" to "50", "moveMs" to "200", "countdown" to "${(180000 - clock.elapsed).coerceAtLeast(0)}"
        ),
        message = when {
            winner == null -> "每人3点生命；方向键移动和瞄准，停止后仍保留朝向；每500毫秒可开火，命中得1分。最后存活者胜，三分钟未决为平局。"
            timedOut -> "三分钟到，仍有多名玩家存活，平局。"
            winner == -1 -> "同时失去全部生命，平局。"
            else -> "${winner!! + 1}号玩家最后存活，获胜。"
        }
    )
}
