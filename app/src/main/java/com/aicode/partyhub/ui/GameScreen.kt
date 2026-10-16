package com.aicode.partyhub.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aicode.partyhub.core.GameAction
import com.aicode.partyhub.core.GameCatalog
import com.aicode.partyhub.core.GameSnapshot
import com.aicode.partyhub.games.cards.CardPattern

internal class GameScreen(
    private val activity: AppCompatActivity,
    private val style: UiStyle,
    val gameId: String,
    private val callbacks: AppUi.Callbacks,
    private val leave: () -> Unit
) {
    val root = style.column()
    private val info = GameCatalog.entries.firstOrNull { it.id == gameId }
    private val scoreRow = style.row()
    private val scores = mutableListOf<TextView>()
    private val message = style.text("", 15f, true)
    private val phase = style.text("", 13f, color = style.colors.muted)
    private val selectionText = style.text("请选择棋子或落点", 14f, color = style.colors.muted)
    private val controls = style.column(6)
    private val boardControls = style.column()
    private val extras = style.column()
    private val board = GameCanvas(style, ::selectCell) { direction -> if (viewer >= 0) submit(viewer, GameAction("direction", listOf(direction))) }
    private val question = style.text("", 36f, true).apply { gravity = Gravity.CENTER }
    private val publicCards = style.text("", 17f, true)
    private val hand = style.column()
    private val handTitle = style.text("", 18f, true)
    private val handover = style.button("确认查看手牌", true) {
        val s = snapshot
        if (s != null && viewer in seats && viewer == s.turn) {
            privacy.reveal(viewer)
            renderCards(true)
            refreshActions()
        }
    }
    private val privacy = HandPrivacy()
    private val selectedCards = linkedSetOf<Int>()
    private val cardViews = mutableMapOf<Int, TextView>()
    private var renderedHand = emptyList<Int>()
    private var renderedViewer = -2
    private var renderedVisible = false
    private var snapshot: GameSnapshot? = null
    private var seats = emptyList<Int>()
    private var viewer = -1
    private var path = emptyList<Int>()
    private var pending: GameAction? = null
    private var choiceDialog: AlertDialog? = null
    private var controlSeats = emptyList<Int>()
    private val arcadeButtons = mutableListOf<Triple<TextView, Int, GameAction>>()
    private val desiredDirections = mutableMapOf<Int, Int>()
    private val directionTicks = mutableMapOf<Int, Int>()
    private var actionSignature = emptyList<GameAction>()
    private var actionSeat = -2
    private var choosingPromotion = false
    private var disposed = false
    private val confirm = style.button("确认落子", true) { pending?.let { submit(viewer, it) } }
    private val clear = style.button("重新选择") { path = emptyList(); pending = null; selectedCards.clear(); updateSelection(); refreshCardSelection() }
    private val accessibleMoves = style.button("合法走法列表") { showLegalMoves() }

    init {
        val header = style.row().apply { setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6)) }
        header.addView(style.button("离开") { leave() }, style.space(width = 68))
        header.addView(style.text(info?.title ?: gameId, 22f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(style.button("规则") {
            choiceDialog?.dismiss()
            choiceDialog = AlertDialog.Builder(activity).setTitle(info?.title ?: gameId)
                .setMessage(snapshot?.extra?.get("rules") ?: info?.rules.orEmpty()).setPositiveButton("知道了", null).show()
        }, style.space(width = 68))
        root.addView(header, style.space())
        scoreRow.setPadding(style.dp(12), 0, style.dp(12), style.dp(8))
        root.addView(scoreRow, style.space())
        val status = style.column(12)
        status.addView(message, style.space()); status.addView(phase, style.space(top = 4))
        val landscape = activity.resources.configuration.screenWidthDp >= 600 && activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp
        message.maxLines = if (landscape) 1 else 2
        root.addView(status, style.space())
        val split = if (landscape) style.row() else style.column()
        val arena = style.column(8)
        if (gameId == "doudizhu") {
            controls.addView(publicCards, style.space(bottom = 14))
            controls.addView(handTitle, style.space(bottom = 8))
            controls.addView(handover, style.space(bottom = 12))
            controls.addView(hand, style.space(bottom = 12))
        } else if (gameId in listOf("reaction", "color", "math")) {
            arena.addView(question, LinearLayout.LayoutParams(-1, -1))
        } else {
            arena.addView(board, LinearLayout.LayoutParams(-1, -1))
        }
        if (gameId != "doudizhu") {
            if (landscape) split.addView(arena, LinearLayout.LayoutParams(0, -1, 1f))
            else split.addView(arena, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        if (info?.category == "board" || gameId == "memory") {
            controls.addView(selectionText, style.space(bottom = 8))
            val buttons = style.row()
            buttons.addView(confirm, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(6) })
            buttons.addView(clear, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(buttons, style.space(bottom = 8))
            controls.addView(accessibleMoves, style.space(bottom = 8))
        }
        controls.addView(boardControls, style.space())
        controls.addView(extras, style.space(top = 8))
        val scrolling = style.scroll(controls)
        if (gameId == "doudizhu") split.addView(scrolling, LinearLayout.LayoutParams(-1, -1))
        else if (landscape) split.addView(scrolling, style.space(width = if (info?.realtime == true) 348 else 280, height = -1))
        else {
            val controlHeight = when {
                info?.realtime == true -> 228
                else -> 196
            }
            split.addView(scrolling, style.space(height = controlHeight))
        }
        root.addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun update(value: GameSnapshot, localSeats: List<Int>, currentViewer: Int) {
        if (disposed) return
        val old = snapshot
        val owned = localSeats.filter { it in 0 until value.players }.distinct()
        if (old?.turn != value.turn || old.cells != value.cells || old.phase != value.phase || viewer != currentViewer || seats != owned || old.legal != value.legal && info?.realtime != true) {
            path = emptyList(); pending = null; selectedCards.clear(); choosingPromotion = false
            choiceDialog?.dismiss(); choiceDialog = null
        }
        seats = owned; viewer = currentViewer; snapshot = value
        privacy.update(value, seats, viewer)
        if (scores.size != value.players) {
            scoreRow.removeAllViews(); scores.clear()
            repeat(value.players) { seat ->
                val label = style.text("", 14f, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(style.dp(6), style.dp(10), style.dp(6), style.dp(10))
                    background = style.shape(style.colors.surface, 14, style.colors.border)
                }
                scoreRow.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(5) })
                scores.add(label)
            }
        }
        val alive = value.extra["alive"].orEmpty().split(',')
        val hp = value.extra["hp"].orEmpty().split(',')
        scores.forEachIndexed { seat, label ->
            val name = if (info?.category == "board") value.labels.getOrNull(seat) ?: "${seat + 1}号" else "${seat + 1}号"
            val detail = when {
                gameId == "doudizhu" -> "${value.counts.getOrElse(seat) { 0 }} 张${if (value.extra["landlord"]?.toIntOrNull() == seat) " · 地主" else ""}"
                alive.getOrNull(seat) == "0" -> "已出局"
                hp.getOrNull(seat)?.toIntOrNull() != null -> "生命 ${hp[seat]} · ${value.scores.getOrElse(seat) { 0 }} 分"
                value.scores.isNotEmpty() -> "${value.scores.getOrElse(seat) { 0 }} 分"
                value.turn == seat -> "正在思考"
                else -> "等待"
            }
            setText(label, "$name${if (seat in seats) " · 本机" else ""}\n$detail")
            label.setTextColor(if (value.turn == seat) style.colors.accent else style.colors.text)
        }
        val winningSeat = value.winner
        val result = when {
            winningSeat == -1 -> "本局结束 · 平局"
            winningSeat != null && gameId == "doudizhu" -> "本局结束 · ${if (value.extra["winningSide"] == "landlord") "地主方" else "农民方"}获胜"
            winningSeat != null -> "本局结束 · ${winningSeat + 1}号获胜"
            else -> value.message
        }
        setText(message, result)
        val round = value.extra["round"]?.let { "第 $it / ${value.extra["totalRounds"].orEmpty()} 轮  ·  " }.orEmpty()
        val time = value.extra["countdown"]?.toLongOrNull()?.takeIf { it > 0 }?.let { "${(it + 999) / 1000} 秒" }.orEmpty()
        setText(phase, if (seats.isEmpty()) "观战模式 · 不能操作" else round + time + if (value.turn >= 0) "  当前：${value.turn + 1}号" else "  每人使用自己的操作区")
        if (gameId == "doudizhu") renderCards()
        else if (gameId in listOf("reaction", "color", "math")) {
            setText(question, if (gameId == "reaction") when (value.phase) { "signal" -> "现在！"; "waiting" -> "等待信号"; "result" -> "本轮结束"; else -> "比赛结束" } else value.extra["question"].orEmpty())
            val ink = value.extra["colorIndex"]?.toIntOrNull()
            val color = if (gameId == "color" && ink != null) value.extra["palette"].orEmpty().split(',').getOrNull(ink)?.let { runCatching { Color.parseColor(it) }.getOrNull() } else null
            question.setTextColor(color ?: if (value.phase == "signal") style.colors.accent else style.colors.text)
            if (gameId == "color") question.setBackgroundColor(Color.rgb(250, 250, 247))
        }
        if (info?.realtime == true) {
            if (controlSeats != seats) { controlSeats = seats.toList(); buildArcadeControls() }
            refreshArcade()
            flushDirections()
        }
        updateSelection()
        refreshActions()
    }

    private fun actionable(seat: Int) = !disposed && snapshot?.let { GameInput.canAct(it, seats, viewer, seat) } == true

    private fun submit(seat: Int, action: GameAction) {
        val s = snapshot ?: return
        if (!actionable(seat)) return
        if (gameId == "doudizhu" && !privacy.visible(s, seats, viewer)) return
        val allowed = when {
            gameId == "doudizhu" && action.type == "play" -> action.values.isNotEmpty() && action.values.all { it in s.hand } && CardPattern.choose(action.values, CardPattern.decode(s.extra["currentPattern"].orEmpty())) != null
            info?.realtime == true -> arcadeAllowed(s, seat, action)
            else -> action in s.legal
        }
        if (!allowed) return
        pending = null; path = emptyList(); selectedCards.clear()
        updateSelection(); refreshCardSelection()
        callbacks.onAction(seat, action)
    }

    private fun selectCell(index: Int) {
        val s = snapshot ?: return
        if (!actionable(viewer)) return
        val direct = s.legal.firstOrNull { it.type in listOf("place", "flip", "dead") && it.values == listOf(index) }
        if (direct != null) {
            path = listOf(index)
            if (direct.type == "flip") submit(viewer, direct) else propose(direct)
            return
        }
        val moves = s.legal.filter { it.type == "move" }
        val extended = path + index
        var candidates = GameInput.movesAt(moves, extended)
        if (path.isEmpty() || candidates.isEmpty() || pending != null) {
            path = listOf(index); pending = null
            candidates = GameInput.movesAt(moves, path)
            if (candidates.isEmpty()) path = emptyList()
        } else path = extended
        if (path.size >= 2 && candidates.isNotEmpty()) {
            if (gameId == "chess" && candidates.any { it.values.size == 3 }) {
                val promotions = candidates.filter { it.values.size == 3 }
                choosingPromotion = true
                val seat = viewer
                choiceDialog = AlertDialog.Builder(activity).setTitle("选择升变棋子")
                    .setItems(promotions.map { promotion(it.values[2]) }.toTypedArray()) { _, which ->
                        choosingPromotion = false
                        if (seat == viewer) propose(promotions[which])
                    }
                    .setNegativeButton("取消") { _, _ -> choosingPromotion = false; path = emptyList(); updateSelection() }
                    .setOnCancelListener { choosingPromotion = false; path = emptyList(); updateSelection() }.show()
            } else candidates.firstOrNull { it.values.size == path.size }?.let { propose(it) }
        }
        updateSelection()
    }

    private fun propose(action: GameAction) {
        val s = snapshot ?: return
        if (!actionable(viewer) || action !in s.legal) return
        pending = action
        if (!style.settings.confirmMoves) submit(viewer, action) else updateSelection()
    }

    private fun updateSelection() {
        val s = snapshot ?: return
        val targets = if (!actionable(viewer) || choosingPromotion) emptySet() else if (path.isNotEmpty() && pending == null) {
            GameInput.nextTargets(s.legal, path)
        } else s.legal.mapNotNull { if (it.type in listOf("move", "place", "flip", "dead")) it.values.firstOrNull() else null }.toSet()
        board.update(s, targets, path)
        confirm.isEnabled = pending != null && actionable(viewer)
        clear.isEnabled = path.isNotEmpty() || pending != null
        accessibleMoves.isEnabled = actionable(viewer) && s.legal.isNotEmpty()
        setText(selectionText, when {
            !actionable(viewer) -> if (seats.isEmpty()) "正在观战" else "等待当前玩家操作"
            pending != null -> "已选：${actionLabel(pending!!)}"
            path.isNotEmpty() -> "${path.joinToString(" → ") { square(it, s.width) }} · 选择下一个高亮目标"
            else -> "点击棋子或高亮落点${if (style.settings.confirmMoves) "，再确认" else ""}"
        })
    }

    private fun showLegalMoves() {
        val s = snapshot ?: return
        val moves = s.legal.filter { it.type in listOf("move", "place", "dead", "flip") }
        if (!actionable(viewer) || moves.isEmpty()) return
        choiceDialog?.dismiss()
        choiceDialog = AlertDialog.Builder(activity).setTitle("选择合法走法")
            .setItems(moves.map(::actionLabel).toTypedArray()) { _, index ->
                path = if (gameId == "chess") moves[index].values.take(2) else moves[index].values
                if (moves[index].type == "flip") submit(viewer, moves[index]) else propose(moves[index])
            }.setNegativeButton("取消", null).show()
    }

    private fun refreshActions() {
        val s = snapshot ?: return
        val visibleNow = actionable(viewer) && (gameId != "doudizhu" || privacy.visible(s, seats, viewer)) && s.phase != "finished"
        val available = if (!visibleNow) emptyList() else
            s.legal.filter { it.type in listOf("pass", "accept_score", "resume", "claim_draw", "bid", "hint") } + GameAction("resign")
        val signature = if (gameId == "doudizhu" && s.phase == "playing" && visibleNow) available + GameAction("play") else available
        if (signature == actionSignature && actionSeat == viewer) return
        actionSignature = signature; actionSeat = viewer
        extras.removeAllViews()
        if (signature.any { it.type == "play" }) {
            val row = style.row()
            val play = style.button("出选中牌", true) {
                val action = GameAction("play", selectedCards.sorted())
                val current = snapshot ?: return@button
                if (CardPattern.choose(action.values, CardPattern.decode(current.extra["currentPattern"].orEmpty())) == null) {
                    handTitle.text = "所选牌型不合法，或不能压过上家"
                } else submit(viewer, action)
            }
            row.addView(play, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(6) })
            row.addView(style.button("清空选择") { selectedCards.clear(); refreshCardSelection() }, LinearLayout.LayoutParams(0, -2, 1f))
            extras.addView(row, style.space(bottom = 8))
        }
        val claims = available.filter { it.type == "claim_draw" }
        val buttons = available.filter { it.type != "claim_draw" }.distinctBy { it.type to it.values }
        buttons.chunked(3).forEach { group ->
            val row = style.row()
            group.forEach { action ->
                row.addView(style.button(actionLabel(action)) {
                    when (action.type) {
                        "hint" -> {
                            val current = snapshot ?: return@button
                            if (actionable(viewer) && privacy.visible(current, seats, viewer)) {
                                selectedCards.clear(); current.legal.firstOrNull { it.type == "play" }?.let { selectedCards.addAll(it.values) }
                                refreshCardSelection(); callbacks.onHint()
                            }
                        }
                        "resign", "accept_score" -> {
                            val seat = viewer
                            choiceDialog = AlertDialog.Builder(activity).setTitle("确认${actionLabel(action)}？")
                                .setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> submit(seat, action) }.show()
                        }
                        else -> submit(viewer, action)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(5) })
            }
            extras.addView(row, style.space(bottom = 8))
        }
        if (claims.isNotEmpty()) extras.addView(style.button("申请和棋") {
            choiceDialog = AlertDialog.Builder(activity).setTitle("选择申请方式")
                .setItems(claims.map { if (it.values.isEmpty()) "按当前局面申请" else "拟走 ${it.values.take(2).joinToString(" → ") { cell -> square(cell, s.width) }} 后申请" }.toTypedArray()) { _, index -> submit(viewer, claims[index]) }
                .setNegativeButton("取消", null).show()
        }, style.space())
    }

    private fun renderCards(force: Boolean = false) {
        val s = snapshot ?: return
        val visible = privacy.visible(s, seats, viewer) && (s.extra["viewer"]?.toIntOrNull() ?: viewer) == viewer
        val cards = if (visible) s.hand else emptyList()
        val last = s.extra["lastCards"].orEmpty().split(',').mapNotNull(String::toIntOrNull)
        val bottom = s.extra["publicBottom"].orEmpty().split(',').mapNotNull(String::toIntOrNull)
        setText(publicCards, "底牌：${if (bottom.isEmpty()) "叫分结束后公开" else bottom.joinToString("  ", transform = ::cardLabel)}\n上手：${if (last.isEmpty()) "自由领出" else last.joinToString("  ", transform = ::cardLabel)}\n叫分 ${s.extra["bid"] ?: "0"} · 倍数 ${s.extra["multiplier"] ?: "1"}")
        handover.visibility = if (!visible && seats.size > 1 && viewer in seats && viewer == s.turn) View.VISIBLE else View.GONE
        handover.text = "我是 ${viewer + 1} 号，确认查看手牌"
        if (!visible) setText(handTitle, if (seats.isEmpty()) "观战 · 手牌不公开" else if (viewer == s.turn && viewer in seats) "请将设备交给 ${viewer + 1} 号玩家" else "手牌已遮挡，等待下一位真人回合")
        else setText(handTitle, "${viewer + 1} 号的手牌 · 点按多选（${selectedCards.size} 张）")
        if (!force && cards == renderedHand && renderedViewer == viewer && renderedVisible == visible) { refreshCardSelection(); return }
        hand.removeAllViews(); cardViews.clear()
        renderedHand = cards.toList(); renderedViewer = viewer; renderedVisible = visible
        if (!visible) {
            selectedCards.clear()
            hand.addView(style.text("手牌保护已开启\n确认身份后才展示；不要替其他玩家查看或操作。", 16f, color = style.colors.muted).apply { setPadding(style.dp(16), style.dp(36), style.dp(16), style.dp(36)); background = style.shape(style.colors.soft) }, style.space())
            return
        }
        val columns = if (activity.resources.configuration.screenWidthDp >= 600) 10 else 6
        cards.chunked(columns).forEach { group ->
            val row = style.row()
            group.forEach { id ->
                val card = style.button(cardLabel(id)) {
                    if (id in selectedCards) selectedCards.remove(id) else selectedCards.add(id)
                    refreshCardSelection()
                }
                card.textSize = 18f * style.settings.fontScale
                card.setPadding(0, style.dp(8), 0, style.dp(8))
                card.contentDescription = "${cardLabel(id)}，未选中"
                row.addView(card, LinearLayout.LayoutParams(0, style.dp(70), 1f).apply { marginEnd = style.dp(4) })
                cardViews[id] = card
            }
            repeat(columns - group.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
            hand.addView(row, style.space(bottom = 8))
        }
        refreshCardSelection()
    }

    private fun refreshCardSelection() {
        val s = snapshot ?: return
        val visible = privacy.visible(s, seats, viewer)
        cardViews.forEach { (id, view) ->
            val chosen = id in selectedCards
            view.background = style.shape(if (chosen) style.colors.soft else Color.rgb(255, 253, 246), 10, if (chosen) style.colors.accent else style.colors.border)
            view.setTextColor(if (id == 53 || id < 52 && id % 4 in listOf(1, 2)) Color.rgb(164, 38, 51) else Color.rgb(30, 43, 48))
            view.translationY = if (chosen) -style.dp(4).toFloat() else 0f
            view.isSelected = chosen
            view.isEnabled = visible && actionable(viewer) && s.phase == "playing"
            view.contentDescription = "${cardLabel(id)}，${if (chosen) "已选中" else "未选中"}"
        }
        if (visible) setText(handTitle, "${viewer + 1} 号的手牌 · 点按多选（${selectedCards.size} 张）")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildArcadeControls() {
        boardControls.removeAllViews(); arcadeButtons.clear()
        controlSeats.chunked(3).forEach { group ->
            val row = style.row()
            group.forEach { seat ->
                val panel = style.card(4)
                panel.addView(style.text("${seat + 1} 号", 15f, true, style.colors.accent).apply { gravity = Gravity.CENTER }, style.space(bottom = 8))
                fun addButton(parent: LinearLayout, name: String, action: GameAction, primary: Boolean = false) {
                    val button = style.button(name, primary) {
                        if (action.type == "direction") { desiredDirections[seat] = action.values[0]; sendDirection(seat) } else submit(seat, action)
                    }
                    button.setPadding(0, style.dp(5), 0, style.dp(5))
                    button.textSize = 16f * style.settings.fontScale
                    button.minimumHeight = style.dp(48)
                    button.minHeight = style.dp(48)
                    button.contentDescription = "${seat + 1}号，$name"
                    if (action.type == "direction") {
                        button.setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> { view.parent.requestDisallowInterceptTouchEvent(true); desiredDirections[seat] = action.values[0]; sendDirection(seat); view.isPressed = true; true }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    view.isPressed = false
                                    if (desiredDirections[seat] == action.values[0]) { desiredDirections[seat] = -1; sendDirection(seat) }
                                    view.parent.requestDisallowInterceptTouchEvent(false)
                                    true
                                }
                                else -> true
                            }
                        }
                    }
                    parent.addView(button, LinearLayout.LayoutParams(0, style.dp(48), 1f).apply { marginEnd = style.dp(2) })
                    arcadeButtons.add(Triple(button, seat, action))
                }
                when (gameId) {
                    "reaction" -> {
                        val taps = style.row()
                        addButton(taps, "抢按", GameAction("tap"), true)
                        panel.addView(taps, style.space())
                    }
                    "color", "math", "coinflip", "numberguess", "oddone", "targettap", "pattern", "sumten", "highlow", "color_match", "memoryrush", "speedchoice" -> repeat(2) { r ->
                        val choices = style.row()
                        repeat(2) { c -> val index = r * 2 + c; val type = if (gameId == "color" || gameId == "math") "answer" else "choose"; addButton(choices, "${snapshot?.labels?.getOrNull(index) ?: "选项 ${index + 1}"}", GameAction(type, listOf(index))) }
                        panel.addView(choices, style.space(bottom = 5))
                    }
                    else -> {
                        listOf(listOf("↑" to 0, "→" to 1), listOf("←" to 3, "↓" to 2)).forEach { pair ->
                            val directions = style.row()
                            pair.forEach { (label, direction) -> addButton(directions, label, GameAction("direction", listOf(direction))) }
                            panel.addView(directions, style.space(bottom = 4))
                        }
                        val actions = style.row()
                        addButton(actions, "停止", GameAction("direction", listOf(-1)))
                        if (gameId == "tank") addButton(actions, "开火", GameAction("fire"), true)
                        if (gameId == "bomber") addButton(actions, "炸弹", GameAction("bomb"), true)
                        panel.addView(actions, style.space())
                    }
                }
                row.addView(panel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(3) })
            }
            boardControls.addView(row, style.space(bottom = 10))
        }
    }

    private fun arcadeAllowed(s: GameSnapshot, seat: Int, action: GameAction): Boolean {
        if (!actionable(seat)) return false
        if (s.extra["alive"]?.split(',')?.getOrNull(seat) == "0") return false
        if (seat == viewer) return action in s.legal
        return when (gameId) {
            "reaction" -> action.type == "tap" && s.phase in listOf("waiting", "signal") && s.extra["responses"]?.split(',')?.getOrNull(seat) == "-1"
            "color", "math" -> action.type == "answer" && action.values.singleOrNull() in 0..3 && s.phase == "playing" && s.extra["answered"]?.split(',')?.getOrNull(seat) == "0"
            "coinflip", "numberguess", "oddone", "targettap", "pattern", "sumten", "highlow", "color_match", "memoryrush", "speedchoice" -> action.type == "choose" && action.values.singleOrNull() in 0..3 && s.phase == "playing"
            "snake", "dodge" -> action.type == "direction" && action.values.singleOrNull() in -1..3
            "tank" -> action.type == "direction" || action.type == "fire"
            "bomber" -> action.type == "direction" || action.type == "bomb"
            else -> false
        }
    }

    private fun refreshArcade() {
        val s = snapshot ?: return
        arcadeButtons.forEach { (button, seat, action) ->
            val alive = s.extra["alive"]?.split(',')?.getOrNull(seat) != "0"
            button.isEnabled = if (action.type == "direction") actionable(seat) && alive else arcadeAllowed(s, seat, action)
            if (action.type == "answer") setText(button, s.labels.getOrNull(action.values[0]) ?: "${action.values[0] + 1}")
        }
    }

    private fun sendDirection(seat: Int) {
        val s = snapshot ?: return
        val direction = desiredDirections[seat] ?: return
        if (directionTicks[seat] == s.tick) return
        if (!actionable(seat)) { desiredDirections.remove(seat); return }
        val action = GameAction("direction", listOf(direction))
        if (!arcadeAllowed(s, seat, action)) return
        directionTicks[seat] = s.tick
        callbacks.onAction(seat, action)
    }

    private fun flushDirections() {
        val s = snapshot ?: return
        val directions = s.extra["directions"].orEmpty().split(',')
        desiredDirections.keys.toList().forEach { seat ->
            if (directions.getOrNull(seat)?.toIntOrNull() == desiredDirections[seat]) {
                if (desiredDirections[seat] == -1) desiredDirections.remove(seat)
            } else sendDirection(seat)
        }
    }

    private fun actionLabel(action: GameAction): String = when (action.type) {
        "resign" -> "认输"; "pass" -> if (gameId == "doudizhu") "不出" else "停一手"
        "accept_score" -> "确认数目"; "resume" -> "继续行棋"; "hint" -> "提示"
        "bid" -> if (action.values.firstOrNull() == 0) "不叫" else "叫 ${action.values.firstOrNull()} 分"
        "dead" -> "标记死子 ${action.values.joinToString { square(it, snapshot?.width ?: 1) }}"
        "place", "flip" -> "${if (action.type == "flip") "翻牌" else "落子"} ${action.values.joinToString { square(it, snapshot?.width ?: 1) }}"
        "move" -> {
            val cells = if (gameId == "chess") action.values.take(2) else action.values
            cells.joinToString(" → ") { square(it, snapshot?.width ?: 1) } + if (gameId == "chess" && action.values.size == 3) " 升${promotion(action.values[2])}" else ""
        }
        else -> action.type
    }
    private fun promotion(code: Int) = when (code) { 2 -> "马"; 3 -> "象"; 4 -> "车"; 5 -> "后"; else -> code.toString() }
    private fun square(index: Int, width: Int): String = if (width <= 0) (index + 1).toString() else {
        val row = if (gameId == "chess" || gameId == "draughts") width - index / width else index / width + 1
        "${('A'.code + index % width).toChar()}$row"
    }
    private fun cardLabel(id: Int): String {
        if (id == 52) return "小王"
        if (id == 53) return "大王"
        val rank = when (val number = id / 4 + 3) { 11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; 15 -> "2"; else -> number.toString() }
        return "${listOf("♠", "♥", "♦", "♣")[id % 4]}$rank"
    }
    private fun setText(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }

    fun dispose() {
        if (disposed) return
        disposed = true
        desiredDirections.clear(); directionTicks.clear()
        choiceDialog?.dismiss(); choiceDialog = null
        selectedCards.clear(); cardViews.clear(); renderedHand = emptyList()
        hand.removeAllViews()
    }
}
