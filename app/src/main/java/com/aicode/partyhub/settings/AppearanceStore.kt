package com.aicode.partyhub.settings

import android.content.Context

class AppearanceStore(context: Context) {
    data class Settings(
        val theme: String = "sea",
        val board: String = "classic",
        val cardBack: String = "waves",
        val sound: Boolean = true,
        val vibration: Boolean = true,
        val fontScale: Float = 1f,
        val coordinates: Boolean = true,
        val reducedMotion: Boolean = false,
        val confirmMoves: Boolean = true,
        val aiDifficulty: Int = 1,
        val aiThinkTimeMs: Int = 1000,
        val launcherIcon: String = "trio"
    )

    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        theme = preferences.getString("theme", "sea") ?: "sea",
        board = preferences.getString("board", "classic") ?: "classic",
        cardBack = preferences.getString("cardBack", "waves") ?: "waves",
        sound = preferences.getBoolean("sound", true),
        vibration = preferences.getBoolean("vibration", true),
        fontScale = preferences.getFloat("fontScale", 1f).coerceIn(0.85f, 1.4f),
        coordinates = preferences.getBoolean("coordinates", true),
        reducedMotion = preferences.getBoolean("reducedMotion", false),
        confirmMoves = preferences.getBoolean("confirmMoves", true),
        aiDifficulty = preferences.getInt("aiDifficulty", 1).coerceIn(0, 2),
        aiThinkTimeMs = preferences.getInt("aiThinkTimeMs", 1000).coerceIn(250, 5000),
        launcherIcon = preferences.getString("launcherIcon", "trio") ?: "trio"
    )

    fun save(settings: Settings) {
        preferences.edit()
            .putString("theme", settings.theme)
            .putString("board", settings.board)
            .putString("cardBack", settings.cardBack)
            .putBoolean("sound", settings.sound)
            .putBoolean("vibration", settings.vibration)
            .putFloat("fontScale", settings.fontScale.coerceIn(0.85f, 1.4f))
            .putBoolean("coordinates", settings.coordinates)
            .putBoolean("reducedMotion", settings.reducedMotion)
            .putBoolean("confirmMoves", settings.confirmMoves)
            .putInt("aiDifficulty", settings.aiDifficulty.coerceIn(0, 2))
            .putInt("aiThinkTimeMs", settings.aiThinkTimeMs.coerceIn(250, 5000))
            .putString("launcherIcon", settings.launcherIcon)
            .apply()
    }
}
