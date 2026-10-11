package dev.aurora.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.aurora.browser.BrowserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 站点图标（favicon）。
 *
 * 快捷入口以前画的是站名首字母——六个方块长得一样，只有字不同。这里去把站点自己的
 * 图标取回来，认得出是哪个站。
 *
 * **只向站点自己发请求**，不用任何第三方图标服务：第三方服务会把「谁装了哪个浏览器、
 * 常去哪些站」留在别人的日志里，而这些图标本来就在站点自己的域名下。
 *
 * 三级缓存：内存 → 磁盘（`cacheDir/favicons`）→ 网络。取不到就回 `null`，
 * 界面退回首字母块——图标是锦上添花，不值得为它转圈或者报错。
 */
class FaviconStore internal constructor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * host -> 图标。用 Compose 的快照 Map：图标到货时主页自己会重画，
     * 不需要调用方去订阅什么。
     */
    private val memory: SnapshotStateMap<String, Bitmap> = mutableStateMapOf()

    /** 内存里已经有的图标；没有（含还没去取过）时为 null。 */
    fun cached(host: String): Bitmap? = memory[key(host)]

    /**
     * 取某个主机的图标。内存里有就直接回，否则依次查磁盘、发网络请求。
     *
     * 并发调用同一个 host 最多各取一次（不会共享结果）：快捷入口指向的域名本来就互不相同，
     * 为这点重复去维护一份「进行中的请求」不划算，而且那个共享一旦有人取消就会把别的
     * 调用方挂住。
     */
    suspend fun load(host: String): Bitmap? {
        val key = key(host)
        if (key.isBlank()) return null
        memory[key]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { readDisk(key) ?: fetchAndCache(key) }
        if (bitmap != null) remember(key, bitmap)
        return bitmap
    }

    private fun remember(key: String, bitmap: Bitmap) {
        // 上限只是兜底：实际只会有几十个主机。真到上限时整块丢掉重新取，
        // 比维护一套 LRU 便宜，也不会让位图无限攒下去。
        if (memory.size >= MAX_ICONS) memory.clear()
        memory[key] = bitmap
    }

    private fun readDisk(key: String): Bitmap? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return FaviconCodec.decode(bytes) ?: run {
            // 坏文件（下载中断、格式变了）删掉，免得每次启动都白读一遍。
            runCatching { file.delete() }
            null
        }
    }

    private fun fetchAndCache(key: String): Bitmap? {
        val bytes = FaviconCodec.fetchBytes(key, REQUEST) ?: return null
        val bitmap = FaviconCodec.decode(bytes) ?: return null
        runCatching {
            fileFor(key).outputStream().use { it.write(bytes) }
        }
        return bitmap
    }

    private fun fileFor(key: String): File = File(cacheDir(), "$key.img")

    private fun cacheDir(): File =
        File(appContext.cacheDir, DIR).apply { if (!isDirectory) mkdirs() }

    /** 主机名当键之前先规整：大小写、前后空格、以及可能带上的端口。 */
    private fun key(host: String): String =
        host.trim().lowercase().removeSuffix(".").replace(':', '_')

    companion object {
        private const val DIR = "favicons"
        private const val MAX_ICONS = 64

        /** 发请求的方式。无状态，全进程共用一个。 */
        private val REQUEST: FaviconRequest = HttpFaviconRequest()

        @Volatile private var instance: FaviconStore? = null

        /** 进程内单例：内存缓存和磁盘缓存都只有一份，主页与其它界面看到的是同一批图。 */
        fun get(context: Context): FaviconStore = instance ?: synchronized(this) {
            instance ?: FaviconStore(context).also { instance = it }
        }
    }
}

/** 发请求的方式。抽成接口是为了让取图流程能在测试里跑，不必真连网。 */
internal fun interface FaviconRequest {
    /** 取一个地址的内容；失败、超时、状态码非 2xx、或超出 [maxBytes] 时回 null。 */
    fun get(url: String, maxBytes: Int): ByteArray?
}

/**
 * 图标格式与页面声明的解析。
 *
 * 与网络无关，纯字节/字符串进出——真正容易出错的部分都在这里，所以单独放出来测。
 */
internal object FaviconCodec {

    /** 一次最多读多少字节：图标再大也就几十 KB，HTML 首页给 512KB 足够了。 */
    const val MAX_ICON_BYTES = 512 * 1024
    const val MAX_PAGE_BYTES = 512 * 1024

    /**
     * 取一个主机的图标字节（已经是可以直接解码的格式）。
     *
     * 两步：先试约定的 `/favicon.ico`（一次请求，命中率不低），不行再去首页读
     * `<link rel="icon">`。第二步要多发一次请求，但很多站点并不在根目录放 favicon，
     * 或者放的是老式 ICO。
     */
    fun fetchBytes(host: String, request: FaviconRequest): ByteArray? {
        val root = "https://$host"
        request.get("$root/favicon.ico", MAX_ICON_BYTES)?.let { bytes ->
            normalizeIcon(bytes)?.let { return it }
        }
        val page = request.get("$root/", MAX_PAGE_BYTES) ?: return null
        val html = runCatching { String(page, Charsets.UTF_8) }.getOrNull() ?: return null
        for (candidate in iconLinks(html, root)) {
            val bytes = request.get(candidate, MAX_ICON_BYTES) ?: continue
            normalizeIcon(bytes)?.let { return it }
        }
        return null
    }

    /**
     * 把响应体变成「`BitmapFactory` 能解的东西」。
     *
     * 两种情况：直接就是 PNG/JPEG/WebP；或者是 ICO 容器——`BitmapFactory` 不认 ICO，
     * 但现在的 ICO 大多内嵌 PNG，把那段抠出来就能解。老式 ICO（内嵌 BMP）抠不出来，
     * 回 `null` 让调用方去试下一个候选。
     */
    fun normalizeIcon(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if (isIco(bytes)) return pngFromIco(bytes)
        // 不是 ICO 就直接交出去：是不是能解的图片由 decode 判断，这里不做格式白名单
        // ——站点用 WebP/GIF 也不稀奇。
        return bytes
    }

    /** 是不是 ICO 容器：ICONDIR 固定以 00 00 01 00 开头。 */
    fun isIco(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() && bytes[3] == 0.toByte()

    /**
     * ICO 里最大的那个内嵌 PNG。
     *
     * ICONDIR 之后是每张图 16 字节的目录项：宽、高、调色板数、保留、位深/色平面、
     * 每像素位数、负载字节数（偏移 8）、负载偏移（偏移 12）。宽度/高度是 0 表示 256。
     * 挑最大的一张：快捷入口的方块是 52dp，小图标（16×16）放上去会糊。
     */
    fun pngFromIco(bytes: ByteArray): ByteArray? {
        val count = readU16(bytes, 4) ?: return null
        var best: ByteArray? = null
        var bestArea = -1
        for (i in 0 until count) {
            val entry = 6 + i * 16
            if (entry + 16 > bytes.size) break
            val width = bytes[entry].toInt() and 0xFF
            val height = bytes[entry + 1].toInt() and 0xFF
            val length = readU32(bytes, entry + 8) ?: continue
            val offset = readU32(bytes, entry + 12) ?: continue
            if (length <= 0 || offset < 0 || offset + length > bytes.size) continue
            val area = (if (width == 0) 256 else width) * (if (height == 0) 256 else height)
            if (area <= bestArea) continue
            val payload = bytes.copyOfRange(offset, offset + length)
            if (!isPng(payload)) continue
            best = payload
            bestArea = area
        }
        return best
    }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    /**
     * 页面里声明的图标地址，按「更可能好看」的顺序排。
     *
     * `apple-touch-icon` 排最前：它是给手机主屏用的，基本都是 180×180 的 PNG，
     * 比 16×16 的 `favicon.ico` 清晰得多。其余按文档顺序。
     *
     * 跳过 SVG：`BitmapFactory` 解不了，试了也是白费一次请求。
     */
    fun iconLinks(html: String, baseUrl: String): List<String> {
        val apple = mutableListOf<String>()
        val others = mutableListOf<String>()
        for (tag in LINK_TAG.findAll(html)) {
            val text = tag.value
            val rel = REL_ATTR.find(text)?.groupValues
                ?.drop(1)?.firstOrNull { it.isNotEmpty() }?.lowercase() ?: continue
            if (!rel.contains("icon")) continue
            if (rel.contains("mask-icon")) continue
            val href = HREF_ATTR.find(text)?.groupValues
                ?.drop(1)?.firstOrNull { it.isNotEmpty() }?.trim() ?: continue
            if (href.isEmpty() || href.startsWith("data:")) continue
            if (TYPE_ATTR.find(text)?.groupValues?.get(1)?.contains("svg", true) == true) continue
            val absolute = resolve(href, baseUrl) ?: continue
            if (absolute.endsWith(".svg", ignoreCase = true)) continue
            if (rel.contains("apple-touch-icon")) apple += absolute else others += absolute
        }
        // 同一个地址可能被声明多次（不同 sizes），去重后保持顺序。
        return (apple + others).distinct()
    }

    /** 相对地址按页面地址解析；解析不了就丢掉这个候选。 */
    private fun resolve(href: String, baseUrl: String): String? =
        runCatching { URI(baseUrl).resolve(href).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * 解码成位图，并夹一下尺寸。
     *
     * 先只读尺寸（`inJustDecodeBounds`），超过 [MAX_ICON_PX] 就按 2 的幂采样缩小：
     * 图标显示最大也就 52dp，把一张 512×512 的原图整个留在内存里没有意义。
     */
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_ICON_PX &&
            bounds.outHeight / (sample * 2) >= MAX_ICON_PX
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    private fun readU16(bytes: ByteArray, at: Int): Int? {
        if (at + 2 > bytes.size) return null
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(bytes: ByteArray, at: Int): Int? {
        if (at + 4 > bytes.size) return null
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    /** 图标最长边最多留这么多像素。 */
    private const val MAX_ICON_PX = 128

    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** 双引号、单引号、不带引号三种写法都认——手写 HTML 的站点三种都有。 */
    private val REL_ATTR = Regex(
        """\brel\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val HREF_ATTR = Regex(
        """\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val TYPE_ATTR = Regex("""\btype\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
}

/** 真正发请求的实现。走 `HttpURLConnection`，不引入网络库。 */
private class HttpFaviconRequest : FaviconRequest {
    override fun get(url: String, maxBytes: Int): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", BrowserIdentity.MOBILE_USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val TIMEOUT_MS = 6_000
    }
}
