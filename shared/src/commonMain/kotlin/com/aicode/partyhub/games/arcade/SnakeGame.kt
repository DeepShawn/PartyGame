package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameEntity
import com.aicode.partyhub.core.GameSnapshot

class SnakeGame(config: GameConfig) : GameEngine {
    override val id = "snake"
    override val players = arcadePlayers(config.players)
    private val width = 15
    private val height = 13
    private val cells = arenaCells(width, height)
    private val random = ArcadeRandom(config.seed)
    private val clock = ArcadeClock(180)
    private val bodies = listOf(
        mutableListOf(ArcadeCell(3, 6), ArcadeCell(2, 6), ArcadeCell(1, 6)),
        mutableListOf(ArcadeCell(11, 6), ArcadeCell(12, 6), ArcadeCell(13, 6)),
        mutableListOf(ArcadeCell(7, 10), ArcadeCell(7, 11), ArcadeCell(6, 11))
    ).take(players)
    private val facing = intArrayOf(1, 3, 0).copyOf(players)
    private val directions = facing.copyOf()
    private val inputUsed = BooleanArray(players)
    private val alive = BooleanArray(players) { true }
    private val scores = IntArray(players)
    private val food = mutableListOf<ArcadeCell>()
    private var winner: Int? = null
    private var timedOut = false

    init { refillFood(true) }

    private fun refillFood(opening: Boolean = false) {
        val occupied = bodies.flatten().toSet()
        val candidates = cells.indices.filter { index ->
            val cell = ArcadeCell(index % width, index / width)
            cells[index] == 0 && cell !in occupied && cell !in food &&
                (!opening || (cell.y != 6 && bodies.all { kotlin.math.abs(it[0].x - cell.x) + kotlin.math.abs(it[0].y - cell.y) >= 3 }))
        }.toMutableList()
        while (food.size < players && candidates.isNotEmpty()) {
            val index = candidates.removeAt(random.nextInt(candidates.size))
            food.add(ArcadeCell(index % width, index / width))
        }
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || winner != null || !alive[seat] || inputUsed[seat]) return false
        if (!action.isIndex("direction", -1..3)) return false
        val direction = action.values[0]
        if (direction >= 0 && direction == (facing[seat] + 2) % 4) return false
        directions[seat] = direction
        inputUsed[seat] = true
        return true
    }

    override fun tick(elapsedMs: Int) = clock.advance(elapsedMs, { winner == null }) { step() }

    private fun step() {
        inputUsed.fill(false)
        val heads = bodies.map { it.first() }
        val targets = heads.mapIndexed { seat, head -> if (alive[seat]) head.move(directions[seat]) else head }
        val moving = BooleanArray(players) { alive[it] && directions[it] >= 0 }
        val growing = BooleanArray(players) { moving[it] && targets[it] in food }
        val dead = BooleanArray(players)
        for (seat in 0 until players) if (alive[seat]) {
            if (moving[seat] && cells[targets[seat].index(width)] == -1) dead[seat] = true
            for (other in 0 until players) if (alive[other]) {
                val occupied = if (moving[other] && !growing[other]) bodies[other].dropLast(1) else bodies[other]
                if (moving[seat] && targets[seat] in occupied) dead[seat] = true
                if (seat != other && (targets[seat] == targets[other] ||
                    (targets[seat] == heads[other] && targets[other] == heads[seat]))) {
                    dead[seat] = true
                    dead[other] = true
                }
            }
        }
        for (seat in 0 until players) if (alive[seat]) {
            if (dead[seat]) {
                alive[seat] = false
            } else if (moving[seat]) {
                facing[seat] = directions[seat]
                bodies[seat].add(0, targets[seat])
                if (growing[seat]) {
                    scores[seat]++
                    food.remove(targets[seat])
                } else bodies[seat].removeAt(bodies[seat].lastIndex)
            }
        }
        val survivors = alive.seats()
        if (survivors.size <= 1) winner = survivors.singleOrNull() ?: scoreWinner(scores, dead.seats())
        if (winner == null && clock.elapsed >= 180000) {
            timedOut = true
            winner = scoreWinner(scores, survivors)
        }
        if (winner == null) refillFood()
    }

    override fun snapshot(viewer: Int): GameSnapshot = GameSnapshot(
        gameId = id, players = players, turn = -1, phase = if (winner == null) "playing" else "finished",
        width = width, height = height, cells = cells.toList(), scores = scores.toList(), winner = winner, tick = clock.elapsed,
        entities = buildList {
            for (seat in 0 until players) if (alive[seat]) {
                bodies[seat].forEachIndexed { segment, cell ->
                    add(GameEntity(seat * 1000 + segment, if (segment == 0) "player" else "snake", seat, cell.x.toFloat(), cell.y.toFloat(), value = segment, angle = facing[seat] * 90f))
                }
            }
            food.forEachIndexed { index, cell -> add(GameEntity(10000 + index, "food", x = cell.x.toFloat(), y = cell.y.toFloat())) }
        },
        legal = if (viewer in 0 until players && alive[viewer] && winner == null && !inputUsed[viewer]) (-1..3).filter { it != (facing[viewer] + 2) % 4 }.map { GameAction("direction", listOf(it)) } else emptyList(),
        extra = mapOf("alive" to alive.csv(), "directions" to directions.joinToString(","), "stepMs" to "180", "countdown" to "${(180000 - clock.elapsed).coerceAtLeast(0)}"),
        message = when {
            winner == null -> "吃食物得1分并增长；每180毫秒前进一步，不可反向，可停止；撞墙或蛇身出局。三分钟按存活者得分结算。"
            timedOut -> "时间到，存活者按得分结算；${if (winner == -1) "平局" else "${winner!! + 1}号玩家获胜"}。"
            winner == -1 -> "同时出局且得分相同，平局。"
            else -> "${winner!! + 1}号玩家获胜；同时出局时比较得分。"
        }
    )
}
