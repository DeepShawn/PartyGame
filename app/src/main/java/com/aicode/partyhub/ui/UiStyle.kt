package com.aicode.partyhub.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import com.aicode.partyhub.settings.AppearanceStore
import org.json.JSONObject

internal data class UiPalette(
    val background: Int, val surface: Int, val text: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val soft: Int, val border: Int,
    val boardLight: Int, val boardDark: Int, val danger: Int, val dark: Boolean
) {
    companion object {
        fun load(context: Context, name: String): UiPalette {
            fun c(hex: String) = Color.parseColor(hex)
            val base = when (name) {
                "midnight" -> UiPalette(c("#121D2A"), c("#1C2A3A"), c("#F0F5FA"), c("#B4C3D1"), c("#80D8C4"), c("#0C2926"), c("#273D49"), c("#3A4A5B"), c("#A5BAC2"), c("#526B79"), c("#FFADAD"), true)
                "cherry" -> UiPalette(c("#FFF5F6"), c("#FFFFFF"), c("#422A34"), c("#7C5969"), c("#A9325D"), Color.WHITE, c("#FBE5EC"), c("#EACDD7"), c("#F8DFE3"), c("#BC8093"), c("#AD2838"), false)
                "wood" -> UiPalette(c("#F6F0E6"), c("#FFFBF4"), c("#3B3026"), c("#75634F"), c("#75603B"), Color.WHITE, c("#EDE2D0"), c("#DCCDAD"), c("#EDD2A1"), c("#AC8053"), c("#AA382E"), false)
                else -> UiPalette(c("#F4F8F7"), Color.WHITE, c("#183634"), c("#57736F"), c("#117D78"), Color.WHITE, c("#E0F2ED"), c("#D7E6E2"), c("#E7D8B8"), c("#77988B"), c("#B73545"), false)
            }
            val json = runCatching { context.assets.open("design/tokens.json").bufferedReader().use { JSONObject(it.readText()) } }.getOrNull() ?: return base
            val theme = json.optJSONObject("themes")?.optJSONObject(name) ?: return base
            val colors = theme.optJSONObject("colors") ?: theme
            fun token(key: String, fallback: Int) = runCatching { Color.parseColor(colors.getString(key)) }.getOrDefault(fallback)
            return base.copy(background = token("background", base.background), surface = token("surface", base.surface),
                text = token("text", base.text), muted = token("muted", base.muted), accent = token("accent", base.accent),
                onAccent = token("onAccent", base.onAccent), soft = token("soft", base.soft), border = token("border", base.border))
        }
    }
}

internal class UiStyle(val context: Context, val settings: AppearanceStore.Settings) {
    val colors = UiPalette.load(context, settings.theme)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun shape(color: Int, radius: Int = 18, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    fun text(value: String, size: Float = 16f, bold: Boolean = false, color: Int = colors.text) = TextView(context).apply {
        text = value
        textSize = size * settings.fontScale
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        isMotionEventSplittingEnabled = true
    }
    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isMotionEventSplittingEnabled = true
    }
    fun card(padding: Int = 18) = column(padding).apply { background = shape(colors.surface, 22, colors.border) }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) = AppCompatButton(context).apply {
        text = label
        textSize = 16f * settings.fontScale
        isAllCaps = false
        minHeight = dp(50)
        minimumHeight = dp(50)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(colors.muted, if (primary) colors.onAccent else colors.accent)))
        val fill = if (primary) colors.accent else colors.soft
        background = if (settings.reducedMotion) shape(fill, 14) else RippleDrawable(ColorStateList.valueOf(colors.border), shape(fill, 14), null)
        stateListAnimator = null
        isSoundEffectsEnabled = settings.sound
        setOnClickListener {
            if (settings.vibration) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }
    fun input(hintText: String, password: Boolean = false) = EditText(context).apply {
        hint = hintText
        textSize = 17f * settings.fontScale
        setTextColor(colors.text)
        setHintTextColor(colors.muted)
        setSingleLine(true)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = shape(colors.background, 12, colors.border)
        minHeight = dp(52)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    fun toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val line = row()
        val copy = column()
        copy.addView(text(label, 17f, true))
        copy.addView(text(detail, 13f, color = colors.muted), space(top = 5))
        line.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(SwitchCompat(context).apply {
            isChecked = checked
            contentDescription = label
            minHeight = dp(52)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(colors.accent, colors.muted))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(colors.soft, colors.border))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        return line
    }
    fun icon(name: String, description: String, size: Int): View {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return text("图标待接入", 12f, color = colors.muted).apply {
            gravity = Gravity.CENTER
            contentDescription = "$description，图标资源待接入"
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
        return ImageView(context).apply {
            setImageResource(id)
            contentDescription = description
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }
    fun scroll(child: View) = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(child, ViewGroup.LayoutParams(-1, -2))
    }
    fun space(width: Int = -1, height: Int = -2, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height)).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }
    fun section(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 23f, true), space(top = 22, bottom = 10))
        if (subtitle != null) parent.addView(text(subtitle, 14f, color = colors.muted), space(bottom = 14))
    }
}
