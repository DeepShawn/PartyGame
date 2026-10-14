package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot
import kotlin.math.roundToInt

class GoGame(config: GameConfig) : GameEngine {
    override val id = "go"
    override val players = 2
    private val size = config.options["boardSize"]?.toIntOrNull()?.takeIf { it == 9 || it == 13 || it == 19 } ?: 9
    private val komi = config.options["komi"]?.toDoubleOrNull() ?: 7.5
    private var cells = IntArray(size * size)
    private var turn = 0
    private var phase = "playing"
    private var winner: Int? = null
    private var result = ""
    private var margin = ""
    private var winMargin = 0.0
    private var scoreText = ""
    private var scores = emptyList<Int>()
    private var finalCells: IntArray? = null
    private val deadSet = linkedSetOf<Int>()
    private val accepted = mutableSetOf<Int>()
    private var lastPass = false
    private var lastNote = ""
    private val history = mutableListOf<String>()
    private val positions = mutableSetOf<String>()

    init {
        positions.add(key(cells))
    }

    override fun apply(seat: Int, action: GameAction): Boolean {
        if (seat !in 0..1 || phase == "finished" || action.text.isNotEmpty()) return false
        if (action.type == "resign") {
            if (action.values.isNotEmpty()) return false
            winner = 1 - seat
            result = "resignation"
            phase = "finished"
            return true
        }
        if (action.type == "place") {
            if (phase != "playing" || seat != turn || action.values.size != 1) return false
            val index = action.values[0]
            if (index !in 0 until cells.size || cells[index] != 0) return false
            val next = cells.copyOf()
            next[index] = seat + 1
            for (neighbor in neighbors(index)) {
                if (next[neighbor] == 2 - seat && liberties(next, neighbor) == 0) removeGroup(next, neighbor)
            }
            if (liberties(next, index) == 0) return false
            val nextKey = key(next)
            if (nextKey in positions) return false
            cells = next
            positions.add(nextKey)
            history.add(token(index))
            lastPass = false
            lastNote = ""
            turn = 1 - turn
            return true
        }
        if (action.type == "pass") {
            if (phase != "playing" || seat != turn || action.values.isNotEmpty()) return false
            history.add("${if (seat == 0) "B" else "W"} pass")
            lastNote = "${label(seat)}停一手"
            if (lastPass) {
                phase = "scoring"
                deadSet.clear()
                accepted.clear()
            } else {
                lastPass = true
            }
            turn = 1 - turn
            return true
        }
        if (action.type == "dead") {
            if (phase != "scoring" || action.values.isEmpty()) return false
            if (action.values.any { it !in 0 until cells.size || cells[it] == 0 }) return false
            action.values.distinct().forEach { index -> if (!deadSet.add(index)) deadSet.remove(index) }
            accepted.clear()
            return true
        }
        if (action.type == "accept_score") {
            if (phase != "scoring" || action.values.isNotEmpty()) return false
            accepted.add(seat)
            if (accepted.size >= players) finalize()
            return true
        }
        if (action.type == "resume") {
            if (phase != "scoring" || action.values.isNotEmpty()) return false
            phase = "playing"
            turn = seat
            deadSet.clear()
            accepted.clear()
            lastPass = false
            lastNote = ""
            return true
        }
        return false
    }

    private fun finalize() {
        val board = cells.copyOf()
        deadSet.forEach { board[it] = 0 }
        var black = 0
        var white = 0
        for (stone in board) {
            when (stone) {
                1 -> black++
                2 -> white++
            }
        }
        val visited = BooleanArray(board.size)
        for (start in board.indices) {
            if (board[start] != 0 || visited[start]) continue
            val region = mutableListOf<Int>()
            val stack = mutableListOf(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val index = stack.removeAt(stack.lastIndex)
                region.add(index)
                for (neighbor in neighbors(index)) {
                    if (board[neighbor] == 0 && !visited[neighbor]) {
                        visited[neighbor] = true
                        stack.add(neighbor)
                    }
                }
            }
            var bordersBlack = false
            var bordersWhite = false
            for (cell in region) for (neighbor in neighbors(cell)) {
                if (board[neighbor] == 1) bordersBlack = true
                if (board[neighbor] == 2) bordersWhite = true
            }
            when {
                bordersBlack && !bordersWhite -> black += region.size
                bordersWhite && !bordersBlack -> white += region.size
            }
        }
        val whiteTotal = white + komi
        winner = when {
            black > whiteTotal -> 0
            whiteTotal > black -> 1
            else -> -1
        }
        scores = listOf(black, white)
        scoreText = "黑 $black 目，白 $white 目（含贴目 ${fmt(komi)}）"
        winMargin = kotlin.math.abs(black - whiteTotal)
        margin = when (winner) {
            0 -> "B+${fmt(winMargin)}"
            1 -> "W+${fmt(winMargin)}"
            else -> "draw"
        }
        result = "score"
        phase = "finished"
        finalCells = board
    }

    private fun key(board: IntArray) = board.joinToString(",")

    private fun neighbors(index: Int): List<Int> {
        val x = index % size
        val y = index / size
        val result = mutableListOf<Int>()
        if (x > 0) result.add(index - 1)
        if (x < size - 1) result.add(index + 1)
        if (y > 0) result.add(index - size)
        if (y < size - 1) result.add(index + size)
        return result
    }

    private fun liberties(board: IntArray, start: Int): Int {
        val color = board[start]
        val seen = mutableSetOf(start)
        val stack = mutableListOf(start)
        var count = 0
        while (stack.isNotEmpty()) {
            val index = stack.removeAt(stack.lastIndex)
            for (neighbor in neighbors(index)) {
                when {
                    board[neighbor] == 0 -> count++
                    board[neighbor] == color && seen.add(neighbor) -> stack.add(neighbor)
                }
            }
        }
        return count
    }

    private fun removeGroup(board: IntArray, start: Int) {
        val color = board[start]
        val stack = mutableListOf(start)
        board[start] = 0
        while (stack.isNotEmpty()) {
            val index = stack.removeAt(stack.lastIndex)
            for (neighbor in neighbors(index)) {
                if (board[neighbor] == color) {
                    board[neighbor] = 0
                    stack.add(neighbor)
                }
            }
        }
    }

    private fun label(seat: Int) = if (seat == 0) "黑方" else "白方"

    private fun token(index: Int): String {
        val x = index % size
        val y = index / size
        return "${if (turn == 0) "B" else "W"} ${LETTERS[x]}${size - y}"
    }

    private fun fmt(value: Double): String {
        val doubled = (value * 2).roundToInt()
        return if (doubled % 2 == 0) "${doubled / 2}" else "${doubled / 2}.5"
    }

    override fun snapshot(viewer: Int) = GameSnapshot(
        id, players, turn, phase, size, size,
        cells = (finalCells ?: cells).toList(),
        legal = when {
            phase == "playing" && viewer == turn -> buildList {
                for (index in 0 until cells.size) if (cells[index] == 0) add(GameAction("place", listOf(index)))
                add(GameAction("pass"))
                add(GameAction("resign"))
            }
            phase == "scoring" && viewer in 0..1 -> buildList {
                for (index in 0 until cells.size) if (cells[index] != 0) add(GameAction("dead", listOf(index)))
                add(GameAction("accept_score"))
                add(GameAction("resume"))
                add(GameAction("resign"))
            }
            else -> emptyList()
        },
        scores = scores,
        labels = listOf("黑方", "白方"), winner = winner, tick = history.size,
        extra = mapOf(
            "pieceEncoding" to "1=黑(先手) 2=白 0=空",
            "boardSize" to "$size",
            "komi" to komi.toString(),
            "gtpMoves" to history.joinToString(" "),
            "dead" to deadSet.sorted().joinToString(","),
            "result" to result,
            "margin" to margin,
            "score" to scoreText,
            "rules" to "黑先；无气棋子被提，禁止自杀，禁止全同再现（PSK：任何退回此前出现过的盘面的落子均非法，含打劫不能立即回提）；连续两次停一手进入数目阶段：双方各自标记死子并确认（标记变化会清除已确认），双方均确认后按中国面积规则（己方棋子＋仅被己方包围的空点）计分，白贴 ${"${fmt(komi)}"} 目；resume 可恢复对局并由请求方落子；scores 为双方实地子数（不含贴目）。"
        ),
        message = when {
            phase == "playing" -> if (lastNote.isNotEmpty()) lastNote else "轮到${label(turn)}"
            phase == "scoring" -> "数目阶段：标记死子后双方各确认一次（可恢复行棋）"
            result == "resignation" -> "${label(1 - winner!!)}认输，${label(winner!!)}获胜"
            winner == -1 -> "和棋（贴目后同分）"
            else -> "${label(winner!!)}胜 ${fmt(winMargin)} 目"
        }
    )

    override fun botAction(seat: Int, difficulty: Int): GameAction? = null

    companion object {
        private const val LETTERS = "ABCDEFGHJKLMNOPQRST"
    }
}
