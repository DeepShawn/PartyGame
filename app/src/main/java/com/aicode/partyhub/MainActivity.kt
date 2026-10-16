package com.aicode.partyhub

import android.app.AlertDialog
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameSnapshot
import com.aicode.partyhub.game.GameCoordinator
import com.aicode.partyhub.net.LanDiscovery
import com.aicode.partyhub.net.LanRoom
import com.aicode.partyhub.net.LanWebServer
import com.aicode.partyhub.net.Link
import com.aicode.partyhub.net.LinkListener
import com.aicode.partyhub.net.TcpTransport
import com.aicode.partyhub.net.WebRtcTransport
import com.aicode.partyhub.room.RoomSnapshot
import com.aicode.partyhub.settings.AppearanceStore
import com.aicode.partyhub.ui.AppUi
import com.aicode.partyhub.net.SupabaseAuthClient
import com.aicode.partyhub.ui.UiLanRoom
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.random.Random

class MainActivity : AppCompatActivity(), GameCoordinator.Listener {

    private enum class Mode { NONE, LOCAL, HOST, GUEST }

    private lateinit var ui: AppUi
    private lateinit var coordinator: GameCoordinator
    private lateinit var store: AppearanceStore
    private var settings = AppearanceStore.Settings()

    private var mode = Mode.NONE
    private var roomId = ""
    private var roomPassword = ""
    private var hostGameId = ""
    private var hostCapacity = 2
    private var guestName = ""
    private var lastRoom: RoomSnapshot? = null
    private var lastLinkInfo = ""
    private var matchSeed = 1

    private var tcp: TcpTransport? = null
    private var lanWeb: LanWebServer? = null
    private var discovery: LanDiscovery? = null
    private var rtc: WebRtcTransport? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    private val foundRooms = linkedMapOf<String, UiLanRoom>()

    private val hostLinks = object : LinkListener {
        override fun onOpen(link: Link) { coordinator.registerRaw(link) }
        override fun onMessage(link: Link, text: String) { coordinator.handleRawMessage(link.id, text) }
        override fun onClosed(link: Link, reason: String?) { coordinator.rawLinkClosed(link.id, reason) }
    }

    private val guestLinks = object : LinkListener {
        override fun onOpen(link: Link) { coordinator.attachGuest(link) }
        override fun onMessage(link: Link, text: String) { coordinator.handleGuestRawMessage(link.id, text) }
        override fun onClosed(link: Link, reason: String?) { coordinator.guestRawClosed(link.id, reason) }
    }

    private val uiCallbacks = object : AppUi.Callbacks {
        override fun onAuth() = showAuthDialog()
        override fun onLocal(gameId: String, humans: Int, total: Int) = startLocal(gameId, humans, total)
        override fun onHost(gameId: String, total: Int, password: String, online: Boolean) =
            if (online) startInternetHost(gameId, total, password) else startLanHost(gameId, total, password)
        override fun onJoin(roomCode: String, password: String, online: Boolean) = startInternetGuest(roomCode, password)
        override fun onDiscover() = discoverRooms()
        override fun onConnectLan(address: String, port: Int, password: String) = startLanGuest(address, port, password)
        override fun onReady(ready: Boolean) {
            if (mode == Mode.GUEST) coordinator.guestReady(ready)
        }
        override fun onStart() {
            if (mode == Mode.HOST) {
                matchSeed = Random.nextInt(1, Int.MAX_VALUE)
                val options = if (hostGameId == "go") mapOf("boardSize" to "9") else emptyMap()
                coordinator.startMatch(matchSeed, settings.aiDifficulty, options)
            } else if (mode == Mode.LOCAL) {
                // 同屏由 onLocal 直接开局，无需再次开始
            }
        }
        override fun onAction(seat: Int, action: GameAction) {
            when (mode) {
                Mode.LOCAL -> coordinator.localAction(seat, action)
                Mode.HOST -> coordinator.applyHostAction(action)
                Mode.GUEST -> coordinator.guestAction(action)
                else -> Unit
            }
        }
        override fun onLeave() = leaveEverything()
        override fun onHint() { ui.showStatus("已为你选择一手可行牌，可直接出牌或自行调整") }
        override fun onSettingsChanged() {
            settings = store.load()
            coordinator.setBotDifficulty(settings.aiDifficulty)
        }
    }

    private lateinit var authClient: SupabaseAuthClient
    private fun showAuthDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0) }
        val email = EditText(this).apply { hint = "邮箱" }; val password = EditText(this).apply { hint = "密码"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(email); box.addView(password)
        AlertDialog.Builder(this).setTitle("账号与成绩").setView(box).setMessage(authClient.email()?.let { "当前账号：$it" } ?: "登录后成绩会同步到排行榜")
            .setPositiveButton("登录") { _, _ -> authClient.signIn(email.text.toString(), password.text.toString()) { ok, msg -> runOnUiThread { ui.showStatus(msg) } } }
            .setNeutralButton("注册") { _, _ -> authClient.signUp(email.text.toString(), password.text.toString()) { ok, msg -> runOnUiThread { ui.showStatus(msg) } } }
            .setNegativeButton(if (authClient.email() == null) "关闭" else "退出登录") { _, _ -> if (authClient.email() != null) { authClient.signOut(); ui.showStatus("已退出登录") } }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authClient = SupabaseAuthClient(this)
        store = AppearanceStore(this)
        settings = store.load()
        coordinator = GameCoordinator(this, this)
        coordinator.setBotDifficulty(settings.aiDifficulty)
        ui = AppUi(this, uiCallbacks)
        ui.showHome()
    }

    // ==================== 本机同屏 ====================

    private fun startLocal(gameId: String, humans: Int, total: Int) {
        leaveTransports(keepUi = true)
        mode = Mode.LOCAL
        val seats = (0 until humans.coerceIn(1, total)).toList()
        val options = if (gameId == "go") mapOf("boardSize" to "9") else emptyMap()
        matchSeed = Random.nextInt(1, Int.MAX_VALUE)
        if (!coordinator.startLocal(gameId, seats, total, matchSeed, settings.aiDifficulty, options)) {
            ui.showStatus("无法开始这款游戏，请检查人数设置")
            mode = Mode.NONE
        }
    }

    // ==================== 房主：局域网 ====================

    private fun startLanHost(gameId: String, total: Int, password: String) {
        leaveTransports(keepUi = true)
        mode = Mode.HOST
        hostGameId = gameId
        hostCapacity = total
        roomId = randomCode()
        roomPassword = password.ifBlank { randomDigits() }
        if (!coordinator.startHost(roomId, gameId, total, roomPassword, "房主")) {
            ui.showStatus("无法创建房间")
            mode = Mode.NONE
            return
        }
        try {
            val transport = TcpTransport(hostLinks)
            val tcpPort = transport.host()
            tcp = transport
            val web = LanWebServer(this, hostLinks)
            val webPort = web.start()
            lanWeb = web
            val ip = localIpv4() ?: "本机地址未识别"
            lastLinkInfo = "房间码  $roomId\n口令  $roomPassword\n手机加入  $ip:$tcpPort\n浏览器加入  http://$ip:$webPort/?room=$roomId\n（网页会自带房间码，只需再填口令；口令只当面或私下告诉朋友）"
            ui.showRoom(coordinator.let { currentRoom() }, true, lastLinkInfo)
            val finder = LanDiscovery(
                this,
                onFound = { room -> publishRoom(room) },
                onLost = { id ->
                    foundRooms.remove(id)
                    ui.showLanRooms(foundRooms.values.toList())
                },
                onError = { error -> ui.showStatus(lanMessage(error)) }
            )
            discovery = finder
            finder.advertise(roomId, "同乐盒 · 房主", tcpPort, webPort, gameId, total)
            acquireMulticast()
        } catch (error: Exception) {
            ui.showStatus("局域网房间创建失败：${error.message ?: "未知原因"}")
            leaveTransports(keepUi = true)
            mode = Mode.NONE
        }
    }

    // ==================== 房主：互联网 ====================

    private fun startInternetHost(gameId: String, total: Int, password: String) {
        leaveTransports(keepUi = true)
        mode = Mode.HOST
        hostGameId = gameId
        hostCapacity = total
        roomId = randomCode()
        roomPassword = password.ifBlank { randomDigits() }
        if (!coordinator.startHost(roomId, gameId, total, roomPassword, "房主")) {
            ui.showStatus("无法创建房间")
            mode = Mode.NONE
            return
        }
        val transport = WebRtcTransport(this, hostLinks) { status -> ui.showStatus(rtcMessage(status)) }
        rtc = transport
        lastLinkInfo = "房间码  $roomId\n口令  $roomPassword\n正在连接公共信令服务…"
        ui.showRoom(currentRoom(), true, lastLinkInfo)
        transport.start("pg-party-${roomId.lowercase()}") {
            lastLinkInfo = "房间码  $roomId\n口令  $roomPassword\n信令已就绪，等待好友加入（部分网络无法直连，可改用局域网）"
            ui.showRoom(currentRoom(), true, lastLinkInfo)
        }
    }

    // ==================== 访客：互联网 ====================

    private fun startInternetGuest(code: String, password: String) {
        leaveTransports(keepUi = true)
        mode = Mode.GUEST
        roomId = code.trim().uppercase()
        roomPassword = password
        guestName = "玩家"
        coordinator.startGuest(roomId, password, guestName)
        val transport = WebRtcTransport(this, guestLinks) { status -> ui.showStatus(rtcMessage(status)) }
        rtc = transport
        transport.start("pg-guest-${UUID.randomUUID().toString().take(12)}") {
            ui.showStatus("正在联系房主…")
            transport.connect("pg-party-${roomId.lowercase()}")
        }
    }

    // ==================== 访客：局域网 ====================

    private fun startLanGuest(address: String, port: Int, password: String) {
        leaveTransports(keepUi = true)
        mode = Mode.GUEST
        roomId = ""
        roomPassword = password
        guestName = "玩家"
        coordinator.startGuest("", password, guestName)
        try {
            val transport = TcpTransport(guestLinks)
            tcp = transport
            transport.connect(address, port)
            ui.showStatus("正在连接 $address:$port …")
        } catch (error: Exception) {
            ui.showStatus("连接失败：${error.message ?: "无法建立连接"}")
            mode = Mode.NONE
        }
    }

    // ==================== 局域网发现 ====================

    private fun discoverRooms() {
        if (discovery == null) {
            discovery = LanDiscovery(
                this,
                onFound = { room -> publishRoom(room) },
                onLost = { id ->
                    foundRooms.remove(id)
                    ui.showLanRooms(foundRooms.values.toList())
                },
                onError = { error -> ui.showStatus(lanMessage(error)) }
            )
            acquireMulticast()
        }
        runCatching { discovery?.discover() }
        ui.showLanRooms(foundRooms.values.toList())
    }

    private fun publishRoom(room: LanRoom) {
        foundRooms[room.id] = UiLanRoom(room.id, room.name, room.address, room.tcpPort, room.gameId)
        ui.showLanRooms(foundRooms.values.toList())
    }

    private fun acquireMulticast() {
        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createMulticastLock("partygame-nsd").apply { setReferenceCounted(false); acquire() }
        }
    }

    // ==================== GameCoordinator.Listener ====================

    override fun onLobby(room: RoomSnapshot) {
        lastRoom = room
        when (mode) {
            Mode.HOST -> ui.showRoom(room, true, lastLinkInfo)
            Mode.GUEST -> {
                val info = if (lastLinkInfo.isBlank()) guestLinkInfo() else lastLinkInfo
                ui.showRoom(room, false, info)
            }
            else -> Unit
        }
    }

    override fun onGame(snapshot: GameSnapshot, localSeats: List<Int>, viewer: Int) {
        ui.showGame(snapshot, localSeats, viewer)
    }

    override fun onStatus(text: String) {
        if (text.isNotBlank()) ui.showStatus(text)
    }

    override fun onClosed(reason: String) {
        if (mode == Mode.NONE) return
        ui.showStatus(reason)
        leaveEverything()
    }

    // ==================== 工具 ====================

    private fun currentRoom(): RoomSnapshot = lastRoom
        ?: RoomSnapshot(roomId, hostGameId, hostCapacity, emptyList(), "lobby", 0)

    private fun guestLinkInfo(): String = when {
        roomId.isNotBlank() -> "房间码  $roomId\n状态  已连接"
        else -> "状态  局域网直连"
    }

    private fun leaveEverything() {
        coordinator.close()
        leaveTransports(keepUi = true)
        roomId = ""
        roomPassword = ""
        hostGameId = ""
        lastRoom = null
        lastLinkInfo = ""
        mode = Mode.NONE
        coordinator = GameCoordinator(this, this)
        coordinator.setBotDifficulty(settings.aiDifficulty)
    }

    private fun leaveTransports(keepUi: Boolean) {
        runCatching { tcp?.close() }; tcp = null
        runCatching { lanWeb?.close() }; lanWeb = null
        runCatching { discovery?.close() }; discovery = null
        runCatching { rtc?.close() }; rtc = null
        runCatching { wifiLock?.release() }; wifiLock = null
        foundRooms.clear()
        if (!keepUi) Unit
    }

    override fun onDestroy() {
        leaveTransports(keepUi = true)
        coordinator.close()
        ui.dispose()
        super.onDestroy()
    }

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun randomDigits(): String = (1..6).map { Random.nextInt(10) }.joinToString("")

    private fun lanMessage(error: String): String = when {
        error.contains("discover") || error.contains("registration") -> "未能启动局域网发现：请确认已连接 Wi-Fi 或热点"
        error.contains("resolve") -> "发现房间但解析地址失败，可手动输入房主地址"
        else -> "局域网发现出现问题：$error"
    }

    private fun rtcMessage(status: String): String = when {
        status.contains("signal: OPEN") -> "信令已连接"
        status.contains("peer-unavailable") -> "房间不存在或房主已离线"
        status.contains("unavailable-id") -> "房间码已被占用，请返回重试"
        status.contains("network") || status.contains("socket") -> "无法连接公共信令服务；同一 Wi-Fi 下可改用局域网房间"
        status.contains("ice: failed") -> "双方网络无法直连（对称 NAT），可改用局域网或手机热点"
        status.contains("ice: disconnected") -> "连接中断，正在等待恢复"
        status.contains("data: OPEN") -> "已建立点对点连接"
        status.contains("rtc: native") -> "本机 WebRTC 初始化失败"
        else -> status
    }

    companion object {
        fun localIpv4(): String? = try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
                .filter { (_, address) -> address is Inet4Address && address.isSiteLocalAddress }
            (candidates.firstOrNull { (name, _) -> name.startsWith("wlan") } ?: candidates.firstOrNull())
                ?.second?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
