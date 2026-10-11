package dev.aurora.browser.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniboxBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsUrlAndSecureIdentity() {
        compose.setContent {
            AuroraTheme {
                OmniboxBar("developer.android.com", SiteSecurity.SECURE, {}, {})
            }
        }
        compose.onNodeWithText("developer.android.com").assertExists()
        compose.onNodeWithContentDescription("连接安全").assertExists()
    }

    @Test fun showsWarningIdentityForBadCert() {
        compose.setContent {
            AuroraTheme { OmniboxBar("bad.example.com", SiteSecurity.WARNING, {}, {}) }
        }
        compose.onNodeWithContentDescription("证书警告").assertExists()
    }

    @Test fun showsPlaceholderWhenUrlBlank() {
        compose.setContent {
            AuroraTheme { OmniboxBar("", SiteSecurity.INSECURE, {}, {}) }
        }
        compose.onNodeWithText("搜索或输入网址").assertExists()
    }

    @Test fun tapAndTrailingActionFireCallbacks() {
        var tap = 0; var trailing = 0
        compose.setContent {
            AuroraTheme { OmniboxBar("a.com", SiteSecurity.SECURE, { tap++ }, { trailing++ }) }
        }
        compose.onNodeWithContentDescription("地址栏，a.com").performClick()
        compose.onNodeWithContentDescription("刷新").performClick()
        assertThat(tap).isEqualTo(1)
        assertThat(trailing).isEqualTo(1)
    }

    // 纯函数，不需要 Compose 环境，但放同一个类里方便一起跑。

    @Test fun hostOfExtractsHostAndFallsBackToRawInput() {
        assertThat(hostOf("https://developer.android.com/guide")).isEqualTo("developer.android.com")
        assertThat(hostOf("http://a.com")).isEqualTo("a.com")
        assertThat(hostOf(null)).isEqualTo("")
        assertThat(hostOf("not a url")).isEqualTo("not a url")
    }

    @Test fun securityOfFollowsScheme() {
        assertThat(securityOf("https://a.com")).isEqualTo(SiteSecurity.SECURE)
        assertThat(securityOf("http://a.com")).isEqualTo(SiteSecurity.INSECURE)
        assertThat(securityOf(null)).isEqualTo(SiteSecurity.INSECURE)
    }

    /** scheme 比较不区分大小写；证书异常优先于 scheme。 */
    @Test fun securityOfIsCaseInsensitiveAndHonoursSslWarning() {
        assertThat(securityOf("HTTPS://A.com")).isEqualTo(SiteSecurity.SECURE)
        assertThat(securityOf("https://a.com", sslWarning = true)).isEqualTo(SiteSecurity.WARNING)
        assertThat(securityOf(null, sslWarning = true)).isEqualTo(SiteSecurity.WARNING)
    }

    @Test fun unbookmarkedPageOffersToBookmark() {
        var toggles = 0
        compose.setContent {
            AuroraTheme {
                OmniboxBar(
                    "a.com", SiteSecurity.SECURE, {}, {},
                    bookmarked = false, onToggleBookmark = { toggles++ },
                )
            }
        }
        compose.onNodeWithContentDescription("收藏").performClick()
        assertThat(toggles).isEqualTo(1)
    }

    @Test fun bookmarkedPageOffersToRemoveTheBookmark() {
        compose.setContent {
            AuroraTheme {
                OmniboxBar(
                    "a.com", SiteSecurity.SECURE, {}, {},
                    bookmarked = true, onToggleBookmark = {},
                )
            }
        }
        compose.onNodeWithContentDescription("取消收藏").assertExists()
    }

    /** 没接书签回调时不摆星标——不可用的控件比没有控件更糟。 */
    @Test fun noBookmarkStarWithoutCallback() {
        compose.setContent {
            AuroraTheme { OmniboxBar("a.com", SiteSecurity.SECURE, {}, {}) }
        }
        compose.onNodeWithContentDescription("收藏").assertDoesNotExist()
    }
}
