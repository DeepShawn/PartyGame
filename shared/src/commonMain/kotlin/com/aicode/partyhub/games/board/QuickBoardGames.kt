package com.aicode.partyhub.games.board

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameSnapshot

abstract class TwoPlayerGrid(private val columns: Int, private val rows: Int, override val id: String) : GameEngine {
    override val players = 2
    protected val cells = IntArray(columns * rows)
    protected var turn = 0
    protected var winner: Int? = null
    protected var moves = 0
    protected abstract fun place(seat: Int, value: Int): Boolean
    protected abstract fun legalActions(): List<GameAction>
    protected abstract fun hasLine(seat: Int): Boolean
    protected abstract fun score(seat: Int): Int
    override fun apply(seat: Int, action: GameAction): Boolean {
        if (winner != null || seat != turn || action.type != "place" || action.values.size != 1) return false
        if (!place(seat, action.values[0])) return false
        moves++
        winner = when { hasLine(seat) -> seat; moves == cells.size -> -1; else -> null }
        if (winner == null) turn = 1 - turn
        return true
    }
    override fun snapshot(viewer: Int) = GameSnapshot(id, players, turn, if (winner == null) "playing" else "finished", columns, rows, cells.toList(), legal = if (winner == null && viewer == turn) legalActions() else emptyList(), scores = listOf(score(0), score(1)), labels = listOf("红方", "黄方"), winner = winner, tick = moves, message = if (winner == null) "轮到${if (turn == 0) "红方" else "黄方"}" else if (winner == -1) "棋盘已满，平局" else "${if (winner == 0) "红方" else "黄方"}获胜")
    override fun botAction(seat: Int, difficulty: Int): GameAction? {
        if (winner != null || seat != turn) return null
        val actions = legalActions(); if (actions.isEmpty()) return null
        val win = actions.firstOrNull { action -> simulate(action, seat) }
        if (win != null) return win
        val block = actions.firstOrNull { action -> simulate(action, 1 - seat) }
        return block ?: actions[(moves + difficulty.coerceIn(0, 2)) % actions.size]
    }
    private fun simulate(action: GameAction, seat: Int): Boolean {
        val backup = cells.copyOf(); val oldTurn = turn; val oldMoves = moves
        val ok = place(seat, action.values[0]); val result = ok && hasLine(seat)
        backup.copyInto(cells); turn = oldTurn; moves = oldMoves
        return result
    }
}

class TicTacToeGame(config: GameConfig) : TwoPlayerGrid(3, 3, "tictactoe") {
    override fun place(seat: Int, value: Int): Boolean { if (value !in cells.indices || cells[value] != 0) return false; cells[value] = seat + 1; return true }
    override fun legalActions() = cells.indices.filter { cells[it] == 0 }.map { GameAction("place", listOf(it)) }
    override fun hasLine(seat: Int): Boolean { val p = seat + 1; return (0..2).any { i -> cells[i * 3] == p && cells[i * 3 + 1] == p && cells[i * 3 + 2] == p || cells[i] == p && cells[i + 3] == p && cells[i + 6] == p } || cells[0] == p && cells[4] == p && cells[8] == p || cells[2] == p && cells[4] == p && cells[6] == p }
    override fun score(seat: Int) = cells.count { it == seat + 1 }
}

class ConnectFourGame(config: GameConfig) : TwoPlayerGrid(7, 6, "connect4") {
    override fun place(seat: Int, value: Int): Boolean { if (value !in 0 until 7) return false; for (row in 5 downTo 0) { val i = row * 7 + value; if (cells[i] == 0) { cells[i] = seat + 1; return true } }; return false }
    override fun legalActions() = (0 until 7).filter { cells[it] == 0 }.map { GameAction("place", listOf(it)) }
    override fun hasLine(seat: Int): Boolean { val p = seat + 1; for (y in 0 until 6) for (x in 0 until 7) if (listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1).any { (dx, dy) -> (0..3).all { n -> val xx = x + dx * n; val yy = y + dy * n; xx in 0..6 && yy in 0..5 && cells[yy * 7 + xx] == p } }) return true; return false }
    override fun score(seat: Int) = cells.count { it == seat + 1 }
}
