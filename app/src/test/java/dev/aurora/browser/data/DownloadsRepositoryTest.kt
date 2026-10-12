package dev.aurora.browser.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 下载入队的地址校验。
 *
 * 真机回归：视频站的 MSE 播放器给出来的视频源是 `blob:` 地址，而
 * `DownloadManager.Request` 的**构造函数**对非 http/https 直接抛
 * `IllegalArgumentException: Can only download HTTP/HTTPS URIs`。旧代码只在
 * `dm.enqueue()` 外面套了 `runCatching`，构造那一步漏在外面——点一下「下载视频」就闪退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsRepositoryTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repo = DownloadsRepository(context)

    private fun request(url: String, mime: String? = null) = DownloadRequest(
        url = url,
        userAgent = "UA",
        contentDisposition = null,
        mimeType = mime,
        contentLength = -1L,
    )

    @Test fun blobUrlIsRejectedWithoutThrowing() {
        // 关键断言是「不抛异常」：旧实现在这里直接崩掉。
        assertThat(repo.enqueue(request("blob:https://a.com/8f3c-1"))).isNull()
    }

    @Test fun dataUrlIsRejected() {
        assertThat(repo.enqueue(request("data:video/mp4;base64,AAAA"))).isNull()
    }

    @Test fun javascriptUrlIsRejected() {
        assertThat(repo.enqueue(request("javascript:alert(1)"))).isNull()
    }

    @Test fun httpAndHttpsAreDownloadable() {
        assertThat(repo.canDownload("http://a.com/v.mp4")).isTrue()
        assertThat(repo.canDownload("https://a.com/v.mp4")).isTrue()
        assertThat(repo.canDownload("HTTPS://A.COM/v.mp4")).isTrue()
    }

    @Test fun nonHttpSchemesAreNotDownloadable() {
        assertThat(repo.canDownload("blob:https://a.com/x")).isFalse()
        assertThat(repo.canDownload("data:video/mp4;base64,AAAA")).isFalse()
        assertThat(repo.canDownload("")).isFalse()
        assertThat(repo.canDownload("not a url")).isFalse()
    }

    // ---------- 进度那一行（列表和播放器共用同一份） ----------

    @Test fun progressShowsSizeAndSpeed() {
        assertThat(
            DownloadsRepository.formatProgress(
                downloaded = 1_048_576L,
                total = 4_194_304L,
                percent = 25f,
                speed = 524_288L,
                approxTotal = false,
            ),
        ).isEqualTo("1.0 MB / 4.0 MB · 512.0 KB/s")
    }

    @Test fun estimatedTotalIsMarkedAsApproximate() {
        // HLS 的总量是反推的，得说清楚它不是准数。
        assertThat(
            DownloadsRepository.formatProgress(
                downloaded = 1_048_576L,
                total = 4_194_304L,
                percent = 25f,
                speed = 0L,
                approxTotal = true,
            ),
        ).isEqualTo("1.0 MB / 约 4.0 MB")
    }

    @Test fun anUnknownSpeedIsOmittedRatherThanShownAsZero() {
        // 「0 B/s」看着像卡住了，其实只是还没测出来（速度要两次采样）。
        assertThat(
            DownloadsRepository.formatProgress(
                downloaded = 1_048_576L,
                total = 4_194_304L,
                percent = 25f,
                speed = 0L,
                approxTotal = false,
            ),
        ).isEqualTo("1.0 MB / 4.0 MB")
    }

    @Test fun anUnknownTotalFallsBackToPercent() {
        assertThat(
            DownloadsRepository.formatProgress(
                downloaded = 1_048_576L,
                total = 0L,
                percent = 12.6f,
                speed = 0L,
                approxTotal = true,
            ),
        ).isEqualTo("1.0 MB · 12%")
    }

    @Test fun fileNameIsNeverBlank() {
        assertThat(DownloadsRepository.deriveFileName("https://a.com/dir/movie.mp4", null, null))
            .isEqualTo("movie.mp4")
        assertThat(DownloadsRepository.deriveFileName("https://a.com", null, null)).isNotEmpty()
    }
}
