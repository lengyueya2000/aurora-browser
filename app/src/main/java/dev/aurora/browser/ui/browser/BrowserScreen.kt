package dev.aurora.browser.ui.browser

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.ui.components.AuroraBottomSheet
import dev.aurora.browser.ui.components.BottomToolbar
import dev.aurora.browser.ui.components.OmniboxBar
import dev.aurora.browser.ui.components.SettingRow
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.components.securityOf
import dev.aurora.browser.ui.home.HomeScreen
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraSpacing

@Composable
fun BrowserScreen(
    vm: BrowserViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val surface by vm.surface.collectAsState()
    val tabs by vm.tabsState.collectAsState()
    val active by vm.activeTab.collectAsState()
    val menuOpen by vm.menuOpen.collectAsState()
    val exitHint by vm.exitHintVisible.collectAsState()
    val query by vm.query.collectAsState()
    val suggestions by vm.suggestions.collectAsState()

    val context = LocalContext.current
    val controller = remember { MutableBrowserController() }
    var progress by remember { mutableIntStateOf(0) }
    // 标签网格与菜单面板都要「关闭全部」，收成一个 lambda 免得两处漂移。
    val closeAllTabs = { tabs.forEach { t -> vm.closeTab(t.id) } }

    BackHandler(enabled = true) {
        when (vm.onBack(canGoBack = controller.canGoBack())) {
            BackOutcome.PAGE_BACK -> controller.goBack()
            BackOutcome.EXIT_NOW -> (context as? Activity)?.finish()
            else -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Aurora.colors.bg),
    ) {
        // 避让层：状态栏与手势条。悬浮工具栏也放在这一层内，
        // 否则底部会被手势导航条压住（原型阶段已踩过这个坑）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when (surface) {
                BrowserSurface.Page -> if (active.isNewTab) {
                    HomeScreen(
                        onNavigate = { vm.navigate(it) },
                        onSearch = { vm.openOmnibox() },
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 顶部 2dp 加载进度条（规格 5.3）。轨道常驻但透明，仅进度段着色，
                        // 这样加载开始/结束不会引起 WebView 上下抖动。
                        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                            if (progress in 1..99) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress / 100f)
                                        .height(2.dp)
                                        .background(Aurora.colors.brand),
                                )
                            }
                        }
                        // 网页态的地址栏：显示域名 + 站点身份；点击回到输入态。
                        // 加载中右侧按钮变「停止加载」（规格 5.3）。
                        val loading = progress in 1..99
                        OmniboxBar(
                            displayUrl = hostOf(active.url),
                            security = securityOf(active.url),
                            onTap = { vm.openOmnibox() },
                            trailingIcon = if (loading) AuroraIcons.Close else AuroraIcons.Refresh,
                            onTrailingAction = {
                                if (loading) controller.stopLoading() else controller.reload()
                            },
                        )
                        WebViewContainer(
                            controller = controller,
                            url = active.url,
                            onProgress = { progress = it },
                            onTitleChange = { vm.updateActiveTab(title = it) },
                            onUrlChange = { vm.updateActiveTab(url = it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                BrowserSurface.Omnibox -> OmniboxScreen(
                    query = query,
                    suggestions = suggestions,
                    onQueryChange = { vm.setQuery(it) },
                    onSubmit = { vm.navigate(it) },
                    onDismiss = { vm.closeOmnibox() },
                )
                BrowserSurface.TabSwitcher -> TabSwitcherScreen(
                    tabs = tabs, activeId = active.id,
                    onSelect = { vm.selectTab(it) }, onClose = { vm.closeTab(it) },
                    onNewTab = { vm.newTab() }, onCloseAll = closeAllTabs,
                    onDone = { vm.closeTabSwitcher() },
                )
            }

            // 悬浮工具栏只在网页/主页态常驻。地址栏聚焦态底部是系统键盘（规格屏 02），
            // 标签网格自带操作栏（规格屏 04/05），两者都不该再叠一层工具栏。
            // 菜单面板打开时工具栏仍在，但会被面板遮罩盖住（规格屏 06）。
            if (surface == BrowserSurface.Page) {
                BottomToolbar(
                    tabCount = tabs.size,
                    canGoBack = controller.canGoBack(),
                    canGoForward = controller.canGoForward(),
                    onBack = { controller.goBack() },
                    onForward = { controller.goForward() },
                    onHome = { vm.newTab() },
                    onTabs = { vm.openTabSwitcher() },
                    onMenu = { vm.openMenu() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    homeSelected = active.isNewTab,
                )
            }

            if (exitHint) {
                Text(
                    "再按一次退出",
                    color = Aurora.colors.textSecondary,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // 主菜单面板在避让层之外，遮罩才能盖满状态栏与手势条区域。
        // M2 只放本里程碑真正能兑现的动作；规格里的 4×2 能力网格
        //（书签/历史/下载/阅读/分享/无痕/扩展/设置）随 M3/M4 加入。
        if (menuOpen) {
            AuroraBottomSheet(onDismiss = { vm.closeMenu() }) {
                Text(
                    "常用功能",
                    color = Aurora.colors.textMuted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = AuroraSpacing.s),
                )
                SettingRow(
                    title = "新建标签页", icon = AuroraIcons.Add,
                    onClick = { vm.newTab() },
                )
                SettingRow(
                    title = "刷新", icon = AuroraIcons.Refresh,
                    onClick = { controller.reload() },
                )
                SettingRow(
                    title = "关闭全部标签页", icon = AuroraIcons.Trash,
                    onClick = closeAllTabs,
                )
                SettingRow(
                    title = "标签页数量", icon = AuroraIcons.Tabs,
                    value = tabs.size.toString(),
                )
            }
        }
    }
}

/** `WebViewContainer` 里 `BindableController` 的具体实现，放在 `BrowserScreen.kt` 底部。 */
class MutableBrowserController : BindableController {
    private var impl: BrowserController? = null
    override fun bind(impl: BrowserController) { this.impl = impl }
    override fun unbind() { impl = null }
    override fun loadUrl(url: String) { impl?.loadUrl(url) }
    override fun reload() { impl?.reload() }
    override fun stopLoading() { impl?.stopLoading() }
    override fun goBack() { impl?.goBack() }
    override fun goForward() { impl?.goForward() }
    override fun canGoBack() = impl?.canGoBack() ?: false
    override fun canGoForward() = impl?.canGoForward() ?: false
}
