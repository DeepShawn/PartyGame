package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.math.abs

class DraughtsGame(config: GameConfig) : GameEngine {
    override val id = "draughts"
    override val players = 2
    private var cells = IntArray(100)
    private var turn = 0
    private var winner: Int? = null
    private var result = ""
    private val history = mutableListOf<String>()
    private val repetitions = mutableMapOf<String, Int>()
    private var kingOnlyPlies = 0

    init {
        val setup = config.options["setup"]
        if (setup == null) {
            for (y in 0..9) for (x in 0..9) if (isDark(x, y)) {
                cells[y * 10 + x] = when (y) {
                    in 0..3 -> -1
                    in 6..9 -> 1
                    else -> 0
                }
            }
        } else {
            val rows = setup.split("/")
            require(rows.size == 10) { "setup must contain 10 rows" }
            rows.forEachIndexed { y, row ->
                require(row.length == 10) { "setup rows must contain 10 columns" }
                row.forEachIndexed { x, ch ->
                    val value = when (ch) {
                        '.' -> 0
                        'w' -> 1
                        'W' -> 2
                        'b' -> -1
                        'B' -> -2
                        else -> throw IllegalArgumentException("unexpected setup cell '$ch'")
                    }
                    if (value != 0) require(isDark(x, y)) { "pieces must stand on dark squares" }
                    cells[y * 10 + x] = value
                }
            }
        }
        turn = when (config.options["turn"]) {
            null, "w" -> 0
            "b" -> 1
            else -> throw IllegalArgumentException("unexpected turn value")
        }
        repetitions[key()] = 1
        adjudicate()
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0..1 || winner != null || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            winner = 1 - seat
            result = "resignation"
            return true
        }
        if (action.type != "move" || action.values.size < 2) return false
        if (seat != turn) return false
        val path = action.values
        if (path.any { it !in 0..99 } || path.toSet().size != path.size) return false
        val captures = captureSequences(cells, turn)
        val legal = if (captures.isNotEmpty()) captures.filter { it.size == captures.maxOf { captured -> captured.size } } else quietMoves(cells, turn)
        if (legal.none { it == path }) return false
        val isCapture = captures.any { it == path }
        val piece = cells[path.first()]
        if (isCapture) {
            val targets = mutableListOf<Int>()
            var current = path.first()
            for (i in 1 until path.size) {
                val landing = path[i]
                val target = between(cells, current, landing)
                if (target < 0) return false
                targets.add(target)
                current = landing
            }
            targets.forEach { cells[it] = 0 }
        }
        cells[path.first()] = 0
        val last = path.last()
        cells[last] = piece
        if (abs(piece) == 1 && last / 10 == if (piece > 0) 0 else 9) cells[last] = if (piece > 0) 2 else -2
        history.add(path.joinToString(">"))
        kingOnlyPlies = if (isCapture || abs(piece) == 1) 0 else kingOnlyPlies + 1
        turn = 1 - turn
        val count = (repetitions[key()] ?: 0) + 1
        repetitions[key()] = count
        if (count >= 3) {
            winner = -1
            result = "threefold_repetition"
        }
        adjudicate()
        return true
    }

    private fun adjudicate() {
        if (winner != null) return
        val mover = 1 - turn
        when {
            countPieces(turn) == 0 -> {
                winner = mover
                result = "no_pieces"
            }
            legalFor(cells, turn).isEmpty() -> {
                winner = mover
                result = "blocked"
            }
            kingOnlyPlies >= 50 -> {
                winner = -1
                result = "king_moves"
            }
        }
    }

    private fun legalFor(board: IntArray, side: Int): List<List<Int>> {
        val captures = captureSequences(board, side)
        if (captures.isEmpty()) return quietMoves(board, side)
        val max = captures.maxOf { it.size }
        return captures.filter { it.size == max }
    }

    private fun captureSequences(board: IntArray, side: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (start in 0..99) {
            if (board[start] * sign(side) <= 0) continue
            extendCaptures(board, side, start, abs(board[start]) == 2, mutableListOf(start), mutableSetOf(), out)
        }
        return out
    }

    private fun extendCaptures(
        board: IntArray,
        side: Int,
        current: Int,
        isKing: Boolean,
        path: MutableList<Int>,
        captured: MutableSet<Int>,
        out: MutableList<List<Int>>
    ) {
        var extended = false
        for ((dx, dy) in DIAGONALS) {
            if (isKing) {
                var cx = current % 10 + dx
                var cy = current / 10 + dy
                while (cx in 0..9 && cy in 0..9 && board[cy * 10 + cx] == 0) {
                    cx += dx
                    cy += dy
                }
                if (cx !in 0..9 || cy !in 0..9) continue
                val target = cy * 10 + cx
                if (board[target] * sign(side) >= 0 || target in captured) continue
                var lx = cx + dx
                var ly = cy + dy
                while (lx in 0..9 && ly in 0..9 && board[ly * 10 + lx] == 0) {
                    val landing = ly * 10 + lx
                    extended = true
                    captured.add(target)
                    path.add(landing)
                    extendCaptures(board, side, landing, true, path, captured, out)
                    path.removeAt(path.size - 1)
                    captured.remove(target)
                    lx += dx
                    ly += dy
                }
            } else {
                val nx = current % 10 + dx
                val ny = current / 10 + dy
                val tx = nx + dx
                val ty = ny + dy
                if (nx !in 0..9 || ny !in 0..9 || tx !in 0..9 || ty !in 0..9) continue
                val target = ny * 10 + nx
                val landing = ty * 10 + tx
                if (board[target] * sign(side) >= 0 || target in captured) continue
                if (board[landing] != 0) continue
                extended = true
                captured.add(target)
                path.add(landing)
                extendCaptures(board, side, landing, false, path, captured, out)
                path.removeAt(path.size - 1)
                captured.remove(target)
            }
        }
        if (!extended && path.size >= 2) out.add(path.toList())
    }

    private fun quietMoves(board: IntArray, side: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for (start in 0..99) {
            if (board[start] * sign(side) <= 0) continue
            val isKing = abs(board[start]) == 2
            val x = start % 10
            val y = start / 10
            for ((dx, dy) in DIAGONALS) {
                if (isKing) {
                    var cx = x + dx
                    var cy = y + dy
                    while (cx in 0..9 && cy in 0..9 && board[cy * 10 + cx] == 0) {
                        out.add(listOf(start, cy * 10 + cx))
                        cx += dx
                        cy += dy
                    }
                } else {
                    if (dy != if (side == 0) -1 else 1) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0..9 || ny !in 0..9) continue
                    if (board[ny * 10 + nx] != 0) continue
                    out.add(listOf(start, ny * 10 + nx))
                }
            }
        }
        return out
    }

    private fun between(board: IntArray, from: Int, to: Int): Int {
        val dx = (to % 10 - from % 10).compareTo(0)
        val dy = (to / 10 - from / 10).compareTo(0)
        var cx = from % 10 + dx
        var cy = from / 10 + dy
        while (cx != to % 10 || cy != to / 10) {
            if (board[cy * 10 + cx] != 0) return cy * 10 + cx
            cx += dx
            cy += dy
        }
        return -1
    }

    private fun countPieces(side: Int): Int = if (side == 0) cells.count { it > 0 } else cells.count { it < 0 }

    private fun sign(side: Int) = if (side == 0) 1 else -1

    private fun isDark(x: Int, y: Int) = (x + y) % 2 == 1

    private fun key() = cells.joinToString(",") + ":" + turn

    private fun label(seat: Int) = if (seat == 0) "白方" else "黑方"

    override fun snapshot(viewer: Int): GameSnapshot {
        val champion = winner
        return GameSnapshot(
        id, players, turn, if (champion == null) "playing" else "finished", 10, 10,
        cells = cells.toList(),
        legal = if (viewer == turn && winner == null) buildList {
            for (path in legalFor(cells, turn)) add(GameAction("move", path))
            add(GameAction("resign"))
        } else emptyList(),
        scores = listOf(countPieces(0), countPieces(1)),
        labels = listOf("白方", "黑方"), winner = winner, tick = history.size,
        extra = mapOf(
            "pieceEncoding" to "1=白兵 2=白王 -1=黑兵 -2=黑王（浅色格恒为 0）",
            "history" to history.joinToString(" "),
            "result" to result,
            "rules" to "FMJD 10×10 规则：白先；有吃必吃且必须吃最多子数（连吃路径完整枚举、须走完）；连吃期间被吃子留在棋盘上、不得跳过已吃子；被吃子整步结束后移除；兵向前一步、吃子可向前或向后；兵停（或吃到）最后一排升王；王沿对角线任意距离行棋与吃子。和棋：同一局面（同方行棋）第三次出现，或连续 50 个半步（双方各 25 步）仅王移动且无吃子。"
        ),
        message = when {
            champion == null -> "轮到${label(turn)}"
            result == "resignation" -> "${label(1 - champion!!)}认输，${label(champion)}获胜"
            result == "no_pieces" -> "${label(champion)}获胜（吃光对方棋子）"
            result == "blocked" -> "${label(champion)}获胜（对方无棋可动）"
            result == "threefold_repetition" -> "三次重复局面，和棋"
            else -> "双方各 25 步仅王移动且无吃子，和棋"
        }
        )
    }

    override fun botAction(seat: Int, difficulty: Int): GameAction? {
        if (seat != turn || winner != null) return null
        val level = difficulty.coerceIn(0, 2)
        val captures = captureSequences(cells, seat)
        val legal = if (captures.isNotEmpty()) captures.filter { it.size == captures.maxOf { captured -> captured.size } } else quietMoves(cells, seat)
        if (legal.isEmpty()) return null
        if (legal.size == 1) return GameAction("move", legal[0])
        fun score(path: List<Int>): Int {
            val piece = cells[path.first()]
            val isCapture = captures.any { it == path }
            val landing = path.last()
            var value = 0
            if (isCapture) value += 100 * (path.size - 2)
            if (abs(piece) == 1 && landing / 10 == if (piece > 0) 0 else 9) value += 80
            val startRow = path.first() / 10
            val endRow = landing / 10
            value += (if (seat == 0) startRow - endRow else endRow - startRow) * 3
            if (abs(piece) == 2) value += 2
            return value
        }
        val pool = if (level == 0) legal else legal.sortedByDescending { score(it) }.take(8)
        var best = pool.first()
        var bestScore = Int.MIN_VALUE
        for (path in pool) {
            var value = score(path)
            if (level >= 1) {
                val moved = simulate(path, captures.any { it == path })
                val opponent = 1 - seat
                val reply = captureSequences(moved, opponent)
                val threat = if (reply.isEmpty()) 0 else reply.maxOf { it.size - 2 }
                value -= threat * (if (level == 2) 50 else 25)
            }
            if (value > bestScore) {
                bestScore = value
                best = path
            }
        }
        return GameAction("move", best)
    }

    private fun simulate(path: List<Int>, isCapture: Boolean): IntArray {
        val board = cells.copyOf()
        val piece = board[path.first()]
        if (isCapture) {
            var current = path.first()
            for (i in 1 until path.size) {
                val landing = path[i]
                val target = between(board, current, landing)
                if (target >= 0) board[target] = 0
                current = landing
            }
        }
        board[path.first()] = 0
        val last = path.last()
        board[last] = piece
        if (abs(piece) == 1 && last / 10 == if (piece > 0) 0 else 9) board[last] = if (piece > 0) 2 else -2
        return board
    }

    companion object {
        private val DIAGONALS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    }
}
