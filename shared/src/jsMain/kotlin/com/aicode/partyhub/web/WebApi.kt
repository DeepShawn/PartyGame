@file:OptIn(ExperimentalJsExport::class)

package com.aicode.partyhub.web

import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameCatalog
import com.aicode.partyhub.core.GameConfig
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameJson
import com.aicode.partyhub.games.arcade.BomberGame
import com.aicode.partyhub.games.arcade.ColorGame
import com.aicode.partyhub.games.arcade.DodgeGame
import com.aicode.partyhub.games.arcade.MathGame
import com.aicode.partyhub.games.arcade.MemoryGame
import com.aicode.partyhub.games.arcade.ReactionGame
import com.aicode.partyhub.games.arcade.SnakeGame
import com.aicode.partyhub.games.arcade.TankGame
import com.aicode.partyhub.games.board.ChessGame
import com.aicode.partyhub.games.board.ChineseCheckersGame
import com.aicode.partyhub.games.board.DraughtsGame
import com.aicode.partyhub.games.board.GoGame
import com.aicode.partyhub.games.board.GomokuGame
import com.aicode.partyhub.games.board.ReversiGame
import com.aicode.partyhub.games.board.XiangqiGame
import com.aicode.partyhub.games.cards.DouDizhuGame
import com.aicode.partyhub.room.PROTOCOL_VERSION
import com.aicode.partyhub.room.RoomController
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * 网页端使用的共享核心入口：跨越 JS 边界只传 JSON 字符串，
 * 保证两端规则、席位与隐藏信息裁剪完全一致。
 */
@JsExport
object PartyCore {
    private var nextHandle = 1
    private val engines = mutableMapOf<Int, GameEngine>()
    private val rooms = mutableMapOf<Int, RoomController>()

    fun protocolVersion(): Int = PROTOCOL_VERSION

    fun catalog(): String = GameJson.encodeToString(GameCatalog.entries)

    fun newEngine(gameId: String, players: Int, seed: Int, difficulty: Int, optionsJson: String): Int {
        val options = try {
            if (optionsJson.isEmpty()) emptyMap() else {
                @Suppress("UNCHECKED_CAST")
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(optionsJson)
                (parsed as? kotlinx.serialization.json.JsonObject)?.entries
                    ?.associate { it.key to it.value.toString().trim('"') } ?: emptyMap()
            }
        } catch (_: Exception) { emptyMap() }
        if (players !in 2..3) return -1
        val config = GameConfig(players = players, seed = seed, difficulty = difficulty, options = options)
        val info = GameCatalog.entries.firstOrNull { it.id == gameId } ?: return -1
        val engine = when {
            info.realtime || gameId in ARCADE -> when (gameId) {
                "reaction" -> ReactionGame(config)
                "color" -> ColorGame(config)
                "math" -> MathGame(config)
                "memory" -> MemoryGame(config)
                "snake" -> SnakeGame(config)
                "tank" -> TankGame(config)
                "bomber" -> BomberGame(config)
                "dodge" -> DodgeGame(config)
                else -> return -1
            }
            else -> when (gameId) {
                "gomoku" -> GomokuGame(config)
                "xiangqi" -> XiangqiGame(config)
                "chess" -> ChessGame(config)
                "go" -> GoGame(config)
                "reversi" -> ReversiGame(config)
                "chinese_checkers" -> ChineseCheckersGame(config)
                "draughts" -> DraughtsGame(config)
                "doudizhu" -> DouDizhuGame(config)
                else -> return -1
            }
        }
        val handle = nextHandle++
        engines[handle] = engine
        return handle
    }

    fun apply(engine: Int, seat: Int, actionJson: String): Boolean {
        val target = engines[engine] ?: return false
        val action = runCatching { GameJson.decodeFromString<GameAction>(actionJson) }.getOrNull() ?: return false
        return target.apply(seat, action)
    }

    fun snapshot(engine: Int, viewer: Int): String {
        val target = engines[engine] ?: return ""
        return GameJson.encodeToString(target.snapshot(viewer))
    }

    fun tick(engine: Int, elapsedMs: Int) { engines[engine]?.tick(elapsedMs) }

    fun botAction(engine: Int, seat: Int, difficulty: Int): String {
        val target = engines[engine] ?: return ""
        val action = runCatching { target.botAction(seat, difficulty) }.getOrNull() ?: return ""
        return GameJson.encodeToString(action)
    }

    fun newRoom(roomId: String, gameId: String, capacity: Int): Int {
        if (roomId.isBlank() || roomId.length > 16 || capacity !in 2..3) return -1
        if (GameCatalog.entries.none { it.id == gameId }) return -1
        val handle = nextHandle++
        rooms[handle] = RoomController(roomId, gameId, capacity)
        return handle
    }

    fun roomAddMember(room: Int, id: String, name: String, host: Boolean): Boolean =
        rooms[room]?.addMember(id, name, host) ?: false

    fun roomSetReady(room: Int, id: String, ready: Boolean): Boolean =
        rooms[room]?.setReady(id, ready) ?: false

    fun roomChooseGame(room: Int, id: String, gameId: String, players: Int): Boolean =
        rooms[room]?.chooseGame(id, gameId, players) ?: false

    fun roomStart(room: Int, memberId: String, engine: Int): Boolean {
        val controller = rooms[room] ?: return false
        val target = engines[engine] ?: return false
        return controller.start(memberId, target)
    }

    fun roomApply(room: Int, id: String, matchId: Int, sequence: Int, actionJson: String): Boolean {
        val controller = rooms[room] ?: return false
        val action = runCatching { GameJson.decodeFromString<GameAction>(actionJson) }.getOrNull() ?: return false
        return controller.apply(id, matchId, sequence, action)
    }

    fun roomApplyBot(room: Int, seat: Int, matchId: Int, actionJson: String): Boolean {
        val controller = rooms[room] ?: return false
        val action = runCatching { GameJson.decodeFromString<GameAction>(actionJson) }.getOrNull() ?: return false
        return controller.applyBot(seat, matchId, action)
    }

    fun roomBotSeats(room: Int): String {
        val seats = rooms[room]?.botSeats() ?: return "[]"
        return seats.joinToString(prefix = "[", postfix = "]")
    }

    fun roomSnapshot(room: Int, id: String): String {
        val controller = rooms[room] ?: return ""
        return GameJson.encodeToString(controller.snapshot(id))
    }

    fun roomDisconnect(room: Int, id: String) { rooms[room]?.disconnect(id) }

    fun roomReconnect(room: Int, id: String): Boolean = rooms[room]?.reconnect(id) ?: false

    fun roomTick(room: Int, elapsedMs: Int) { rooms[room]?.tick(elapsedMs) }

    fun roomReturnToLobby(room: Int, id: String): Boolean = rooms[room]?.returnToLobby(id) ?: false

    fun release(handle: Int) {
        engines.remove(handle)
        rooms.remove(handle)
    }

    private val ARCADE = setOf("reaction", "color", "math", "memory", "snake", "tank", "bomber", "dodge")
}
