package com.aicode.partyhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameCatalog
import com.aicode.partyhub.core.GameInfo
import com.aicode.partyhub.core.GameSnapshot
import com.aicode.partyhub.room.RoomSnapshot
import com.aicode.partyhub.settings.AppearanceStore
import com.aicode.partyhub.settings.LauncherIconManager

data class UiLanRoom(val id: String, val name: String, val address: String, val port: Int, val gameId: String)

class AppUi(private val activity: AppCompatActivity, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onLocal(gameId: String, humans: Int, total: Int)
        fun onHost(gameId: String, total: Int, password: String, online: Boolean)
        fun onJoin(roomCode: String, password: String, online: Boolean)
        fun onDiscover()
        fun onConnectLan(address: String, port: Int, password: String)
        fun onReady(ready: Boolean)
        fun onStart()
        fun onAction(seat: Int, action: GameAction)
        fun onLeave()
        fun onHint()
        fun onSettingsChanged()
    }

    private val store = AppearanceStore(activity)
    private var settings = store.load()
    private var style = UiStyle(activity, settings)
    private var mode = ""
    private var tab = 0
    private var category = "all"
    private var search = ""
    private var rooms = emptyList<UiLanRoom>()
    private var roomList: LinearLayout? = null
    private var gameList: LinearLayout? = null
    private var status: TextView? = null
    private var dialog: AlertDialog? = null
    private var selectionDialog: AlertDialog? = null
    private var game: GameScreen? = null
    private var room: RoomSnapshot? = null
    private var roomHost = false
    private var roomLink = ""
    private var roomMembers: LinearLayout? = null
    private var roomSummary: TextView? = null
    private var roomAction: TextView? = null
    private var roomHeader: TextView? = null
    private var disposed = false
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (dialog?.isShowing == true) dialog?.dismiss()
            else if (mode != "home") confirmLeave()
            else if (tab != 0) { tab = 0; showHome() }
            else { isEnabled = false; activity.onBackPressedDispatcher.onBackPressed(); isEnabled = true }
        }
    }

    init { activity.onBackPressedDispatcher.addCallback(activity, back) }

    fun showHome() {
        if (disposed) return
        game?.dispose(); game = null
        dialog?.dismiss(); selectionDialog?.dismiss()
        roomList = null; gameList = null
        mode = "home"
        room = null
        settings = store.load()
        style = UiStyle(activity, settings)
        val s = style
        val root = s.column()
        val shell = if (wide()) s.row() else s.column()
        val content = s.column(20)
        content.addView(s.text("PARTYGAME  /  同乐盒", 12f, true, s.colors.accent), s.space(bottom = 8))
        val title = listOf("好时光，一起玩", "相聚，不限距离", "换个心情，开一局", "按你的习惯来")[tab]
        content.addView(s.text(title, 29f, true), s.space(bottom = 8))
        content.addView(s.text(listOf("16 款小游戏 · 同屏、好友与 AI", "同一 Wi-Fi 或互联网，找到你的玩伴", "四套配色、棋盘与专属桌面图标", "舒适的字号，清晰的反馈，专注每一步")[tab], 15f, color = s.colors.muted), s.space(bottom = 14))
        when (tab) {
            0 -> gamesPage(content)
            1 -> connectionPage(content)
            2 -> appearancePage(content)
            else -> settingsPage(content)
        }
        val scroller = s.scroll(content)
        val center = FrameLayout(activity)
        center.addView(scroller, FrameLayout.LayoutParams(if (wide()) s.dp(900).coerceAtMost(screenWidth() - s.dp(136)) else -1, -1, Gravity.CENTER_HORIZONTAL))
        val navigation = if (wide()) s.column(8) else s.row().apply { setPadding(s.dp(8), s.dp(6), s.dp(8), s.dp(6)) }
        navigation.background = s.shape(s.colors.surface, 0)
        listOf("游戏", "联机", "外观", "设置").forEachIndexed { index, label ->
            val b = s.button(label, index == tab) { tab = index; showHome() }
            b.contentDescription = "$label${if (index == tab) "，已选中" else ""}"
            navigation.addView(b, if (wide()) s.space(width = 104, top = 6) else LinearLayout.LayoutParams(0, s.dp(54), 1f).apply { marginStart = s.dp(3); marginEnd = s.dp(3) })
        }
        if (wide()) {
            shell.addView(navigation, s.space(width = 120, height = -1))
            shell.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            shell.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
            shell.addView(navigation, s.space())
        }
        root.addView(shell, LinearLayout.LayoutParams(-1, 0, 1f))
        install(root)
    }

    private fun install(root: LinearLayout) {
        val s = style
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        root.setBackgroundColor(s.colors.background)
        status = s.text("", 13f, color = s.colors.muted).apply {
            setPadding(s.dp(18), s.dp(7), s.dp(18), s.dp(7))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.addView(status, s.space())
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = !s.colors.dark
            isAppearanceLightNavigationBars = !s.colors.dark
        }
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = s.colors.background
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = s.colors.background
        activity.setContentView(root)
        root.requestApplyInsets()
    }

    private fun gamesPage(parent: LinearLayout) {
        val s = style
        val field = s.input("搜索游戏或玩法")
        field.setText(search)
        parent.addView(field, s.space(bottom = 12))
        val filters = s.row()
        listOf("all" to "全部", "arcade" to "竞技", "board" to "棋盘", "cards" to "纸牌").forEach { (id, label) ->
            filters.addView(s.button(label, category == id) { category = id; showHome() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(4) })
        }
        parent.addView(filters, s.space(bottom = 12))
        gameList = s.column()
        parent.addView(gameList, s.space())
        renderGames()
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { search = text.toString(); renderGames() }
            override fun afterTextChanged(text: Editable?) = Unit
        })
    }

    private fun renderGames() {
        val container = gameList ?: return
        val s = style
        container.removeAllViews()
        val entries = GameCatalog.entries.filter { (category == "all" || it.category == category) && (it.title + it.subtitle + it.rules).contains(search.trim(), true) }
        if (entries.isEmpty()) { container.addView(s.text("没有找到这款游戏，试试其他关键词。", 16f)); return }
        val columns = when { wide() -> 3; activity.resources.configuration.screenWidthDp >= 350 -> 2; else -> 1 }
        entries.chunked(columns).forEach { group ->
            val line = s.row().apply { gravity = Gravity.TOP }
            group.forEach { info ->
                val card = s.card(14)
                card.addView(s.icon("ic_game_${info.id}", info.title, 64))
                card.addView(s.text(info.title, 19f, true), s.space(top = 12, bottom = 6))
                card.addView(s.text(info.subtitle, 13f, color = s.colors.muted), s.space(bottom = 12))
                card.addView(s.text("${if (info.minPlayers == info.maxPlayers) "${info.minPlayers} 人" else "${info.minPlayers}–${info.maxPlayers} 人"}${if (info.bot) "  ·  AI" else "  ·  好友"}", 12f, true, s.colors.accent))
                card.addView(s.button("开始游戏") { gameOptions(info) }, s.space(top = 12))
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(6); marginStart = s.dp(6) })
            }
            repeat(columns - group.size) { line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
            container.addView(line, s.space(bottom = 14))
        }
    }

    private fun gameOptions(info: GameInfo, hostOnly: Boolean = false, online: Boolean = false) {
        val s = style
        val body = s.column(20)
        body.addView(s.icon("ic_game_${info.id}", info.title, 84))
        body.addView(s.text(info.subtitle, 18f, true), s.space(top = 12))
        body.addView(s.text(info.rules, 15f, color = s.colors.muted), s.space(top = 12, bottom = 16))
        var total = info.minPlayers
        var humans = if (info.bot) 1 else total
        val totalButton = s.button("游戏席位：$total 人") {}
        val humanButton = s.button("同屏真人：$humans 人") {}
        totalButton.setOnClickListener {
            choose("游戏席位", (info.minPlayers..info.maxPlayers).map { "$it 人" }) { index ->
                total = info.minPlayers + index
                humans = if (info.bot) humans.coerceAtMost(total) else total
                totalButton.text = "游戏席位：$total 人"; humanButton.text = "同屏真人：$humans 人"
            }
        }
        humanButton.setOnClickListener { choose("真人席位，其余由 AI 补齐", (1..total).map { "$it 人" }) { humans = it + 1; humanButton.text = "同屏真人：$humans 人" } }
        totalButton.isEnabled = info.minPlayers != info.maxPlayers
        humanButton.isEnabled = info.bot
        body.addView(totalButton, s.space(bottom = 8))
        if (!hostOnly) {
            body.addView(humanButton, s.space(bottom = 8))
            body.addView(s.text(if (info.bot) "同屏真人使用席位 1 起的连续席位，其余交给 AI。" else "本游戏需要真人玩伴；每个人拥有独立操作区。", 13f, color = s.colors.muted), s.space(bottom = 12))
            body.addView(s.button("同屏开局", true) { dialog?.dismiss(); callbacks.onLocal(info.id, humans, total) }, s.space(bottom = 10))
        }
        val password = s.input("房间口令（可留空）", true)
        password.filters = arrayOf(android.text.InputFilter.LengthFilter(128))
        body.addView(password, s.space(top = 8, bottom = 10))
        if (hostOnly) body.addView(s.button(if (online) "创建互联网房间" else "创建局域网房间", true) { dialog?.dismiss(); callbacks.onHost(info.id, total, password.text.toString(), online) }, s.space())
        else {
            body.addView(s.button("创建局域网房间") { dialog?.dismiss(); callbacks.onHost(info.id, total, password.text.toString(), false) }, s.space(bottom = 8))
            body.addView(s.button("创建互联网房间") { dialog?.dismiss(); callbacks.onHost(info.id, total, password.text.toString(), true) }, s.space())
        }
        present(info.title, body)
    }

    private fun connectionPage(parent: LinearLayout) {
        val s = style
        val nearby = s.card()
        nearby.addView(s.text("在身边，同一网络", 21f, true))
        nearby.addView(s.text("连接同一 Wi-Fi 或手机热点，再发现房间。", 14f, color = s.colors.muted), s.space(top = 8, bottom = 14))
        nearby.addView(s.button("创建局域网房间", true) { selectHost(false) }, s.space(bottom = 8))
        nearby.addView(s.button("发现附近房间") { showStatus("正在发现局域网房间…"); callbacks.onDiscover() }, s.space())
        roomList = s.column()
        nearby.addView(roomList, s.space(top = 12))
        renderLanRooms()
        nearby.addView(s.button("手动输入地址连接") { lanDialog() }, s.space(top = 12))
        parent.addView(nearby, s.space(bottom = 16))
        val internet = s.card()
        internet.addView(s.text("远方，也能同乐", 21f, true))
        internet.addView(s.text("把房间码分享给好友。互联网直连受网络环境影响，不保证所有网络都能穿透。", 14f, color = s.colors.muted), s.space(top = 8, bottom = 14))
        internet.addView(s.button("创建互联网房间", true) { selectHost(true) }, s.space(bottom = 10))
        val code = s.input("输入好友的房间码")
        val password = s.input("房间口令", true)
        code.filters = arrayOf(android.text.InputFilter.LengthFilter(128))
        password.filters = arrayOf(android.text.InputFilter.LengthFilter(128))
        internet.addView(code, s.space(bottom = 8)); internet.addView(password, s.space(bottom = 10))
        internet.addView(s.button("加入房间") {
            val value = code.text.toString().trim()
            if (value.isEmpty()) code.error = "请先填写房间码" else callbacks.onJoin(value, password.text.toString(), true)
        }, s.space())
        parent.addView(internet, s.space())
    }

    private fun selectHost(online: Boolean) = choose("选择房间游戏", GameCatalog.entries.map { it.title }) { gameOptions(GameCatalog.entries[it], true, online) }

    private fun lanDialog(found: UiLanRoom? = null) {
        val s = style
        val body = s.column(20)
        val address = s.input("房主 IP / 主机名").apply { setText(found?.address ?: "") }
        val port = s.input("端口").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(found?.port?.toString() ?: "") }
        val password = s.input("房间口令", true)
        body.addView(address, s.space(bottom = 8)); body.addView(port, s.space(bottom = 8)); body.addView(password, s.space(bottom = 14))
        body.addView(s.button("连接", true) {
            val host = address.text.toString().trim()
            val number = port.text.toString().toIntOrNull()
            when {
                host.isBlank() || host.any { it.isWhitespace() || it == '/' } || host.length > 253 -> address.error = "请输入有效地址，不含协议和空格"
                number == null || number !in 1..65535 -> port.error = "端口范围为 1–65535"
                else -> { dialog?.dismiss(); callbacks.onConnectLan(host, number, password.text.toString()) }
            }
        }, s.space())
        present(found?.name ?: "连接局域网房间", body)
    }

    fun showLanRooms(rooms: List<UiLanRoom>) {
        if (disposed) return
        this.rooms = rooms.toList()
        if (mode == "home" && tab == 1) renderLanRooms()
    }

    private fun renderLanRooms() {
        val list = roomList ?: return
        val s = style
        list.removeAllViews()
        if (rooms.isEmpty()) list.addView(s.text("尚未发现房间。可点击发现，或直接输入房主地址。", 14f, color = s.colors.muted))
        rooms.forEach { found ->
            val title = GameCatalog.entries.firstOrNull { it.id == found.gameId }?.title ?: found.gameId
            list.addView(s.button("${found.name} · $title\n${found.address}:${found.port}") { lanDialog(found) }, s.space(top = 8))
        }
    }

    private fun appearancePage(parent: LinearLayout) {
        val s = style
        s.section(parent, "主题配色", "柔和底色，清晰对比。不靠浮光抢走注意力。")
        listOf("sea" to "海盐青", "midnight" to "午夜蓝", "cherry" to "樱桃粉", "wood" to "暖木色").chunked(2).forEach { pair ->
            val row = s.row()
            pair.forEach { (id, name) ->
                val palette = UiPalette.load(activity, id)
                val card = s.card(14)
                card.background = s.shape(palette.surface, 18, if (settings.theme == id) palette.accent else palette.border)
                val samples = s.row()
                listOf(palette.background, palette.accent, palette.soft, palette.text).forEach { color ->
                    samples.addView(View(activity).apply { background = s.shape(color, 12) }, LinearLayout.LayoutParams(0, s.dp(36), 1f).apply { marginEnd = s.dp(4) })
                }
                card.addView(samples, s.space(bottom = 10))
                card.addView(s.text(name + if (settings.theme == id) " · 已选" else "", 16f, true, palette.text))
                card.contentDescription = "切换为$name"
                card.isFocusable = true
                card.setOnClickListener { change(settings.copy(theme = id), true) }
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(8) })
            }
            parent.addView(row, s.space(bottom = 10))
        }
        s.section(parent, "棋盘与牌背")
        parent.addView(s.button("棋盘：${boardName(settings.board)}") {
            choose("棋盘风格", listOf("经典木纹色", "主题配色", "高对比")) { change(settings.copy(board = listOf("classic", "theme", "contrast")[it]), true) }
        }, s.space(bottom = 10))
        parent.addView(s.button("牌背：${backName(settings.cardBack)}") {
            choose("牌背纹样", listOf("海浪", "方格", "简约")) { change(settings.copy(cardBack = listOf("waves", "grid", "plain")[it]), true) }
        }, s.space())
        s.section(parent, "桌面图标", "先预览，再点击应用；切换后桌面可能需要片刻刷新。")
        var selected = settings.launcherIcon
        val selectedLabel = s.text("当前：${iconName(selected)}", 15f, true, s.colors.accent)
        val icons = s.row()
        listOf("trio", "dice", "board", "mono").forEach { id ->
            val cell = s.column(4)
            cell.gravity = Gravity.CENTER
            cell.addView(s.icon("ic_launcher_$id", iconName(id), if (wide()) 72 else 56))
            cell.addView(s.text(iconName(id), 13f), s.space(top = 8))
            cell.minimumHeight = s.dp(100)
            cell.isFocusable = true
            cell.contentDescription = "预览${iconName(id)}图标"
            cell.setOnClickListener { selected = id; selectedLabel.text = "预览：${iconName(id)}（尚未应用）" }
            icons.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        parent.addView(icons, s.space(bottom = 12)); parent.addView(selectedLabel, s.space(bottom = 12))
        parent.addView(s.button("应用桌面图标", true) {
            LauncherIconManager(activity).apply(selected).onSuccess {
                change(settings.copy(launcherIcon = selected), false)
                selectedLabel.text = "已应用：${iconName(selected)}"
                showStatus("图标已切换，等待桌面刷新")
            }.onFailure { showStatus("暂时无法切换图标：请确认桌面别名已随应用打包") }
        }, s.space())
    }

    private fun settingsPage(parent: LinearLayout) {
        val s = style
        val panel = s.card()
        fun toggle(label: String, detail: String, value: Boolean, update: (Boolean) -> AppearanceStore.Settings) {
            panel.addView(s.toggle(label, detail, value) { change(update(it), false) }, s.space(bottom = 18))
        }
        toggle("落子前确认", "选好目标后，再按确认落子", settings.confirmMoves) { settings.copy(confirmMoves = it) }
        toggle("棋盘坐标", "显示行列，方便读棋和交流", settings.coordinates) { settings.copy(coordinates = it) }
        toggle("按键音效", "轻量系统按键音，不播放背景音乐", settings.sound) { settings.copy(sound = it) }
        toggle("触感反馈", "操作时跟随系统触感设置", settings.vibration) { settings.copy(vibration = it) }
        toggle("低动效", "关闭界面涟漪；实时游戏仍保持更新", settings.reducedMotion) { settings.copy(reducedMotion = it) }
        parent.addView(panel, s.space())
        s.section(parent, "阅读与操作")
        parent.addView(s.button("字号：${(settings.fontScale * 100).toInt()}%") {
            choose("字号", listOf("标准 100%", "舒适 115%", "大字 130%")) { change(settings.copy(fontScale = listOf(1f, 1.15f, 1.3f)[it]), true) }
        }, s.space())
        s.section(parent, "AI 对手", "应用于后续新对局，由游戏引擎决定实际思考策略。")
        parent.addView(s.button("难度：${listOf("轻松", "标准", "挑战")[settings.aiDifficulty]}") {
            choose("AI 难度", listOf("轻松", "标准", "挑战")) { change(settings.copy(aiDifficulty = it), true) }
        }, s.space(bottom = 10))
        parent.addView(s.button("思考时间：${settings.aiThinkTimeMs} 毫秒") {
            choose("AI 思考时间预算", listOf("500 毫秒", "1000 毫秒", "2000 毫秒", "4000 毫秒")) { change(settings.copy(aiThinkTimeMs = listOf(500, 1000, 2000, 4000)[it]), true) }
        }, s.space())
        parent.addView(s.text("纯原生 Android 界面 · 设置保存在此设备\n同屏纸牌会在换手时遮挡，请把设备交给对应玩家。", 14f, color = s.colors.muted), s.space(top = 24))
    }

    private fun change(next: AppearanceStore.Settings, redraw: Boolean) {
        settings = next
        store.save(next)
        callbacks.onSettingsChanged()
        if (redraw && mode == "home") showHome()
    }

    fun showRoom(room: RoomSnapshot, host: Boolean, linkInfo: String) {
        if (disposed) return
        val rebuild = mode != "room" || this.room?.roomId != room.roomId
        this.room = room; roomHost = host; roomLink = linkInfo
        if (rebuild) {
            dialog?.dismiss(); game?.dispose(); game = null; mode = "room"
            val s = style
            val root = s.column()
            val body = s.column(22)
            body.addView(s.text("好友房间", 28f, true), s.space(bottom = 12))
            val card = s.card()
            roomHeader = s.text("", 22f, true)
            card.addView(roomHeader, s.space(bottom = 12))
            roomSummary = s.text("", 15f, color = s.colors.muted).apply { setTextIsSelectable(true) }
            card.addView(roomSummary, s.space(bottom = 12))
            card.addView(s.button("复制房间信息") {
                val current = this.room ?: return@button
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("房间信息", "${current.roomId}\n$roomLink"))
                showStatus("已复制房间信息，不包含口令")
            }, s.space())
            body.addView(card, s.space(bottom = 20))
            roomMembers = s.column()
            body.addView(roomMembers, s.space(bottom = 16))
            roomAction = s.button("", true) {
                val current = this.room ?: return@button
                if (roomHost) callbacks.onStart() else callbacks.onReady(current.members.firstOrNull { it.seat == current.yourSeat }?.ready != true)
            }
            body.addView(roomAction, s.space(bottom = 10))
            body.addView(s.button("离开房间") { confirmLeave() }, s.space())
            root.addView(s.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
            install(root)
        }
        val s = style
        val info = GameCatalog.entries.firstOrNull { it.id == room.gameId }
        roomHeader?.text = "${info?.title ?: room.gameId} · ${room.capacity} 人"
        roomSummary?.text = "房间码  ${room.roomId}\n$linkInfo${if (room.message.isNotBlank()) "\n${room.message}" else ""}"
        roomMembers?.apply {
            removeAllViews()
            for (seat in 0 until room.capacity) {
                val member = room.members.firstOrNull { it.seat == seat }
                val description = when {
                    seat in room.bots -> "AI 对手"
                    member == null -> if (info?.bot == true) "空闲 · 开局由 AI 补齐" else "等待好友加入"
                    !member.connected -> "已断线 · 等待重连"
                    member.host -> "房主 · 已准备"
                    member.ready -> "已准备"
                    else -> "等待准备"
                }
                val item = s.card(16)
                item.addView(s.text("${seat + 1}  ${member?.name ?: "席位 ${seat + 1}"}${if (seat == room.yourSeat) " · 你" else ""}", 18f, true))
                item.addView(s.text(description, 14f, color = s.colors.muted), s.space(top = 6))
                addView(item, s.space(bottom = 10))
            }
            room.members.filter { it.seat < 0 }.forEach { addView(s.text("观战 · ${it.name}", 15f), s.space(top = 8)) }
        }
        val own = room.members.firstOrNull { it.seat == room.yourSeat && room.yourSeat >= 0 }
        val occupied = room.members.filter { it.seat >= 0 }
        roomAction?.text = if (host) "开始游戏" else if (own?.ready == true) "取消准备" else "准备好了"
        roomAction?.isEnabled = room.phase == "lobby" && if (host) occupied.all { it.ready && it.connected } && (info?.bot == true || occupied.size >= room.capacity) else own != null
    }

    fun showGame(snapshot: GameSnapshot, localSeats: List<Int>, viewer: Int) {
        if (disposed) return
        if (mode != "game" || game?.gameId != snapshot.gameId) {
            dialog?.dismiss(); game?.dispose(); mode = "game"
            settings = store.load(); style = UiStyle(activity, settings)
            game = GameScreen(activity, style, snapshot.gameId, callbacks, ::confirmLeave)
            install(game!!.root)
        }
        game?.update(snapshot, localSeats, viewer)
    }

    fun showStatus(message: String) {
        if (disposed) return
        status?.text = message
        status?.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (dialog?.isShowing == true) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun confirmLeave() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("离开当前对局？").setMessage("离开会断开此设备的房间连接。本地未结束的对局也将退出。")
            .setNegativeButton("继续玩", null).setPositiveButton("离开") { _, _ -> callbacks.onLeave() }.show()
    }

    private fun present(title: String, body: View) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle(title).setView(style.scroll(body)).setNegativeButton("关闭", null).create().also { it.show() }
    }

    private fun choose(title: String, values: List<String>, selected: (Int) -> Unit) {
        selectionDialog?.dismiss()
        selectionDialog = AlertDialog.Builder(activity).setTitle(title).setItems(values.toTypedArray()) { _, index -> selected(index) }.setNegativeButton("取消", null).show()
    }

    private fun wide() = activity.resources.configuration.screenWidthDp >= 840
    private fun screenWidth() = activity.resources.displayMetrics.widthPixels
    private fun boardName(id: String) = when (id) { "theme" -> "主题配色"; "contrast" -> "高对比"; else -> "经典木纹色" }
    private fun backName(id: String) = when (id) { "grid" -> "方格"; "plain" -> "简约"; else -> "海浪" }
    private fun iconName(id: String) = when (id) { "dice" -> "幸运骰"; "board" -> "棋盘"; "mono" -> "极简"; else -> "同乐三人" }

    fun dispose() {
        if (disposed) return
        disposed = true
        dialog?.dismiss(); dialog = null
        selectionDialog?.dismiss(); selectionDialog = null
        game?.dispose(); game = null
        back.remove()
        roomList = null; gameList = null; status = null
        roomMembers = null; roomSummary = null; roomAction = null; roomHeader = null
    }
}
