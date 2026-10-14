package com.aicode.partyhub.ui

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameSnapshot

internal object GameInput {
    fun canAct(snapshot: GameSnapshot, localSeats: List<Int>, viewer: Int, seat: Int): Boolean {
        if (snapshot.winner != null || snapshot.phase == "finished" || seat !in localSeats || seat !in 0 until snapshot.players) return false
        return snapshot.turn < 0 || (viewer == seat && (seat == snapshot.turn || snapshot.legal.isNotEmpty()))
    }

    fun movesAt(legal: List<GameAction>, path: List<Int>): List<GameAction> = legal.filter {
        it.type == "move" && it.values.size >= path.size && it.values.take(path.size) == path
    }

    fun nextTargets(legal: List<GameAction>, path: List<Int>): Set<Int> = movesAt(legal, path)
        .mapNotNull { it.values.getOrNull(path.size) }.toSet()
}

internal class HandPrivacy {
    var revealedSeat: Int? = null
        private set
    private var previousTurn: Int? = null
    private var previousViewer: Int? = null
    private var previousSeats = emptyList<Int>()

    fun update(snapshot: GameSnapshot, localSeats: List<Int>, viewer: Int) {
        if (snapshot.gameId != "doudizhu" || localSeats.size < 2 || previousTurn != snapshot.turn || previousViewer != viewer || previousSeats != localSeats) revealedSeat = null
        previousTurn = snapshot.turn
        previousViewer = viewer
        previousSeats = localSeats.toList()
    }

    fun reveal(viewer: Int) { revealedSeat = viewer }

    fun visible(snapshot: GameSnapshot, localSeats: List<Int>, viewer: Int): Boolean =
        viewer in localSeats && viewer in 0 until snapshot.players &&
            (snapshot.extra["viewer"]?.toIntOrNull() ?: viewer) == viewer &&
            (localSeats.size == 1 || (viewer == snapshot.turn && revealedSeat == viewer))
}
