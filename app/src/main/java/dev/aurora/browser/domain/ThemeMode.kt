package dev.aurora.browser.domain

/**
 * 主题模式：浅色 / 深色 / 跟随系统。
 *
 * 默认 [LIGHT]：1.16 起的主设计是浅色「晨雾」，装机即见；1.15 之前那套深色还在，
 * 在「设置 → 外观」里切一下即可。默认值不做成 [SYSTEM]，是因为那样在深色手机上
 * 装完看到的还是旧界面 —— 用户选了新设计，却要看设置页才知道自己拿到的是什么。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    /**
     * 系统当前是深色时，本模式最终该不该用深色令牌。
     *
     * 抽成纯函数：`MainActivity` 要在组合之外给系统栏算图标颜色，那里读不到
     * `isSystemInDarkTheme()`，只能把系统状态当参数传进来。
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val Default: ThemeMode = LIGHT

        /** 持久化里存的是枚举名；未知值回落到默认，避免旧配置让应用崩。 */
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
