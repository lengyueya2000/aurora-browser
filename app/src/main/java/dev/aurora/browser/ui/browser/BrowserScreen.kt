package dev.aurora.browser.ui.browser

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aurora.browser.AuroraApp
import dev.aurora.browser.BrowserViewModelFactory
import dev.aurora.browser.BuildConfig
import dev.aurora.browser.data.DownloadItem
import dev.aurora.browser.data.DownloadsRepository
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.ui.components.ActionGridTile
import dev.aurora.browser.ui.components.AuroraBottomSheet
import dev.aurora.browser.ui.components.BottomToolbar
import dev.aurora.browser.ui.components.OmniboxBar
import dev.aurora.browser.ui.components.SettingRow
import dev.aurora.browser.ui.components.auroraClickable
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.components.securityOf
import dev.aurora.browser.ui.downloads.DownloadsScreen
import dev.aurora.browser.ui.home.HomeScreen
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.library.LibraryScreen
import dev.aurora.browser.ui.library.LibraryTab
import dev.aurora.browser.ui.privacy.PrivacyScreen
import dev.aurora.browser.ui.reader.ReaderExtractor
import dev.aurora.browser.ui.reader.ReaderScreen
import dev.aurora.browser.ui.settings.SettingsScreen
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraSpacing

/** 扫码结果与扫码 Intent 的约定常量（与 zxing 的扫码 App 兼容）。 */
private const val SCAN_ACTION = "com.google.zxing.client.android.SCAN"
private const val SCAN_RESULT = "SCAN_RESULT"

/** 主菜单面板内容的高度上限：超出后滚动，避免在矮屏上顶到状态栏。 */
private val SheetContentMaxHeight = 460.dp

@Composable
fun BrowserScreen(
    modifier: Modifier = Modifier,
    vm: BrowserViewModel = viewModel(factory = rememberAuroraViewModelFactory()),
) {
    val context = LocalContext.current

    val surface by vm.surface.collectAsState()
    val tabs by vm.tabsState.collectAsState()
    val active by vm.activeTab.collectAsState()
    val menuOpen by vm.menuOpen.collectAsState()
    val exitHint by vm.exitHintVisible.collectAsState()
    val query by vm.query.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val history by vm.history.collectAsState()
    val engine by vm.searchEngine.collectAsState()
    val showFeed by vm.showFeed.collectAsState()
    val blockTrackers by vm.blockTrackers.collectAsState()
    val trackerCount by vm.trackerCount.collectAsState()
    val sslWarning by vm.sslWarning.collectAsState()
    val message by vm.message.collectAsState()

    val controller = remember { MutableBrowserController() }
    val pool = remember { WebViewPool(context) }
    val downloads = remember { DownloadsRepository(context) }
    // WebView 归池所有，离开组合不会自动销毁——必须显式回收，否则退出界面后仍在跑 JS。
    DisposableEffect(Unit) { onDispose { pool.disposeAll() } }

    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf<Pair<String, String>?>(null) }
    var libraryTab by remember { mutableStateOf(LibraryTab.BOOKMARKS) }
    var downloadItems by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }

    // 关掉的标签要连 WebView 一起释放，否则池里会留着看不见的实例（内存 + 后台 JS）。
    LaunchedEffect(tabs.map { it.id }) { pool.retain(tabs.map { it.id }.toSet()) }

    // 一次性提示（剪贴板为空、没有扫码应用……）
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    // WebView 事件出口。WebView 比组合活得久，所以每次组合把最新回调写进池上的字段，
    // 客户端只读字段——捕获组合期 lambda 会一直调用首次组合的那一份（见 WebViewPool 注释）。
    SideEffect {
        pool.blockTrackers = { vm.blockTrackers.value }
        pool.onProgress = { _, p -> progress = p; controller.refreshHistory() }
        pool.onPageStarted = { loadError = null; vm.onPageStarted() }
        pool.onPageFinished = { id, title, url ->
            vm.onPageFinished(id, title, url)
            controller.refreshHistory()
        }
        pool.onTrackerBlocked = { vm.onTrackerBlocked() }
        pool.onSslError = { vm.onSslError() }
        pool.onExternalUrl = { _, url -> openExternal(context, url) }
        pool.onLoadError = { _, description -> loadError = description }
        pool.onDownloadRequest = { _, request ->
            val id = downloads.enqueue(request, referer = vm.activeTab.value.url)
            vm.showMessage(if (id != null) "已开始下载" else "无法开始下载")
            downloadItems = downloads.query()
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) vm.navigate(spoken) else vm.showMessage("没有识别到内容")
    }
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val scanned = result.data?.getStringExtra(SCAN_RESULT)
        if (result.resultCode == Activity.RESULT_OK && !scanned.isNullOrBlank()) vm.navigate(scanned)
    }

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
            // Page 内容常驻：WebView 一旦离开组合就会被 onDispose 销毁，回到网页态只能重新加载，
            // 丢掉滚动位置与前进/后退历史（地址栏/标签网格来回切一次就中招）。
            // 因此 Page 分支不再受 surface 控制，其余界面改为叠在它之上的不透明覆盖层。
            if (active.isNewTab) {
                HomeScreen(
                    onNavigate = { vm.navigate(it) },
                    onSearch = { vm.openOmnibox() },
                    showFeed = showFeed,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (active.isIncognito) IncognitoBanner()
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
                        security = securityOf(active.url, sslWarning = sslWarning),
                        onTap = { vm.openOmnibox() },
                        trailingIcon = if (loading) AuroraIcons.Close else AuroraIcons.Refresh,
                        onTrailingAction = {
                            if (loading) controller.stopLoading() else controller.reload()
                        },
                        // 必须用已 collectAsState 的 bookmarks 列表来判断：StateFlow.value 在
                        // 组合期读不出订阅，vm.isBookmarked() 只会取到当次组合那一刻的旧值，
                        // 收藏状态变了星标也不会重绘（真机上就是点完不变色，直到别的状态
                        // 顺带触发重组才补上）。
                        bookmarked = bookmarks.any { it.url == active.url },
                        onToggleBookmark = { vm.toggleBookmark() },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        WebViewContainer(
                            controller = controller,
                            pool = pool,
                            tabId = active.id,
                            url = active.url,
                            incognito = active.isIncognito,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (loadError != null) {
                            LoadErrorOverlay(onRetry = { loadError = null; controller.reload() })
                        }
                    }
                }
            }

            when (surface) {
                BrowserSurface.Omnibox -> OmniboxScreen(
                    query = query,
                    suggestions = suggestions,
                    onQueryChange = { vm.setQuery(it) },
                    onSubmit = { vm.navigate(it) },
                    onPickSuggestion = { vm.navigate(it.url) },
                    onDismiss = { vm.closeOmnibox() },
                    onPasteAndGo = { pasteAndGo(context, vm) },
                    onVoice = { startVoice(context, voiceLauncher, vm) },
                    onScan = { startScan(scanLauncher, vm) },
                    onIncognito = { vm.newIncognitoTab() },
                )
                BrowserSurface.TabSwitcher -> TabSwitcherScreen(
                    tabs = tabs, activeId = active.id,
                    onSelect = { vm.selectTab(it) }, onClose = { vm.closeTab(it) },
                    onNewTab = { vm.newTab() }, onNewIncognitoTab = { vm.newIncognitoTab() },
                    onCloseAll = closeAllTabs,
                    onDone = { vm.closeTabSwitcher() },
                )
                BrowserSurface.Library -> LibraryScreen(
                    bookmarks = bookmarks,
                    history = history,
                    onOpen = { vm.navigate(it) },
                    onRemoveBookmark = { vm.removeBookmark(it) },
                    onClearHistory = { vm.clearHistory() },
                    onBack = { vm.closeSecondarySurface() },
                    initialTab = libraryTab,
                )
                BrowserSurface.Downloads -> DownloadsScreen(
                    downloads = downloadItems,
                    onOpen = { id -> downloads.contentUri(id)?.let { openUri(context, it, vm) } },
                    onRemove = { id -> downloads.remove(id); downloadItems = downloads.query() },
                    onBack = { vm.closeSecondarySurface() },
                )
                BrowserSurface.Privacy -> PrivacyScreen(
                    trackerCount = trackerCount,
                    blockEnabled = blockTrackers,
                    onBlockChange = { vm.setBlockTrackers(it) },
                    currentUrl = active.url,
                    security = securityOf(active.url, sslWarning = sslWarning),
                    onBack = { vm.closeSecondarySurface() },
                )
                BrowserSurface.Reader -> {
                    val (title, text) = reader ?: (active.title to "")
                    ReaderScreen(
                        title = title,
                        url = active.url.orEmpty(),
                        text = text,
                        onBack = { vm.closeSecondarySurface() },
                    )
                }
                BrowserSurface.Settings -> SettingsScreen(
                    engine = engine,
                    onEngineChange = { vm.setSearchEngine(it) },
                    showFeed = showFeed,
                    onShowFeedChange = { vm.setShowFeed(it) },
                    blockTrackers = blockTrackers,
                    onBlockTrackersChange = { vm.setBlockTrackers(it) },
                    onOpenDownloads = {
                        downloadItems = downloads.query()
                        vm.openSurface(BrowserSurface.Downloads)
                    },
                    onBack = { vm.closeSecondarySurface() },
                    versionName = BuildConfig.VERSION_NAME,
                )
                BrowserSurface.Page -> Unit
            }

            // 悬浮工具栏只在网页/主页态常驻。地址栏聚焦态底部是系统键盘（规格屏 02），
            // 其余二级界面自带页头，都不该再叠一层工具栏。
            // 菜单面板打开时工具栏仍在，但会被面板遮罩盖住（规格屏 06）。
            if (surface == BrowserSurface.Page) {
                BottomToolbar(
                    tabCount = tabs.size,
                    // 读 State.value 才会在组合期订阅快照，历史变化时工具栏才会重组。
                    canGoBack = controller.canGoBackState.value,
                    canGoForward = controller.canGoForwardState.value,
                    onBack = { controller.goBack() },
                    onForward = { controller.goForward() },
                    onHome = { vm.newTab() },
                    onTabs = { vm.openTabSwitcher() },
                    onMenu = { vm.openMenu() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    homeSelected = active.isNewTab,
                    incognito = active.isIncognito,
                )
            }

            if (exitHint) {
                Text(
                    "再按一次退出",
                    color = Aurora.colors.textSecondary,
                    // 与悬浮工具栏同用 BottomCenter：不抬起来会被工具栏盖住（96dp 高过工具栏）。
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                )
            }
        }

        // 主菜单面板在避让层之外，遮罩才能盖满状态栏与手势条区域。
        if (menuOpen) {
            AuroraBottomSheet(onDismiss = { vm.closeMenu() }) {
                Column(
                    modifier = Modifier
                        .heightIn(max = SheetContentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MenuGroupTitle("常用功能")
                    ActionGrid(
                        onBookmarks = {
                            libraryTab = LibraryTab.BOOKMARKS
                            vm.openSurface(BrowserSurface.Library)
                        },
                        onHistory = {
                            libraryTab = LibraryTab.HISTORY
                            vm.openSurface(BrowserSurface.Library)
                        },
                        onDownloads = {
                            downloadItems = downloads.query()
                            vm.openSurface(BrowserSurface.Downloads)
                        },
                        onReader = { extractReadable(pool, active.id, vm) { reader = it } },
                        onShare = { sharePage(context, active.url, active.title, vm) },
                        onIncognito = { vm.newIncognitoTab() },
                        onPrivacy = { vm.openSurface(BrowserSurface.Privacy) },
                        onSettings = { vm.openSurface(BrowserSurface.Settings) },
                    )
                    MenuGroupTitle("标签页")
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
}

/** 菜单面板的 4×2 动作网格（规格 5.5）。 */
@Composable
private fun ActionGrid(
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onReader: () -> Unit,
    onShare: () -> Unit,
    onIncognito: () -> Unit,
    onPrivacy: () -> Unit,
    onSettings: () -> Unit,
) {
    val tiles: List<Pair<Int, Pair<String, () -> Unit>>> = listOf(
        AuroraIcons.Star to ("书签" to onBookmarks),
        AuroraIcons.History to ("历史" to onHistory),
        AuroraIcons.Download to ("下载" to onDownloads),
        AuroraIcons.Reader to ("阅读" to onReader),
        AuroraIcons.Share to ("分享" to onShare),
        AuroraIcons.Incognito to ("无痕" to onIncognito),
        AuroraIcons.ShieldCheck to ("隐私" to onPrivacy),
        AuroraIcons.Settings to ("设置" to onSettings),
    )
    Column(verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s)) {
        tiles.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
            ) {
                row.forEach { (icon, spec) ->
                    ActionGridTile(
                        icon = icon,
                        label = spec.first,
                        modifier = Modifier.weight(1f),
                        onClick = spec.second,
                    )
                }
                repeat(4 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MenuGroupTitle(text: String) {
    Text(
        text = text,
        color = Aurora.colors.textMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = AuroraSpacing.l, bottom = AuroraSpacing.s),
    )
}

/** 无痕标签的常驻提示条（规格 5.10）：整屏不做独立配色，靠这条说明 + 工具栏描边区分。 */
@Composable
private fun IncognitoBanner() {
    val c = Aurora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgElevated)
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Icon(
            painter = painterResource(AuroraIcons.Incognito),
            contentDescription = null,
            tint = c.brand,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "无痕浏览：不记录历史、不留 Cookie、不保存表单",
            color = c.textMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 主文档加载失败的整页兜底（`WebViewClient.onReceivedError`，仅主框架）。 */
@Composable
private fun LoadErrorOverlay(onRetry: () -> Unit) {
    val c = Aurora.colors
    Column(
        modifier = Modifier.fillMaxSize().background(c.bg).padding(AuroraSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(AuroraIcons.Globe),
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "无法连接",
            color = c.text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AuroraSpacing.m),
        )
        Text(
            "检查网络后重试，或稍后再试。",
            color = c.textMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = AuroraSpacing.xs),
        )
        Box(
            modifier = Modifier
                .padding(top = AuroraSpacing.l)
                .height(AuroraSpacing.touchTarget)
                .auroraClickable(onClick = onRetry)
                .padding(horizontal = AuroraSpacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text("重试", color = c.onBrand)
        }
    }
}

/**
 * `WebViewContainer` 里 `BindableController` 的具体实现，放在 `BrowserScreen.kt` 底部。
 *
 * WebView 实例归 `WebViewPool` 所有，切标签时容器只做「解绑 → 重新绑定」，
 * 因此这里必须容忍 `impl == null`（标签切换的中间态）。
 */
class MutableBrowserController : BindableController {
    private var impl: BrowserController? = null
    // 历史状态用 snapshot state 暴露：工具栏在组合期读取，plain var 不会触发重组。
    private val _canGoBack = mutableStateOf(false)
    private val _canGoForward = mutableStateOf(false)
    val canGoBackState: State<Boolean> get() = _canGoBack
    val canGoForwardState: State<Boolean> get() = _canGoForward

    override fun bind(impl: BrowserController) { this.impl = impl; refreshHistory() }
    override fun unbind() { impl = null; _canGoBack.value = false; _canGoForward.value = false }
    /** 页面导航后由调用方刷新，使工具栏的前进/后退可用态跟随 WebView。 */
    fun refreshHistory() {
        _canGoBack.value = impl?.canGoBack() ?: false
        _canGoForward.value = impl?.canGoForward() ?: false
    }
    override fun loadUrl(url: String) { impl?.loadUrl(url) }
    override fun reload() { impl?.reload() }
    override fun stopLoading() { impl?.stopLoading() }
    override fun goBack() { impl?.goBack(); refreshHistory() }
    override fun goForward() { impl?.goForward(); refreshHistory() }
    override fun canGoBack() = _canGoBack.value
    override fun canGoForward() = _canGoForward.value
}
/**
 * 从应用容器取依赖。只在 `vm` 参数被省略时求值，因此单测传入自己的
 * `BrowserViewModel` 时不会去 cast `applicationContext`。
 */
@Composable
private fun rememberAuroraViewModelFactory(): ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as AuroraApp
    return remember(app) { BrowserViewModelFactory(app.tabs, app.store) }
}

private fun pasteAndGo(context: Context, vm: BrowserViewModel) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()
    if (text.isNullOrBlank()) vm.showMessage("剪贴板为空") else vm.navigate(text)
}

private fun startVoice(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    vm: BrowserViewModel,
) {
    // 先问系统有没有识别服务：直接 launch 在无服务时会抛 ActivityNotFoundException，
    // 而 manifest 里没声明 <queries> 时 resolveActivity 在 API 30+ 恒返回 null。
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        vm.showMessage("未找到语音识别应用")
        return
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要搜索的内容")
    }
    runCatching { launcher.launch(intent) }.onFailure { vm.showMessage("无法启动语音识别") }
}

private fun startScan(launcher: ActivityResultLauncher<Intent>, vm: BrowserViewModel) {
    val intent = Intent(SCAN_ACTION).apply { putExtra("SCAN_MODE", "QR_CODE_MODE") }
    runCatching { launcher.launch(intent) }.onFailure { vm.showMessage("未安装扫码应用") }
}

private fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没有能打开该链接的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun openUri(context: Context, uri: Uri, vm: BrowserViewModel) {
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure { vm.showMessage("无法打开该文件") }
}

private fun sharePage(context: Context, url: String?, title: String, vm: BrowserViewModel) {
    if (url.isNullOrBlank()) {
        vm.showMessage("当前没有可分享的网页")
        return
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
        .putExtra(Intent.EXTRA_SUBJECT, title)
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { vm.showMessage("没有可分享的应用") }
}

/**
 * 抽正文交给页面里的 JS：取 `article`/`main`/`body` 的可见文本。
 * 抽不到就只提示，不进入阅读模式——摆一个空白阅读页比不进去更让人困惑。
 */
private fun extractReadable(
    pool: WebViewPool,
    tabId: String,
    vm: BrowserViewModel,
    onResult: (Pair<String, String>) -> Unit,
) {
    pool.evaluate(tabId, ReaderExtractor.SCRIPT) { title, text ->
        if (text.isBlank()) {
            vm.showMessage("本页无法进入阅读模式")
        } else {
            onResult(title to text)
            vm.openSurface(BrowserSurface.Reader)
        }
    }
}
