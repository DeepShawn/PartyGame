package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.math.abs
import kotlin.math.max

class XiangqiGame(config: GameConfig) : GameEngine {
    override val id = "xiangqi"
    override val players = 2
    private data class Move(val from: Int, val to: Int) {
        fun action() = GameAction("move", listOf(from, to))
    }
    private data class Record(val key: String, val mover: Int, val check: Boolean)
    private val initial = parse(config.options["fen"] ?: "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w")
    private var board = initial.first
    private var turn = initial.second
    private var moves = legalMoves(board, turn)
    private val records = mutableListOf(Record(key(board, turn), -1, false))
    private val history = mutableListOf<String>()
    private var quietPlies = 0
    private var winner: Int? = null
    private var result = ""

    init { adjudicate() }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0..1 || winner != null || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            winner = 1 - seat; result = "resignation"
            return true
        }
        if (seat != turn || action.type != "move" || action.values.size != 2) return false
        val move = moves.firstOrNull { it.from == action.values[0] && it.to == action.values[1] } ?: return false
        val progress = board[move.to] != 0 || abs(board[move.from]) == 1
        board = advance(board, move)
        turn = 1 - turn
        val check = inCheck(board, turn)
        quietPlies = if (progress || check) 0 else quietPlies + 1
        records.add(Record(key(board, turn), seat, check))
        history.add("${move.from}-${move.to}")
        moves = legalMoves(board, turn)
        adjudicate()
        return true
    }

    private fun adjudicate() {
        if (moves.isEmpty()) {
            winner = 1 - turn
            result = if (inCheck(board, turn)) "checkmate" else "stalemate"
            return
        }
        val repetition = repetitionWinner(records)
        if (repetition != null) {
            winner = repetition
            result = if (repetition == -1) "threefold_repetition" else "perpetual_check"
        } else if (quietPlies >= 120) {
            winner = -1; result = "no_progress"
        }
    }

    override fun snapshot(viewer: Int) = GameSnapshot(
        id, players, turn, if (winner == null) "playing" else "finished", 9, 10,
        cells = board.toList(), legal = if (viewer == turn && winner == null) moves.map { it.action() } else emptyList(),
        labels = listOf("红方", "黑方"), winner = winner, tick = history.size,
        extra = mapOf("history" to history.joinToString(" "), "result" to result, "check" to inCheck(board, turn).toString(),
            "rules" to "娱乐规则：红先；将死、困毙均负。含行棋方的同一局面第三次出现时，若仅一方在两个循环中每步均将军，长将方判负，否则和棋（双方长将亦和）。连续120步无吃子、无兵卒移动且无将军和棋。本版不采用正规竞赛的长捉、复杂循环与裁判裁定规则。"),
        message = if (winner != null) result else if (inCheck(board, turn)) "将军" else "轮到${if (turn == 0) "红方" else "黑方"}"
    )

    override fun botAction(seat: Int, difficulty: Int): GameAction? {
        if (seat != turn || seat !in 0..1 || winner != null) return null
        val level = difficulty.coerceIn(0, 2)
        val budget = listOf(128, 800, 2400)[level]
        var nodes = 0
        var exhausted = false
        val path = records.toMutableList()
        fun ordered(b: IntArray, available: List<Move>) = available.sortedByDescending {
            values[abs(b[it.to])] * 10 - values[abs(b[it.from])] + if (abs(b[it.from]) == 1) 8 else 0
        }
        fun search(b: IntArray, side: Int, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
            if (nodes >= budget) { exhausted = true; return evaluate(b, side) }
            nodes++
            val available = legalMoves(b, side)
            if (available.isEmpty()) return -100000 + ply
            val repeated = repetitionWinner(path)
            if (repeated != null) return if (repeated == -1) 0 else if (repeated == side) 90000 - ply else -90000 + ply
            if (depth == 0) return evaluate(b, side)
            var alpha = alphaIn
            var best = -100001
            for (move in ordered(b, available)) {
                val next = advance(b, move)
                path.add(Record(key(next, 1 - side), side, inCheck(next, 1 - side)))
                val value = -search(next, 1 - side, depth - 1, -beta, -alpha, ply + 1)
                path.removeAt(path.lastIndex)
                best = max(best, value); alpha = max(alpha, value)
                if (alpha >= beta || exhausted) break
            }
            return best
        }
        var chosen = moves.first()
        for (depth in 1..(level + 1)) {
            var best = -100001
            var candidate = chosen
            for (move in ordered(board, moves)) {
                val next = advance(board, move)
                path.add(Record(key(next, 1 - turn), turn, inCheck(next, 1 - turn)))
                val score = -search(next, 1 - turn, depth - 1, -100001, -best, 1)
                path.removeAt(path.lastIndex)
                if (exhausted) break
                if (score > best) { best = score; candidate = move }
            }
            if (exhausted) break
            chosen = candidate
        }
        return chosen.action()
    }

    companion object {
        private val values = intArrayOf(0, 100, 410, 200, 900, 450, 200, 20000)
        private val orthogonal = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        private val diagonal = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        private val horse = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        private fun sign(seat: Int) = if (seat == 0) 1 else -1
        private fun palace(y: Int, x: Int, side: Int) = x in 3..5 && y in (if (side == 1) 7..9 else 0..2)
        private fun key(board: IntArray, turn: Int) = board.joinToString(",") + ":$turn"
        private fun parse(fen: String): Pair<IntArray, Int> {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size == 2 || parts.size == 6)
            require(parts[1] in listOf("w", "r", "b"))
            val rows = parts[0].split('/')
            require(rows.size == 10)
            val board = IntArray(90)
            rows.forEachIndexed { y, row ->
                var x = 0
                for (ch in row) {
                    if (ch in '1'..'9') x += ch.digitToInt() else {
                        val normalized = when (ch.lowercaseChar()) { 'h' -> 'n'; 'e' -> 'b'; else -> ch.lowercaseChar() }
                        val piece = " pnbrcak".indexOf(normalized)
                        require(piece in 1..7 && x < 9)
                        board[y * 9 + x++] = piece * if (ch.isUpperCase()) 1 else -1
                    }
                    require(x <= 9)
                }
                require(x == 9)
            }
            require(board.count { it == 7 } == 1 && board.count { it == -7 } == 1)
            for (side in listOf(1, -1)) {
                val king = board.indexOf(side * 7)
                require(palace(king / 9, king % 9, side))
            }
            val turn = if (parts[1] == "b") 1 else 0
            require(!inCheck(board, 1 - turn)) { "The side that just moved cannot remain in check" }
            return board to turn
        }

        private fun targets(b: IntArray, from: Int): List<Int> {
            val piece = b[from]
            val side = if (piece > 0) 1 else -1
            val y = from / 9; val x = from % 9
            val result = mutableListOf<Int>()
            fun add(yy: Int, xx: Int) {
                if (yy in 0..9 && xx in 0..8 && b[yy * 9 + xx] * side <= 0) result.add(yy * 9 + xx)
            }
            when (abs(piece)) {
                1 -> {
                    add(y - side, x)
                    if (if (side == 1) y <= 4 else y >= 5) { add(y, x - 1); add(y, x + 1) }
                }
                2 -> for ((dy, dx) in horse) {
                    val leg = if (abs(dy) == 2) (y + dy / 2) * 9 + x else y * 9 + x + dx / 2
                    if (y + dy in 0..9 && x + dx in 0..8 && b[leg] == 0) add(y + dy, x + dx)
                }
                3 -> for ((dy, dx) in diagonal) {
                    val yy = y + dy * 2; val xx = x + dx * 2
                    if (yy in (if (side == 1) 5..9 else 0..4) && xx in 0..8 && b[(y + dy) * 9 + x + dx] == 0) add(yy, xx)
                }
                4, 5 -> for ((dy, dx) in orthogonal) {
                    var yy = y + dy; var xx = x + dx; var screen = false
                    while (yy in 0..9 && xx in 0..8) {
                        val occupied = b[yy * 9 + xx] != 0
                        if (abs(piece) == 4) {
                            add(yy, xx)
                            if (occupied) break
                        } else if (!screen) {
                            if (occupied) screen = true else add(yy, xx)
                        } else if (occupied) { add(yy, xx); break }
                        yy += dy; xx += dx
                    }
                }
                6 -> for ((dy, dx) in diagonal) if (palace(y + dy, x + dx, side)) add(y + dy, x + dx)
                7 -> {
                    for ((dy, dx) in orthogonal) if (palace(y + dy, x + dx, side)) add(y + dy, x + dx)
                    var yy = y - side
                    while (yy in 0..9) {
                        if (b[yy * 9 + x] != 0) {
                            if (b[yy * 9 + x] == -side * 7) result.add(yy * 9 + x)
                            break
                        }
                        yy -= side
                    }
                }
            }
            return result
        }

        private fun inCheck(b: IntArray, seat: Int): Boolean {
            val king = b.indexOf(sign(seat) * 7)
            if (king < 0) return true
            for (from in b.indices) if (b[from] * sign(seat) < 0 && king in targets(b, from)) return true
            return false
        }

        private fun legalMoves(b: IntArray, seat: Int): List<Move> = buildList {
            for (from in b.indices) if (b[from] * sign(seat) > 0) for (to in targets(b, from)) {
                if (abs(b[to]) == 7) continue
                val move = Move(from, to)
                if (!inCheck(advance(b, move), seat)) add(move)
            }
        }

        private fun advance(b: IntArray, move: Move): IntArray = b.copyOf().also { it[move.to] = it[move.from]; it[move.from] = 0 }

        private fun repetitionWinner(records: List<Record>): Int? {
            val same = records.indices.filter { records[it].key == records.last().key }
            if (same.size < 3) return null
            val start = same[same.size - 3]
            val checkingSides = (0..1).filter { seat ->
                val turns = records.subList(start + 1, records.size).filter { it.mover == seat }
                turns.isNotEmpty() && turns.all { it.check }
            }
            return if (checkingSides.size == 1) 1 - checkingSides[0] else -1
        }

        private fun evaluate(b: IntArray, seat: Int): Int {
            var value = 0
            for (i in b.indices) {
                val piece = b[i]
                if (piece == 0) continue
                val side = if (piece > 0) 1 else -1
                val progress = if (piece > 0) 9 - i / 9 else i / 9
                val bonus = if (abs(piece) == 1) progress * 8 + if (progress >= 5) 80 else 0 else if (abs(piece) in 2..5) 4 - abs(4 - i % 9) else 0
                value += side * (values[abs(piece)] + bonus)
            }
            return value * sign(seat)
        }
    }
}
