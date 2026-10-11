package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackPressDispatcherTest {
    private var now = 0L
    private fun dispatcher() = BackPressDispatcher(exitWindowMs = 2000, clock = { now })

    private fun BackPressDispatcher.press(
        panel: Boolean = false, omnibox: Boolean = false, canGoBack: Boolean = false,
    ) = onBack(panelOpen = panel, omniboxFocused = omnibox, canGoBack = canGoBack)

    @Test fun panelTakesPriorityOverEverything() {
        assertThat(dispatcher().press(panel = true, omnibox = true, canGoBack = true))
            .isEqualTo(BackOutcome.CLOSE_PANEL)
    }

    @Test fun omniboxClosesBeforePageBack() {
        assertThat(dispatcher().press(omnibox = true, canGoBack = true))
            .isEqualTo(BackOutcome.EXIT_OMNIBOX)
    }

    @Test fun pageBackUsedWhenPossible() {
        assertThat(dispatcher().press(canGoBack = true)).isEqualTo(BackOutcome.PAGE_BACK)
    }

    @Test fun firstBackWithNothingToDoArmsExit() {
        assertThat(dispatcher().press()).isEqualTo(BackOutcome.ARM_EXIT)
    }

    @Test fun secondBackInsideWindowExits() {
        val d = dispatcher()
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)
        now = 1500
        assertThat(d.press()).isEqualTo(BackOutcome.EXIT_NOW)
    }

    @Test fun secondBackAfterWindowRearms() {
        val d = dispatcher()
        d.press()
        now = 2500
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)
    }

    @Test fun handlingSomethingElseResetsTheArm() {
        val d = dispatcher()
        d.press()                                   // ARM_EXIT
        d.press(canGoBack = true)                   // PAGE_BACK 消耗掉这次返回
        now = 100
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)   // 重新计时，不直接退出
    }

    /** 关面板也算「做了事」，必须清掉计时，否则关完面板再按一次就直接退出了。 */
    @Test fun closingPanelAlsoResetsTheArm() {
        val d = dispatcher()
        d.press()                                   // ARM_EXIT
        d.press(panel = true)                       // CLOSE_PANEL
        now = 100
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)
    }

    /** 窗口是闭区间：正好 2000ms 仍算双击（`<=`），差 1ms 就要重新计时。 */
    @Test fun exitWindowBoundaryIsInclusive() {
        val d = dispatcher()
        d.press()
        now = 2000
        assertThat(d.press()).isEqualTo(BackOutcome.EXIT_NOW)

        val other = dispatcher()
        now = 0
        other.press()
        now = 2001
        assertThat(other.press()).isEqualTo(BackOutcome.ARM_EXIT)
    }

    /** 退出后计时被清空：第三次返回要重新蓄力，不能连按三次就退两次。 */
    @Test fun exitingClearsTheArm() {
        val d = dispatcher()
        d.press()
        now = 1000
        assertThat(d.press()).isEqualTo(BackOutcome.EXIT_NOW)
        now = 1100
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)
    }
}
