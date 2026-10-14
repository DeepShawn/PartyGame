package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.math.abs

class ChessGame(config: GameConfig) : GameEngine {
    override val id = "chess"
    override val players = 2
    private data class Position(val board: IntArray, val turn: Int, val rights: Int, val ep: Int, val half: Int, val full: Int)
    private data class Move(val from: Int, val to: Int, val promotion: Int = 0) {
        fun action() = GameAction("move", if (promotion == 0) listOf(from, to) else listOf(from, to, promotion))
        fun uci() = square(from) + square(to) + if (promotion == 0) "" else "  nbrq"[promotion].toString()
    }
    private var position = parse(config.options["fen"] ?: START)
    private val initialFen = fen(position)
    private val history = mutableListOf<String>()
    private val repetitions = mutableMapOf<String, Int>()
    private var moves = legalMoves(position)
    private var winner: Int? = null
    private var result = ""

    init {
        repetitions[key(position, moves)] = 1
        adjudicate()
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0..1 || winner != null || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            finish(1 - seat, "resignation")
            return true
        }
        if (seat != position.turn) return false
        if (action.type == "claim_draw") {
            val reason = if (action.values.isEmpty()) claimReason(position, moves, false) else {
                val move = match(action.values) ?: return false
                val next = advance(position, move)
                claimReason(next, legalMoves(next), true)
            }
            if (reason == null) return false
            finish(-1, reason)
            return true
        }
        if (action.type != "move") return false
        val move = match(action.values) ?: return false
        position = advance(position, move)
        history.add(move.uci())
        moves = legalMoves(position)
        val key = key(position, moves)
        repetitions[key] = (repetitions[key] ?: 0) + 1
        adjudicate()
        return true
    }

    private fun match(values: List<Int>): Move? {
        if (values.size !in 2..3) return null
        val from = values[0]
        val to = values[1]
        if (from !in 0..63 || to !in 0..63) return null
        val promoting = abs(position.board[from]) == 1 && to / 8 in listOf(0, 7)
        if (values.size == 3 && (!promoting || values[2] !in 2..5)) return null
        val promotion = if (promoting) values.getOrElse(2) { 5 } else 0
        return moves.firstOrNull { it.from == from && it.to == to && it.promotion == promotion }
    }

    private fun claimReason(p: Position, legal: List<Move>, prospective: Boolean): String? = when {
        (repetitions[key(p, legal)] ?: 0) + (if (prospective) 1 else 0) >= 3 -> "threefold_repetition"
        p.half >= 100 -> "fifty_move"
        else -> null
    }

    private fun finish(winningSeat: Int, reason: String) {
        winner = winningSeat
        result = reason
    }

    private fun adjudicate() {
        when {
            moves.isEmpty() -> if (inCheck(position, position.turn)) finish(1 - position.turn, "checkmate") else finish(-1, "stalemate")
            deadMaterial(position.board) -> finish(-1, "insufficient_material")
            (repetitions[key(position, moves)] ?: 0) >= 5 -> finish(-1, "fivefold_repetition")
            position.half >= 150 -> finish(-1, "seventy_five_move")
        }
    }

    override fun snapshot(viewer: Int): GameSnapshot {
        val legal = if (viewer == position.turn && winner == null) buildList {
            addAll(moves.map { it.action() })
            if (claimReason(position, moves, false) != null) add(GameAction("claim_draw"))
            else for (move in moves) {
                val next = advance(position, move)
                if ((next.half >= 100 || repetitions.values.any { it >= 2 }) && claimReason(next, legalMoves(next), true) != null)
                    add(GameAction("claim_draw", move.action().values))
            }
        } else emptyList()
        return GameSnapshot(
            id, players, position.turn, if (winner == null) "playing" else "finished", 8, 8,
            cells = position.board.toList(), legal = legal, labels = listOf("白方", "黑方"), winner = winner, tick = history.size,
            extra = mapOf("fen" to fen(position), "initialFen" to initialFen, "uciHistory" to history.joinToString(" "),
                "history" to history.joinToString(" "), "result" to result, "check" to inCheck(position, position.turn).toString(),
                "rules" to "白先；升变2马3象4车5后，省略默认后。三次重复或50回合可claim_draw，亦可附拟走move参数申请；五次重复或75回合自动和棋，将死优先。可证明的子力不足自动和棋；其他封锁死局需双方另行裁定。"),
            message = if (winner != null) result else if (inCheck(position, position.turn)) "将军" else "轮到${if (position.turn == 0) "白方" else "黑方"}"
        )
    }

    override fun botAction(seat: Int, difficulty: Int): GameAction? = null

    companion object {
        private const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private val knightSteps = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        private val directions = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
        private fun sign(seat: Int) = if (seat == 0) 1 else -1
        private fun square(index: Int) = "${'a' + index % 8}${8 - index / 8}"
        private fun parse(value: String): Position {
            val parts = value.trim().split(Regex("\\s+"))
            require(parts.size == 6) { "FEN must contain six fields" }
            val board = IntArray(64)
            val rows = parts[0].split('/')
            require(rows.size == 8)
            rows.forEachIndexed { y, row ->
                var x = 0
                for (ch in row) {
                    if (ch in '1'..'8') x += ch.digitToInt() else {
                        val piece = " pnbrqk".indexOf(ch.lowercaseChar())
                        require(piece in 1..6 && x < 8)
                        board[y * 8 + x++] = piece * if (ch.isUpperCase()) 1 else -1
                    }
                    require(x <= 8)
                }
                require(x == 8)
            }
            require(board.count { it == 6 } == 1 && board.count { it == -6 } == 1)
            require((0..7).all { abs(board[it]) != 1 && abs(board[56 + it]) != 1 })
            require(parts[1] == "w" || parts[1] == "b")
            val turn = if (parts[1] == "w") 0 else 1
            var rights = 0
            if (parts[2] != "-") {
                require(parts[2].isNotEmpty() && parts[2].toSet().size == parts[2].length)
                for (ch in parts[2]) {
                    val bit = "KQkq".indexOf(ch)
                    require(bit >= 0)
                    rights = rights or (1 shl bit)
                }
            }
            for ((bit, rook) in listOf(1 to 63, 2 to 56, 4 to 7, 8 to 0)) {
                if (rights and bit != 0) {
                    val side = if (bit <= 2) 1 else -1
                    require(board[rook] == side * 4 && board[if (side == 1) 60 else 4] == side * 6)
                }
            }
            val ep = if (parts[3] == "-") -1 else {
                require(parts[3].length == 2 && parts[3][0] in 'a'..'h' && parts[3][1] == if (turn == 0) '6' else '3')
                (8 - parts[3][1].digitToInt()) * 8 + (parts[3][0] - 'a')
            }
            if (ep >= 0) require(board[ep] == 0 && board[ep + sign(turn) * 8] == -sign(turn))
            val half = parts[4].toIntOrNull()
            val full = parts[5].toIntOrNull()
            require(half != null && half in 0..1000000 && full != null && full in 1..1000000)
            val p = Position(board, turn, rights, ep, half, full)
            require(!inCheck(p, 1 - turn)) { "The side that just moved cannot remain in check" }
            return p
        }

        private fun fen(p: Position): String = buildString {
            for (y in 0..7) {
                if (y > 0) append('/')
                var empty = 0
                for (x in 0..7) {
                    val piece = p.board[y * 8 + x]
                    if (piece == 0) empty++ else {
                        if (empty > 0) append(empty)
                        empty = 0
                        val ch = " pnbrqk"[abs(piece)]
                        append(if (piece > 0) ch.uppercaseChar() else ch)
                    }
                }
                if (empty > 0) append(empty)
            }
            append(if (p.turn == 0) " w " else " b ")
            append("KQkq".filterIndexed { i, _ -> p.rights and (1 shl i) != 0 }.ifEmpty { "-" })
            append(' '); append(if (p.ep < 0) "-" else square(p.ep))
            append(" ${p.half} ${p.full}")
        }

        private fun key(p: Position, legal: List<Move>): String {
            val ep = if (p.ep >= 0 && legal.any { it.to == p.ep && abs(p.board[it.from]) == 1 && it.from % 8 != it.to % 8 }) p.ep else -1
            return p.board.joinToString(",") + ":${p.turn}:${p.rights}:$ep"
        }

        private fun attacked(board: IntArray, target: Int, by: Int): Boolean {
            val side = sign(by)
            val y = target / 8; val x = target % 8
            val pawnY = y + side
            if (pawnY in 0..7) for (dx in listOf(-1, 1)) {
                if (x + dx in 0..7 && board[pawnY * 8 + x + dx] == side) return true
            }
            for ((dy, dx) in knightSteps) if (y + dy in 0..7 && x + dx in 0..7 && board[(y + dy) * 8 + x + dx] == side * 2) return true
            for ((i, d) in directions.withIndex()) {
                var yy = y + d.first; var xx = x + d.second; var distance = 1
                while (yy in 0..7 && xx in 0..7) {
                    val piece = board[yy * 8 + xx]
                    if (piece != 0) {
                        if (piece * side > 0 && (abs(piece) == 5 || abs(piece) == (if (i < 4) 3 else 4) || distance == 1 && abs(piece) == 6)) return true
                        break
                    }
                    yy += d.first; xx += d.second; distance++
                }
            }
            return false
        }

        private fun inCheck(p: Position, seat: Int): Boolean {
            val king = p.board.indexOf(sign(seat) * 6)
            return king < 0 || attacked(p.board, king, 1 - seat)
        }

        private fun legalMoves(p: Position): List<Move> {
            val candidates = mutableListOf<Move>()
            val side = sign(p.turn)
            fun add(from: Int, to: Int) {
                if (p.board[to] * side > 0 || abs(p.board[to]) == 6) return
                if (abs(p.board[from]) == 1 && to / 8 in listOf(0, 7)) for (piece in 2..5) candidates.add(Move(from, to, piece))
                else candidates.add(Move(from, to))
            }
            for (from in 0..63) {
                val piece = p.board[from]
                if (piece * side <= 0) continue
                val y = from / 8; val x = from % 8
                when (abs(piece)) {
                    1 -> {
                        val yy = y - side
                        if (yy !in 0..7) continue
                        if (p.board[yy * 8 + x] == 0) {
                            add(from, yy * 8 + x)
                            if (y == if (side == 1) 6 else 1) {
                                val to = (y - 2 * side) * 8 + x
                                if (p.board[to] == 0) add(from, to)
                            }
                        }
                        for (dx in listOf(-1, 1)) if (x + dx in 0..7) {
                            val to = yy * 8 + x + dx
                            if (p.board[to] * side < 0 || to == p.ep) add(from, to)
                        }
                    }
                    2 -> for ((dy, dx) in knightSteps) if (y + dy in 0..7 && x + dx in 0..7) add(from, (y + dy) * 8 + x + dx)
                    else -> {
                        for ((i, d) in directions.withIndex()) {
                            if (abs(piece) == 3 && i >= 4 || abs(piece) == 4 && i < 4) continue
                            var yy = y + d.first; var xx = x + d.second
                            while (yy in 0..7 && xx in 0..7) {
                                val to = yy * 8 + xx
                                add(from, to)
                                if (p.board[to] != 0 || abs(piece) == 6) break
                                yy += d.first; xx += d.second
                            }
                        }
                        if (abs(piece) == 6 && from == (if (side == 1) 60 else 4) && !inCheck(p, p.turn)) {
                            val offset = if (p.turn == 0) 0 else 2
                            for (kingSide in listOf(true, false)) {
                                val bit = 1 shl (offset + if (kingSide) 0 else 1)
                                val step = if (kingSide) 1 else -1
                                val rook = from + if (kingSide) 3 else -4
                                val spaces = if (kingSide) 1..2 else 1..3
                                if (p.rights and bit != 0 && p.board[rook] == side * 4 && spaces.all { p.board[from + it * step] == 0 }) {
                                    val transit = advance(p, Move(from, from + step))
                                    if (!inCheck(transit, p.turn)) candidates.add(Move(from, from + 2 * step))
                                }
                            }
                        }
                    }
                }
            }
            return candidates.filter { !inCheck(advance(p, it), p.turn) }
        }

        private fun advance(p: Position, move: Move): Position {
            val board = p.board.copyOf()
            val piece = board[move.from]
            val captured = board[move.to]
            board[move.from] = 0
            if (abs(piece) == 1 && move.to == p.ep && captured == 0 && move.from % 8 != move.to % 8) board[move.to + sign(p.turn) * 8] = 0
            board[move.to] = if (move.promotion == 0) piece else sign(p.turn) * move.promotion
            if (abs(piece) == 6 && abs(move.to - move.from) == 2) {
                val rook = move.from + if (move.to > move.from) 3 else -4
                board[(move.from + move.to) / 2] = board[rook]
                board[rook] = 0
            }
            var rights = p.rights
            if (abs(piece) == 6) rights = rights and (if (p.turn == 0) 12 else 3)
            for ((square, bit) in listOf(63 to 1, 56 to 2, 7 to 4, 0 to 8)) {
                if (move.from == square || move.to == square) rights = rights and bit.inv()
            }
            val ep = if (abs(piece) == 1 && abs(move.to - move.from) == 16) (move.to + move.from) / 2 else -1
            return Position(board, 1 - p.turn, rights, ep, if (abs(piece) == 1 || captured != 0) 0 else p.half + 1, p.full + p.turn)
        }

        private fun deadMaterial(board: IntArray): Boolean {
            val pieces = board.indices.filter { board[it] != 0 && abs(board[it]) != 6 }
            if (pieces.isEmpty()) return true
            if (pieces.size == 1) return abs(board[pieces[0]]) in 2..3
            return pieces.all { abs(board[it]) == 3 } && pieces.map { (it / 8 + it % 8) % 2 }.distinct().size == 1
        }
    }
}
