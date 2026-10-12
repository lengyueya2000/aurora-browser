package dev.aurora.browser.ui.browser

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** 供 ViewModel 与返回键使用的控制器；不暴露 WebView 本体。 */
interface BrowserController {
    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
}

/**
 * 把当前标签对应的 `WebView` 贴进组合。WebView 实例由 [WebViewPool] 持有——每个标签一个，
 * 切标签只是换显示哪个，不销毁、也不共享前进/后退栈。
 *
 * 进度/标题/地址/下载等事件不经这里上抛，而是由 [WebViewPool] 的字段直接转给 `BrowserScreen`：
 * WebView 比本组合活得久，捕获组合期 lambda 会拿到过期的回调。
 *
 * @param url 要加载的地址；`null`/空白表示新标签页，不加载。
 * @param epoch 换代号：渲染进程崩溃后 +1，容器据此重新去池里取一个可用的 WebView
 *   并重新加载当前地址（旧实例已经被 `destroy()`，贴上去也是白的）。
 */
@Composable
fun WebViewContainer(
    controller: BrowserController,
    pool: WebViewPool,
    tabId: String,
    modifier: Modifier = Modifier,
    url: String? = null,
    incognito: Boolean = false,
    epoch: Int = 0,
) {
    val webView = remember(tabId, epoch) { pool.get(tabId, incognito) }

    // 把控制器指到当前这个 WebView 实例上。onDispose 只解绑，不销毁——
    // WebView 归 pool 所有，切标签后还要继续用。
    DisposableEffect(controller, webView) {
        val impl = object : BrowserController {
            override fun loadUrl(url: String) = webView.loadUrl(url)
            override fun reload() = webView.reload()
            override fun stopLoading() = webView.stopLoading()
            override fun goBack() { if (webView.canGoBack()) webView.goBack() }
            override fun goForward() { if (webView.canGoForward()) webView.goForward() }
            override fun canGoBack() = webView.canGoBack()
            override fun canGoForward() = webView.canGoForward()
        }
        (controller as? BindableController)?.bind(impl)
        onDispose { (controller as? BindableController)?.unbind() }
    }

    LaunchedEffect(url, epoch) {
        // 只加载「WebView 当前不在的地址」：onPageFinished 会把真实地址回写进 active.url，
        // 若不加这道判断，重定向/站内跳转会因 url 变化再触发一次 loadUrl（重复加载 + 多一条历史）。
        if (!url.isNullOrBlank() && url != webView.url) webView.loadUrl(url)
    }

    AndroidView(
        factory = { ctx -> FrameLayout(ctx) },
        update = { host -> host.display(webView) },
        modifier = modifier,
    )
}

/** 把目标 WebView 挂到宿主容器上；已经是它自己时就别动（避免每帧重挂）。 */
private fun FrameLayout.display(webView: WebView) {
    if (childCount == 1 && getChildAt(0) === webView) return
    removeAllViews()
    (webView.parent as? ViewGroup)?.removeView(webView)
    addView(webView)
}

/** 让 ViewModel 侧的控制器能指向真正的 WebView 实例。 */
interface BindableController : BrowserController {
    fun bind(impl: BrowserController)
    fun unbind()
}
