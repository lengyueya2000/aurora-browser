package dev.aurora.browser.ui.icons

import androidx.annotation.DrawableRes
import dev.aurora.browser.R

/**
 * 图标名 → drawable 资源。名称与 prototype/index.html 的 data-i 值一致，
 * 便于设计与实现对照。资源由 tools/phosphor_to_drawable.mjs 生成。
 */
object AuroraIcons {
    @DrawableRes val Back = R.drawable.ic_aurora_back
    @DrawableRes val Forward = R.drawable.ic_aurora_forward
    @DrawableRes val Home = R.drawable.ic_aurora_home
    @DrawableRes val Tabs = R.drawable.ic_aurora_tabs
    @DrawableRes val More = R.drawable.ic_aurora_more
    @DrawableRes val Search = R.drawable.ic_aurora_search
    @DrawableRes val Close = R.drawable.ic_aurora_close
    @DrawableRes val Add = R.drawable.ic_aurora_add
    @DrawableRes val ShieldCheck = R.drawable.ic_aurora_shield_check
    @DrawableRes val Lock = R.drawable.ic_aurora_lock
    @DrawableRes val Star = R.drawable.ic_aurora_star
    @DrawableRes val History = R.drawable.ic_aurora_history
    @DrawableRes val Download = R.drawable.ic_aurora_download
    @DrawableRes val Reader = R.drawable.ic_aurora_reader
    @DrawableRes val Incognito = R.drawable.ic_aurora_incognito
    @DrawableRes val Share = R.drawable.ic_aurora_share
    @DrawableRes val Settings = R.drawable.ic_aurora_settings
    @DrawableRes val Extensions = R.drawable.ic_aurora_extensions
    @DrawableRes val Mic = R.drawable.ic_aurora_mic
    @DrawableRes val QrCode = R.drawable.ic_aurora_qr_code
    @DrawableRes val ChevronRight = R.drawable.ic_aurora_chevron_right
    @DrawableRes val Minus = R.drawable.ic_aurora_minus
    @DrawableRes val TextSize = R.drawable.ic_aurora_text_size
    @DrawableRes val Moon = R.drawable.ic_aurora_moon
    @DrawableRes val Volume = R.drawable.ic_aurora_volume
    @DrawableRes val Globe = R.drawable.ic_aurora_globe
    @DrawableRes val Trash = R.drawable.ic_aurora_trash
    @DrawableRes val Clock = R.drawable.ic_aurora_clock
    @DrawableRes val Desktop = R.drawable.ic_aurora_desktop
    @DrawableRes val Translate = R.drawable.ic_aurora_translate
    @DrawableRes val Image = R.drawable.ic_aurora_image
    @DrawableRes val Sparkle = R.drawable.ic_aurora_sparkle
    @DrawableRes val Refresh = R.drawable.ic_aurora_refresh
    @DrawableRes val Clipboard = R.drawable.ic_aurora_clipboard
    @DrawableRes val Play = R.drawable.ic_aurora_play
    @DrawableRes val Pause = R.drawable.ic_aurora_pause

    private val byName = mapOf(
        "arrow-left" to Back, "arrow-right" to Forward, "house-simple" to Home,
        "squares-four" to Tabs, "dots-three" to More, "magnifying-glass" to Search,
        "x" to Close, "plus" to Add, "shield-check" to ShieldCheck, "lock-simple" to Lock,
        "star" to Star, "clock-counter-clockwise" to History, "download-simple" to Download,
        "book-open-text" to Reader, "eye-slash" to Incognito, "share-network" to Share,
        "gear-six" to Settings, "puzzle-piece" to Extensions, "microphone" to Mic,
        "qr-code" to QrCode, "caret-right" to ChevronRight, "minus" to Minus,
        "text-aa" to TextSize, "moon" to Moon, "speaker-high" to Volume, "globe" to Globe,
        "trash-simple" to Trash, "clock" to Clock, "desktop" to Desktop,
        "translate" to Translate, "image" to Image, "sparkle" to Sparkle,
        "arrow-clockwise" to Refresh, "clipboard-text" to Clipboard,
        "play" to Play, "pause" to Pause,
    )

    /**
     * 已登记的全部图标名。`tools/phosphor_to_drawable.mjs` 与图标测试都读它，
     * 避免「设计稿加了图标、生成脚本和测试没跟上」这类静默漂移。
     */
    val names: Set<String> get() = byName.keys

    /** @throws IllegalArgumentException 当图标名不存在时——静默返回 0 会让图标消失且难以排查。 */
    @DrawableRes
    fun of(name: String): Int =
        byName[name] ?: throw IllegalArgumentException("Unknown Aurora icon: $name")
}
