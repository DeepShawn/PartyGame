package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.math.abs
import kotlin.math.max

class ChineseCheckersGame(config: GameConfig) : GameEngine {
    override val id = "chinese_checkers"
    override val players = config.players.coerceIn(2, 3)
    private val cells = IntArray(289) { if (BOARD.valid[it]) 0 else -1 }
    private val history = mutableListOf<List<Int>>()
    private var turn = 0
    private var winner: Int? = null
    private var result = ""
    private var skipNote = ""

    init {
        if (config.options["setup"] == null) {
            camps()[0].forEach { cells[it] = 1 }
            when (players) {
                2 -> camps()[3].forEach { cells[it] = 2 }
                else -> {
                    camps()[2].forEach { cells[it] = 2 }
                    camps()[4].forEach { cells[it] = 3 }
                }
            }
        }
        config.options["setup"]?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
            val parts = entry.split(":")
            require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) { "setup entries must look like index:value" }
            val cell = parts[0].toIntOrNull() ?: throw IllegalArgumentException("bad setup index")
            val value = parts[1].toIntOrNull() ?: throw IllegalArgumentException("bad setup value")
            require(cell in 0..288 && BOARD.valid[cell]) { "setup cell must be a star point" }
            require(value in 1..players) { "setup value out of range" }
            cells[cell] = value
        }
        turn = config.options["turn"]?.let { raw ->
            raw.toIntOrNull() ?: throw IllegalArgumentException("bad turn value")
        } ?: 0
        require(turn in 0 until players) { "turn out of range" }
        refresh()
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0 until players || winner != null || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            winner = if (players == 2) 1 - seat else -1
            result = "resignation"
            return true
        }
        if (action.type != "move" || action.values.size < 2) return false
        if (seat != turn) return false
        val path = actionsFor(turn, cells).firstOrNull { it == action.values } ?: return false
        cells[path.first()] = 0
        cells[path.last()] = seat + 1
        history.add(path)
        if (homeCount(seat) == 10) {
            winner = seat
            result = "race"
            return true
        }
        turn = (turn + 1) % players
        refresh()
        return true
    }

    private fun refresh() {
        if (winner != null) return
        skipNote = ""
        var skips = 0
        while (skips < players) {
            if (actionsFor(turn, cells).isNotEmpty()) {
                if (skips > 0) skipNote = "${label(turn)}没有可走的路线，跳过"
                return
            }
            turn = (turn + 1) % players
            skips++
        }
        winner = -1
        result = "deadlock"
    }

    private fun actionsFor(seat: Int, board: IntArray): List<List<Int>> {
        val result = ArrayList<List<Int>>()
        for (from in 0..288) {
            if (board[from] != seat + 1) continue
            if (locked(seat, from)) continue
            for (k in 0..5) {
                val neighbor = BOARD.neighbors[from][k]
                if (neighbor >= 0 && board[neighbor] == 0) result.add(listOf(from, neighbor))
            }
            jumpDfs(board, from, from, mutableSetOf(from), mutableListOf(from), result)
        }
        return result
    }

    private fun jumpDfs(
        board: IntArray,
        from: Int,
        current: Int,
        visited: MutableSet<Int>,
        path: MutableList<Int>,
        out: MutableList<List<Int>>
    ) {
        for (k in 0..5) {
            val neighbor = BOARD.neighbors[current][k]
            if (neighbor < 0 || board[neighbor] == 0) continue
            val landing = BOARD.landings[current][k]
            if (landing < 0 || board[landing] != 0 || landing in visited) continue
            visited.add(landing)
            path.add(landing)
            out.add(path.toList())
            jumpDfs(board, from, landing, visited, path, out)
            path.removeAt(path.size - 1)
            visited.remove(landing)
        }
    }

    private fun locked(seat: Int, cell: Int) = BOARD.campOf[cell] == targetCamp(seat)

    private fun camps() = BOARD.camps

    private fun startCamp(seat: Int) = if (players == 2) (if (seat == 0) 0 else 3) else seat * 2

    private fun targetCamp(seat: Int) = (startCamp(seat) + 3) % 6

    private fun homeCount(seat: Int) = camps()[targetCamp(seat)].count { cells[it] == seat + 1 }

    private fun label(seat: Int) = "玩家${seat + 1}"

    override fun snapshot(viewer: Int): GameSnapshot {
        val champion = winner
        return GameSnapshot(
        id, players, turn, if (champion == null) "playing" else "finished", 17, 17,
        cells = cells.toList(),
        legal = if (viewer == turn && winner == null) buildList {
            for (action in actionsFor(turn, cells)) add(GameAction("move", action))
            add(GameAction("resign"))
        } else emptyList(),
        scores = (0 until players).map { homeCount(it) },
        labels = (0 until players).map { label(it) },
        winner = winner, tick = history.size,
        extra = mapOf(
            "pieceEncoding" to "0=空 -1=星形外无效格 1/2/3=玩家1/2/3",
            "camps" to camps().joinToString(";") { camp -> camp.joinToString(",") },
            "result" to result,
            "rules" to "两人相对入座、三人相隔；每次走一枚棋子：相邻一步，或沿直线连续跳过紧邻棋子（可换方向、不吃子、可中途停、同一落点不得重复经过）；进入目标营地的棋子锁定不可再动；十子全部进入目标营地即获胜。"
        ),
        message = when {
            champion == null -> if (skipNote.isNotEmpty()) skipNote else "轮到${label(turn)}"
            result == "race" -> "${label(champion!!)}全部就位，获胜"
            result == "resignation" -> "有玩家认输，对局结束"
            else -> "无子可动，和棋"
        }
        )
    }

    override fun botAction(seat: Int, difficulty: Int): GameAction? {
        if (seat != turn || winner != null) return null
        val level = difficulty.coerceIn(0, 2)
        val actions = actionsFor(seat, cells)
        if (actions.isEmpty()) return null
        if (actions.size == 1) return GameAction("move", actions[0])
        fun actionScore(side: Int, path: List<Int>): Int {
            val target = targetCamp(side)
            val landing = path.last()
            val distance = camps()[target].minOf { BOARD.distance(landing, it) }
            var score = (20 - distance) * 10 + (path.size - 2) * 3
            if (BOARD.campOf[landing] == target) score += 15
            return score
        }
        val pool = if (level == 0) actions else actions.sortedByDescending { actionScore(seat, it) }.take(6)
        var best = pool.first()
        var bestScore = Int.MIN_VALUE
        for (path in pool) {
            var score = actionScore(seat, path) * (if (level == 0) 1 else 100)
            if (level >= 1) {
                val from = path.first()
                val to = path.last()
                val piece = cells[from]
                cells[from] = 0
                cells[to] = piece
                var threat = 0
                for (opponent in 0 until players) {
                    if (opponent == seat) continue
                    for (action in actionsFor(opponent, cells)) {
                        val value = actionScore(opponent, action)
                        if (value > threat) threat = value
                    }
                }
                cells[to] = 0
                cells[from] = piece
                score -= threat * (if (level == 2) 100 else 50)
            }
            if (score > bestScore) {
                bestScore = score
                best = path
            }
        }
        return GameAction("move", best)
    }

    private object BOARD {
        val valid = BooleanArray(289)
        val campOf = IntArray(289) { -1 }
        val camps = List(6) { ArrayList<Int>() }
        val neighbors = Array(289) { IntArray(6) { -1 } }
        val landings = Array(289) { IntArray(6) { -1 } }
        private val DIRECTIONS = listOf(1 to 0, 0 to 1, -1 to 1, -1 to 0, 0 to -1, 1 to -1)

        init {
            val ball = mutableSetOf<Pair<Int, Int>>()
            for (i in -4..4) for (j in -4..4) if (abs(i) <= 4 && abs(j) <= 4 && abs(i + j) <= 4) ball.add(i to j)
            val first = mutableSetOf<Pair<Int, Int>>()
            for (i in 5..8) for (j in -8..0) if (j >= i - 8) first.add(i to j)
            val triangles = mutableListOf<Set<Pair<Int, Int>>>()
            var current: Set<Pair<Int, Int>> = first
            repeat(6) {
                triangles.add(current)
                current = current.map { (i, j) -> (i + j) to -i }.toSet()
            }
            val star = mutableSetOf<Pair<Int, Int>>()
            star.addAll(ball)
            triangles.forEach { star.addAll(it) }
            val indexOf = mutableMapOf<Pair<Int, Int>, Int>()
            for ((i, j) in star) {
                val x = i + 8
                val y = j + 8
                valid[y * 17 + x] = true
                indexOf[i to j] = y * 17 + x
            }
            triangles.forEachIndexed { k, triangle ->
                triangle.forEach { point -> camps[k].add(indexOf.getValue(point)) }
                camps[k].sort()
            }
            for ((i, j) in star) {
                val index = indexOf.getValue(i to j)
                campOf[index] = triangles.indexOfFirst { (i to j) in it }
                DIRECTIONS.forEachIndexed { k, (di, dj) ->
                    val neighbor = (i + di) to (j + dj)
                    if (neighbor in star) neighbors[index][k] = indexOf.getValue(neighbor)
                    val landing = (i + 2 * di) to (j + 2 * dj)
                    if (landing in star) landings[index][k] = indexOf.getValue(landing)
                }
            }
        }

        fun distance(a: Int, b: Int): Int {
            val ai = a % 17 - 8
            val aj = a / 17 - 8
            val bi = b % 17 - 8
            val bj = b / 17 - 8
            val di = ai - bi
            val dj = aj - bj
            return max(abs(di), max(abs(dj), abs(di + dj)))
        }
    }
}
