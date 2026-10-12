package dev.aurora.browser.domain

enum class BackOutcome { CLOSE_PANEL, EXIT_OMNIBOX, PAGE_BACK, GO_HOME, ARM_EXIT, EXIT_NOW }

/**
 * 返回键决策。规格顺序：关面板 → 退地址栏聚焦 → 网页后退 → 回首页 → 双击退出。
 * 抽成纯逻辑以便单测，不在 Composable 里写 if 链。
 *
 * [atHome] 指标签自己的首页（新标签页），而不是「WebView 退到头了」。首页不占 WebView 的
 * 历史记录，所以网页退到头之后还剩一层可退；少了这一层，从首页打开一个网页再按返回就会
 * 直接退出 App——而用户想退的是「回到首页」。
 */
class BackPressDispatcher(
    private val exitWindowMs: Long = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var armedAt: Long? = null

    fun onBack(
        panelOpen: Boolean,
        omniboxFocused: Boolean,
        atHome: Boolean,
        canGoBack: Boolean,
    ): BackOutcome {
        if (panelOpen) return consume(BackOutcome.CLOSE_PANEL)
        if (omniboxFocused) return consume(BackOutcome.EXIT_OMNIBOX)
        // 没到首页就还没退到头：能退历史就退历史，退不动了就回首页。
        if (!atHome) {
            return consume(if (canGoBack) BackOutcome.PAGE_BACK else BackOutcome.GO_HOME)
        }

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
