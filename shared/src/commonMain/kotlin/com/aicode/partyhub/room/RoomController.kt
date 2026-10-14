package com.aicode.partyhub.room

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameCatalog
import com.aicode.partyhub.core.GameEngine

class RoomController(val roomId: String, gameId: String, capacity: Int) {
    var gameId: String = gameId
        private set
    var capacity: Int = capacity
        private set
    var matchId: Int = 0
        private set
    var engine: GameEngine? = null
        private set
    private val members = linkedMapOf<String, RoomMember>()
    private val sequences = mutableMapOf<String, Int>()
    private val disconnectedMs = mutableMapOf<String, Int>()
    private val takeover = mutableSetOf<Int>()

    fun addMember(id: String, name: String, host: Boolean = false): Boolean {
        if (id.isBlank() || id.length > 96 || name.isBlank() || name.length > 20) return false
        if (members.containsKey(id) || members.size >= 3 || engine != null) return false
        if (host && members.values.any { it.host }) return false
        val occupied = members.values.map { it.seat }.toSet()
        val seat = (0 until capacity).firstOrNull { it !in occupied } ?: -1
        members[id] = RoomMember(id, name, seat, host, host)
        return true
    }

    fun setReady(id: String, ready: Boolean): Boolean {
        val member = members[id] ?: return false
        if (engine != null) return false
        members[id] = member.copy(ready = ready || member.host)
        return true
    }

    fun chooseGame(id: String, nextId: String, players: Int): Boolean {
        if (members[id]?.host != true || engine != null) return false
        val info = GameCatalog.entries.firstOrNull { it.id == nextId } ?: return false
        if (players !in info.minPlayers..info.maxPlayers) return false
        gameId = nextId
        capacity = players
        members.entries.toList().forEachIndexed { index, (key, member) ->
            members[key] = member.copy(seat = if (index < capacity) index else -1, ready = member.host)
        }
        return true
    }

    fun start(id: String, game: GameEngine): Boolean {
        if (members[id]?.host != true || engine != null) return false
        if (game.id != gameId || game.players != capacity) return false
        if (members.values.any { it.seat >= 0 && (!it.ready || !it.connected) }) return false
        val info = GameCatalog.find(gameId)
        if (!info.bot && members.values.count { it.seat >= 0 } < capacity) return false
        engine = game
        matchId++
        sequences.clear()
        takeover.clear()
        return true
    }

    fun apply(id: String, match: Int, sequence: Int, action: GameAction): Boolean {
        val member = members[id] ?: return false
        val game = engine ?: return false
        if (!member.connected || member.seat !in 0 until capacity || member.seat in takeover) return false
        if (match != matchId || sequence < 0 || sequence <= (sequences[id] ?: -1)) return false
        if (action.type.length > 32 || action.text.length > 256 || action.values.size > 64) return false
        sequences[id] = sequence
        return game.apply(member.seat, action)
    }

    fun applyBot(seat: Int, match: Int, action: GameAction): Boolean {
        if (match != matchId || seat !in botSeats()) return false
        return engine?.apply(seat, action) ?: false
    }

    fun botSeats(): List<Int> {
        if (engine == null || !GameCatalog.find(gameId).bot) return emptyList()
        val occupied = members.values.filter { it.seat >= 0 }.map { it.seat }.toSet()
        return (0 until capacity).filter { it !in occupied || it in takeover }
    }

    fun disconnect(id: String) {
        val member = members[id] ?: return
        if (member.host) return
        if (engine == null || member.seat < 0) {
            members.remove(id)
        } else {
            members[id] = member.copy(connected = false)
            disconnectedMs[id] = 0
        }
    }

    fun reconnect(id: String): Boolean {
        val member = members[id] ?: return false
        if (member.connected) return false
        members[id] = member.copy(connected = true)
        disconnectedMs.remove(id)
        takeover.remove(member.seat)
        sequences.remove(id)
        return true
    }

    fun tick(elapsedMs: Int) {
        if (elapsedMs !in 1..1000) return
        disconnectedMs.keys.toList().forEach { id ->
            val elapsed = (disconnectedMs[id] ?: 0) + elapsedMs
            disconnectedMs[id] = elapsed
            if (elapsed >= 20_000 && GameCatalog.find(gameId).bot) {
                members[id]?.let { takeover.add(it.seat) }
            }
        }
        engine?.tick(elapsedMs)
    }

    fun returnToLobby(id: String): Boolean {
        if (members[id]?.host != true) return false
        engine = null
        takeover.clear()
        disconnectedMs.clear()
        sequences.clear()
        members.entries.toList().forEach { (key, member) ->
            if (!member.connected) members.remove(key)
            else members[key] = member.copy(ready = member.host)
        }
        return true
    }

    fun snapshot(id: String): RoomSnapshot {
        val member = members[id]
        val game = if (member != null) engine?.snapshot(member.seat) else null
        return RoomSnapshot(
            roomId, gameId, capacity, if (member != null) members.values.toList() else emptyList(),
            if (engine == null) "lobby" else "playing", matchId, member?.seat ?: -1,
            if (member != null) botSeats() else emptyList(), game,
            if (member == null) "尚未通过房间验证" else ""
        )
    }
}
