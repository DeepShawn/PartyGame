package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameEntity
import com.aicode.partyhub.core.GameSnapshot

class BomberGame(config: GameConfig) : GameEngine {
    override val id = "bomber"
    override val players = arcadePlayers(config.players)
    private val width = 13
    private val height = 11
    private val cells = arenaCells(width, height)
    private val clock = ArcadeClock(50)
    private var positions = listOf(ArcadeCell(1, 1), ArcadeCell(11, 9), ArcadeCell(11, 1)).take(players)
    private val alive = BooleanArray(players) { true }
    private val scores = IntArray(players)
    private val directions = IntArray(players) { -1 }
    private val inputUsed = BooleanArray(players)
    private val nextBomb = IntArray(players)
    private val bombIds = IntArray(players)
    private val bombs = mutableListOf<Bomb>()
    private val blasts = mutableListOf<Blast>()
    private var nextBlastId = 1000000
    private var winner: Int? = null
    private var timedOut = false

    private data class Bomb(val id: Int, val owner: Int, val cell: ArcadeCell, val due: Int)
    private data class Blast(val id: Int, val owner: Int, val cell: ArcadeCell, val until: Int)

    init {
        val random = ArcadeRandom(config.seed)
        for (y in 1 until height - 1) for (x in 1..width / 2) {
            val corner = x <= 3 && (y <= 3 || y >= height - 4)
            val value = when {
                x % 2 == 0 && y % 2 == 0 -> -1
                corner -> 0
                random.nextInt(100) < 55 -> -2
                else -> 0
            }
            cells[y * width + x] = value
            cells[y * width + width - 1 - x] = value
        }
        for (y in listOf(1, height - 2)) for (x in listOf(4, width - 5)) cells[y * width + x] = -2
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || winner != null || !alive[seat]) return false
        if (action.isIndex("direction", -1..3)) {
            if (inputUsed[seat]) return false
            directions[seat] = action.values[0]
            inputUsed[seat] = true
            return true
        }
        if (action.isButton("bomb")) {
            if (clock.elapsed < nextBomb[seat] || bombs.count { it.owner == seat } >= 2 || bombs.any { it.cell == positions[seat] }) return false
            bombs.add(Bomb(100 + seat * 10000 + bombIds[seat]++, seat, positions[seat], clock.elapsed + 2000))
            nextBomb[seat] = clock.elapsed + 400
            return true
        }
        return false
    }

    override fun tick(elapsedMs: Int) = clock.advance(elapsedMs, { winner == null }) { step() }

    private fun step() {
        blasts.removeAll { it.until <= clock.elapsed }
        val dead = BooleanArray(players) { alive[it] && blasts.any { blast -> blast.cell == positions[it] } }
        if (clock.elapsed % 200 == 0) {
            val targets = positions.mapIndexed { seat, cell ->
                val target = cell.move(directions[seat])
                if (alive[seat] && !dead[seat] && cells[target.index(width)] == 0 &&
                    (target == cell || bombs.none { it.cell == target })) target else cell
            }
            positions = blockedMoves(positions, targets, alive)
            inputUsed.fill(false)
        }
        explode()
        for (seat in 0 until players) if (alive[seat]) {
            if (dead[seat] || blasts.any { it.cell == positions[seat] }) alive[seat] = false
        }
        val survivors = alive.seats()
        if (survivors.size <= 1) winner = survivors.singleOrNull() ?: -1
        if (winner == null && clock.elapsed >= 180000) {
            winner = -1
            timedOut = true
        }
    }

    private fun explode() {
        val queue = bombs.filter { it.due <= clock.elapsed || blasts.any { blast -> blast.cell == it.cell } }.sortedBy { it.id }.toMutableList()
        val exploded = mutableSetOf<Int>()
        val hits = mutableMapOf<ArcadeCell, MutableSet<Int>>()
        fun hit(cell: ArcadeCell, owner: Int) {
            hits.getOrPut(cell) { mutableSetOf() }.add(owner)
            bombs.filter { it.cell == cell && it.id !in exploded }.forEach { queue.add(it) }
        }
        var cursor = 0
        while (cursor < queue.size) {
            val bomb = queue[cursor++]
            if (!exploded.add(bomb.id)) continue
            hit(bomb.cell, bomb.owner)
            for (direction in 0..3) {
                var cell = bomb.cell
                for (range in 1..3) {
                    cell = cell.move(direction)
                    val value = cells[cell.index(width)]
                    if (value == -1) break
                    hit(cell, bomb.owner)
                    if (value == -2 || bombs.any { it.cell == cell }) break
                }
            }
        }
        // Every ray sees the pre-explosion map, so processing order cannot open a crate early.
        for ((cell, owners) in hits) {
            if (cells[cell.index(width)] == -2) {
                cells[cell.index(width)] = 0
                owners.forEach { scores[it]++ }
            }
            for (owner in owners.sorted()) blasts.add(Blast(nextBlastId++, owner, cell, clock.elapsed + 500))
        }
        bombs.removeAll { it.id in exploded }
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = if (winner == null) "playing" else "finished",
        width = width, height = height, cells = cells.toList(), scores = scores.toList(), winner = winner, tick = clock.elapsed,
        entities = buildList {
            for (seat in 0 until players) if (alive[seat]) add(GameEntity(seat, "player", seat, positions[seat].x.toFloat(), positions[seat].y.toFloat(), angle = directions[seat].coerceAtLeast(0) * 90f))
            bombs.sortedBy { it.id }.forEach { add(GameEntity(it.id, "bomb", it.owner, it.cell.x.toFloat(), it.cell.y.toFloat(), value = (it.due - clock.elapsed).coerceAtLeast(0))) }
            blasts.forEach { add(GameEntity(it.id, "blast", it.owner, it.cell.x.toFloat(), it.cell.y.toFloat(), value = it.until - clock.elapsed)) }
        },
        legal = buildList {
            if (viewer in 0 until players && alive[viewer] && winner == null) {
                if (!inputUsed[viewer]) for (direction in -1..3) add(GameAction("direction", listOf(direction)))
                if (clock.elapsed >= nextBomb[viewer] && bombs.count { it.owner == viewer } < 2 && bombs.none { it.cell == positions[viewer] }) add(GameAction("bomb"))
            }
        },
        extra = mapOf(
            "alive" to alive.csv(), "directions" to directions.joinToString(","),
            "bombCooldown" to nextBomb.joinToString(",") { "${(it - clock.elapsed).coerceAtLeast(0)}" },
            "fuseMs" to "2000", "blastMs" to "500", "blastRange" to "3", "maxBombs" to "2",
            "stepMs" to "50", "moveMs" to "200", "countdown" to "${(180000 - clock.elapsed).coerceAtLeast(0)}"
        ),
        message = when {
            winner == null -> "炸弹2秒后沿四向炸3格，火焰持续半秒；墙阻挡，木箱阻挡并被摧毁，炸弹会连锁。每人最多2枚，炸箱得1分，最后存活者胜，三分钟未决平局。"
            timedOut -> "三分钟到，仍有多名玩家存活，平局。"
            winner == -1 -> "同一步全部出局，平局。"
            else -> "${winner!! + 1}号玩家最后存活，获胜。"
        }
    )
}
