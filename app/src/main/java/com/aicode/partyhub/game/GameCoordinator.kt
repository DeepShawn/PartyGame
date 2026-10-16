package com.aicode.partyhub.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameCatalog
import com.aicode.partyhub.core.GameEngine
import com.aicode.partyhub.core.GameJson
import com.aicode.partyhub.core.GameSnapshot
import com.aicode.partyhub.net.Link
import com.aicode.partyhub.room.RoomController
import com.aicode.partyhub.room.RoomMessage
import com.aicode.partyhub.room.RoomSnapshot
import com.aicode.partyhub.security.SecureLink
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 对局协调器：本机同屏 / 房主 / 访客三种模式；房主为唯一权威，
 * 负责计时、AI 补位与按接收者裁剪的广播。外部引擎（Stockfish/KataGo）只在房主端运行。
 */
class GameCoordinator(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLobby(room: RoomSnapshot)
        fun onGame(snapshot: GameSnapshot, localSeats: List<Int>, viewer: Int)
        fun onStatus(text: String)
        fun onClosed(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "party-engine").apply { isDaemon = true }
    }
    private var closed = false

    // —— 本机同屏 ——
    private var localEngine: GameEngine? = null
    private var localHumans: List<Int> = emptyList()
    private var localGameId = ""

    // —— 房主 ——
    private var room: RoomController? = null
    private var hostMemberId = ""
    private var roomPassword = ""
    private val rawLinks = mutableMapOf<String, SecureLink>()
    private val guestNames = mutableMapOf<String, String?>()
    private var hostSequence = 0
    private var hostRealtime = false

    // —— 访客 ——
    private var guestLink: SecureLink? = null
    private var guestRawId: String? = null
    private var guestName = ""
    private var guestSequence = 0
    private var guestMatch = -1
    private var guestRoomId = ""
    private var guestPassword = ""

    // —— 外部引擎 ——
    private var stockfish: StockfishEngine? = null
    private var katago: KataGoEngine? = null
    private val botBusy = mutableSetOf<Int>()

    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            localEngine?.let { engine ->
                engine.tick(TICK_MS)
                runLocalBots(engine)
                emitLocal(engine)
            }
            room?.let { controller ->
                controller.tick(TICK_MS)
                runHostBots(controller)
                if (hostRealtime) broadcast()
            }
            main.postDelayed(this, TICK_MS.toLong())
        }
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS.toLong())
    }

    // ================= 本机同屏 =================

    fun startLocal(gameId: String, humans: List<Int>, total: Int, seed: Int, difficulty: Int, options: Map<String, String> = emptyMap()): Boolean {
        val info = GameCatalog.entries.firstOrNull { it.id == gameId } ?: return false
        if (humans.isEmpty() || humans.any { it !in 0 until total }) return false
        if (total !in info.minPlayers..info.maxPlayers) return false
        val bots = (0 until total).filter { it !in humans }
        if (bots.isNotEmpty() && !info.bot) return false
        val engine = GameFactory.create(gameId, total, seed, difficulty, options) ?: return false
        localEngine = engine
        localHumans = humans
        localGameId = gameId
        listener.onStatus(if (bots.isEmpty()) "" else "AI 已补位：${bots.joinToString("、") { "席位 ${it + 1}" }}")
        emitLocal(engine)
        startTicker()
        return true
    }

    fun localAction(seat: Int, action: GameAction): Boolean {
        val engine = localEngine ?: return false
        if (seat !in localHumans) return false
        val applied = engine.apply(seat, action)
        if (applied) emitLocal(engine)
        return applied
    }

    private fun runLocalBots(engine: GameEngine) {
        val view = engine.snapshot(-1)
        if (view.winner != null || view.phase == "finished") return
        val total = view.players
        for (seat in 0 until total) {
            if (seat in localHumans || seat in botBusy) continue
            if (!(view.turn == seat || (view.turn < 0 && view.realtimeFor(localGameId)))) continue
            botBusy.add(seat)
            requestBotAction(localGameId, engine, seat, view) { action ->
                main.post {
                    botBusy.remove(seat)
                    if (!closed && action != null) {
                        engine.apply(seat, action) 
                        localEngine?.let { emitLocal(it) }
                    }
                }
            }
        }
    }

    private fun emitLocal(engine: GameEngine) {
        val viewer = preferredViewer(engine.snapshot(-1), localHumans)
        listener.onGame(engine.snapshot(viewer), localHumans, viewer)
    }

    private fun preferredViewer(view: GameSnapshot, humans: List<Int>): Int {
        if (humans.size > 1 && view.turn in humans) return view.turn
        return humans.firstOrNull() ?: 0
    }

    // ================= 房主 =================

    fun startHost(roomId: String, gameId: String, capacity: Int, password: String, hostName: String): Boolean {
        if (room != null) return false
        val controller = RoomController(roomId, gameId, capacity)
        hostMemberId = UUID.randomUUID().toString()
        if (!controller.addMember(hostMemberId, hostName, true)) return false
        room = controller
        roomPassword = password
        listener.onLobby(controller.snapshot(hostMemberId))
        startTicker()
        return true
    }

    fun registerRaw(link: Link): SecureLink {
        val rawId = link.id
        val secure = SecureLink.host(link, room?.roomId ?: "", roomPassword, object : SecureLink.Handler {
            override fun onReady(link: SecureLink) {
                main.post {
                    guestNames[link.id] = null
                    listener.onStatus("有玩家正在验证口令")
                }
            }

            override fun onMessage(link: SecureLink, text: String) = onGuestMessage(link.id, text)

            override fun onClosed(link: SecureLink, reason: String) {
                main.post {
                    rawLinks.remove(rawId)
                    dropGuest(link.id, reason)
                }
            }
        })
        rawLinks[rawId] = secure
        return secure
    }

    fun handleRawMessage(linkId: String, text: String) {
        rawLinks[linkId]?.handleRawMessage(text)
    }

    fun rawLinkClosed(linkId: String, reason: String?) {
        rawLinks[linkId]?.rawClosed(reason)
        rawLinks.remove(linkId)
    }

    private fun onGuestMessage(linkId: String, text: String) {
        val controller = room ?: return
        val message = try {
            GameJson.decodeFromString(RoomMessage.serializer(), text)
        } catch (_: Exception) {
            guestNames[linkId]?.let { rejectGuest(linkId, "消息格式无效") }
            return
        }
        main.post {
            when (message.type) {
                "join" -> {
                    if (guestNames[linkId] != null) return@post
                    val name = message.name.trim().take(20)
                    if (name.isEmpty()) { rejectGuest(linkId, "昵称无效"); return@post }
                    if (!controller.addMember(linkId, name, false)) { rejectGuest(linkId, "房间已满或对局已开始"); return@post }
                    guestNames[linkId] = name
                    listener.onStatus("$name 已加入")
                    broadcast()
                }
                "ready" -> {
                    if (guestNames[linkId] == null) return@post
                    controller.setReady(linkId, message.ready)
                    broadcast()
                }
                "action" -> {
                    val action = message.action ?: return@post
                    if (guestNames[linkId] == null) return@post
                    if (controller.apply(linkId, message.matchId, message.sequence, action)) broadcast()
                    else resync(linkId, "操作无效或已过期，已同步最新状态")
                }
                "leave" -> dropGuest(linkId, "${guestNames[linkId] ?: "玩家"} 离开了房间")
                else -> Unit
            }
        }
    }

    private fun rejectGuest(linkId: String, reason: String) {
        val secure = rawLinks[linkId] ?: return
        val message = RoomMessage(type = "reject", message = reason)
        runCatching { secure.send(GameJson.encodeToString(RoomMessage.serializer(), message)) }
        secure.close()
        rawLinks.remove(linkId)
    }

    private fun resync(linkId: String, reason: String) {
        val controller = room ?: return
        val secure = rawLinks[linkId] ?: return
        val snapshot = controller.snapshot(linkId).copy(message = reason)
        val message = RoomMessage(type = "room", room = snapshot)
        runCatching { secure.send(GameJson.encodeToString(RoomMessage.serializer(), message)) }
    }

    private fun dropGuest(linkId: String, reason: String) {
        val controller = room ?: return
        rawLinks.remove(linkId)
        guestNames.remove(linkId)
        controller.disconnect(linkId)
        listener.onStatus(reason)
        broadcast()
    }

    fun chooseGame(gameId: String, capacity: Int): Boolean {
        val controller = room ?: return false
        val ok = controller.chooseGame(hostMemberId, gameId, capacity)
        if (ok) broadcast()
        return ok
    }

    fun setHostReady(ready: Boolean): Boolean {
        val controller = room ?: return false
        val ok = controller.setReady(hostMemberId, ready)
        if (ok) broadcast()
        return ok
    }

    fun startMatch(seed: Int, difficulty: Int, options: Map<String, String> = emptyMap()): Boolean {
        val controller = room ?: return false
        if (controller.engine != null) return false
        val snapshot = controller.snapshot(hostMemberId)
        val info = GameCatalog.find(snapshot.gameId)
        val engine = GameFactory.create(snapshot.gameId, snapshot.capacity, seed, difficulty, options) ?: return false
        if (!controller.start(hostMemberId, engine)) return false
        botDifficultyOverride = difficulty.coerceIn(0, 2)
        hostRealtime = info.realtime
        broadcast()
        return true
    }

    fun applyHostAction(action: GameAction): Boolean {
        val controller = room ?: return false
        val applied = controller.apply(hostMemberId, controller.matchId, hostSequence++, action)
        if (applied) broadcast()
        return applied
    }

    fun returnToLobby(): Boolean {
        val controller = room ?: return false
        val ok = controller.returnToLobby(hostMemberId)
        if (ok) {
            hostSequence = 0
            botBusy.clear()
            stockfish?.close(); stockfish = null
            katago?.close(); katago = null
            broadcast()
        }
        return ok
    }

    private fun broadcast() {
        val controller = room ?: return
        val current = controller.snapshot(hostMemberId)
        for (linkId in rawLinks.keys.toList()) {
            if (current.members.none { it.id == linkId }) continue
            val secure = rawLinks[linkId] ?: continue
            val message = RoomMessage(type = "room", room = controller.snapshot(linkId))
            runCatching { secure.send(GameJson.encodeToString(RoomMessage.serializer(), message)) }
        }
        listener.onLobby(current)
        current.game?.let { listener.onGame(it, listOf(current.yourSeat), current.yourSeat) }
    }

    private fun runHostBots(controller: RoomController) {
        val engine = controller.engine ?: return
        val view = engine.snapshot(-1)
        if (view.winner != null || view.phase == "finished") return
        val info = GameCatalog.find(controller.gameId)
        for (seat in controller.botSeats()) {
            if (seat in botBusy) continue
            val isTurn = view.turn == seat || (view.turn < 0 && info.realtime)
            if (!isTurn) continue
            botBusy.add(seat)
            requestBotAction(controller.gameId, engine, seat, view) { action ->
                main.post {
                    botBusy.remove(seat)
                    if (!closed && action != null) {
                        controller.applyBot(seat, controller.matchId, action)
                        broadcast()
                    }
                }
            }
        }
    }

    // ================= 访客 =================

    fun startGuest(roomId: String, password: String, name: String) {
        guestRoomId = roomId
        guestPassword = password
        guestName = name
        guestSequence = 0
        guestMatch = -1
    }

    fun attachGuest(raw: Link) {
        val rawId = raw.id
        guestRawId = rawId
        val secure = SecureLink.guest(raw, guestRoomId, guestPassword, object : SecureLink.Handler {
            override fun onReady(link: SecureLink) {
                main.post {
                    val join = RoomMessage(type = "join", roomId = guestRoomId, name = guestName)
                    runCatching { link.send(GameJson.encodeToString(RoomMessage.serializer(), join)) }
                    listener.onStatus("已加入房间，等待房主确认")
                }
            }

            override fun onMessage(link: SecureLink, text: String) = onHostMessage(text)

            override fun onClosed(link: SecureLink, reason: String) {
                main.post { listener.onClosed(reason) }
            }
        })
        guestLink = secure
    }

    private fun onHostMessage(text: String) {
        val message = try {
            GameJson.decodeFromString(RoomMessage.serializer(), text)
        } catch (_: Exception) {
            listener.onClosed("收到无法解析的房间消息")
            return
        }
        main.post {
            when (message.type) {
                "room" -> {
                    val snapshot = message.room ?: return@post
                    guestMatch = snapshot.matchId
                    listener.onLobby(snapshot)
                    snapshot.game?.let { listener.onGame(it, if (snapshot.yourSeat >= 0) listOf(snapshot.yourSeat) else emptyList(), snapshot.yourSeat) }
                }
                "reject" -> listener.onClosed(message.message.ifEmpty { "被房主拒绝加入" })
                else -> Unit
            }
        }
    }

    fun guestReady(ready: Boolean) {
        val secure = guestLink ?: return
        val message = RoomMessage(type = "ready", ready = ready)
        runCatching { secure.send(GameJson.encodeToString(RoomMessage.serializer(), message)) }
    }

    fun handleGuestRawMessage(linkId: String, text: String) {
        if (linkId == guestRawId) guestLink?.handleRawMessage(text)
    }

    fun guestRawClosed(linkId: String, reason: String?) {
        if (linkId == guestRawId) guestLink?.rawClosed(reason)
    }

    fun guestAction(action: GameAction) {
        val secure = guestLink ?: return
        if (guestMatch < 0) return
        val message = RoomMessage(type = "action", matchId = guestMatch, sequence = guestSequence++, action = action)
        runCatching { secure.send(GameJson.encodeToString(RoomMessage.serializer(), message)) }
    }

    fun guestLeave() {
        val secure = guestLink ?: return
        val message = RoomMessage(type = "leave")
        runCatching { secure.send(GameJson.encodeToString(RoomMessage.serializer(), message)) }
        close()
    }

    // ================= AI 补位 =================

    private fun requestBotAction(gameId: String, engine: GameEngine, seat: Int, view: GameSnapshot, done: (GameAction?) -> Unit) {
        try {
            when (gameId) {
                "chess" -> {
                    val fen = engine.snapshot(-1).extra["fen"]
                    val legal = engine.snapshot(seat).legal
                    if (fen.isNullOrBlank() || legal.isEmpty()) { done(null); return }
                    worker.execute {
                        val action = runCatching { stockfish().bestMove(fen, moveTimeFor(), skillFor(hostDifficulty))?.let { uciToAction(it, legal) } }.getOrNull()
                        done(action ?: legal.firstOrNull())
                    }
                }
                "go" -> {
                    val extra = engine.snapshot(-1).extra
                    val size = extra["boardSize"]?.toIntOrNull() ?: 9
                    val komi = extra["komi"]?.toDoubleOrNull() ?: 7.5
                    val moves = extra["gtpMoves"]?.split(' ')?.filter { it.isNotBlank() }?.chunked(2)?.map { it.joinToString(" ") } ?: emptyList()
                    val color = if (view.turn == 0) "B" else "W"
                    val legal = engine.snapshot(seat).legal
                    worker.execute {
                        val action = runCatching { katago().genMove(size, komi, moves, color)?.let { gtpToAction(it, size, legal) } }.getOrNull()
                        done(action ?: legal.firstOrNull())
                    }
                }
                else -> done(engine.botAction(seat, hostDifficulty) ?: engine.snapshot(seat).legal.firstOrNull())
            }
        } catch (_: Exception) {
            done(null)
        }
    }

    private val hostDifficulty: Int
        get() = botDifficultyOverride ?: 1

    private var botDifficultyOverride: Int? = null

    fun setBotDifficulty(difficulty: Int) {
        botDifficultyOverride = difficulty.coerceIn(0, 2)
    }

    private fun skillFor(difficulty: Int): Int = intArrayOf(3, 10, 18)[difficulty.coerceIn(0, 2)]

    private fun moveTimeFor(): Int = intArrayOf(200, 600, 1500)[botDifficultyOverride ?: 1]

    @Synchronized
    private fun stockfish(): StockfishEngine {
        val current = stockfish
        if (current != null) return current
        val engineFile = StockfishEngine.locate(context) ?: error("Stockfish 未随应用打包")
        val created = StockfishEngine(engineFile, context.filesDir)
        stockfish = created
        return created
    }

    @Synchronized
    private fun katago(): KataGoEngine {
        val current = katago
        if (current != null) return current
        val files = EngineAssets.ensure(context) ?: error("KataGo 未随应用打包或配置缺失")
        val created = KataGoEngine(files.engine, files.config, files.model, context.filesDir)
        katago = created
        return created
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(ticker)
        localEngine = null
        room?.let { controller ->
            rawLinks.values.forEach { it.close() }
            rawLinks.clear()
            controller.returnToLobby(hostMemberId)
        }
        room = null
        guestLink?.close()
        guestLink = null
        stockfish?.close(); stockfish = null
        katago?.close(); katago = null
        worker.shutdown()
    }

    private fun GameSnapshot.realtimeFor(gameId: String): Boolean = GameCatalog.entries.firstOrNull { it.id == gameId }?.realtime == true

    companion object {
        const val TICK_MS = 100

        /** UCI 着法 → 共享规则动作（升变 2马3象4车5后）。 */
        fun uciToAction(uci: String, legal: List<GameAction>): GameAction? {
            val match = Regex("^([a-h][1-8])([a-h][1-8])([qrbn])?$").find(uci) ?: return null
            val from = squareIndex(match.groupValues[1])
            val to = squareIndex(match.groupValues[2])
            val promotion = match.groupValues[3].takeIf { it.isNotEmpty() }
                ?.let { mapOf("n" to 2, "b" to 3, "r" to 4, "q" to 5)[it] } ?: 0
            val candidates = legal.filter { it.type == "move" && it.values.size >= 2 && it.values[0] == from && it.values[1] == to }
            if (candidates.isEmpty()) return null
            if (promotion != 0) return candidates.firstOrNull { it.values.getOrNull(2) == promotion }
            return candidates.firstOrNull { it.values.size == 2 } ?: candidates.first()
        }

        /** GTP 着法（如 D4/pass）→ 共享规则动作。[rowFlip] 依棋盘索引约定，由 GoGame 测试守护。 */
        fun gtpToAction(gtp: String, boardSize: Int, legal: List<GameAction>): GameAction? {
            if (gtp.equals("pass", ignoreCase = true)) {
                return legal.firstOrNull { it.type == "pass" } ?: GameAction("pass")
            }
            val match = Regex("^([A-HJ-Ta-hj-t])(\\d{1,2})$").find(gtp.trim()) ?: return null
            val letters = "ABCDEFGHJKLMNOPQRST"
            val x = letters.indexOf(match.groupValues[1].first().uppercaseChar())
            val rowFromBottom = match.groupValues[2].toInt()
            if (x < 0 || rowFromBottom !in 1..boardSize) return null
            val y = boardSize - rowFromBottom
            val index = y * boardSize + x
            return legal.firstOrNull { it.type == "place" && it.values.firstOrNull() == index }
        }

        private fun squareIndex(square: String): Int {
            val file = square[0].code - 'a'.code
            val rank = 7 - (square[1].code - '1'.code)
            return rank * 8 + file
        }
    }
}
