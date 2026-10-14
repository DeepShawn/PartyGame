package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

class GomokuGame(config: GameConfig) : GameEngine {
    override val id = "gomoku"
    override val players = 2
    private val cells = IntArray(225)
    private val history = mutableListOf<Int>()
    private var turn = 0
    private var winner: Int? = null
    private var result = ""

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
        if (index !in 0..224 || cells[index] != 0) return false
        cells[index] = turn + 1
        history.add(index)
        winner = if (wins(index)) turn else null
        if (winner != null) {
            result = "five_in_row"
        } else if (history.size == 225) {
            winner = -1
            result = "draw"
        } else {
            turn = 1 - turn
        }
        return true
    }

    private fun wins(index: Int) = winAt(cells, index)

    override fun snapshot(viewer: Int) = GameSnapshot(
        id, players, turn, if (winner == null) "playing" else "finished", 15, 15,
        cells = cells.toList(),
        legal = if (viewer == turn && winner == null) buildList {
            for (i in 0..224) if (cells[i] == 0) add(GameAction("place", listOf(i)))
            add(GameAction("resign"))
        } else emptyList(),
        labels = listOf("黑方", "白方"), winner = winner, tick = history.size,
        extra = mapOf(
            "pieceEncoding" to "1=黑(先手) 2=白 0=空",
            "history" to history.joinToString(" "),
            "result" to result,
            "rules" to "无禁手自由规则：黑先，双方轮流落子；横、竖、斜任意方向五连或长连获胜；棋盘填满且无五连为和棋。"
        ),
        message = when {
            winner == null -> "轮到${if (turn == 0) "黑方" else "白方"}"
            result == "resignation" -> "${if (winner == 0) "黑方" else "白方"}认输"
            result == "draw" -> "和棋（棋盘已满）"
            else -> "${if (winner == 0) "黑方" else "白方"}五连获胜"
        }
    )

    override fun botAction(seat: Int, difficulty: Int): GameAction? {
        if (seat != turn || winner != null) return null
        val level = difficulty.coerceIn(0, 2)
        val candidates = (0..224).filter { cells[it] == 0 }
        if (candidates.isEmpty()) return null
        if (history.isEmpty()) return GameAction("place", listOf(112))
        val center = 112
        fun staticScore(cell: Int): Int {
            val attack = patternScore(cells, cell, seat + 1)
            val risk = patternScore(cells, cell, 2 - seat)
            return when {
                attack >= WIN -> 2_000_000
                risk >= WIN -> 1_000_000 + attack
                else -> attack + risk * 99 / 100 - distance(cell, center)
            }
        }
        fun lookahead(cell: Int, base: Int): Int {
            cells[cell] = seat + 1
            var reply = 0
            var nodes = 0
            for (opp in candidates) {
                if (opp == cell || nodes >= 120) continue
                nodes++
                val threat = patternScore(cells, opp, 2 - seat)
                if (threat > reply) reply = threat
            }
            cells[cell] = 0
            return base - reply / 2
        }
        var best = candidates.first()
        var bestScore = Int.MIN_VALUE
        val pool = if (level == 0) candidates else candidates.sortedByDescending { staticScore(it) }.take(10)
        for (cell in pool) {
            val base = staticScore(cell)
            val critical = base >= 1_000_000
            val score = when {
                level == 0 || critical -> base
                level == 1 -> base + openness(cell) * 3
                else -> lookahead(cell, base)
            }
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
        }
        return GameAction("place", listOf(best))
    }

    companion object {
        private const val WIN = 1_000_000
        private val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)

        private fun winAt(board: IntArray, index: Int): Boolean {
            val stone = board[index]
            if (stone == 0) return false
            val x = index % 15
            val y = index / 15
            for ((dx, dy) in directions) {
                var count = 1
                for (step in listOf(1, -1)) {
                    var cx = x + dx * step
                    var cy = y + dy * step
                    while (cx in 0..14 && cy in 0..14 && board[cy * 15 + cx] == stone) {
                        count++
                        cx += dx * step
                        cy += dy * step
                    }
                }
                if (count >= 5) return true
            }
            return false
        }

        private fun patternScore(board: IntArray, cell: Int, stone: Int): Int {
            if (board[cell] != 0) return 0
            board[cell] = stone
            var total = 0
            val x = cell % 15
            val y = cell / 15
            for ((dx, dy) in directions) {
                var run = 1
                var open = 0
                for (step in listOf(1, -1)) {
                    var cx = x + dx * step
                    var cy = y + dy * step
                    var length = 0
                    while (cx in 0..14 && cy in 0..14 && board[cy * 15 + cx] == stone) {
                        length++
                        cx += dx * step
                        cy += dy * step
                    }
                    run += length
                    if (cx in 0..14 && cy in 0..14 && board[cy * 15 + cx] == 0) open++
                }
                total += shapeScore(run, open)
            }
            board[cell] = 0
            return total
        }

        private fun shapeScore(run: Int, open: Int): Int = when {
            run >= 5 -> WIN
            run == 4 && open == 2 -> 50_000
            run == 4 && open == 1 -> 4_000
            run == 3 && open == 2 -> 2_000
            run == 3 && open == 1 -> 300
            run == 2 && open == 2 -> 100
            run == 2 && open == 1 -> 20
            run == 1 && open == 2 -> 8
            run == 1 && open == 1 -> 2
            else -> 0
        }

        private fun distance(cell: Int, center: Int): Int {
            val dx = cell % 15 - center % 15
            val dy = cell / 15 - center / 15
            return if (dx < 0) -dx + if (dy < 0) -dy else dy else dx + if (dy < 0) -dy else dy
        }

        private fun openness(cell: Int): Int {
            val x = cell % 15
            val y = cell / 15
            return 4 - (if (x == 0 || x == 14) 1 else 0) - (if (y == 0 || y == 14) 1 else 0)
        }
    }
}
