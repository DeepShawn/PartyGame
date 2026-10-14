package com.aicode.partyhub.games.arcade

import com.aicode.partyhub.core.GameAction

internal fun arcadePlayers(count: Int): Int {
    require(count in 2..3) { "Arcade games require two or three players" }
    return count
}

internal class ArcadeRandom(seed: Int) {
    private var state = if (seed == 0) 0x6D2B79F5 else seed

    fun nextInt(bound: Int): Int {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        return (state ushr 1) % bound
    }

    fun <T> shuffle(values: MutableList<T>) {
        for (i in values.lastIndex downTo 1) {
            val j = nextInt(i + 1)
            val old = values[i]
            values[i] = values[j]
            values[j] = old
        }
    }
}

internal class ArcadeClock(private val stepMs: Int) {
    var elapsed = 0
        private set
    private var pending = 0L

    fun advance(elapsedMs: Int, running: () -> Boolean, step: () -> Unit) {
        if (elapsedMs <= 0 || !running()) return
        pending += elapsedMs.toLong()
        while (pending >= stepMs && running()) {
            pending -= stepMs
            elapsed += stepMs
            step()
        }
    }
}

internal data class ArcadeCell(val x: Int, val y: Int) {
    fun move(direction: Int): ArcadeCell = when (direction) {
        0 -> copy(y = y - 1)
        1 -> copy(x = x + 1)
        2 -> copy(y = y + 1)
        3 -> copy(x = x - 1)
        else -> this
    }
    fun index(width: Int) = y * width + x
}

internal fun GameAction.isButton(name: String) = type == name && values.isEmpty() && text.isEmpty()
internal fun GameAction.isIndex(name: String, range: IntRange) =
    type == name && text.isEmpty() && values.size == 1 && values[0] in range

internal fun scoreWinner(scores: IntArray, candidates: List<Int> = scores.indices.toList()): Int {
    val best = candidates.maxOf { scores[it] }
    return candidates.filter { scores[it] == best }.singleOrNull() ?: -1
}

internal fun BooleanArray.seats() = indices.filter { this[it] }
internal fun BooleanArray.csv() = joinToString(",") { if (it) "1" else "0" }

internal fun arenaCells(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
    val x = index % width
    val y = index / width
    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) -1 else 0
}

internal fun blockedMoves(old: List<ArcadeCell>, proposed: List<ArcadeCell>, alive: BooleanArray): List<ArcadeCell> {
    val targets = proposed.toMutableList()
    for (i in old.indices) for (j in i + 1 until old.size) {
        if (!alive[i] || !alive[j]) continue
        if (proposed[i] == proposed[j] || (proposed[i] == old[j] && proposed[j] == old[i])) {
            targets[i] = old[i]
            targets[j] = old[j]
        }
    }
    var changed: Boolean
    do {
        changed = false
        for (i in old.indices) if (alive[i] && targets[i] != old[i]) {
            if (old.indices.any { j -> j != i && alive[j] && targets[j] == old[j] && targets[i] == old[j] }) {
                targets[i] = old[i]
                changed = true
            }
        }
    } while (changed)
    return targets
}
