package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

class ReversiGame(config: GameConfig) : GameEngine {
    override val id = "reversi"
    override val players = 2
    private val cells = IntArray(64)
    private val history = mutableListOf<Int>()
    private var turn = 0
    private var winner: Int? = null
    private var result = ""
    private var skipNote = ""

    init {
        val setup = config.options["setup"]
        if (setup == null) {
            cells[27] = 1
            cells[28] = 2
            cells[35] = 2
            cells[36] = 1
        } else {
            val rows = setup.split("/")
            require(rows.size == 8) { "setup must contain 8 rows" }
            rows.forEachIndexed { y, row ->
                require(row.length == 8) { "setup rows must contain 8 columns" }
                row.forEachIndexed { x, ch ->
                    cells[y * 8 + x] = when (ch) {
                        '.' -> 0
                        'b' -> 1
                        'w' -> 2
                        else -> throw IllegalArgumentException("unexpected setup cell '$ch'")
                    }
                }
            }
        }
        turn = when (config.options["turn"]) {
            null, "b" -> 0
            "w" -> 1
            else -> throw IllegalArgumentException("unexpected turn value")
        }
        refresh()
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0..1 || winner != null || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            winner = 1 - seat
            result = "resignation"
            return true
        }
        if (action.type != "place" || action.values.size != 1) return false
        if (seat != turn) return false
        val index = action.values[0]
        if (index !in 0..63) return false
        val flips = flipsFor(index, seat)
        if (flips.isEmpty()) return false
        flips.forEach { cells[it] = seat + 1 }
        cells[index] = seat + 1
        history.add(index)
        turn = 1 - turn
        refresh()
        return true
    }

    private fun refresh() {
        if (winner != null) return
        skipNote = ""
        if (movesFor(turn).isNotEmpty()) return
        val other = 1 - turn
        if (movesFor(other).isNotEmpty()) {
            skipNote = "${label(1 - turn)}无子可下，跳过"
            turn = other
            return
        }
        val black = cells.count { it == 1 }
        val white = cells.count { it == 2 }
        winner = when {
            black > white -> 0
            white > black -> 1
            else -> -1
        }
        result = "count"
    }

    private fun movesFor(seat: Int): List<Int> = (0..63).filter { flipsFor(it, seat).isNotEmpty() }

    private fun flipsFor(index: Int, seat: Int): List<Int> {
        if (cells[index] != 0) return emptyList()
        val player = seat + 1
        val result = mutableListOf<Int>()
        val x = index % 8
        val y = index / 8
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val line = mutableListOf<Int>()
            var cx = x + dx
            var cy = y + dy
            while (cx in 0..7 && cy in 0..7 && cells[cy * 8 + cx] == 3 - player) {
                line.add(cy * 8 + cx)
                cx += dx
                cy += dy
            }
            if (line.isNotEmpty() && cx in 0..7 && cy in 0..7 && cells[cy * 8 + cx] == player) result.addAll(line)
        }
        return result
    }

    override fun snapshot(viewer: Int): GameSnapshot {
        val champion = winner
        return GameSnapshot(
        id, players, turn, if (champion == null) "playing" else "finished", 8, 8,
        cells = cells.toList(),
        legal = if (viewer == turn && winner == null) buildList {
            for (index in movesFor(turn)) add(GameAction("place", listOf(index)))
            add(GameAction("resign"))
        } else emptyList(),
        scores = listOf(cells.count { it == 1 }, cells.count { it == 2 }),
        labels = listOf("黑方", "白方"), winner = winner, tick = history.size,
        extra = mapOf(
            "pieceEncoding" to "1=黑(先手) 2=白 0=空",
            "history" to history.joinToString(" "),
            "result" to result,
            "rules" to "黑先；落子必须在横、竖、斜任一方向夹住至少一枚对方棋子并翻转全部被夹住的棋子；一方无子可下自动跳过；双方均无子可下时子多者获胜，子数相同为和棋。"
        ),
        message = when {
            champion == null -> if (skipNote.isEmpty()) "轮到${label(turn)}" else skipNote
            result == "resignation" -> "${label(champion!!)}获胜（对方认输）"
            champion == -1 -> "双方均无子可下，平局"
            else -> "双方均无子可下，${label(champion)}获胜（黑${cells.count { it == 1 }}子 白${cells.count { it == 2 }}子）"
        }
        )
    }

    override fun botAction(seat: Int, difficulty: Int): GameAction? {
        if (seat != turn || winner != null) return null
        val level = difficulty.coerceIn(0, 2)
        val options = movesFor(seat)
        if (options.isEmpty()) return null
        fun weighted(index: Int, side: Int): Int {
            val flips = flipsFor(index, side)
            if (flips.isEmpty()) return Int.MIN_VALUE
            var score = flips.size * 10
            if (index in CORNERS) score += 600
            else if (adjacentToEmptyCorner(index)) score -= 100
            return score
        }
        val pool = if (level == 0) options else options.sortedByDescending { weighted(it, seat) }.take(8)
        var best = pool.first()
        var bestScore = Int.MIN_VALUE
        for (index in pool) {
            var score = weighted(index, seat)
            if (level >= 1) {
                val flips = flipsFor(index, seat)
                val saved = flips.map { it to cells[it] }
                flips.forEach { cells[it] = seat + 1 }
                cells[index] = seat + 1
                var reply = Int.MIN_VALUE
                for (opp in 0..63) {
                    val value = weighted(opp, 1 - seat)
                    if (value > reply) reply = value
                }
                cells[index] = 0
                saved.forEach { (cell, value) -> cells[cell] = value }
                if (reply != Int.MIN_VALUE) score -= reply
            }
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        return GameAction("place", listOf(best))
    }

    private fun adjacentToEmptyCorner(index: Int): Boolean {
        val x = index % 8
        val y = index / 8
        for (corner in CORNERS) {
            if (cells[corner] != 0) continue
            val cx = corner % 8
            val cy = corner / 8
            if ((cx - x) in -1..1 && (cy - y) in -1..1 && (cx != x || cy != y)) return true
        }
        return false
    }

    private fun label(seat: Int) = if (seat == 0) "黑方" else "白方"

    companion object {
        private val CORNERS = listOf(0, 7, 56, 63)
    }
}
