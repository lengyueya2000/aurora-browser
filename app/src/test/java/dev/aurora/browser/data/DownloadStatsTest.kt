package dev.aurora.browser.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 下载速度与总大小。
 *
 * 速度是「两次采样的字节差 ÷ 时间差」，几个边界必须定死：刚开始下的时候不能报速度、
 * 卡住的时候要掉回 0、删掉重下的时候不能算出负数。总大小则要区分「已知」和「反推」——
 * HLS 的总长度 Media3 给不出来，只能从已完成比例反推，比例太低时不报。
 */
class DownloadStatsTest {

    // ---------- 速度 ----------

    @Test fun firstSampleReportsNothing() {
        val speed = DownloadSpeed()
        assertThat(speed.sample("a", bytes = 0L, nowMs = 0L)).isEqualTo(0L)
    }

    @Test fun twoSamplesGivesBytesPerSecond() {
        val speed = DownloadSpeed()
        speed.sample("a", bytes = 0L, nowMs = 0L)
        // 1 秒内下了 1000 字节 → 1000 B/s。
        assertThat(speed.sample("a", bytes = 1_000L, nowMs = 1_000L)).isEqualTo(1_000L)
    }

    @Test fun tooShortAnIntervalReportsNothing() {
        val speed = DownloadSpeed()
        speed.sample("a", bytes = 0L, nowMs = 0L)
        // 只隔了 100 毫秒：除数太小，算出来的速度会是个荒唐的大数，宁可不报。
        assertThat(speed.sample("a", bytes = 10L, nowMs = 100L)).isEqualTo(0L)
    }

    @Test fun aStalledDownloadDropsBackToZero() {
        val speed = DownloadSpeed(windowMs = 1_000L, minSampleMs = 100L)
        speed.sample("a", bytes = 0L, nowMs = 0L)
        assertThat(speed.sample("a", bytes = 1_000L, nowMs = 100L)).isEqualTo(10_000L)
        // 字节不再增长，样本也不再新增；窗口一过期就只剩一个样本，速度掉回 0。
        assertThat(speed.sample("a", bytes = 1_000L, nowMs = 1_500L)).isEqualTo(0L)
    }

    @Test fun bytesThatStoodStillAreNotSampled() {
        val speed = DownloadSpeed(windowMs = 1_000L, minSampleMs = 100L)
        speed.sample("a", bytes = 0L, nowMs = 0L)
        speed.sample("a", bytes = 1_000L, nowMs = 100L)
        // 又过了 100 毫秒，字节一个没动：窗口的右端不往后推，于是同样的增量被更长的时间摊薄。
        assertThat(speed.sample("a", bytes = 1_000L, nowMs = 200L)).isEqualTo(5_000L)
    }

    @Test fun oldSamplesAreDroppedFromTheWindow() {
        val speed = DownloadSpeed(windowMs = 1_000L, minSampleMs = 100L)
        speed.sample("a", bytes = 0L, nowMs = 0L)
        speed.sample("a", bytes = 100_000L, nowMs = 100L) // 开头一阵猛冲
        // 两秒后才又动了一点：开头那阵冲刺已经出了窗口，不该再算进来。
        assertThat(speed.sample("a", bytes = 100_100L, nowMs = 2_000L)).isEqualTo(0L)
        assertThat(speed.sample("a", bytes = 100_300L, nowMs = 2_200L)).isEqualTo(1_000L)
    }

    @Test fun bytesGoingBackwardsRestartsInsteadOfGoingNegative() {
        val speed = DownloadSpeed()
        speed.sample("a", bytes = 5_000L, nowMs = 0L)
        // 删掉重下、或者换了源：字节数倒退。这里必须返回 0 而不是负数。
        assertThat(speed.sample("a", bytes = 1_000L, nowMs = 1_000L)).isEqualTo(0L)
        assertThat(speed.sample("a", bytes = 2_000L, nowMs = 2_000L)).isEqualTo(1_000L)
    }

    @Test fun keysAreIndependent() {
        val speed = DownloadSpeed()
        speed.sample("a", bytes = 0L, nowMs = 0L)
        speed.sample("b", bytes = 900L, nowMs = 0L)
        assertThat(speed.sample("a", bytes = 1_000L, nowMs = 1_000L)).isEqualTo(1_000L)
        assertThat(speed.sample("b", bytes = 1_000L, nowMs = 1_000L)).isEqualTo(100L)
    }

    @Test fun forgottenAndDroppedKeysStartOver() {
        val speed = DownloadSpeed()
        speed.sample("a", bytes = 0L, nowMs = 0L)
        speed.sample("a", bytes = 1_000L, nowMs = 1_000L)
        speed.retain(setOf("b"))
        // 样本被清掉，于是又回到「只有一次采样」的状态。
        assertThat(speed.sample("a", bytes = 2_000L, nowMs = 2_000L)).isEqualTo(0L)

        speed.sample("c", bytes = 0L, nowMs = 0L)
        speed.sample("c", bytes = 1_000L, nowMs = 1_000L)
        speed.forget("c")
        assertThat(speed.sample("c", bytes = 2_000L, nowMs = 2_000L)).isEqualTo(0L)
    }

    // ---------- 总大小 ----------

    @Test fun knownContentLengthWins() {
        assertThat(estimateTotalBytes(contentLength = 12_000L, bytesDownloaded = 3_000L, percent = 25f))
            .isEqualTo(12_000L)
    }

    @Test fun unknownLengthIsDerivedFromPercent() {
        // HLS：总长度未知，已完成 25% 下了 100 MB，那总量就是 400 MB。
        assertThat(
            estimateTotalBytes(contentLength = -1L, bytesDownloaded = 100L * 1024 * 1024, percent = 25f),
        ).isEqualTo(400L * 1024 * 1024)
    }

    @Test fun tooLittleProgressIsNotEstimated() {
        // 才 0.5%：头几个分片和平均值差得远，这时候算出来的数字会往下缩，不如说不知道。
        assertThat(estimateTotalBytes(contentLength = -1L, bytesDownloaded = 5_000_000L, percent = 0.5f))
            .isEqualTo(0L)
    }

    @Test fun unsetPercentIsNotEstimated() {
        // Media3 在没有进度时给的是 C.PERCENTAGE_UNSET（-1）。
        assertThat(estimateTotalBytes(contentLength = -1L, bytesDownloaded = 5_000_000L, percent = -1f))
            .isEqualTo(0L)
        assertThat(estimateTotalBytes(contentLength = -1L, bytesDownloaded = 0L, percent = 50f))
            .isEqualTo(0L)
    }

    @Test fun finishedDownloadEqualsWhatWasDownloaded() {
        assertThat(estimateTotalBytes(contentLength = -1L, bytesDownloaded = 7_000L, percent = 100f))
            .isEqualTo(7_000L)
    }
}
