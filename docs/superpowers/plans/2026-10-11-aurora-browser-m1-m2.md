# Aurora Browser — M1 设计系统基础 + M2 浏览核心 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一个可运行的安卓浏览器骨架：完整的设计令牌系统与核心组件，加上网页浏览、多标签管理、地址栏建议三条主链路。

**Architecture:** 单 Activity + 状态驱动 UI（不引入 navigation-compose）。浏览界面由一个密封类 `BrowserSurface` 表达（Page / Omnibox / TabSwitcher），避免路由栈与浏览器手势语义打架。设计令牌通过 `CompositionLocal` 注入，组件只读语义令牌，不写死颜色。数据层先做纯内存实现（`TabRepository`），持久化留到 M3。

**Tech Stack:** Kotlin、Jetpack Compose（Material 3）、AndroidX WebView、ViewModel + StateFlow、Robolectric + Compose UI Test（JVM 上跑 UI 测试，无需模拟器）。

**Spec:** `docs/superpowers/specs/2026-10-11-android-browser-design.md`
设计令牌的权威来源：`design-system/aurora-browser/MASTER.md`
视觉参照（12 屏，可直接在浏览器打开）：`prototype/index.html`

## Global Constraints

以下为项目级约束，**每个任务的需求都隐含包含本节**。数值逐字取自规格文档。

- `minSdk = 26`，`targetSdk = 35`，`compileSdk = 35`。JVM target 17。
- 深色优先。M1/M2 只实现深色主题；浅色令牌在规格中已定义但**本期不实现**。
- 语义色（深色）——组件禁止写死 hex，只能用这些语义令牌：
  `bg #0F172A`、`bg-elevated #131C31`、`surface #1B2336`、`surface-hover #272F42`、
  `border #334155`、`divider rgba(148,163,184,.16)`、`text #F8FAFC`、`text-secondary #CBD5E1`、
  `text-muted #94A3B8`、`text-disabled #64748B`、`brand #38BDF8`、`brand-strong #0EA5E9`、
  `on-brand #0F172A`、`success #22C55E`、`warning #F59E0B`、`danger #EF4444`、
  `scrim rgba(2,6,23,.72)`、`glass rgba(19,28,49,.72)`、`glass-border rgba(148,163,184,.14)`
- 间距 4/8dp 节奏：`4, 8, 12, 16, 24, 32, 48`。
- 圆角：`xs 6, sm 10, md 14, lg 20, xl 28, full 999`。
- 字号（sp）/行高：`display 28/34 w700`、`title 20/26 w600`、`heading 17/24 w600`、
  `body 15/22 w400`、`label 13/18 w500`、`caption 11/15 w500`、`mono 14/20 w500`。
- 最小触摸目标 **48×48dp**；相邻可点目标间距 **≥8dp**。
- 动效：`fast 120ms`、`base 200ms`、`slow 320ms`；标准缓动 `cubic-bezier(.2,0,0,1)`。
  按压态**不得改变布局尺寸**。系统开启「移除动画」时全部降级为即时切换。
- 列表一律 `LazyColumn` / `LazyVerticalGrid`；**每屏只允许一个滚动容器**。
- 图标：Phosphor Regular（线性）。**禁止 emoji 充当结构图标**。
  同一层级只用一种图标风格。
- 正文与次级文字对比 ≥4.5:1。颜色不得作为唯一信息载体。
- 返回键顺序：关面板 → 退地址栏聚焦 → 网页后退 → 双击退出。
- 所有可点内容必须避开状态栏与手势导航条（用 `WindowInsets`）。

---

## 文件结构

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
tools/phosphor_to_drawable.mjs                     # 一次性图标转换脚本
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/drawable/ic_aurora_*.xml           # 由脚本生成
app/src/main/java/dev/aurora/browser/
  MainActivity.kt                                   # 唯一 Activity，setContent
  ui/theme/Tokens.kt                                # 颜色/间距/圆角/动效/高度/字体令牌
  ui/theme/AuroraTheme.kt                           # CompositionLocal + AuroraTheme
  ui/icons/AuroraIcons.kt                           # 图标名 → drawable 资源映射
  ui/components/BottomToolbar.kt                    # 5 槽悬浮玻璃工具栏
  ui/components/OmniboxBar.kt                       # 地址栏
  ui/components/AuroraBottomSheet.kt                # 底部抽屉骨架
  ui/components/AuroraSwitch.kt                     # 开关
  ui/components/SettingRow.kt                       # 设置行
  ui/components/SegmentedControl.kt                 # 分段控件
  domain/model/BrowserTab.kt                        # 标签数据模型
  domain/TabRepository.kt                           # 标签内存仓库
  domain/BackPressDispatcher.kt                     # 返回键决策（纯逻辑）
  ui/browser/BrowserSurface.kt                      # 密封类：Page / Omnibox / TabSwitcher
  ui/browser/BrowserViewModel.kt                    # 编排三者状态
  ui/browser/BrowserScreen.kt                       # 顶层组装
  ui/browser/WebViewContainer.kt                    # WebView 封装
  ui/browser/TabSwitcherScreen.kt                   # 标签网格
  ui/browser/OmniboxScreen.kt                       # 地址栏聚焦态
  ui/home/HomeScreen.kt                             # 主页 / 新标签页
app/src/test/java/dev/aurora/browser/…              # JVM 单测 + Robolectric UI 测试
```

**职责边界：**
- `ui/theme/*` 是唯一允许出现十六进制颜色字面量的地方。其余模块只能引用语义令牌。
- `domain/*` 是纯 Kotlin，**不得 import 任何 `android.*` 或 `androidx.compose.*`**，因此可以纯 JVM 单测。
- `ui/browser/WebViewContainer.kt` 是唯一直接接触 `android.webkit.WebView` 的文件。
- 组件（`ui/components/*`）不持有业务状态，只接收参数 + 回调。

---

## M1 — 设计系统基础

### Task 1: 工程脚手架与测试环境

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/dev/aurora/browser/MainActivity.kt`（由向导生成，Task 12 再改一次）
- Test: `app/src/test/java/dev/aurora/browser/ScaffoldSmokeTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: 可编译的 `:app` 模块；`MainActivity`（后续任务往里挂 `AuroraTheme` 与 `BrowserScreen`）；JVM 上可运行 Compose UI 测试的能力（Robolectric）

- [ ] **Step 1: 用 Android Studio 生成骨架**

用 Android Studio 的 **New Project → Empty Activity（Compose）** 生成工程，包名 `dev.aurora.browser`，
`minSdk 26`、`compileSdk 35`、`targetSdk 35`、语言 Kotlin、构建脚本 Kotlin DSL。
保留向导生成的 Compose/Kotlin/AGP 版本号（不要手写臆测的版本）。

- [ ] **Step 2: 追加测试依赖**

在 `gradle/libs.versions.toml` 的 `[versions]` 中追加：

```toml
robolectric = "4.14.1"
truth = "1.4.4"
androidxTestCore = "1.6.1"
androidxTestJunit = "1.2.1"
```

`[libraries]` 中追加：

```toml
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
androidx-test-core = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestJunit" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
```

> 最后一行是必需的：向导的 Empty Compose Activity 模板只带 `lifecycle-runtime-ktx`，
> **不带** `lifecycle-viewmodel-compose`，而 Task 10/12 的 `viewModel()` 依赖它。
> `version.ref = "lifecycleRuntimeKtx"` 复用向导已生成的版本号（若向导的 ref 名不同，
> 按实际名字改）。

**没有向导时**（无 Android Studio 的纯命令行环境）还要自己补上向导本来会生成的东西：
`androidx.compose.material3:material3`（不写版本，由 BOM 管理）、`androidx.core:core-ktx`、
`androidx.activity:activity-compose`、`androidx.lifecycle:lifecycle-runtime-ktx`，
以及 Kotlin 2.x 必须显式应用的 `org.jetbrains.kotlin.plugin.compose` 插件
（Kotlin 2.0 起 Compose 编译器随 Kotlin 发布，不再有 `composeOptions.kotlinCompilerExtensionVersion`）。

（`compose-ui-test-*` 不写版本，由向导已配好的 Compose BOM 统一管理。）

在 `app/build.gradle.kts` 中：

```kotlin
android {
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)   // viewModel() 所需
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

> `isIncludeAndroidResources = true` 是 Robolectric 能渲染 Compose 的前提，缺了会报资源找不到。

同一步里补 `app/src/main/AndroidManifest.xml`。向导模板**不带** `INTERNET` 权限，
缺了 WebView 会直接加载失败（真机上表现为空白页，很容易误判成代码问题）：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:label="Aurora"
        android:supportsRtl="true"
        android:theme="@style/Theme.Aurora">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> `android:theme` 填向导在 `res/values/themes.xml` 里生成的那个名字（`Theme.<项目名>`），
> 不要照抄这里的 `Theme.Aurora`——向导用的项目名未必是 `Aurora`。
> 保留向导生成的 `app_name` 字符串资源，`android:label` 指向它也可以。
> `configChanges` 含 `orientation|screenSize`：旋转时不重建 Activity，WebView 的
> 历史栈与滚动位置得以保留。少了它，每次旋转都会回到起始页。
> 本计划不使用 `Application` 子类，所以这里没有 `android:name`。

- [ ] **Step 3: 写失败测试**

`app/src/test/java/dev/aurora/browser/ScaffoldSmokeTest.kt`：

```kotlin
package dev.aurora.browser

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaffoldSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun composeRendersOnJvm() {
        compose.setContent { Text("Aurora") }
        compose.onNodeWithText("Aurora").assertExists()
    }
}
```

> `@Config(sdk = [34])` 而不是 35：Robolectric 对最新 SDK 的支持常滞后一版。若 35 可用可去掉此行。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ScaffoldSmokeTest"`
Expected: BUILD SUCCESSFUL，1 test passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "chore: scaffold Aurora Browser with JVM-runnable Compose tests"
```

---

### Task 2: 设计令牌与主题

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/theme/Tokens.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/theme/AuroraTheme.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/theme/TokensTest.kt`

**Interfaces:**
- Consumes: Task 1 的工程
- Produces:
  - `data class AuroraColors(val bg: Color, val bgElevated: Color, val surface: Color, val surfaceHover: Color, val border: Color, val divider: Color, val text: Color, val textSecondary: Color, val textMuted: Color, val textDisabled: Color, val brand: Color, val brandStrong: Color, val onBrand: Color, val success: Color, val warning: Color, val danger: Color, val scrim: Color, val glass: Color, val glassBorder: Color)`
  - `object AuroraSpacing { val xs: Dp = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val xxxl: Dp = 48.dp; val touchTarget: Dp = 48.dp; val touchGap: Dp = 8.dp }`
  - `object AuroraRadius { val xs = 6.dp; val s = 10.dp; val m = 14.dp; val l = 20.dp; val xl = 28.dp; val full = 999.dp }`
  - `object AuroraMotion { const val FAST = 120; const val BASE = 200; const val SLOW = 320; val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f) }`
  - `val LocalAuroraColors: ProvidableCompositionLocal<AuroraColors>`
  - `@Composable fun AuroraTheme(colors: AuroraColors = DarkAuroraColors, content: @Composable () -> Unit)`

- [ ] **Step 1: 写失败测试**

`TokensTest.kt`：

```kotlin
package dev.aurora.browser.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokensTest {
    @Test fun semanticColorsMatchSpec() {
        val c = DarkAuroraColors
        assertThat(
            listOf(
                c.bg, c.bgElevated, c.surface, c.surfaceHover, c.border, c.divider,
                c.text, c.textSecondary, c.textMuted, c.textDisabled,
                c.brand, c.brandStrong, c.onBrand,
                c.success, c.warning, c.danger,
                c.scrim, c.glass, c.glassBorder,
            )
        ).containsExactly(
            Color(0xFF0F172A), Color(0xFF131C31), Color(0xFF1B2336), Color(0xFF272F42),
            Color(0xFF334155), Color(0x2994A3B8),
            Color(0xFFF8FAFC), Color(0xFFCBD5E1), Color(0xFF94A3B8), Color(0xFF64748B),
            Color(0xFF38BDF8), Color(0xFF0EA5E9), Color(0xFF0F172A),
            Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444),
            Color(0xB8020617), Color(0xB8131C31), Color(0x2494A3B8),
        ).inOrder()
    }

    @Test fun spacingFollowsFourEightRhythm() {
        assertThat(
            listOf(
                AuroraSpacing.xs.value, AuroraSpacing.s.value, AuroraSpacing.m.value,
                AuroraSpacing.l.value, AuroraSpacing.xl.value, AuroraSpacing.xxl.value,
                AuroraSpacing.xxxl.value,
            )
        ).containsExactly(4f, 8f, 12f, 16f, 24f, 32f, 48f).inOrder()
    }

    @Test fun radiusScaleMatchesSpec() {
        assertThat(
            listOf(
                AuroraRadius.xs.value, AuroraRadius.s.value, AuroraRadius.m.value,
                AuroraRadius.l.value, AuroraRadius.xl.value, AuroraRadius.full.value,
            )
        ).containsExactly(6f, 10f, 14f, 20f, 28f, 999f).inOrder()
    }

    @Test fun motionDurationsMatchSpec() {
        assertThat(listOf(AuroraMotion.FAST, AuroraMotion.BASE, AuroraMotion.SLOW))
            .containsExactly(120, 200, 320).inOrder()
    }

    @Test fun touchTargetMinimumIs48dp() {
        assertThat(AuroraSpacing.touchTarget.value).isAtLeast(48f)
        assertThat(AuroraSpacing.touchGap.value).isAtLeast(8f)
    }
}
```

> 十九个语义色全部断言，包括四个 alpha 合成值——它们是最容易抄错的一批，
> 而原稿只覆盖了 6 个。圆角与动效同理：一个 `full` 写错照样能编译通过。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.theme.TokensTest"`
Expected: 编译失败 —— `Unresolved reference: DarkAuroraColors`

- [ ] **Step 3: 实现令牌**

`Tokens.kt`：

```kotlin
package dev.aurora.browser.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 唯一允许出现十六进制颜色字面量的文件。
 * 值取自 design-system/aurora-browser/MASTER.md，改动需同步该文件。
 */
data class AuroraColors(
    val bg: Color, val bgElevated: Color, val surface: Color, val surfaceHover: Color,
    val border: Color, val divider: Color,
    val text: Color, val textSecondary: Color, val textMuted: Color, val textDisabled: Color,
    val brand: Color, val brandStrong: Color, val onBrand: Color,
    val success: Color, val warning: Color, val danger: Color,
    val scrim: Color, val glass: Color, val glassBorder: Color,
)

val DarkAuroraColors = AuroraColors(
    bg = Color(0xFF0F172A), bgElevated = Color(0xFF131C31),
    surface = Color(0xFF1B2336), surfaceHover = Color(0xFF272F42),
    border = Color(0xFF334155), divider = Color(0x2994A3B8),   // 16% of #94A3B8
    text = Color(0xFFF8FAFC), textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF94A3B8), textDisabled = Color(0xFF64748B),
    brand = Color(0xFF38BDF8), brandStrong = Color(0xFF0EA5E9), onBrand = Color(0xFF0F172A),
    success = Color(0xFF22C55E), warning = Color(0xFFF59E0B), danger = Color(0xFFEF4444),
    scrim = Color(0xB8020617),                                  // 72% of #020617
    glass = Color(0xB8131C31),                                  // 72% of #131C31
    glassBorder = Color(0x2494A3B8),                            // 14% of #94A3B8
)

object AuroraSpacing {
    val xs: Dp = 4.dp; val s: Dp = 8.dp; val m: Dp = 12.dp; val l: Dp = 16.dp
    val xl: Dp = 24.dp; val xxl: Dp = 32.dp; val xxxl: Dp = 48.dp
    /** Android 最小触摸目标。所有可点组件不得小于此值。 */
    val touchTarget: Dp = 48.dp
    /** 相邻可点目标的最小间距。 */
    val touchGap: Dp = 8.dp
}

object AuroraRadius {
    val xs: Dp = 6.dp; val s: Dp = 10.dp; val m: Dp = 14.dp
    val l: Dp = 20.dp; val xl: Dp = 28.dp; val full: Dp = 999.dp
}

object AuroraMotion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 320
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
```

`AuroraTheme.kt`：

```kotlin
package dev.aurora.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAuroraColors = staticCompositionLocalOf { DarkAuroraColors }

@Composable
fun AuroraTheme(
    colors: AuroraColors = DarkAuroraColors,
    content: @Composable () -> Unit,
) {
    // M1/M2 深色优先：无论系统主题如何都用深色令牌。
    val material = darkColorScheme(
        primary = colors.brand,
        onPrimary = colors.onBrand,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surfaceHover,
        onSurfaceVariant = colors.textMuted,
        outline = colors.border,
        error = colors.danger,
    )
    CompositionLocalProvider(LocalAuroraColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/** 组件内读语义色的统一入口：`Aurora.colors.brand`。 */
object Aurora {
    val colors: AuroraColors
        @Composable get() = LocalAuroraColors.current
}
```

> 本期不读系统深浅色——规格明确「深色是第一公民」，M4 接入浅色时再引入
> `isSystemInDarkTheme()` 分支。同理 `AuroraMotion` 只是把规格的时长/缓动表落成令牌，
> M1/M2 的所有转场都用 Material 3 默认动画，因此本期没有它的调用点。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.theme.TokensTest"`
Expected: BUILD SUCCESSFUL，5 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(theme): add Aurora design tokens and dark-first theme"
```

---

### Task 3: 图标系统（Phosphor → VectorDrawable）

**Files:**
- Create: `tools/phosphor_to_drawable.mjs`
- Create (生成): `app/src/main/res/drawable/ic_aurora_*.xml`
- Create: `app/src/main/java/dev/aurora/browser/ui/icons/AuroraIcons.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/icons/AuroraIconsTest.kt`

**Interfaces:**
- Consumes: Task 2 的主题
- Produces:
  - `object AuroraIcons { @DrawableRes fun of(name: String): Int }`，以及具名常量
    `val Back, Forward, Home, Tabs, More, Search, Close, Add, ShieldCheck, Lock, Star,
     History, Download, Reader, Incognito, Share, Settings, Extensions, Mic, QrCode,
     ChevronRight, Minus, TextSize, Moon, Volume, Globe, Trash, Clock, Desktop, Translate, Image, Sparkle,
     Refresh, Clipboard`（共 34 个）
  - 图标名与原型 `prototype/index.html` 中的 `data-i` 值一致（`arrow-left`、`house-simple` …）

- [ ] **Step 1: 写图标转换脚本**

`tools/phosphor_to_drawable.mjs`（Node 18+，无第三方依赖）：

```js
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const OUT = 'app/src/main/res/drawable';
const CDN = 'https://unpkg.com/@phosphor-icons/core@2.1.1/assets/regular';

// 名称 → 资源名。必须与 AuroraIcons.kt 的常量一一对应。
const ICONS = {
  'arrow-left': 'back', 'arrow-right': 'forward', 'house-simple': 'home',
  'squares-four': 'tabs', 'dots-three': 'more', 'magnifying-glass': 'search',
  'x': 'close', 'plus': 'add', 'shield-check': 'shield_check', 'lock-simple': 'lock',
  'star': 'star', 'clock-counter-clockwise': 'history', 'download-simple': 'download',
  'book-open-text': 'reader', 'eye-slash': 'incognito', 'share-network': 'share',
  'gear-six': 'settings', 'puzzle-piece': 'extensions', 'microphone': 'mic',
  'qr-code': 'qr_code', 'caret-right': 'chevron_right', 'minus': 'minus',
  'text-aa': 'text_size', 'moon': 'moon', 'speaker-high': 'volume',
  'globe': 'globe', 'trash-simple': 'trash', 'clock': 'clock',
  'desktop': 'desktop', 'translate': 'translate', 'image': 'image', 'sparkle': 'sparkle',
  'arrow-clockwise': 'refresh', 'clipboard-text': 'clipboard',
};

mkdirSync(OUT, { recursive: true });
let n = 0;
for (const [name, res] of Object.entries(ICONS)) {
  const svg = await (await fetch(`${CDN}/${name}.svg`)).text();
  const d = svg.match(/<path d="([^"]+)"/)?.[1];
  if (!d) { console.error(`MISSING path for ${name}`); process.exitCode = 1; continue; }
  const xml = `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="256" android:viewportHeight="256"
    android:tint="?android:attr/colorControlNormal">
  <path android:fillColor="@android:color/white" android:pathData="${d}"/>
</vector>
`;
  writeFileSync(join(OUT, `ic_aurora_${res}.xml`), xml);
  n++;
}
console.log(`wrote ${n} drawables to ${OUT}`);
```

> `android:tint` 必须写 `?android:attr/...`（框架命名空间），不能写 `?attr/colorControlNormal`。
> 后者是 AppCompat 的应用命名空间属性，本项目没有 AppCompat 依赖、主题是平台主题
> `android:Theme.Material.NoActionBar`，引用它会导致 drawable 膨胀失败
> （Robolectric 的 `getDrawable` 会直接抛异常）。`fillColor="@android:color/white"` 是占位，
> 实际颜色由 tint 决定。
> 另外：在 Compose 里 `Icon(painterResource(...), tint = ...)` 会自己套 ColorFilter，
> 所以 `android:tint` 对 Compose 渲染是冗余的——留着是为了这些 drawable 被 Compose 之外的
> 地方（通知、快捷方式图标）复用时也有合理颜色。

- [ ] **Step 2: 运行脚本生成 drawable**

Run: `node tools/phosphor_to_drawable.mjs`
Expected: `wrote 34 drawables to app/src/main/res/drawable`

- [ ] **Step 3: 写失败测试**

`AuroraIconsTest.kt`：

```kotlin
package dev.aurora.browser.ui.icons

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuroraIconsTest {
    @Test fun everyRequiredIconResolvesToADrawable() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val names = listOf(
            "arrow-left", "arrow-right", "house-simple", "squares-four", "dots-three",
            "magnifying-glass", "x", "plus", "shield-check", "lock-simple", "star",
            "clock-counter-clockwise", "download-simple", "book-open-text", "eye-slash",
            "share-network", "gear-six", "puzzle-piece", "microphone", "qr-code",
            "caret-right", "minus", "text-aa", "moon", "speaker-high", "globe",
            "trash-simple", "clock", "desktop", "translate", "image", "sparkle",
            "arrow-clockwise", "clipboard-text",
        )
        for (name in names) {
            val res = AuroraIcons.of(name)
            assertThat(res).isNotEqualTo(0)
            assertThat(ctx.getDrawable(res)).isNotNull()
        }
    }

    @Test fun unknownIconNameFailsLoudly() {
        val error = runCatching { AuroraIcons.of("no-such-icon") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.icons.AuroraIconsTest"`
Expected: 编译失败 —— `Unresolved reference: AuroraIcons`

- [ ] **Step 5: 实现 AuroraIcons**

`AuroraIcons.kt`：

```kotlin
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
    )

    /** @throws IllegalArgumentException 当图标名不存在时——静默返回 0 会让图标消失且难以排查。 */
    @DrawableRes
    fun of(name: String): Int =
        byName[name] ?: throw IllegalArgumentException("Unknown Aurora icon: $name")
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.icons.AuroraIconsTest"`
Expected: BUILD SUCCESSFUL，2 tests passed

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(icons): generate Phosphor vector drawables and AuroraIcons lookup"
```

---

### Task 4: 底部悬浮工具栏 BottomToolbar

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/components/BottomToolbar.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/components/BottomToolbarTest.kt`

**Interfaces:**
- Consumes: `Aurora.colors`、`AuroraSpacing`、`AuroraRadius`、`AuroraIcons`
- Produces:
  ```kotlin
  @Composable fun BottomToolbar(
      tabCount: Int,
      onBack: () -> Unit,
      onForward: () -> Unit,
      onHome: () -> Unit,
      onTabs: () -> Unit,
      onMenu: () -> Unit,
      modifier: Modifier = Modifier,
      canGoBack: Boolean = true,
      canGoForward: Boolean = false,
      homeSelected: Boolean = false,
      tabsSelected: Boolean = false,
      incognito: Boolean = false,
  )
  ```
  所有图标按钮的 `contentDescription` 固定为：`"后退"`、`"前进"`、`"主页"`、`"标签页，N 个"`、`"更多"`。

- [ ] **Step 1: 写失败测试**

`BottomToolbarTest.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
// DpRect 的 width/height 是扩展属性，不是成员——缺这两个 import 测试编译不过。
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomToolbarTest {
    @get:Rule val compose = createComposeRule()

    private fun render(
        tabCount: Int = 3,
        canGoBack: Boolean = true,
        onBack: () -> Unit = {},
        onTabs: () -> Unit = {},
    ) = compose.setContent {
        AuroraTheme {
            BottomToolbar(
                tabCount = tabCount, canGoBack = canGoBack,
                onBack = onBack, onForward = {}, onHome = {}, onTabs = onTabs, onMenu = {},
            )
        }
    }

    @Test fun rendersAllFiveSlotsWithAccessibleNames() {
        render()
        compose.onNodeWithContentDescription("后退").assertExists()
        compose.onNodeWithContentDescription("前进").assertExists()
        compose.onNodeWithContentDescription("主页").assertExists()
        compose.onNodeWithContentDescription("标签页，3 个").assertExists()
        compose.onNodeWithContentDescription("更多").assertExists()
    }

    @Test fun tabCountOver99CollapsesTo99Plus() {
        render(tabCount = 128)
        compose.onNodeWithContentDescription("标签页，99+ 个").assertExists()
    }

    @Test fun backDisabledWhenCannotGoBack() {
        render(canGoBack = false)
        compose.onNodeWithContentDescription("后退").assertIsNotEnabled()
    }

    @Test fun tappingTabsInvokesCallback() {
        var taps = 0
        render(onTabs = { taps++ })
        compose.onNodeWithContentDescription("标签页，3 个").performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test fun everySlotMeetsMinimumTouchTarget() {
        render()
        for (label in listOf("后退", "前进", "主页", "标签页，3 个", "更多")) {
            val b = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertThat(b.width.value).isAtLeast(48f)
            assertThat(b.height.value).isAtLeast(48f)
        }
    }

    @Test fun adjacentSlotsKeepMinimumTouchGap() {
        render()
        val labels = listOf("后退", "前进", "主页", "标签页，3 个", "更多")
        val bounds = labels.map {
            compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
        }
        // DpRect 的 left/right 是成员，width 是扩展属性——两种写法别混。
        bounds.zipWithNext { a, b ->
            assertThat((b.left - a.right).value).isAtLeast(8f)
        }
    }

    @Test fun tabSlotHasClickAction() {
        render()
        compose.onNodeWithContentDescription("标签页，3 个").assertHasClickAction()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.components.BottomToolbarTest"`
Expected: 编译失败 —— `Unresolved reference: BottomToolbar`

- [ ] **Step 3: 实现 BottomToolbar**

`BottomToolbar.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

@Composable
fun BottomToolbar(
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = true,
    canGoForward: Boolean = false,
    homeSelected: Boolean = false,
    tabsSelected: Boolean = false,
    incognito: Boolean = false,
) {
    val c = Aurora.colors
    val tabLabel = if (tabCount > 99) "99+" else tabCount.toString()

    Row(
        modifier = modifier
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.xl)
            .height(58.dp)
            .clip(RoundedCornerShape(AuroraRadius.full))
            .background(if (incognito) c.bg.copy(alpha = 0.82f) else c.glass)
            .border(
                width = 1.dp,
                color = if (incognito) c.brand.copy(alpha = 0.34f) else c.glassBorder,
                shape = RoundedCornerShape(AuroraRadius.full),
            )
            .padding(horizontal = AuroraSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.touchGap),
    ) {
        ToolbarSlot(
            icon = AuroraIcons.Back, label = "后退",
            enabled = canGoBack, selected = false, onClick = onBack,
        )
        ToolbarSlot(
            icon = AuroraIcons.Forward, label = "前进",
            enabled = canGoForward, selected = false, onClick = onForward,
        )
        ToolbarSlot(
            icon = AuroraIcons.Home, label = "主页",
            enabled = true, selected = homeSelected, onClick = onHome,
        )
        // 标签槽显示计数方块，语义同原型
        ToolbarSlot(label = "标签页，$tabLabel 个", enabled = true, selected = tabsSelected, onClick = onTabs) {
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 24.dp)
                    .clip(RoundedCornerShape(AuroraRadius.xs))
                    .border(1.6.dp, LocalContentColor.current, RoundedCornerShape(AuroraRadius.xs)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tabLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalContentColor.current,
                )
            }
        }
        ToolbarSlot(
            icon = AuroraIcons.More, label = "更多",
            enabled = true, selected = false, onClick = onMenu,
            rotateDegrees = 90f,
        )
    }
}

@Composable
private fun ToolbarSlot(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    icon: Int? = null,
    rotateDegrees: Float = 0f,
    content: (@Composable () -> Unit)? = null,
) {
    val c = Aurora.colors
    val tint = when {
        !enabled -> c.textDisabled
        selected -> c.brand
        else -> c.textSecondary
    }
    Box(
        modifier = Modifier
            .size(AuroraSpacing.touchTarget)          // 48dp，满足最小触摸目标
            .clip(CircleShape)
            .semantics { contentDescription = label }  // 计数槽没有 Icon，语义只能挂在这里
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides tint) {
            if (content != null) content()
            else if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,   // 语义已由外层 Box 承载，避免重复朗读
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .then(
                            if (rotateDegrees != 0f) Modifier.rotate(rotateDegrees) else Modifier
                        ),
                )
            }
        }
    }
}
```

> 需要的额外 import：`androidx.compose.runtime.remember`、`androidx.compose.ui.draw.rotate`、
> `androidx.compose.ui.semantics.contentDescription`、`androidx.compose.ui.semantics.semantics`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.components.BottomToolbarTest"`
Expected: BUILD SUCCESSFUL，7 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(components): add floating BottomToolbar with 48dp targets"
```

---

### Task 5: 地址栏 OmniboxBar

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/components/OmniboxBar.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/components/OmniboxBarTest.kt`

**Interfaces:**
- Consumes: 主题、`AuroraIcons`
- Produces:
  ```kotlin
  enum class SiteSecurity { SECURE, INSECURE, WARNING }

  @Composable fun OmniboxBar(
      displayUrl: String,                 // 已格式化的域名，例如 "developer.android.com"
      security: SiteSecurity,
      onTap: () -> Unit,
      onTrailingAction: () -> Unit,
      modifier: Modifier = Modifier,
      trailingIcon: Int = AuroraIcons.Refresh,   // 加载中传 Close
      placeholder: String = "搜索或输入网址",
  )

  /** URL → 展示用域名；解析失败时原样返回，空串输入返回空串。 */
  fun hostOf(url: String?): String

  /** 按 scheme 判定站点安全态。本期只有 http/https 两种输入。 */
  fun securityOf(url: String?): SiteSecurity
  ```
  语义：`contentDescription` 为 `"地址栏，developer.android.com"`；站点身份图标描述为
  `"连接安全"` / `"连接不安全"` / `"证书警告"`。

  `hostOf` / `securityOf` 与 `SiteSecurity` 同住一个文件，因为它们是一组：
  Task 11 的标签列表和 Task 12 的网页态地址栏都要用，私有复制会导致两处漂移。

- [ ] **Step 1: 写失败测试**

`OmniboxBarTest.kt`：

```kotlin
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
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.components.OmniboxBarTest"`
Expected: 编译失败 —— `Unresolved reference: OmniboxBar`

- [ ] **Step 3: 实现 OmniboxBar**

`OmniboxBar.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

enum class SiteSecurity { SECURE, INSECURE, WARNING }

@Composable
fun OmniboxBar(
    displayUrl: String,
    security: SiteSecurity,
    onTap: () -> Unit,
    onTrailingAction: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: Int = AuroraIcons.Refresh,   // 加载中传 AuroraIcons.Close
    placeholder: String = "搜索或输入网址",
) {
    val c = Aurora.colors
    val blank = displayUrl.isBlank()
    val (identityIcon, identityDesc, identityTint) = when (security) {
        SiteSecurity.SECURE -> Triple(AuroraIcons.Lock, "连接安全", c.success)
        SiteSecurity.INSECURE -> Triple(AuroraIcons.Globe, "连接不安全", c.textMuted)
        SiteSecurity.WARNING -> Triple(AuroraIcons.ShieldCheck, "证书警告", c.warning)
    }

    Row(
        modifier = modifier
            .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.xs)
            .height(44.dp)
            .clip(RoundedCornerShape(AuroraRadius.full))
            .background(c.surfaceHover)
            // mergeDescendants = true：整条地址栏合成一个无障碍节点，读屏一次读出
            // "地址栏，域名"，而不是把它拆成图标/文本/按钮三段。
            // 右侧动作按钮自身是 clickable（也是合并节点），实测嵌套的合并节点不会被
            // 祖先吸收——`onNodeWithContentDescription("刷新")` 仍解析到按钮本身并触发
            // onTrailingAction，不会被整行吞掉（见 progress.md Ruling 13）。
            .semantics(mergeDescendants = true) {
                contentDescription = if (blank) "地址栏" else "地址栏，$displayUrl"
            }
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(start = AuroraSpacing.m, end = AuroraSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(identityTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(identityIcon),
                contentDescription = identityDesc,
                tint = identityTint,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = if (blank) placeholder else displayUrl,
            color = if (blank) c.textDisabled else c.textSecondary,
            fontFamily = if (blank) FontFamily.Default else FontFamily.Monospace,
            fontSize = if (blank) 14.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(AuroraSpacing.touchTarget)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTrailingAction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(trailingIcon),
                contentDescription = if (trailingIcon == AuroraIcons.Close) "停止加载" else "刷新",
                tint = c.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * URL → 展示用域名。解析失败（含手输的半截地址）时原样返回，
 * 这样用户至少能看到自己敲进去的东西，而不是空白。
 */
fun hostOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return runCatching { java.net.URI(url).host }.getOrNull() ?: url
}

/**
 * 按 scheme 判定站点安全态。本期输入只有 http/https；
 * 证书异常（WARNING）要等 M3 接入 `WebViewClient.onReceivedSslError` 才能产生。
 */
fun securityOf(url: String?): SiteSecurity =
    if (url != null && url.startsWith("https://")) SiteSecurity.SECURE else SiteSecurity.INSECURE
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.components.OmniboxBarTest"`
Expected: BUILD SUCCESSFUL，6 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(components): add OmniboxBar with site identity states"
```

---

### Task 6: 底部抽屉与设置类组件

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/components/AuroraBottomSheet.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/components/AuroraSwitch.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/components/SettingRow.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/components/SegmentedControl.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/components/PanelComponentsTest.kt`

**Interfaces:**
- Consumes: 主题、`AuroraIcons`
- Produces:
  ```kotlin
  @Composable fun AuroraBottomSheet(
      onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)

  @Composable fun AuroraSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
      modifier: Modifier = Modifier, label: String? = null)

  @Composable fun SettingRow(
      title: String, modifier: Modifier = Modifier, subtitle: String? = null,
      value: String? = null, icon: Int? = null, onClick: (() -> Unit)? = null,
      trailing: (@Composable () -> Unit)? = null)

  @Composable fun <T> SegmentedControl(
      options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier)
  ```

- [ ] **Step 1: 写失败测试**

`PanelComponentsTest.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
class PanelComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchTogglesAndExposesState() {
        var state = false
        compose.setContent {
            AuroraTheme { AuroraSwitch(checked = state, onCheckedChange = { state = it }, label = "始终使用 HTTPS") }
        }
        compose.onNodeWithText("始终使用 HTTPS").assertIsOff()
        compose.onNodeWithText("始终使用 HTTPS").performClick()
        assertThat(state).isTrue()
    }

    @Test fun switchReflectsCheckedState() {
        compose.setContent { AuroraTheme { AuroraSwitch(checked = true, onCheckedChange = {}, label = "追踪器拦截") } }
        compose.onNodeWithText("追踪器拦截").assertIsOn()
    }

    @Test fun settingRowRendersTitleSubtitleValueAndFiresClick() {
        var clicks = 0
        compose.setContent {
            AuroraTheme {
                SettingRow(title = "主题", subtitle = "跟随系统", value = "深色", onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("主题").assertExists()
        compose.onNodeWithText("跟随系统").assertExists()
        compose.onNodeWithText("深色").assertExists()
        compose.onNodeWithText("主题").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test fun segmentedControlSelectsOption() {
        var selected = "grid"
        compose.setContent {
            AuroraTheme {
                SegmentedControl(
                    options = listOf("grid" to "网格", "list" to "列表"),
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText("列表").performClick()
        assertThat(selected).isEqualTo("list")
    }

    @Test fun sheetDismissesOnScrimTap() {
        var dismissed = 0
        compose.setContent {
            AuroraTheme { AuroraBottomSheet(onDismiss = { dismissed++ }) { } }
        }
        compose.onNodeWithContentDescription("关闭面板").performClick()
        assertThat(dismissed).isEqualTo(1)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.components.PanelComponentsTest"`
Expected: 编译失败 —— 四个组件均未解析

- [ ] **Step 3: 实现四个组件**

`AuroraSwitch.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.aurora.browser.ui.theme.*

/**
 * 整行可点，命中区 ≥48dp。label 既是可见文案也是无障碍名称，
 * 因此点击 label 也能切换（用 toggleable 而非只包 Switch）。
 */
@Composable
fun AuroraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val c = Aurora.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AuroraSpacing.touchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(text = label, color = c.text, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Switch(
            checked = checked,
            onCheckedChange = null,          // 由父级 toggleable 统一处理，避免双重触发
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onBrand,
                checkedTrackColor = c.brand,
                uncheckedThumbColor = c.textMuted,
                uncheckedTrackColor = c.surfaceHover,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}
```

`SettingRow.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: Int? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Aurora.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .then(
                if (onClick != null) Modifier.clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            )
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,          // 装饰性：旁边有可见标题
                tint = c.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = c.text)
            if (subtitle != null) {
                Text(text = subtitle, color = c.textDisabled, fontFamily = FontFamily.Monospace,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
        if (value != null) {
            Text(text = value, color = c.textSecondary, fontFamily = FontFamily.Monospace)
        }
        if (trailing != null) trailing()
        else if (onClick != null && value == null) {
            Icon(
                painter = painterResource(AuroraIcons.ChevronRight),
                contentDescription = null,
                tint = c.textDisabled,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
```

`SegmentedControl.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.theme.*

@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aurora.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AuroraRadius.full))
            .background(c.bgElevated)
            .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.full))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val isOn = value == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(if (isOn) c.surface else c.bgElevated)
                    .semantics { this.selected = isOn }
                    .clickable(
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, color = if (isOn) c.text else c.textMuted)
            }
        }
    }
}
```

`AuroraBottomSheet.kt`：

```kotlin
package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.theme.*

/**
 * 底部抽屉。遮罩本身承载「关闭面板」语义，点击关闭。
 * 上沿圆角 lg，顶部 34×4dp 拖拽把手。
 */
@Composable
fun AuroraBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Aurora.colors
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.scrim)
                .semantics { contentDescription = "关闭面板" }
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = AuroraRadius.l, topEnd = AuroraRadius.l))
                .background(c.bgElevated)
                .padding(bottom = AuroraSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = AuroraSpacing.s, bottom = AuroraSpacing.m)
                    .size(width = 34.dp, height = 4.dp)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.border)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AuroraSpacing.l), content = content)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.components.PanelComponentsTest"`
Expected: BUILD SUCCESSFUL，5 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(components): add bottom sheet, switch, setting row, segmented control"
```

---

## M2 — 浏览核心

### Task 7: 标签模型与仓库（纯 Kotlin）

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/domain/model/BrowserTab.kt`
- Create: `app/src/main/java/dev/aurora/browser/domain/TabRepository.kt`
- Test: `app/src/test/java/dev/aurora/browser/domain/TabRepositoryTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin，**不得** import `android.*`）
- Produces:
  ```kotlin
  data class BrowserTab(
      val id: String,
      val url: String? = null,          // null = 新标签页
      val title: String = "新标签页",
      val isIncognito: Boolean = false,
      val groupId: String? = null,
  ) { val isNewTab: Boolean get() = url == null }

  class TabRepository {
      val tabs: StateFlow<List<BrowserTab>>       // 永远至少 1 个；永远有且仅有一个 activeId
      val activeId: StateFlow<String>
      val active: BrowserTab
      fun newTab(url: String? = null, incognito: Boolean = false): BrowserTab
      fun close(id: String)
      fun select(id: String)
      fun update(id: String, url: String? = null, title: String? = null)
      fun move(from: Int, to: Int)
  }
  ```
  不变量：`tabs` 非空；`activeId` 始终指向 `tabs` 中的某一项；关闭最后一个标签会自动新建一个空白标签。

- [ ] **Step 1: 写失败测试**

`TabRepositoryTest.kt`：

```kotlin
package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabRepositoryTest {
    @Test fun startsWithExactlyOneActiveBlankTab() {
        val repo = TabRepository()
        assertThat(repo.tabs.value).hasSize(1)
        assertThat(repo.active.isNewTab).isTrue()
        assertThat(repo.activeId.value).isEqualTo(repo.tabs.value.first().id)
    }

    @Test fun newTabBecomesActiveAndIsAppended() {
        val repo = TabRepository()
        val first = repo.tabs.value.first().id
        val second = repo.newTab("https://a.com")
        assertThat(repo.tabs.value).hasSize(2)
        assertThat(repo.tabs.value.last().id).isEqualTo(second.id)
        assertThat(repo.activeId.value).isEqualTo(second.id)
        assertThat(repo.tabs.value.first().id).isEqualTo(first)
    }

    @Test fun closingActiveTabSelectsNeighbour() {
        val repo = TabRepository()
        val a = repo.tabs.value.first().id
        val b = repo.newTab("https://b.com").id
        repo.select(a)
        repo.close(a)
        assertThat(repo.tabs.value.map { it.id }).containsExactly(b)
        assertThat(repo.activeId.value).isEqualTo(b)
    }

    @Test fun closingLastTabCreatesBlankTab() {
        val repo = TabRepository()
        repo.close(repo.activeId.value)
        assertThat(repo.tabs.value).hasSize(1)
        assertThat(repo.active.isNewTab).isTrue()
    }

    @Test fun closingInactiveTabKeepsActive() {
        val repo = TabRepository()
        val a = repo.tabs.value.first().id
        val b = repo.newTab("https://b.com").id
        repo.select(b)
        repo.close(a)
        assertThat(repo.activeId.value).isEqualTo(b)
        assertThat(repo.tabs.value).hasSize(1)
    }

    @Test fun updateSetsUrlAndTitle() {
        val repo = TabRepository()
        val id = repo.activeId.value
        repo.update(id, url = "https://c.com", title = "C")
        assertThat(repo.active.url).isEqualTo("https://c.com")
        assertThat(repo.active.title).isEqualTo("C")
        assertThat(repo.active.isNewTab).isFalse()
    }

    @Test fun moveReordersTabs() {
        val repo = TabRepository()
        val a = repo.tabs.value.first().id
        val b = repo.newTab("https://b.com").id
        val c = repo.newTab("https://c.com").id
        repo.move(from = 0, to = 2)
        assertThat(repo.tabs.value.map { it.id }).containsExactly(b, c, a).inOrder()
    }

    @Test fun tabsListIsImmutableSnapshot() {
        val repo = TabRepository()
        val snapshot = repo.tabs.value
        repo.newTab("https://d.com")
        assertThat(snapshot).hasSize(1)              // 旧快照不被就地修改
        assertThat(repo.tabs.value).hasSize(2)
    }

    @Test fun incognitoTabsAreFlagged() {
        val repo = TabRepository()
        val t = repo.newTab("https://e.com", incognito = true)
        assertThat(repo.tabs.value.first { it.id == t.id }.isIncognito).isTrue()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.domain.TabRepositoryTest"`
Expected: 编译失败 —— `Unresolved reference: TabRepository`

- [ ] **Step 3: 实现模型与仓库**

`BrowserTab.kt`：

```kotlin
package dev.aurora.browser.domain.model

data class BrowserTab(
    val id: String,
    val url: String? = null,
    val title: String = "新标签页",
    val isIncognito: Boolean = false,
    val groupId: String? = null,
) {
    val isNewTab: Boolean get() = url == null
}
```

`TabRepository.kt`：

```kotlin
package dev.aurora.browser.domain

import dev.aurora.browser.domain.model.BrowserTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 内存标签仓库。M2 不持久化——进程重启即清空（规格：持久化属 M3）。
 * 用不可变 List 快照驱动 UI，避免 Compose 静默失效。
 * 不变量：tabs 非空；activeId 始终指向 tabs 中的一项。
 */
class TabRepository {
    private val _tabs = MutableStateFlow(listOf(BrowserTab(id = newId())))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow(_tabs.value.first().id)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    val active: BrowserTab
        get() = _tabs.value.first { it.id == _activeId.value }

    private fun newId(): String = UUID.randomUUID().toString()

    fun newTab(url: String? = null, incognito: Boolean = false): BrowserTab {
        val tab = BrowserTab(id = newId(), url = url, isIncognito = incognito)
        _tabs.value = _tabs.value + tab
        _activeId.value = tab.id
        return tab
    }

    fun close(id: String) {
        val current = _tabs.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val remaining = current.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val blank = BrowserTab(id = newId())
            _tabs.value = listOf(blank)
            _activeId.value = blank.id
            return
        }
        _tabs.value = remaining
        if (_activeId.value == id) {
            // 关闭当前标签后落到右邻，越界则落到新的最后一个
            _activeId.value = remaining[minOf(index, remaining.lastIndex)].id
        }
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) _activeId.value = id
    }

    fun update(id: String, url: String? = null, title: String? = null) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id != id) tab
            else tab.copy(url = url ?: tab.url, title = title ?: tab.title)
        }
    }

    fun move(from: Int, to: Int) {
        val list = _tabs.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _tabs.value = list.toList()
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.domain.TabRepositoryTest"`
Expected: BUILD SUCCESSFUL，9 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(domain): add BrowserTab model and in-memory TabRepository"
```

---

### Task 8: 返回键决策 BackPressDispatcher（纯逻辑）

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/domain/BackPressDispatcher.kt`
- Test: `app/src/test/java/dev/aurora/browser/domain/BackPressDispatcherTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin）
- Produces:
  ```kotlin
  enum class BackOutcome { CLOSE_PANEL, EXIT_OMNIBOX, PAGE_BACK, ARM_EXIT, EXIT_NOW }

  class BackPressDispatcher(private val exitWindowMs: Long = 2000, private val clock: () -> Long = System::currentTimeMillis) {
      fun onBack(
          panelOpen: Boolean,
          omniboxFocused: Boolean,
          canGoBack: Boolean,
          /** 调用方在收到 ARM_EXIT 后提示「再按一次退出」。 */
      ): BackOutcome
  }
  ```
  语义：`panelOpen` 优先 → `omniboxFocused` → `canGoBack` → 双击退出。
  双击窗口内再次按返回得到 `EXIT_NOW`，超时则重新 `ARM_EXIT`。

- [ ] **Step 1: 写失败测试**

`BackPressDispatcherTest.kt`：

```kotlin
package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackPressDispatcherTest {
    private var now = 0L
    private fun dispatcher() = BackPressDispatcher(exitWindowMs = 2000, clock = { now })

    private fun BackPressDispatcher.press(
        panel: Boolean = false, omnibox: Boolean = false, canGoBack: Boolean = false,
    ) = onBack(panelOpen = panel, omniboxFocused = omnibox, canGoBack = canGoBack)

    @Test fun panelTakesPriorityOverEverything() {
        assertThat(dispatcher().press(panel = true, omnibox = true, canGoBack = true))
            .isEqualTo(BackOutcome.CLOSE_PANEL)
    }

    @Test fun omniboxClosesBeforePageBack() {
        assertThat(dispatcher().press(omnibox = true, canGoBack = true))
            .isEqualTo(BackOutcome.EXIT_OMNIBOX)
    }

    @Test fun pageBackUsedWhenPossible() {
        assertThat(dispatcher().press(canGoBack = true)).isEqualTo(BackOutcome.PAGE_BACK)
    }

    @Test fun firstBackWithNothingToDoArmsExit() {
        assertThat(dispatcher().press()).isEqualTo(BackOutcome.ARM_EXIT)
    }

    @Test fun secondBackInsideWindowExits() {
        val d = dispatcher()
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)
        now = 1500
        assertThat(d.press()).isEqualTo(BackOutcome.EXIT_NOW)
    }

    @Test fun secondBackAfterWindowRearms() {
        val d = dispatcher()
        d.press()
        now = 2500
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)
    }

    @Test fun handlingSomethingElseResetsTheArm() {
        val d = dispatcher()
        d.press()                                   // ARM_EXIT
        d.press(canGoBack = true)                   // PAGE_BACK 消耗掉这次返回
        now = 100
        assertThat(d.press()).isEqualTo(BackOutcome.ARM_EXIT)   // 重新计时，不直接退出
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.domain.BackPressDispatcherTest"`
Expected: 编译失败 —— `Unresolved reference: BackPressDispatcher`

- [ ] **Step 3: 实现**

`BackPressDispatcher.kt`：

```kotlin
package dev.aurora.browser.domain

enum class BackOutcome { CLOSE_PANEL, EXIT_OMNIBOX, PAGE_BACK, ARM_EXIT, EXIT_NOW }

/**
 * 返回键决策。规格顺序：关面板 → 退地址栏聚焦 → 网页后退 → 双击退出。
 * 抽成纯逻辑以便单测，不在 Composable 里写 if 链。
 */
class BackPressDispatcher(
    private val exitWindowMs: Long = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var armedAt: Long? = null

    fun onBack(panelOpen: Boolean, omniboxFocused: Boolean, canGoBack: Boolean): BackOutcome {
        if (panelOpen) return consume(BackOutcome.CLOSE_PANEL)
        if (omniboxFocused) return consume(BackOutcome.EXIT_OMNIBOX)
        if (canGoBack) return consume(BackOutcome.PAGE_BACK)

        val armed = armedAt
        val now = clock()
        return if (armed != null && now - armed <= exitWindowMs) {
            armedAt = null
            BackOutcome.EXIT_NOW
        } else {
            armedAt = now
            BackOutcome.ARM_EXIT
        }
    }

    /** 任何「真的做了事」的返回都会清掉计时，避免误退出。 */
    private fun consume(outcome: BackOutcome): BackOutcome {
        armedAt = null
        return outcome
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.domain.BackPressDispatcherTest"`
Expected: BUILD SUCCESSFUL，7 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(domain): add BackPressDispatcher with double-press exit"
```

---

### Task 9: WebView 封装

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/browser/WebViewContainer.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/browser/WebViewContainerTest.kt`

**Interfaces:**
- Consumes: 主题
- Produces:
  ```kotlin
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

  @Composable fun WebViewContainer(
      controller: BrowserController,
      modifier: Modifier = Modifier,
      url: String? = null,                       // null 表示新标签页，不加载
      onProgress: (Int) -> Unit = {},
      onTitleChange: (String) -> Unit = {},
      onUrlChange: (String) -> Unit = {},
      factory: ((android.content.Context) -> android.webkit.WebView)? = null,  // 测试注入
  )
  ```
  本文件是**唯一**允许 import `android.webkit.*` 的地方。

- [ ] **Step 1: 写失败测试**

`WebViewContainerTest.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import android.webkit.WebView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewContainerTest {
    @get:Rule val compose = createComposeRule()

    private class FakeController : BrowserController {
        var loaded: String? = null
        var reloads = 0
        override fun loadUrl(url: String) { loaded = url }
        override fun reload() { reloads++ }
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun canGoBack() = false
        override fun canGoForward() = false
    }

    @Test fun loadsRequestedUrlOnFirstComposition() {
        val controller = FakeController()
        compose.setContent {
            AuroraTheme { WebViewContainer(controller = controller, url = "https://a.com") }
        }
        compose.onRoot().assertExists()
        // 断言 WebView 真的被创建并收到 URL
        val web = ShadowWebView.getLastCreatedWebView() as WebView
        assertThat(ShadowWebView.getLastLoadedUrl()).isEqualTo("https://a.com")
        assertThat(web).isNotNull()
    }

    @Test fun blankUrlDoesNotLoadAnything() {
        val controller = FakeController()
        compose.setContent { AuroraTheme { WebViewContainer(controller = controller, url = null) } }
        compose.onRoot().assertExists()
        assertThat(ShadowWebView.getLastLoadedUrl()).isNull()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.WebViewContainerTest"`
Expected: 编译失败 —— `Unresolved reference: WebViewContainer`

- [ ] **Step 3: 实现**

`WebViewContainer.kt`：

```kotlin
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

interface BrowserController {
    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
}

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
            override fun onReceivedTitle(view: WebView, title: CharSequence?) =
                onTitleChange(title?.toString().orEmpty())
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
```

> `ShadowWebView.getLastLoadedUrl()` 在 Robolectric 中记录最近一次 `loadUrl`。
> 若该静态方法在你的 Robolectric 版本上不可用，改用
> `Shadows.shadowOf(web).lastLoadedUrl`。两种写法二选一，不要都留着。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.WebViewContainerTest"`
Expected: BUILD SUCCESSFUL，2 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(browser): wrap WebView behind a testable BrowserController"
```

---

### Task 10: 浏览界面状态机与 ViewModel

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/browser/BrowserSurface.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/browser/BrowserViewModel.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/browser/BrowserViewModelTest.kt`

**Interfaces:**
- Consumes: `TabRepository`（Task 7）、`BackPressDispatcher`（Task 8）
- Produces:
  ```kotlin
  sealed interface BrowserSurface {
      data object Page : BrowserSurface
      data object Omnibox : BrowserSurface
      data object TabSwitcher : BrowserSurface
  }

  class BrowserViewModel(
      private val tabs: TabRepository = TabRepository(),
      private val back: BackPressDispatcher = BackPressDispatcher(),
  ) : ViewModel() {
      val surface: StateFlow<BrowserSurface>
      val tabsState: StateFlow<List<BrowserTab>>
      val activeTab: StateFlow<BrowserTab>
      val menuOpen: StateFlow<Boolean>
      val exitHintVisible: StateFlow<Boolean>
      val query: StateFlow<String>
      val suggestions: StateFlow<List<String>>          // 由 query 派生

      fun openOmnibox(); fun closeOmnibox(); fun setQuery(q: String)
      fun openTabSwitcher(); fun closeTabSwitcher()
      fun openMenu(); fun closeMenu()
      fun newTab(url: String? = null); fun closeTab(id: String); fun selectTab(id: String)
      fun navigate(url: String)                          // 关掉 omnibox + 落到 Page
      fun onBack(canGoBack: Boolean): BackOutcome        // 由 Activity 调用
  }
  ```

- [ ] **Step 1: 写失败测试**

`BrowserViewModelTest.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.TabRepository
import org.junit.Test

class BrowserViewModelTest {
    private fun vm() = BrowserViewModel(tabs = TabRepository(), back = dev.aurora.browser.domain.BackPressDispatcher())

    @Test fun startsOnPageSurface() {
        assertThat(vm().surface.value).isEqualTo(BrowserSurface.Page)
    }

    @Test fun navigatingClosesOmniboxAndLandsOnPage() {
        val vm = vm()
        vm.openOmnibox()
        vm.navigate("https://a.com")
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
        assertThat(vm.activeTab.value.url).isEqualTo("https://a.com")
    }

    @Test fun newTabGoesToBlankPageNotOmnibox() {
        val vm = vm()
        vm.newTab()
        assertThat(vm.activeTab.value.isNewTab).isTrue()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
    }

    @Test fun menuAndTabSwitcherAreIndependentOfSurface() {
        val vm = vm()
        vm.openTabSwitcher()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)
        vm.openMenu()
        assertThat(vm.menuOpen.value).isTrue()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)   // 面板不覆盖 surface
    }

    @Test fun suggestionsFilterByQuery() {
        val vm = vm()
        vm.setQuery("com")
        assertThat(vm.suggestions.value).isNotEmpty()
        vm.setQuery("")
        assertThat(vm.suggestions.value).isEmpty()
    }

    @Test fun backClosesMenuBeforeSwitchingSurface() {
        val vm = vm()
        vm.openTabSwitcher()
        vm.openMenu()
        assertThat(vm.onBack(canGoBack = false)).isEqualTo(BackOutcome.CLOSE_PANEL)
        assertThat(vm.menuOpen.value).isFalse()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)
    }

    @Test fun backExitsOmniboxThenUsesPageBack() {
        val vm = vm()
        vm.openOmnibox()
        assertThat(vm.onBack(canGoBack = true)).isEqualTo(BackOutcome.EXIT_OMNIBOX)
        assertThat(vm.onBack(canGoBack = true)).isEqualTo(BackOutcome.PAGE_BACK)
    }

    @Test fun backFromTabSwitcherReturnsToPage() {
        val vm = vm()
        vm.openTabSwitcher()
        assertThat(vm.onBack(canGoBack = false)).isEqualTo(BackOutcome.CLOSE_PANEL)
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.BrowserViewModelTest"`
Expected: 编译失败 —— `Unresolved reference: BrowserViewModel`

- [ ] **Step 3: 实现状态机与 ViewModel**

`BrowserSurface.kt`：

```kotlin
package dev.aurora.browser.ui.browser

/**
 * 浏览界面只有一个「面」，避免路由栈与浏览器手势语义打架。
 * 菜单/面板是叠加层，用独立布尔量表达，不占用 surface。
 */
sealed interface BrowserSurface {
    data object Page : BrowserSurface
    data object Omnibox : BrowserSurface
    data object TabSwitcher : BrowserSurface
}
```

`BrowserViewModel.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.BackPressDispatcher
import dev.aurora.browser.domain.TabRepository
import dev.aurora.browser.domain.model.BrowserTab
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val SUGGESTION_POOL = listOf(
    "Compose 延迟布局 LazyLayout",
    "Compose 共享元素转场",
    "Compose 性能检查清单",
    "developer.android.com",
    "juejin.cn",
    "m3.material.io",
)

class BrowserViewModel(
    private val tabs: TabRepository = TabRepository(),
    private val back: BackPressDispatcher = BackPressDispatcher(),
) : ViewModel() {

    private val _surface = MutableStateFlow<BrowserSurface>(BrowserSurface.Page)
    val surface: StateFlow<BrowserSurface> = _surface.asStateFlow()

    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val suggestions: StateFlow<List<String>> = _query
        .map { q ->
            if (q.isBlank()) emptyList()
            else SUGGESTION_POOL.filter { it.contains(q, ignoreCase = true) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _exitHintVisible = MutableStateFlow(false)
    val exitHintVisible: StateFlow<Boolean> = _exitHintVisible.asStateFlow()

    val tabsState: StateFlow<List<BrowserTab>> = tabs.tabs
    val activeTab: StateFlow<BrowserTab> = combine(tabs.tabs, tabs.activeId) { list, id ->
        list.first { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, tabs.active)

    fun openOmnibox() { _surface.value = BrowserSurface.Omnibox }
    fun closeOmnibox() { _surface.value = BrowserSurface.Page; _query.value = "" }
    fun setQuery(q: String) { _query.value = q }
    fun openTabSwitcher() { _surface.value = BrowserSurface.TabSwitcher }
    fun closeTabSwitcher() { _surface.value = BrowserSurface.Page }
    fun openMenu() { _menuOpen.value = true }
    fun closeMenu() { _menuOpen.value = false }

    fun newTab(url: String? = null) {
        tabs.newTab(url)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
    }

    fun closeTab(id: String) = tabs.close(id)
    fun selectTab(id: String) { tabs.select(id); _surface.value = BrowserSurface.Page }
    fun updateActiveTab(url: String? = null, title: String? = null) =
        tabs.update(tabs.activeId.value, url = url, title = title)

    fun navigate(url: String) {
        tabs.update(tabs.activeId.value, url = url)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        _query.value = ""
    }

    /** 由 Activity 的 BackHandler 调用。 */
    fun onBack(canGoBack: Boolean): BackOutcome {
        val outcome = back.onBack(
            panelOpen = _menuOpen.value || _surface.value == BrowserSurface.TabSwitcher,
            omniboxFocused = _surface.value == BrowserSurface.Omnibox,
            canGoBack = canGoBack,
        )
        when (outcome) {
            BackOutcome.CLOSE_PANEL -> if (_menuOpen.value) _menuOpen.value = false
                                        else _surface.value = BrowserSurface.Page
            BackOutcome.EXIT_OMNIBOX -> closeOmnibox()
            BackOutcome.ARM_EXIT -> { _exitHintVisible.value = true; armExitHintReset() }
            else -> Unit
        }
        return outcome
    }

    private fun armExitHintReset() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _exitHintVisible.value = false
        }
    }
}
```

> `BackOutcome.PAGE_BACK` 由调用方（Activity/Composable）真正执行 `controller.goBack()`——
> ViewModel 不持有 WebView。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.BrowserViewModelTest"`
Expected: BUILD SUCCESSFUL，8 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(browser): add BrowserSurface state machine and BrowserViewModel"
```

---

### Task 11: 标签管理界面

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/browser/TabSwitcherScreen.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/browser/TabSwitcherScreenTest.kt`

**Interfaces:**
- Consumes: `BottomToolbar`（Task 4）、`SegmentedControl`（Task 6）、`BrowserTab`（Task 7）
- Produces:
  ```kotlin
  enum class TabLayout { GRID, LIST }

  @Composable fun TabSwitcherScreen(
      tabs: List<BrowserTab>, activeId: String,
      onSelect: (String) -> Unit, onClose: (String) -> Unit,
      onNewTab: () -> Unit, onCloseAll: () -> Unit, onDone: () -> Unit,
      modifier: Modifier = Modifier,
      initialLayout: TabLayout = TabLayout.GRID,
  )
  ```

- [ ] **Step 1: 写失败测试**

`TabSwitcherScreenTest.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabSwitcherScreenTest {
    @get:Rule val compose = createComposeRule()

    private val tabs = listOf(
        BrowserTab("a", "https://a.com", "A 站"),
        BrowserTab("b", "https://b.com", "B 站"),
        BrowserTab("c", null, "新标签页"),
    )

    private fun render(
        onSelect: (String) -> Unit = {}, onClose: (String) -> Unit = {},
        onNewTab: () -> Unit = {}, onCloseAll: () -> Unit = {}, onDone: () -> Unit = {},
    ) = compose.setContent {
        AuroraTheme {
            TabSwitcherScreen(
                tabs = tabs, activeId = "a",
                onSelect = onSelect, onClose = onClose,
                onNewTab = onNewTab, onCloseAll = onCloseAll, onDone = onDone,
            )
        }
    }

    @Test fun showsTabCountIncludingSingular() {
        render()
        compose.onNodeWithText("3").assertExists()
    }

    @Test fun rendersOneCloseButtonPerTab() {
        render()
        compose.onAllNodesWithContentDescription("关闭标签页").assertCountEquals(3)
    }

    @Test fun gridIsDefaultAndListToggleExists() {
        render()
        compose.onNodeWithText("网格").assertExists()
        compose.onNodeWithText("列表").assertExists()
    }

    @Test fun tappingClosePassesTheRightTabId() {
        var closed: String? = null
        render(onClose = { closed = it })
        compose.onAllNodesWithContentDescription("关闭标签页")[1].performClick()
        assertThat(closed).isEqualTo("b")
    }

    @Test fun tappingNewTabFiresCallback() {
        var n = 0
        render(onNewTab = { n++ })
        compose.onNodeWithContentDescription("新建标签页").performClick()
        assertThat(n).isEqualTo(1)
    }

    @Test fun closeAllFiresCallback() {
        var n = 0
        render(onCloseAll = { n++ })
        compose.onNodeWithContentDescription("关闭全部标签页").performClick()
        assertThat(n).isEqualTo(1)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.TabSwitcherScreenTest"`
Expected: 编译失败 —— `Unresolved reference: TabSwitcherScreen`

- [ ] **Step 3: 实现**

`TabSwitcherScreen.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

enum class TabLayout { GRID, LIST }

@Composable
fun TabSwitcherScreen(
    tabs: List<BrowserTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    initialLayout: TabLayout = TabLayout.GRID,
) {
    val c = Aurora.colors
    var layout by remember { mutableStateOf(initialLayout) }

    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
        // 顶栏：计数 + 视图切换 + 新建 + 关闭全部
        Row(
            modifier = Modifier.fillMaxWidth().padding(AuroraSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            Text(
                text = tabs.size.toString(),
                color = c.text,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
            Text("个标签页", color = c.textMuted)
            Spacer(Modifier.weight(1f))
            SegmentedControl(
                options = listOf(TabLayout.GRID to "网格", TabLayout.LIST to "列表"),
                selected = layout,
                onSelect = { layout = it },
            )
        }

        // 每屏只允许一个滚动容器
        Box(modifier = Modifier.weight(1f)) {
            when (layout) {
                TabLayout.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        TabCard(tab, selected = tab.id == activeId,
                            onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
                    }
                }
                TabLayout.LIST -> LazyColumn(
                    contentPadding = PaddingValues(vertical = AuroraSpacing.s),
                ) {
                    listItems(tabs, key = { it.id }) { tab ->
                        TabRowItem(tab, selected = tab.id == activeId,
                            onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
                    }
                }
            }
        }

        // 底部：关闭全部 / 新建 / 完成
        Row(
            modifier = Modifier.fillMaxWidth().padding(AuroraSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            IconAction(AuroraIcons.Trash, "关闭全部标签页", onCloseAll)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.brand)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNewTab,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Add),
                    contentDescription = "新建标签页",
                    tint = c.onBrand,
                )
            }
            Spacer(Modifier.weight(1f))
            IconAction(AuroraIcons.ChevronRight, "完成", onDone)
        }
    }
}

@Composable
private fun IconAction(icon: Int, label: String, onClick: () -> Unit) {
    val c = Aurora.colors
    Box(
        modifier = Modifier
            .size(AuroraSpacing.touchTarget)
            .clip(RoundedCornerShape(AuroraRadius.full))
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = c.textSecondary)
    }
}

@Composable
private fun TabCard(tab: BrowserTab, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = Aurora.colors
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.surface)
            .border(
                1.dp,
                if (selected) c.brand else c.divider,
                RoundedCornerShape(AuroraRadius.m),
            )
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(96.dp).background(c.bgElevated)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AuroraSpacing.s)
                    .size(28.dp)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.scrim)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    )
                    .semantics { contentDescription = "关闭标签页" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Close),
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = tab.title,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(AuroraSpacing.s),
        )
    }
}

@Composable
private fun TabRowItem(tab: BrowserTab, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = Aurora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (selected) c.surfaceHover else c.bg)
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tab.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = tab.url?.let { hostOf(it) } ?: "新标签页",
                color = c.textDisabled,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        IconAction(AuroraIcons.Close, "关闭标签页", onClose)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.TabSwitcherScreenTest"`
Expected: BUILD SUCCESSFUL，6 tests passed

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(browser): add tab switcher with grid and list layouts"
```

---

### Task 12: 地址栏聚焦态、主页与顶层组装

**Files:**
- Create: `app/src/main/java/dev/aurora/browser/ui/browser/OmniboxScreen.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/dev/aurora/browser/ui/browser/BrowserScreen.kt`
- Modify: `app/src/main/java/dev/aurora/browser/MainActivity.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/browser/OmniboxScreenTest.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/browser/BrowserScreenTest.kt`
- Test: `app/src/test/java/dev/aurora/browser/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: 前 11 个任务的全部产物
- Produces:
  ```kotlin
  @Composable fun OmniboxScreen(
      query: String, suggestions: List<String>,
      onQueryChange: (String) -> Unit, onSubmit: (String) -> Unit,
      onDismiss: () -> Unit, modifier: Modifier = Modifier)

  data class Shortcut(val label: String, val url: String)

  @Composable fun HomeScreen(
      onNavigate: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier,
      shortcuts: List<Shortcut> = DefaultShortcuts, showFeed: Boolean = true)

  @Composable fun BrowserScreen(vm: BrowserViewModel = viewModel(), modifier: Modifier = Modifier)
  ```
  `OmniboxScreen` 语义：输入框描述为 `"地址栏输入"`；建议行描述为 `"建议：<文本>"`；
  快捷动作按钮为 `"扫码"`、`"语音"`、`"粘贴并前往"`、`"无痕"`。

- [ ] **Step 1: 写失败测试**

`OmniboxScreenTest.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniboxScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersSuggestionsAndQuickActions() {
        compose.setContent {
            AuroraTheme {
                OmniboxScreen(
                    query = "com",
                    suggestions = listOf("Compose 延迟布局", "Compose 转场"),
                    onQueryChange = {}, onSubmit = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("建议：Compose 延迟布局").assertExists()
        compose.onNodeWithContentDescription("扫码").assertExists()
        compose.onNodeWithContentDescription("语音").assertExists()
        compose.onNodeWithContentDescription("粘贴并前往").assertExists()
        compose.onNodeWithContentDescription("无痕").assertExists()
    }

    @Test fun typingReportsQueryChanges() {
        val typed = mutableListOf<String>()
        compose.setContent {
            AuroraTheme {
                OmniboxScreen("", emptyList(), onQueryChange = { typed += it }, onSubmit = {}, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("地址栏输入").performTextInput("a")
        assertThat(typed).contains("a")
    }

    @Test fun tappingSuggestionSubmitsIt() {
        var submitted: String? = null
        compose.setContent {
            AuroraTheme {
                OmniboxScreen("c", listOf("Compose"), onQueryChange = {}, onSubmit = { submitted = it }, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("建议：Compose").performClick()
        assertThat(submitted).isEqualTo("Compose")
    }

    @Test fun dismissButtonFires() {
        var n = 0
        compose.setContent {
            AuroraTheme { OmniboxScreen("", emptyList(), {}, {}, onDismiss = { n++ }) }
        }
        compose.onNodeWithContentDescription("关闭地址栏").performClick()
        assertThat(n).isEqualTo(1)
    }
}
```

`HomeScreenTest.kt`：

```kotlin
package dev.aurora.browser.ui.home

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
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val shortcuts = listOf(
        Shortcut("知乎", "https://zhihu.com"),
        Shortcut("哔哩哔哩", "https://bilibili.com"),
    )

    @Test fun rendersShortcutsAndSearchEntry() {
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("知乎").assertExists()
        compose.onNodeWithText("哔哩哔哩").assertExists()
        compose.onNodeWithContentDescription("搜索或输入网址").assertExists()
    }

    @Test fun tappingShortcutNavigatesToItsUrl() {
        var url: String? = null
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = { url = it }, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("知乎").performClick()
        assertThat(url).isEqualTo("https://zhihu.com")
    }

    @Test fun tappingSearchBarOpensOmnibox() {
        var n = 0
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = { n++ }, shortcuts = shortcuts) }
        }
        compose.onNodeWithContentDescription("搜索或输入网址").performClick()
        assertThat(n).isEqualTo(1)
    }

    @Test fun feedCanBeHidden() {
        compose.setContent {
            AuroraTheme {
                HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts, showFeed = false)
            }
        }
        compose.onNodeWithText("为你推荐").assertDoesNotExist()
    }
}
```

`BrowserScreenTest.kt`：

顶层组装的自动化替身。原计划把 `BrowserScreen` 交给手动冒烟验证，但手动验证跑不了 CI、
也跑不进 subagent——这里用 Robolectric 覆盖它的可见性规则。默认标签是空白新标签页，
所以这几条路径都不会创建 `WebView`，在 Robolectric 下是确定性的。

```kotlin
package dev.aurora.browser.ui.browser

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
class BrowserScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun render(vm: BrowserViewModel) {
        compose.setContent { AuroraTheme { BrowserScreen(vm = vm) } }
    }

    @Test fun homeSurfaceShowsToolbarWithTabCount() {
        render(BrowserViewModel())
        compose.onNodeWithContentDescription("主页").assertExists()
        compose.onNodeWithContentDescription("标签页，1 个").assertExists()
    }

    @Test fun omniboxSurfaceHidesFloatingToolbar() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openOmnibox()
        compose.onNodeWithContentDescription("地址栏输入").assertExists()
        compose.onNodeWithContentDescription("主页").assertDoesNotExist()
    }

    @Test fun tabSwitcherHidesFloatingToolbar() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openTabSwitcher()
        compose.onNodeWithText("网格").assertExists()
        compose.onNodeWithContentDescription("主页").assertDoesNotExist()
    }

    @Test fun menuSheetOffersOnlyMilestoneTwoActions() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openMenu()
        compose.onNodeWithText("新建标签页").assertExists()
        compose.onNodeWithText("刷新").assertExists()
        compose.onNodeWithText("关闭全部标签页").assertExists()
        compose.onNodeWithText("标签页数量").assertExists()
    }

    @Test fun menuSheetNewTabRowClosesTheSheetAndAddsATab() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openMenu()
        compose.onNodeWithText("新建标签页").performClick()
        compose.onNodeWithText("新建标签页").assertDoesNotExist()
        assertThat(vm.tabsState.value).hasSize(2)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.aurora.browser.ui.browser.OmniboxScreenTest" --tests "dev.aurora.browser.ui.browser.BrowserScreenTest" --tests "dev.aurora.browser.ui.home.HomeScreenTest"`
Expected: 编译失败 —— `Unresolved reference: OmniboxScreen` / `BrowserScreen` / `HomeScreen`

- [ ] **Step 3: 实现 OmniboxScreen**

`OmniboxScreen.kt`：

```kotlin
package dev.aurora.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

@Composable
fun OmniboxScreen(
    query: String,
    suggestions: List<String>,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aurora.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(modifier = modifier.fillMaxSize().background(c.bg).padding(AuroraSpacing.m)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(AuroraRadius.m))
                .background(c.surface)
                .border(1.5.dp, c.brand, RoundedCornerShape(AuroraRadius.m))
                .padding(horizontal = AuroraSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            Icon(painterResource(AuroraIcons.Search), null, tint = c.brand, modifier = Modifier.size(18.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索或输入网址", color = c.textDisabled)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = c.text),
                    cursorBrush = SolidColor(c.brand),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { if (query.isNotBlank()) onSubmit(query) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "地址栏输入" },
                )
            }
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "关闭地址栏" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(AuroraIcons.Close), null, tint = c.textMuted, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(AuroraSpacing.m))

        // 唯一滚动容器
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(suggestions, key = { it }) { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clickable(
                            role = Role.Button,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSubmit(s) },
                        )
                        .semantics { contentDescription = "建议：$s" }
                        .padding(horizontal = AuroraSpacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                ) {
                    Icon(painterResource(AuroraIcons.Search), null, tint = c.textMuted, modifier = Modifier.size(19.dp))
                    Text(s, color = c.text, modifier = Modifier.weight(1f))
                    Icon(painterResource(AuroraIcons.ChevronRight), null, tint = c.textDisabled, modifier = Modifier.size(16.dp))
                }
            }
            item {
                Spacer(Modifier.height(AuroraSpacing.l))
                Row(horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s)) {
                    QuickAction(AuroraIcons.QrCode, "扫码", Modifier.weight(1f)) {}
                    QuickAction(AuroraIcons.Mic, "语音", Modifier.weight(1f)) {}
                    QuickAction(AuroraIcons.Clipboard, "粘贴并前往", Modifier.weight(1f)) {}
                    QuickAction(AuroraIcons.Incognito, "无痕", Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = Aurora.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.bgElevated)
            .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.m))
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = AuroraSpacing.m)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Icon(painterResource(icon), null, tint = c.brand, modifier = Modifier.size(21.dp))
        Text(label, color = c.textSecondary)
    }
}
```

- [ ] **Step 4: 实现 HomeScreen**

`HomeScreen.kt`：

```kotlin
package dev.aurora.browser.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

data class Shortcut(val label: String, val url: String)

val DefaultShortcuts = listOf(
    Shortcut("知乎", "https://www.zhihu.com"),
    Shortcut("哔哩哔哩", "https://www.bilibili.com"),
    Shortcut("微博", "https://weibo.com"),
    Shortcut("GitHub", "https://github.com"),
    Shortcut("掘金", "https://juejin.cn"),
    Shortcut("淘宝", "https://www.taobao.com"),
)

private data class FeedItem(val title: String, val source: String, val tag: String)
private val DefaultFeed = listOf(
    FeedItem("Compose 1.11 稳定版发布：延迟布局与共享元素转场", "Android Weekly", "技术"),
    FeedItem("为什么现代浏览器都在重做标签管理？", "少数派", "产品"),
    FeedItem("一次点击背后：追踪器拦截的技术原理拆解", "InfoQ", "隐私"),
)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    shortcuts: List<Shortcut> = DefaultShortcuts,
    showFeed: Boolean = true,
) {
    val c = Aurora.colors
    // 唯一滚动容器：快捷入口网格嵌在 LazyColumn 的一个 item 里，用非滚动网格
    LazyColumn(modifier = modifier.fillMaxSize().background(c.bg)) {
        item {
            Box(
                modifier = Modifier
                    .padding(AuroraSpacing.m)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.surfaceHover)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSearch,
                    )
                    .semantics { contentDescription = "搜索或输入网址" }
                    .padding(horizontal = AuroraSpacing.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s)) {
                    Icon(painterResource(AuroraIcons.Search), null, tint = c.textDisabled,
                        modifier = Modifier.size(18.dp))
                    Text("搜索或输入网址", color = c.textDisabled)
                }
            }
        }
        item {
            SectionTitle("快捷入口")
            Column(modifier = Modifier.padding(horizontal = AuroraSpacing.l)) {
                shortcuts.chunked(4).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = AuroraSpacing.s),
                        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s)) {
                        row.forEach { s ->
                            ShortcutTile(s, Modifier.weight(1f)) { onNavigate(s.url) }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (showFeed) {
            item { SectionTitle("为你推荐") }
            items(DefaultFeed.size) { i ->
                val f = DefaultFeed[i]
                Row(
                    modifier = Modifier
                        .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AuroraRadius.m))
                        .background(c.surface)
                        .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.m))
                        .padding(AuroraSpacing.m),
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                ) {
                    Box(modifier = Modifier.size(width = 88.dp, height = 66.dp)
                        .clip(RoundedCornerShape(AuroraRadius.s)).background(c.bgElevated))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AuroraSpacing.xs)) {
                        Text(f.title, color = c.text, maxLines = 2)
                        Text("${f.tag} · ${f.source}", color = c.textDisabled,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }   // 给悬浮工具栏留出空间，避免遮挡内容
    }
}

@Composable
private fun SectionTitle(text: String) {
    val c = Aurora.colors
    Text(
        text = text,
        color = c.textMuted,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(
            start = AuroraSpacing.l, end = AuroraSpacing.l,
            top = AuroraSpacing.xl, bottom = AuroraSpacing.s,
        ),
    )
}

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Aurora.colors
    Column(
        modifier = modifier
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = s.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceHover),
            contentAlignment = Alignment.Center,
        ) {
            Text(s.label.take(1), color = c.text)
        }
        Text(s.label, color = c.textMuted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1)
    }
}
```

> `HomeScreenTest` 里用 `onNodeWithText("知乎")` 同时匹配到图标里的「知」不会冲突——
> 但 `ShortcutTile` 的首字方块和标签都含文字，若断言失败，改用
> `onNodeWithContentDescription("知乎")` 定位，语义描述已在上面写好。

- [ ] **Step 5: 实现顶层组装与 MainActivity**

> 本任务不引入 `AppContainer` / `Application` 子类。M2 只有一个 `BrowserViewModel`，
> 它自己持有 `TabRepository`（构造参数带默认值），`viewModel()` 的无参构造即可满足。
> 多一个容器只会让「仓库到底是不是同一个实例」变成疑问。等 M3 接入 Room 持久化、
> 依赖真正超过一个时再引入，届时用一个 `ViewModelProvider.Factory` 把容器喂给 ViewModel。

`BrowserScreen.kt`：

```kotlin
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
```

> `MutableBrowserController` 是 `WebViewContainer` 里 `BindableController` 的具体实现，
> 放在 `BrowserScreen.kt` 底部：
> ```kotlin
> class MutableBrowserController : BindableController {
>     private var impl: BrowserController? = null
>     override fun bind(impl: BrowserController) { this.impl = impl }
>     override fun unbind() { impl = null }
>     override fun loadUrl(url: String) { impl?.loadUrl(url) }
>     override fun reload() { impl?.reload() }
>     override fun stopLoading() { impl?.stopLoading() }
>     override fun goBack() { impl?.goBack() }
>     override fun goForward() { impl?.goForward() }
>     override fun canGoBack() = impl?.canGoBack() ?: false
>     override fun canGoForward() = impl?.canGoForward() ?: false
> }
> ```
> 它实现 `BindableController`（Task 9 定义），所以 `WebViewContainer` 的
> `(controller as? BindableController)?.bind(impl)` 能生效。

`MainActivity.kt`：

```kotlin
package dev.aurora.browser

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.aurora.browser.ui.browser.BrowserScreen
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用只有深色一套令牌，系统栏必须固定成「深底浅字」。
        // 用默认的 auto 会在系统为浅色时把状态栏图标刷成深色，落在深色背景上等于隐形。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            AuroraTheme {
                // color = colors.bg：Surface 默认取 colorScheme.surface，
                // 与规格的页面底色 bg 不是同一个值，必须显式指定。
                Surface(modifier = Modifier.fillMaxSize(), color = Aurora.colors.bg) {
                    // 状态栏/手势条避让在 BrowserScreen 内部统一处理
                    BrowserScreen()
                }
            }
        }
    }
}
```

- [ ] **Step 6: 跑全部测试确认通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部任务累计 71 tests passed

> 本任务新增的 `OmniboxScreenTest`（4 例）、`HomeScreenTest`（4 例）、`BrowserScreenTest`（5 例）
> 共 13 例；加上前 11 个任务的 58 例，合计 71 例。

- [ ] **Step 7: 手动冒烟验证**

`BrowserScreenTest` 已覆盖可见性规则；这一步验证的是它测不到的部分——真实 WebView、
系统键盘、手势条避让。需要一台设备或模拟器。

Run: `./gradlew :app:installDebug` 后启动应用，确认：
1. 启动落在主页，快捷入口与信息流可见，底部悬浮工具栏完整不被手势条遮挡；
2. 点快捷入口能打开网页，顶部地址栏显示域名 + 锁图标，页面加载时顶部出现 2dp 进度条并在完成后消失；
3. 点标签槽进入标签网格，能新建/关闭/切换；
4. 点「更多」弹出底部面板，含「新建标签页 / 刷新 / 关闭全部标签页 / 标签页数量」，点遮罩关闭；
5. 返回键顺序：底部面板 → 标签网格 → 主页；主页再按一次出现「再按一次退出」，2 秒内再按退出。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat(browser): wire omnibox, home, and top-level BrowserScreen"
```

---

## 验证清单（全部任务完成后）

- [ ] `./gradlew :app:testDebugUnitTest` 全绿
- [ ] `./gradlew :app:lintDebug` 无 error（warning 可接受）
- [ ] 深色主题下正文对比 ≥4.5:1（抽查 `textMuted #94A3B8` on `bg #0F172A` = 7.0:1）
- [ ] 所有可点目标 ≥48dp（`BottomToolbarTest` 已覆盖工具栏；其余组件用同一断言方式抽查）
- [ ] 本里程碑不引入自定义动画，所有转场走 Material 3 默认时长（`AuroraMotion` 令牌为 M3/M4 的过渡动画预留，本期无调用点）
- [ ] 每屏只有一个滚动容器（`HomeScreen` 的快捷入口网格用 `Column.chunked`，非嵌套滚动）
- [ ] 无 emoji 图标；无组件内硬编码 hex（`grep -rn "0xFF" app/src/main/java --include=*.kt` 只应命中 `ui/theme/Tokens.kt`）
- [ ] 系统字号放大到 200% 时无截断（手动验证 `SettingRow`、`OmniboxBar`、`BottomToolbar`）

## 明确不在本计划范围

以下属规格中的 M3/M4，需要各自独立的实现计划：

- **M3**：广告与追踪拦截（计数、分类、白名单）、阅读模式、下载管理、书签与历史、标签持久化（Room）
- **M4**：无痕模式的完整实现、设置页、扩展、同步、浅色主题、无障碍打磨、性能

本计划刻意不含：Hilt（M2 由 `BrowserViewModel` 自持唯一依赖，容器留到 M3 依赖变多时再引入）、
navigation-compose（状态机替代）、`Application` 子类、多语言资源、浅色主题分支。