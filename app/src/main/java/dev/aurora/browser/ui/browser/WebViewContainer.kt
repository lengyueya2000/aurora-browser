package dev.aurora.browser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * 把平台 `WebView` 包在 Compose 里，并把它的能力通过 [BrowserController] 暴露出去。
 * 本文件是全应用唯一允许 import `android.webkit.*` 的地方。
 *
 * @param url 要加载的地址；`null`/空白表示新标签页，不加载。
 * @param onProgress 加载进度 0..100。
 * @param onTitleChange 页面标题变化。
 * @param onUrlChange 页面真实地址变化（重定向后会与请求地址不同）。
 * @param factory 测试注入的 WebView 工厂；生产环境传 `null` 用默认实现。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewContainer(
    controller: BrowserController,
    modifier: Modifier = Modifier,
    url: String? = null,
    onProgress: (Int) -> Unit = {},
    onTitleChange: (String) -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    factory: ((Context) -> WebView)? = null,
) {
    val context = LocalContext.current
    val webView = remember {
        (factory ?: { ctx: Context -> WebView(ctx) })(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
    }

    // 把 WebView 的进度/标题/URL 变化桥接给上层
    DisposableEffect(webView) {
        val client = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                onTitleChange(view.title.orEmpty())
                onUrlChange(url)
            }
        }
        webView.webViewClient = client
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
            override fun onReceivedTitle(view: WebView, title: String?) =
                onTitleChange(title.orEmpty())
        }
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()      // 断开对 Composable 的引用
            webView.webChromeClient = null
            webView.destroy()
        }
    }

    // 把控制器指到当前这个 WebView 实例上
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

    LaunchedEffect(url) {
        if (!url.isNullOrBlank()) webView.loadUrl(url)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** 让 ViewModel 侧的控制器能指向真正的 WebView 实例。 */
interface BindableController : BrowserController {
    fun bind(impl: BrowserController)
    fun unbind()
}
