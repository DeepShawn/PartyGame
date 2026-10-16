package com.aicode.partyhub.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.aicode.partyhub.core.GameSnapshot
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal class GameCanvas(private val style: UiStyle, private val onCell: (Int) -> Unit, private val onSwipe: ((Int) -> Unit)? = null) : View(style.context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lettering = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val pieceText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private var snapshot: GameSnapshot? = null
    private var highlights = emptySet<Int>()
    private var selected = emptyList<Int>()
    private var numbers = emptyArray<String>()
    private var rowLabels = emptyArray<String>()
    private var colLabels = emptyArray<String>()
    private var owners = emptyList<Int>()
    private var dead = emptySet<Int>()
    private val chessWhite = arrayOf("", "♙", "♘", "♗", "♖", "♕", "♔")
    private val chessBlack = arrayOf("", "♟", "♞", "♝", "♜", "♛", "♚")
    private val xiangqiRed = arrayOf("", "兵", "馬", "相", "俥", "炮", "仕", "帥")
    private val xiangqiBlack = arrayOf("", "卒", "馬", "象", "車", "砲", "士", "將")
    private val players = intArrayOf(Color.rgb(32, 123, 174), Color.rgb(183, 67, 86), Color.rgb(137, 98, 22))
    private var left = 0f
    private var top = 0f
    private var step = 1f
    private var gridWidth = 0f
    private var gridHeight = 0f
    private var touched = -1
    private var touchX = 0f
    private var touchY = 0f
    private var keyboardCell = 0

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        background = style.shape(style.colors.surface, 20, style.colors.border)
        isSoundEffectsEnabled = style.settings.sound
    }

    fun update(value: GameSnapshot, targets: Set<Int>, path: List<Int>) {
        snapshot = value
        highlights = targets
        selected = path
        if (numbers.size != value.cells.size) numbers = Array(value.cells.size) { (it + 1).toString() }
        if (rowLabels.size != value.height) rowLabels = Array(value.height) { (it + 1).toString() }
        if (colLabels.size != value.width) colLabels = Array(value.width) { if (it < 26) ('A' + it).toString() else (it + 1).toString() }
        owners = value.extra["owners"].orEmpty().split(',').mapNotNull(String::toIntOrNull)
        dead = value.extra["dead"].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
        contentDescription = "${value.width} 列 ${value.height} 行棋盘。${value.message}。可用方向键选格，回车选中；也可使用棋盘下方的合法走法列表。"
        invalidate()
    }

    private fun intersections(s: GameSnapshot) = s.gameId in listOf("go", "gomoku", "xiangqi")

    private fun geometry(s: GameSnapshot) {
        val margin = style.dp(if (style.settings.coordinates) 24 else 14).toFloat()
        val cross = intersections(s)
        val cols = if (cross) (s.width - 1).coerceAtLeast(1) else s.width.coerceAtLeast(1)
        val rows = if (cross) (s.height - 1).coerceAtLeast(1) else s.height.coerceAtLeast(1)
        val padding = if (cross) 1f else 0f
        step = min((width - 2 * margin) / (cols + padding), (height - 2 * margin) / (rows + padding)).coerceAtLeast(1f)
        gridWidth = cols * step; gridHeight = rows * step
        left = (width - gridWidth) / 2f; top = (height - gridHeight) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot ?: return
        if (s.width <= 0 || s.height <= 0) return
        geometry(s)
        if (s.gameId in listOf("snake", "tank", "bomber", "dodge")) { arena(canvas, s); return }
        val cross = intersections(s)
        val light = when (style.settings.board) { "theme" -> style.colors.soft; "contrast" -> Color.rgb(247, 247, 244); else -> style.colors.boardLight }
        val dark = when (style.settings.board) { "theme" -> style.colors.accent; "contrast" -> Color.rgb(93, 105, 113); else -> style.colors.boardDark }
        fill.color = light
        val edge = if (cross) step * 0.5f else 0f
        canvas.drawRoundRect(left - edge, top - edge, left + gridWidth + edge, top + gridHeight + edge, style.dp(8).toFloat(), style.dp(8).toFloat(), fill)
        line.strokeWidth = style.dp(1).toFloat().coerceAtMost(step * 0.055f)
        line.color = Color.rgb(90, 88, 72)
        if (cross) {
            for (y in 0 until s.height) canvas.drawLine(left, top + y * step, left + gridWidth, top + y * step, line)
            for (x in 0 until s.width) {
                if (s.gameId == "xiangqi" && x in 1 until s.width - 1) {
                    canvas.drawLine(left + x * step, top, left + x * step, top + 4 * step, line)
                    canvas.drawLine(left + x * step, top + 5 * step, left + x * step, top + gridHeight, line)
                } else canvas.drawLine(left + x * step, top, left + x * step, top + gridHeight, line)
            }
            if (s.gameId == "xiangqi" && s.width == 9 && s.height == 10) {
                for (base in intArrayOf(0, 7)) {
                    canvas.drawLine(left + 3 * step, top + base * step, left + 5 * step, top + (base + 2) * step, line)
                    canvas.drawLine(left + 5 * step, top + base * step, left + 3 * step, top + (base + 2) * step, line)
                }
                label(canvas, "楚河", left + 2 * step, top + 4.5f * step, step * 0.45f, Color.rgb(90, 70, 50))
                label(canvas, "漢界", left + 6 * step, top + 4.5f * step, step * 0.45f, Color.rgb(90, 70, 50))
            }
        }
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val index = y * s.width + x
            val value = s.cells.getOrElse(index) { 0 }
            val invalid = s.gameId == "chinese_checkers" && value < 0
            val cx = left + (x + if (cross) 0f else 0.5f) * step
            val cy = top + (y + if (cross) 0f else 0.5f) * step
            if (!cross && !invalid) {
                fill.color = if (s.gameId in listOf("chess", "draughts") && (x + y) % 2 == 1) dark else light
                canvas.drawRect(left + x * step, top + y * step, left + (x + 1) * step, top + (y + 1) * step, fill)
                line.color = Color.argb(55, 60, 70, 65)
                canvas.drawRect(left + x * step, top + y * step, left + (x + 1) * step, top + (y + 1) * step, line)
            }
            if (invalid) continue
            if (s.gameId == "memory") memoryCard(canvas, value, index, cx, cy)
            else if (value != 0) piece(canvas, s.gameId, value, cx, cy)
            if (index in highlights) {
                line.color = if (style.settings.board == "contrast") Color.rgb(0, 70, 150) else style.colors.accent
                line.strokeWidth = (step * 0.075f).coerceAtLeast(2f)
                canvas.drawCircle(cx, cy, step * 0.30f, line)
                fill.color = line.color
                canvas.drawCircle(cx, cy, step * 0.07f, fill)
            }
            if (index in selected || (hasFocus() && index == keyboardCell)) {
                line.color = Color.rgb(204, 116, 13); line.strokeWidth = (step * 0.085f).coerceAtLeast(2f)
                canvas.drawRoundRect(cx - step * 0.46f, cy - step * 0.46f, cx + step * 0.46f, cy + step * 0.46f, step * 0.12f, step * 0.12f, line)
            }
            if (index in dead) {
                line.color = style.colors.danger; line.strokeWidth = step * 0.07f
                canvas.drawLine(cx - step * 0.2f, cy - step * 0.2f, cx + step * 0.2f, cy + step * 0.2f, line)
                canvas.drawLine(cx + step * 0.2f, cy - step * 0.2f, cx - step * 0.2f, cy + step * 0.2f, line)
            }
        }
        if (style.settings.coordinates) {
            for (x in 0 until s.width) label(canvas, colLabels[x], left + (x + if (cross) 0f else 0.5f) * step, top - edge - style.dp(10), min(step * 0.36f, style.dp(13).toFloat()), style.colors.muted)
            for (y in 0 until s.height) label(canvas, rowLabels[y], left - edge - style.dp(12), top + (y + if (cross) 0f else 0.5f) * step, min(step * 0.36f, style.dp(13).toFloat()), style.colors.muted)
        }
    }

    private fun piece(canvas: Canvas, gameId: String, value: Int, x: Float, y: Float) {
        if (gameId == "chess") {
            val glyph = (if (value > 0) chessWhite else chessBlack).getOrElse(abs(value)) { "?" }
            pieceText.textSize = step * 0.82f
            pieceText.color = Color.rgb(23, 33, 39)
            pieceText.style = Paint.Style.STROKE; pieceText.strokeWidth = step * 0.035f
            canvas.drawText(glyph, x, y - (pieceText.ascent() + pieceText.descent()) / 2, pieceText)
            pieceText.style = Paint.Style.FILL
            pieceText.color = if (value > 0) Color.WHITE else Color.rgb(23, 33, 39)
            canvas.drawText(glyph, x, y - (pieceText.ascent() + pieceText.descent()) / 2, pieceText)
            return
        }
        if (gameId == "xiangqi") {
            fill.color = Color.rgb(255, 241, 207); canvas.drawCircle(x, y, step * 0.42f, fill)
            line.color = if (value > 0) Color.rgb(162, 39, 39) else Color.rgb(38, 48, 46)
            line.strokeWidth = step * 0.035f; canvas.drawCircle(x, y, step * 0.39f, line)
            label(canvas, (if (value > 0) xiangqiRed else xiangqiBlack).getOrElse(abs(value)) { "?" }, x, y, step * 0.6f, line.color)
            return
        }
        fill.color = when {
            gameId == "chinese_checkers" -> players[(value - 1).coerceIn(0, 2)]
            gameId == "draughts" -> if (value > 0) Color.rgb(250, 249, 240) else Color.rgb(29, 38, 40)
            value == 1 -> Color.rgb(29, 38, 40)
            else -> Color.rgb(250, 249, 240)
        }
        canvas.drawCircle(x, y, step * 0.40f, fill)
        line.color = Color.rgb(91, 101, 99); line.strokeWidth = step * 0.025f
        canvas.drawCircle(x, y, step * 0.40f, line)
        if (gameId == "draughts" && abs(value) > 1) label(canvas, "王", x, y, step * 0.48f, if (value > 0) Color.BLACK else Color.WHITE)
    }

    private fun memoryCard(canvas: Canvas, value: Int, index: Int, x: Float, y: Float) {
        val owner = owners.getOrElse(index) { -1 }
        fill.color = if (owner >= 0) players[owner.coerceIn(0, 2)] else if (value < 0) style.colors.accent else style.colors.surface
        canvas.drawRoundRect(x - step * 0.43f, y - step * 0.43f, x + step * 0.43f, y + step * 0.43f, step * 0.12f, step * 0.12f, fill)
        if (value >= 0) label(canvas, numbers.getOrElse(value) { "?" }, x, y, step * 0.50f, if (owner >= 0) Color.WHITE else style.colors.text)
        else {
            line.color = style.colors.onAccent; line.strokeWidth = step * 0.025f
            if (style.settings.cardBack != "plain") for (i in -1..1) {
                val offset = i * step * 0.16f
                canvas.drawLine(x - step * 0.25f, y + offset, x + step * 0.25f, y + offset, line)
                if (style.settings.cardBack == "grid") canvas.drawLine(x + offset, y - step * 0.25f, x + offset, y + step * 0.25f, line)
            }
        }
    }

    private fun arena(canvas: Canvas, s: GameSnapshot) {
        fill.color = style.colors.background
        canvas.drawRoundRect(left, top, left + gridWidth, top + gridHeight, step * 0.2f, step * 0.2f, fill)
        for (i in s.cells.indices) {
            if (s.cells[i] == 0) continue
            val x = left + i % s.width * step; val y = top + i / s.width * step
            fill.color = if (s.cells[i] < 0) style.colors.muted else Color.rgb(169, 125, 73)
            canvas.drawRoundRect(x + step * 0.06f, y + step * 0.06f, x + step * 0.94f, y + step * 0.94f, step * 0.13f, step * 0.13f, fill)
        }
        for (entity in s.entities) {
            val cx = left + (entity.x + 0.5f) * step; val cy = top + (entity.y + 0.5f) * step
            val w = entity.width * step * 0.45f; val h = entity.height * step * 0.45f
            fill.color = if (entity.owner in 0..2) players[entity.owner] else style.colors.danger
            when (entity.kind) {
                "food" -> { fill.color = Color.rgb(210, 140, 29); canvas.drawCircle(cx, cy, step * 0.22f, fill) }
                "bomb" -> { fill.color = Color.rgb(35, 45, 50); canvas.drawCircle(cx, cy, step * 0.33f, fill); label(canvas, "!", cx, cy, step * 0.5f, Color.WHITE) }
                "blast" -> { fill.color = Color.rgb(232, 155, 40); canvas.drawRect(cx - w, cy - h, cx + w, cy + h, fill) }
                "bullet" -> canvas.drawCircle(cx, cy, step * 0.13f, fill)
                "obstacle" -> { fill.color = style.colors.danger; canvas.drawRoundRect(cx - w, cy - h, cx + w, cy + h, step * 0.10f, step * 0.10f, fill) }
                else -> {
                    canvas.save()
                    canvas.rotate(entity.angle, cx, cy)
                    canvas.drawRoundRect(cx - w, cy - h, cx + w, cy + h, step * 0.2f, step * 0.2f, fill)
                    if (entity.kind == "player" && s.gameId == "tank") {
                        line.color = Color.WHITE; line.strokeWidth = step * 0.12f
                        canvas.drawLine(cx, cy, cx, cy - step * 0.55f, line)
                    }
                    canvas.restore()
                    if (entity.kind == "player") label(canvas, numbers.getOrElse(entity.owner) { (entity.owner + 1).toString() }, cx, cy, step * 0.43f, Color.WHITE)
                }
            }
        }
    }

    private fun label(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int) {
        lettering.textSize = size; lettering.color = color
        canvas.drawText(value, x, y - (lettering.ascent() + lettering.descent()) / 2, lettering)
    }

    private fun hit(x: Float, y: Float): Int {
        val s = snapshot ?: return -1
        if (s.width <= 0 || s.height <= 0) return -1
        geometry(s)
        val column = if (intersections(s)) ((x - left) / step).roundToInt() else kotlin.math.floor((x - left) / step).toInt()
        val row = if (intersections(s)) ((y - top) / step).roundToInt() else kotlin.math.floor((y - top) / step).toInt()
        return if (column in 0 until s.width && row in 0 until s.height) row * s.width + column else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX = event.x; touchY = event.y; touched = hit(event.x, event.y); return true }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchX; val dy = event.y - touchY
                if (snapshot?.gameId in listOf("snake", "tank", "bomber", "dodge") && kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) >= style.dp(24)) {
                    onSwipe?.invoke(if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) if (dx > 0) 1 else 3 else if (dy > 0) 2 else 0)
                    touched = -1
                    return true
                }
                val cell = hit(event.x, event.y)
                if (cell >= 0 && cell == touched) { keyboardCell = cell; performClick(); onCell(cell) }
                touched = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> { touched = -1; return true }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = snapshot ?: return super.onKeyDown(keyCode, event)
        if (s.width <= 0 || s.height <= 0) return super.onKeyDown(keyCode, event)
        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> keyboardCell - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> keyboardCell + 1
            KeyEvent.KEYCODE_DPAD_UP -> keyboardCell - s.width
            KeyEvent.KEYCODE_DPAD_DOWN -> keyboardCell + s.width
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { onCell(keyboardCell); return true }
            else -> return super.onKeyDown(keyCode, event)
        }
        keyboardCell = next.coerceIn(0, s.width * s.height - 1)
        announceForAccessibility("${keyboardCell / s.width + 1} 行 ${keyboardCell % s.width + 1} 列")
        invalidate()
        return true
    }
}
