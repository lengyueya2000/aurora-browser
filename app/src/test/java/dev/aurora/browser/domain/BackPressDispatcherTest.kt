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
}
