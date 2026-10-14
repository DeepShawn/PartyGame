package com.aicode.partyhub.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

class LauncherIconManager(private val context: Context) {
    private val aliases = mapOf("trio" to "Trio", "dice" to "Dice", "board" to "Board", "mono" to "Mono")

    fun apply(icon: String): Result<Unit> = runCatching {
        val suffix = requireNotNull(aliases[icon]) { "未知的桌面图标" }
        val manager = context.packageManager
        val selected = ComponentName(context.packageName, "com.aicode.partyhub.Launcher$suffix")
        val components = aliases.values.map { ComponentName(context.packageName, "com.aicode.partyhub.Launcher$it") }
        components.forEach { manager.getActivityInfo(it, PackageManager.MATCH_DISABLED_COMPONENTS) }
        manager.setComponentEnabledSetting(selected, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        components.filter { it != selected }.forEach {
            manager.setComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
