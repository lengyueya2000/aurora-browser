package dev.aurora.browser.domain

enum class BackOutcome { CLOSE_PANEL, EXIT_OMNIBOX, PAGE_BACK, ARM_EXIT, EXIT_NOW }

/**
 * 返回键决策。规格顺序：关面板 → 退地址栏聚焦 → 网页后退 → 双击退出。
 * 抽成纯逻辑以便单测，不在 Composable 里写 if 链。
 */
class BackPressDispatcher(
    private val exitWindowMs: Long = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var armedAt: Long? = null

    fun onBack(panelOpen: Boolean, omniboxFocused: Boolean, canGoBack: Boolean): BackOutcome {
        if (panelOpen) return consume(BackOutcome.CLOSE_PANEL)
        if (omniboxFocused) return consume(BackOutcome.EXIT_OMNIBOX)
        if (canGoBack) return consume(BackOutcome.PAGE_BACK)

        val armed = armedAt
        val now = clock()
        return if (armed != null && now - armed <= exitWindowMs) {
            armedAt = null
            BackOutcome.EXIT_NOW
        } else {
            armedAt = now
            BackOutcome.ARM_EXIT
        }
    }

    /** 任何「真的做了事」的返回都会清掉计时，避免误退出。 */
    private fun consume(outcome: BackOutcome): BackOutcome {
        armedAt = null
        return outcome
    }
}
