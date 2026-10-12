package dev.aurora.browser.ui.browser

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aurora.browser.AuroraApp
import dev.aurora.browser.BrowserViewModelFactory
import dev.aurora.browser.BuildConfig
import dev.aurora.browser.data.DownloadItem
import dev.aurora.browser.data.DownloadRequest
import dev.aurora.browser.data.DownloadsRepository
import dev.aurora.browser.data.FaviconStore
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.media.EpisodeCodec
import dev.aurora.browser.media.EpisodeList
import dev.aurora.browser.media.EpisodeSession
import dev.aurora.browser.media.EpisodeTabSync
import dev.aurora.browser.media.MediaDownload
import dev.aurora.browser.media.MediaUrls
import dev.aurora.browser.media.OfflineItem
import dev.aurora.browser.media.OfflineMedia
import dev.aurora.browser.media.PlaybackPreferences
import dev.aurora.browser.media.PlayerActivity
import dev.aurora.browser.media.ResumeEntry
import dev.aurora.browser.ui.cast.CastController
import dev.aurora.browser.ui.components.ActionGridTile
import dev.aurora.browser.ui.components.AuroraBottomSheet
import dev.aurora.browser.ui.components.BottomToolbar
import dev.aurora.browser.ui.components.BottomToolbarHeight
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
import kotlinx.coroutines.delay

/** 扫码结果与扫码 Intent 的约定常量（与 zxing 的扫码 App 兼容）。 */
private const val SCAN_ACTION = "com.google.zxing.client.android.SCAN"
private const val SCAN_RESULT = "SCAN_RESULT"

/**
 * 下载页的刷新间隔。
 *
 * 半秒是「看着像在动」和「别太费电」之间的折中：进度、已下载字节、速度都是会变的数字，
 * 只在打开那一刻取一次的话，用户看到的是一个停住的进度条。
 */
private const val DOWNLOADS_REFRESH_MS = 500L

/**
 * 从 Context 里解出 Activity。
 *
 * Compose 的 `LocalContext` 可能是被包装过的（`ContextThemeWrapper`、各家 ROM 的
 * `ContextWrapper`），`as? Activity` 会直接失败。逐层剥到 `baseContext` 才靠得住。
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        val base = ctx.baseContext
        if (base === ctx) return null
        ctx = base
    }
    return null
}

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
    val blockTrackers by vm.blockTrackers.collectAsState()
    val desktopMode by vm.desktopMode.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val trackerCount by vm.trackerCount.collectAsState()
    val sslWarning by vm.sslWarning.collectAsState()
    val message by vm.message.collectAsState()

    val controller = remember { MutableBrowserController() }
    // 桌面版设置按取值 lambda 传进池里：池活得比组合久，传值的话新建的标签会读到创建那一刻的旧值。
    val pool = remember(vm) { WebViewPool(context, desktopMode = { vm.desktopMode.value }) }
    val downloads = remember { DownloadsRepository(context) }
    val favicons = remember(context) { FaviconStore.get(context) }
    val cast = remember(context) { CastController(context) }
    // 网页视频全屏：View 要挂到窗口内容之上，所以需要 Activity；预览/单测下退化为空实现。
    // 不能直接 `as? Activity`：LocalContext 在带主题包装时是 ContextThemeWrapper，
    // 转换会静默失败，于是全屏「点了没反应」——必须逐层解包。
    val activity = remember(context) { context.findActivity() }
    val fullscreen = remember(activity) {
        VideoFullscreenController(activity?.let { ActivityFullscreenHost(it) } ?: NoopFullscreenHost)
    }
    // 缩略图要从窗口的合成结果里抠出来（见 WebViewPool.captureThumbnailAsync），所以要窗口。
    val window = activity?.window
    // 悬浮工具栏叠在网页上，抠图时会把那颗胶囊一起抠进去，所以裁掉它占的那一条。
    val toolbarInsetPx = with(LocalDensity.current) { BottomToolbarHeight.roundToPx() }
    // WebView 归池所有，离开组合不会自动销毁——必须显式回收，否则退出界面后仍在跑 JS。
    // 键与上面 remember 的键对齐：`vm`/`activity` 换了就是换了一套池和控制器，
    // 只写 `DisposableEffect(Unit)` 的话旧池连同里面还在跑 JS 的 WebView 永远不会释放。
    DisposableEffect(pool, fullscreen) {
        onDispose {
            // 全屏视频是挂在窗口上的，退出界面时必须摘掉，否则会盖住下一个界面。
            fullscreen.hide()
            pool.disposeAll()
        }
    }

    /**
     * 播放器切集后通知浏览器把该标签挪到新一集（追剧模式，见 `EpisodeSession`）。
     *
     * 装上 / 卸下的位置与池的生命周期对齐：池换了一套（换 `vm`）时，旧的那份通知必须跟着
     * 卸掉，否则会往一个已经作废的标签列表里写地址。
     *
     * 回调里读 `vm.activeTab.value` 而不是捕获组合里的 `active`——通知可能在组合重建之后才
     * 到（播放器是另一个界面，它切集时我们这边可能已经重组过好几次了），捕获值会是旧的。
     *
     * **不参与播放链路**：标签已经被回收、地址导航失败，都不影响播放器里正在放的那一集。
     * 无痕标签照样导航——「无痕」约束的是落盘的历史，不是当前这次加载。
     */
    DisposableEffect(pool) {
        EpisodeSession.tabSync = EpisodeTabSync { pageUrl ->
            val tab = vm.activeTab.value
            pool.navigate(tab.id, pageUrl)
        }
        onDispose { EpisodeSession.tabSync = EpisodeTabSync { } }
    }

    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf<Pair<String, String>?>(null) }
    var libraryTab by remember { mutableStateOf(LibraryTab.BOOKMARKS) }
    var downloadItems by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    var offlineItems by remember { mutableStateOf<List<OfflineItem>>(emptyList()) }
    // 离线缓存的落盘体积（`OfflineMedia.cacheBytes`）。跟列表一起刷，删除后就能立刻
    // 看见数字变小——这是「删除是不是真删了」在非 root 设备上唯一的证据。
    var offlineCacheBytes by remember { mutableLongStateOf(0L) }
    // 页面自己发起的下载（`<a download>`、附件响应）先挂在这里，弹确认框，用户点了才真下。
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }
    var mediaSheet by remember { mutableStateOf<MediaSheet?>(null) }
    var resumeItems by remember { mutableStateOf(emptyList<ResumeEntry>()) }
    var tabThumbnails by remember { mutableStateOf(emptyMap<String, Bitmap>()) }
    // 渲染进程崩溃后要换一个新 WebView 贴进组合（见 WebViewContainer 的 epoch 参数）。
    var webViewEpoch by remember { mutableIntStateOf(0) }

    // 主页「继续观看」的数据来自内置播放器——另一个界面，但同一个进程、同一个偏好文件。
    // 每次回到前台重读一遍：不重读的话，看完一集退回来，卡片上还是进去之前的进度。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeItems = PlaybackPreferences.create(context).resumable()
    }

    /**
     * 重读下载管理要显示的三份数据：系统下载器的条目、离线缓存的条目、离线缓存的体积。
     *
     * 三个一起刷是必须的：只刷列表的话，删掉一条后列表空了、体积还停在旧值，看上去就像
     * 「删了但没删干净」。
     */
    fun refreshDownloads() {
        downloadItems = downloads.query()
        offlineItems = OfflineMedia.items(context)
        offlineCacheBytes = OfflineMedia.cacheBytes(context)
    }

    /**
     * 开始下载一个已经解析好的地址。
     *
     * 浮层按钮和主菜单两处都用它；「直链还是播放列表」的分岔在 [MediaDownload] 里，
     * 内置播放器里的下载按钮走同一份判断。
     */
    val startDownload: (url: String, title: String) -> Boolean = { url, title ->
        val result = MediaDownload.enqueue(
            context = context,
            url = url,
            title = title,
            referer = active.url,
            downloads = downloads,
        )
        vm.showMessage(result.message)
        if (MediaUrls.isStream(url)) {
            offlineItems = OfflineMedia.items(context)
        } else {
            downloadItems = downloads.query()
        }
        result.started
    }

    // 关掉的标签要连 WebView 一起释放，否则池里会留着看不见的实例（内存 + 后台 JS）。
    LaunchedEffect(tabs.map { it.id }) { pool.retain(tabs.map { it.id }.toSet()) }

    // 下载页开着的时候按 [DOWNLOADS_REFRESH_MS] 刷一次。
    // 只在真的有东西在跑时才继续查库：闲着的轮询纯属浪费，也让速度计的采样间隔失去意义。
    // 循环不 break 而是每轮重新判断，这样在页面上新开一个下载时不用重建这个协程。
    //
    // 进页面时**先无条件查一次**：之前这里一进来就等 500ms、而且只在下一次判断为「有东西
    // 在跑」时才真查，结果是「下载还没开始就先打开下载管理」看到一片空白，得退出重进才有
    // 内容。菜单那两条入口虽然也各自查了一次，但把「进入即加载」放在这里才拦得住所有入口。
    LaunchedEffect(surface) {
        if (surface != BrowserSurface.Downloads) return@LaunchedEffect
        refreshDownloads()
        while (true) {
            delay(DOWNLOADS_REFRESH_MS)
            if (downloadItems.any { it.isRunning } || offlineItems.any { it.isRunning }) {
                refreshDownloads()
            }
        }
    }

    // 桌面版开关一变，池里**已经建好**的 WebView 也要跟着换 UA 并重载（新建的那些在
    // create() 里就已经读到最新值了）。放在这里而不是两个开关各自的点击回调里：菜单和
    // 设置页改的是同一份设置，走同一条路才不会有一边忘了重载。
    LaunchedEffect(desktopMode) {
        pool.applyDesktopMode(desktopMode)
    }

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
            // 顺手拍一张缩略图。为什么是这一刻：WebView 的画面由渲染进程合成，只有它正
            // 显示着才拍得到，而「页面加载完」是唯一能确定它正显示着、画面又稳定了的时机。
            // 拍不到（页面还没画上屏）由池自己按时间表重试，见 WebViewPool.CaptureGapsMs。
            val tab = tabs.firstOrNull { it.id == id }
            if (tab != null && !tab.isIncognito) {
                pool.captureThumbnailAsync(id, window, toolbarInsetPx) { bitmap ->
                    if (bitmap != null) tabThumbnails = tabThumbnails + (id to bitmap)
                }
            }
        }
        // 标题变化只回写标题：它不算「加载完成」，不该顺带写历史、连拍缩略图。
        pool.onTitle = { id, title -> vm.onTitle(id, title) }
        // 渲染进程崩了：池里那个实例已经作废，换一个新 WebView 贴进组合并提示用户。
        pool.onRenderGone = { id ->
            vm.showMessage("页面渲染进程崩溃，已重置该标签")
            tabThumbnails = tabThumbnails - id
            webViewEpoch++
        }
        pool.onTrackerBlocked = { vm.onTrackerBlocked() }
        pool.onSslError = { vm.onSslError() }
        pool.onExternalUrl = { _, url -> openExternal(context, url) }
        pool.onLoadError = { _, description -> loadError = description }
        // 页面发起的下载：不直接入队。`<a download>` 或附件响应可以没有任何用户点击就
        // 触发（脚本自动点、加载即跳转），静默下载会白耗流量、占空间。先弹确认框，用户
        // 点了「下载」才入队——浮层里那个「下载」按钮是用户自己点的，不走这条路。
        pool.onDownloadRequest = { _, request -> pendingDownload = request }
        // 视频全屏：WebView 交出要全屏播放的 View，由我们挂到窗口上并切沉浸式。
        pool.onShowFullscreenView = { view, callback -> fullscreen.show(view, callback) }
        pool.onHideFullscreenView = { fullscreen.hide() }
        // 视频画面上的「全屏 / 下载 / 投屏」按钮。地址是页面给的，必须和当前页面提取到的
        // 视频源对得上才执行——注入的 JS 接口对页面里所有脚本（含跨域 iframe）都可见。
        pool.onMediaAction = { action, url, extra ->
            val tabId = active.id
            pool.evaluateVideos(tabId) { sources ->
                val fromPage = sources.firstOrNull { it.url == url }
                // 页面还没给出地址时（`<video>` 刚挂上、`src` 还没设）注入的脚本会传空串，
                // 这是允许的（见 MediaAction.isValid）：直接拿嗅探结果兜底，而不是报「已失效」。
                if (fromPage == null && url.isNotEmpty()) {
                    vm.showMessage("这个视频地址已经失效，刷新页面再试")
                    return@evaluateVideos
                }
                // 页面里那个地址是不是 MSE 的壳子（blob:/data:）。是的话它出了页面就取不到
                // 内容，真实流只能靠网络层嗅探——欧乐影院这类站点就是这种情形。
                val sniffed = pool.sniffedPlayableUrl(tabId)
                val target = if (fromPage == null || fromPage.isStream) sniffed else fromPage.url
                val mime = fromPage?.type.orEmpty()
                when (action) {
                    MediaAction.PLAY -> {
                        val playUrl = target
                        val pageUrl = active.url
                        when {
                            playUrl == null ->
                                vm.showMessage("没嗅到这个视频的真实流地址，先让它播几秒再试")

                            // 页面地址是选集判定的基准（相对地址按它绝对化、主键跟它比），
                            // 空白标签上没得判，直接当单集视频播。
                            pageUrl.isNullOrBlank() -> openInPlayer(
                                context = context,
                                url = playUrl,
                                title = active.title,
                                referer = null,
                                extra = extra,
                                vm = vm,
                            )

                            else -> {
                                // 顺手提取选集（追剧模式）。**只等第一拍**：用户按下的这一刻页面
                                // 早就加载完了，选集要是在 DOM 里，这一拍就够；为等 SPA 迟到的
                                // 列表把开播拖慢 2.5 秒，对占绝大多数的单集视频是纯粹的伤害。
                                // 抓不到就当单集视频——按钮缺席好过按钮点了没反应（规格 5.5/7）。
                                pool.extractEpisodes(
                                    tabId = tabId,
                                    pageUrl = pageUrl,
                                    pageTitle = active.title,
                                    gaps = longArrayOf(0L),
                                ) { episodes ->
                                    openInPlayer(
                                        context = context,
                                        url = playUrl,
                                        title = active.title,
                                        referer = pageUrl,
                                        extra = extra,
                                        vm = vm,
                                        episodes = episodes,
                                        incognito = active.isIncognito,
                                        desktopMode = vm.desktopMode.value,
                                    )
                                }
                            }
                        }
                    }
                    MediaAction.DOWNLOAD -> {
                        if (target == null) {
                            vm.showMessage("没嗅到这个视频的真实流地址，先让它播几秒再试")
                        } else {
                            startDownload(target, active.title)
                        }
                    }
                    MediaAction.CAST -> {
                        val ok = target != null && cast.playOnDevice(target, mime)
                        if (!ok) vm.showMessage("没有能投屏播放该视频的应用")
                    }
                }
            }
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

    // 返回键与工具栏后退键共用这一条：能退历史就退历史，退不动了就回首页。
    // 首页不占 WebView 的历史，所以「网页退到头」不等于「没地方可退」——少了这一步，
    // 从首页点进一个网页再按返回就直接退出 App 了。
    val backAction: () -> Unit = {
        when (vm.onBack(atHome = active.isNewTab, canGoBack = controller.canGoBack())) {
            BackOutcome.PAGE_BACK -> controller.goBack()
            // 回首页要把 WebView 一起释放：页面留着只会在后台继续跑，还会污染下一次导航的历史。
            BackOutcome.GO_HOME -> pool.dispose(vm.goHome())
            BackOutcome.EXIT_NOW -> activity?.finish()
            else -> Unit
        }
    }

    BackHandler(enabled = true) {
        // 全屏视频最优先：全屏中按返回是「退出全屏」，而不是后退网页。
        // 不拦这一下，用户在全屏里按返回会直接离开页面，画面还停在全屏黑底上。
        if (fullscreen.onBackPressed()) return@BackHandler
        backAction()
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
            // Page 内容常驻：WebView 实例归池所有，离开组合不会销毁它，但宿主容器会被从窗口上
            // 摘掉，WebView 跟着脱离窗口——渲染与页面定时器暂停，回来重新挂载还会闪一下。
            // 因此 Page 分支不再受 surface 控制，其余界面改为叠在它之上的不透明覆盖层。
            if (active.isNewTab) {
                HomeScreen(
                    onNavigate = { vm.navigate(it) },
                    onSearch = { vm.openOmnibox() },
                    resume = resumeItems,
                    favicons = favicons,
                    // 记住的地址就是可播地址（当时嗅探出来的那条），所以直接交给播放器；
                    // 标题与来源页一起带上——来源页决定 Referer，标题省得播放器再猜。
                    // 选集也带上：播放器当初把它存进了这条记录，不带的话左下角那对
                    // 「上一集 / 下一集」会消失（那对按钮只在知道还有相邻集时才出现）。
                    onResume = { entry ->
                        openInPlayer(
                            context = context,
                            url = entry.url,
                            title = entry.title,
                            referer = entry.referer,
                            extra = (entry.positionMs / 1000L).toString(),
                            vm = vm,
                            episodes = EpisodeCodec.fromJson(entry.episodes),
                            episodeIndex = entry.episodeIndex,
                        )
                    },
                    onForgetResume = { entry ->
                        PlaybackPreferences.create(context).forget(entry.url)
                        resumeItems = resumeItems.filterNot { it.url == entry.url }
                    },
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
                    // 网页态的地址栏：显示域名 + 站点身份；点击进输入态。
                    // 加载中右侧按钮变「停止加载」（规格 5.3）。
                    val loading = progress in 1..99
                    OmniboxBar(
                        displayUrl = hostOf(active.url),
                        security = securityOf(active.url, sslWarning = sslWarning),
                        // 点一下进输入态，并把当前网址预填进输入框（整段选中）：用户要的是
                        // 「自由编辑网址」，不是「点一下就被复制」——真机反馈原话是
                        // 「点击输入框，可以自由编辑网址，而不是默认复制」。
                        //
                        // 复制这条路没有丢：预填 + 全选之后，系统自带的选区菜单里就有复制，
                        // 而且是用户主动选、主动点的，比替他把剪贴板冲掉更合适
                        // （剪贴板里可能正放着他要粘的东西）。
                        onTap = { vm.openOmnibox(active.url) },
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
                            epoch = webViewEpoch,
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
                    thumbnails = tabThumbnails,
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
                    onRemove = { id -> downloads.remove(id); refreshDownloads() },
                    onBack = { vm.closeSecondarySurface() },
                    // 离线缓存的条目只能在应用内播：它存在应用私有目录里，没有对外的文件。
                    // 没下完也能点（边下边看）：播放器读的是下载用的同一份缓存，已落盘的分片
                    // 直接命中，没下到的当场从网络补。
                    offline = offlineItems,
                    cacheBytes = offlineCacheBytes,
                    onOpenOffline = { item ->
                        vm.closeSecondarySurface()
                        // 离线缓存的条目也带上当初记下的选集：同一个流地址在续播记录里
                        // 有就能拿到，这样「边下边看」进去也有上一集 / 下一集可点。
                        val saved = resumeItems.firstOrNull { it.url == item.url }
                        openInPlayer(
                            context = context,
                            url = item.url,
                            title = item.title,
                            referer = active.url,
                            extra = "0",
                            vm = vm,
                            episodes = EpisodeCodec.fromJson(saved?.episodes),
                            episodeIndex = saved?.episodeIndex ?: -1,
                        )
                    },
                    onRemoveOffline = { item ->
                        OfflineMedia.remove(context, item.url)
                        refreshDownloads()
                    },
                    // 暂停 / 继续：只有离线缓存这条能给（系统下载器没有公开的按条暂停接口，
                    // 见 DownloadsScreen 的文件头注释）。Media3 的下载器自己维护分片断点，
                    // 继续时从断点接着下，不重头来。
                    onPauseOffline = { item ->
                        OfflineMedia.pause(context, item.url)
                        refreshDownloads()
                    },
                    onResumeOffline = { item ->
                        OfflineMedia.resume(context, item.url)
                        refreshDownloads()
                    },
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
                    blockTrackers = blockTrackers,
                    onBlockTrackersChange = { vm.setBlockTrackers(it) },
                    desktopMode = desktopMode,
                    onDesktopModeChange = { vm.setDesktopMode(it) },
                    themeMode = themeMode,
                    onThemeModeChange = { vm.setThemeMode(it) },
                    onOpenDownloads = {
                        refreshDownloads()
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
                    // 「有地方可退」包括退到首页：网页历史退到头了，后退键仍然该亮着。
                    canGoBack = !active.isNewTab,
                    canGoForward = controller.canGoForwardState.value,
                    onBack = backAction,
                    onForward = { controller.goForward() },
                    onHome = { vm.newTab() },
                    onTabs = {
                        tabThumbnails = tabThumbnailMap(tabs, active, pool)
                        vm.openTabSwitcher()
                    },
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
                            refreshDownloads()
                            vm.openSurface(BrowserSurface.Downloads)
                        },
                        onReader = { extractReadable(pool, active.id, vm) { reader = it } },
                        onShare = { sharePage(context, active.url, active.title, vm) },
                        onIncognito = { vm.newIncognitoTab() },
                        onPrivacy = { vm.openSurface(BrowserSurface.Privacy) },
                        onSettings = { vm.openSurface(BrowserSurface.Settings) },
                    )
                    MenuGroupTitle("页面")
                    SettingRow(
                        title = "请求桌面版网站",
                        subtitle = "换桌面版 UA 并重新加载本页",
                        icon = AuroraIcons.Globe,
                        value = if (desktopMode) "已开启" else "已关闭",
                        onClick = {
                            vm.closeMenu()
                            val enabled = !desktopMode
                            vm.setDesktopMode(enabled)
                            // 改设置只是第一步：池里的 WebView 是复用实例，真正换 UA 并重载
                            // 由下面那个 LaunchedEffect(desktopMode) 统一负责，这样从菜单和
                            // 从设置页改都走同一条路。
                            vm.showMessage(if (enabled) "已请求桌面版网站" else "已恢复手机版网站")
                        },
                    )
                    MenuGroupTitle("媒体")
                    SettingRow(
                        title = "下载视频",
                        subtitle = "提取当前页面里的视频地址",
                        icon = AuroraIcons.Download,
                        onClick = {
                            vm.closeMenu()
                            pool.evaluateVideos(active.id) { sources ->
                                // blob:/data:（MSE 流式）交给不了 DownloadManager，先排掉；
                                // 一个源都没有或者只剩流式源时，退回网络层嗅探到的真实流——
                                // 欧乐影院这类站点只有这样才有得下。
                                val files = sources.filterNot { it.isStream }
                                val sniffed = pool.sniffedPlayableUrl(active.id)
                                when {
                                    files.size == 1 ->
                                        startDownload(files.first().url, active.title)
                                    files.size > 1 -> mediaSheet = MediaSheet.Download(files)
                                    sniffed != null -> startDownload(sniffed, active.title)
                                    sources.isNotEmpty() ->
                                        vm.showMessage("这个视频是流式播放，还没拿到真实地址，先播几秒再试")
                                    else -> vm.showMessage("本页没有找到可下载的视频")
                                }
                            }
                        },
                    )
                    SettingRow(
                        title = "投屏",
                        subtitle = "屏幕镜像或把视频投到电视",
                        icon = AuroraIcons.Desktop,
                        onClick = { vm.closeMenu(); mediaSheet = MediaSheet.CastOptions },
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

        // 媒体面板（下载视频 / 投屏）与菜单面板同层：都在避让层之外，遮罩才盖得住系统栏区域。
        when (val sheet = mediaSheet) {
            is MediaSheet.Download -> VideoSourceSheet(
                title = "选择要下载的视频",
                sources = sheet.sources,
                icon = AuroraIcons.Download,
                onPick = { source ->
                    mediaSheet = null
                    startDownload(source.url, active.title)
                },
                onDismiss = { mediaSheet = null },
            )
            is MediaSheet.CastVideo -> VideoSourceSheet(
                title = "选择要投屏的视频",
                sources = sheet.sources,
                icon = AuroraIcons.Share,
                onPick = { source ->
                    mediaSheet = null
                    if (!cast.playOnDevice(source.url, source.type)) {
                        vm.showMessage("没有能投屏播放该视频的应用")
                    }
                },
                onDismiss = { mediaSheet = null },
            )
            MediaSheet.CastOptions -> CastOptionsSheet(
                onMirror = {
                    mediaSheet = null
                    if (!cast.openSystemCastSettings()) {
                        vm.showMessage("请在系统快捷设置里打开「投屏」")
                    }
                },
                onCastVideo = {
                    pool.evaluateVideos(active.id) { sources ->
                        // 同下载：页面里只有 blob:（MSE）时，退回嗅探到的真实流，
                        // 否则投出去的地址电视根本取不到内容。
                        val files = sources.filterNot { it.isStream }
                        val sniffed = pool.sniffedPlayableUrl(active.id)
                        val single = files.singleOrNull()
                        when {
                            single != null -> {
                                mediaSheet = null
                                if (!cast.playOnDevice(single.url, single.type)) {
                                    vm.showMessage("没有能投屏播放该视频的应用")
                                }
                            }
                            files.size > 1 -> mediaSheet = MediaSheet.CastVideo(files)
                            sniffed != null -> {
                                mediaSheet = null
                                if (!cast.playOnDevice(sniffed, "")) {
                                    vm.showMessage("没有能投屏播放该视频的应用")
                                }
                            }
                            else -> {
                                mediaSheet = null
                                vm.showMessage("本页没有找到可投屏的视频")
                            }
                        }
                    }
                },
                onDismiss = { mediaSheet = null },
            )
            null -> Unit
        }

        // 页面发起的下载确认框（挂在 onDownloadRequest 上，见那里）。浮层/菜单里用户主动
        // 点的「下载」不走这条，不弹框。
        pendingDownload?.let { request ->
            AlertDialog(
                onDismissRequest = { pendingDownload = null },
                title = { Text("下载文件", color = Aurora.colors.text) },
                text = {
                    Column {
                        Text("当前网页想下载这个文件：", color = Aurora.colors.textSecondary)
                        Text(
                            DownloadsRepository.deriveFileName(
                                request.url,
                                request.contentDisposition,
                                request.mimeType,
                            ),
                            color = Aurora.colors.text,
                            modifier = Modifier.padding(top = AuroraSpacing.s),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = downloads.enqueue(request, referer = vm.activeTab.value.url)
                            vm.showMessage(if (id != null) "已开始下载" else "无法开始下载")
                            refreshDownloads()
                            pendingDownload = null
                        },
                    ) { Text("下载") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDownload = null }) { Text("取消") }
                },
            )
        }
    }
}

/**
 * 媒体面板的三个形态。投屏与下载共用同一份「从页面提取出来的视频源」，
 * 只是点下去之后一个入队下载、一个交给投屏播放器。
 */
private sealed interface MediaSheet {
    data class Download(val sources: List<VideoSource>) : MediaSheet
    data class CastVideo(val sources: List<VideoSource>) : MediaSheet
    data object CastOptions : MediaSheet
}

/**
 * 拿内置播放器（Media3）全屏播这个地址。
 *
 * 网页里的「全屏」按钮走的就是这里。以前是直接 `video.requestFullscreen()`，但视频站
 * 普遍用 MSE，`<video>` 只是个 `blob:` 壳子，WebView 把它的自定义 View 搬上窗口之后
 * 画面就是黑的——用户实测的「全屏之后是黑屏」。换成原生播放器就没有这一层。
 *
 * @param extra 页面传过来的「当前播到第几秒」；解析不了就从头播。
 * @param episodes 页面里提取到的选集（追剧模式）；`null` 表示这页没判出剧集，播放器里
 *   就没有选集面板，播完停在结尾——与 1.13 一致。
 * @param incognito 无痕标签：「已看」标记不落盘（见 `PlaybackPreferences.markWatched`）。
 * @param desktopMode 当前是否「请求桌面版网站」；播放器切集时的嗅探要用同一个 UA。
 */
private fun openInPlayer(
    context: Context,
    url: String,
    title: String,
    referer: String?,
    extra: String,
    vm: BrowserViewModel,
    episodes: EpisodeList? = null,
    episodeIndex: Int = -1,
    incognito: Boolean = false,
    desktopMode: Boolean = false,
) {
    val startMs = extra.trim().toLongOrNull()?.coerceAtLeast(0L)?.times(1000L) ?: 0L
    val intent = PlayerActivity.intent(
        context = context,
        url = url,
        title = title,
        referer = referer,
        startPositionMs = startMs,
        // 剧名与选集随 Intent 走：播放器是另一个界面，它拿不到页面上下文。JSON 而不是
        // 一个 Parcelable 列表——跨进程重建（进程被回收后恢复）时 JSON 更耐操，
        // 而且它能在纯 JVM 单测里往返（见 EpisodeCodecTest）。
        episodes = episodes?.let { EpisodeCodec.toJson(it) },
        // 从「继续观看」回来时，正在播的那一集由存下来的下标决定，不能再用列表里的
        // `current`——那个是当初那个**页面**上的集，切过集之后就对不上了。
        episodeIndex = if (episodeIndex >= 0) episodeIndex else episodes?.currentIndex ?: -1,
        incognito = incognito,
        desktopMode = desktopMode,
    )
    runCatching { context.startActivity(intent) }
        .onFailure { vm.showMessage("无法打开内置播放器") }
}

/**
 * 打开标签网格时收集各标签已有的缩略图。
 *
 * 图不是这一刻拍的：拍图的时机是「页面刚加载完」（见 [pool.onPageFinished]），因为
 * WebView 的画面由渲染进程合成，只有它正显示着才拍得到。这里只负责把池里存着的图
 * 按当前标签列表取出来——已关闭的标签即使池里还留着图，也不该再出现在网格里。
 *
 * 无痕标签不拍也不显示：网格上照旧给它画无痕图标，浏览内容也就不落到这张图里。
 */
private fun tabThumbnailMap(
    tabs: List<BrowserTab>,
    active: BrowserTab,
    pool: WebViewPool,
): Map<String, Bitmap> {
    // 兜底：`View.draw()` 在硬件加速下基本拍不出东西，但万一某台设备上 PixelCopy 不可用，
    // 它至少还能留一张。拍不出来就返回 null，网格照旧画占位图。
    if (!active.isIncognito && pool.thumbnail(active.id) == null) pool.captureThumbnail(active.id)
    return tabs.mapNotNull { tab -> pool.thumbnail(tab.id)?.let { tab.id to it } }.toMap()
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

/**
 * 无痕标签的常驻提示条（规格 5.10）：整屏不做独立配色，靠这条说明 + 工具栏描边区分。
 *
 * 文案只说做得到的事，**刻意不提 Cookie**：Android 的 WebView 全进程共用一个 Cookie 罐，
 * 没有按实例隔离的接口，无痕标签照样会带上普通标签存下的 Cookie、它新建的 Cookie 也留在
 * 磁盘上。写「不留 Cookie」就是一句做不到的承诺（规格 5.10 已同步删掉这一条）。
 */
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
            "无痕浏览：不记录历史、不保存表单、不缓存页面、不使用本地存储",
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
