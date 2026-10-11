# Aurora Browser（极光浏览器）

一款「全能功能型」安卓浏览器：在单手可及的界面上，把标签管理、拦截、阅读、下载、书签这些能力收进一个克制的深色界面里。

- **技术栈**：Kotlin + Jetpack Compose（Material 3）+ Android WebView
- **平台**：Android 8.0+（API 26+），targetSdk 35
- **视觉**：现代简约 · 深色优先（Dark Mode / OLED）

## 当前进度

| 模块 | 说明 |
|---|---|
| 设计系统 | 语义化令牌（颜色/间距/圆角/动效）+ 深色主题，全部界面不出现裸色值 |
| 图标 | 34 个 Phosphor 图标生成 VectorDrawable，无第三方图标库依赖 |
| 底部悬浮工具栏 | 前进/后退/主页/标签/菜单，触摸目标 ≥48dp，相邻目标间距 ≥8dp |
| 工具栏留白 | 工具栏浮在内容之上，应用内页面与网页末尾都按工具栏高度留白，滚到底不会把最后一行压在工具栏底下 |
| 地址栏 | 关键词搜索与网址自动识别；输入框建议含搜索/网址/书签/历史四类；域名 + 站点身份展示；加载中切换为「停止加载」 |
| 搜索引擎 | 默认 Bing（境内境外都可达），可在设置里换成百度 / Google |
| 标签管理 | 卡片/列表两种布局，切换、关闭、关闭全部、新建；**每个标签独立 WebView**，前进/后退历史互不干扰 |
| 无痕标签 | 标签级隔离：不写历史、关闭 DOM 存储与缓存、禁用第三方 Cookie，界面顶部常驻无痕提示条 |
| 主页 | 搜索入口 + 快捷入口网格（推荐信息流已移除） |
| 书签与历史 | 收藏星标、书签/历史分段列表、过滤、删除、清空；历史上限 500 条 |
| 下载管理 | 交给系统 `DownloadManager`（后台下载 + 通知栏进度），进行中/已完成分段，支持打开与删除 |
| 视频全屏 | 接管 `WebChromeClient` 的全屏回调：视频铺满窗口、隐藏状态栏与导航栏、横屏可铺到刘海区，返回键先退出全屏 |
| 视频下载 | 进页面提取 `<video>` / `<source>` 地址（HTML5 视频不会触发 `DownloadListener`），多源时让用户选；blob/MSE 流式地址如实标注「不支持」 |
| 投屏 | 屏幕镜像（送到系统投屏页）与投屏播放视频（交给能播 video 的应用）；详见「明确没做的」 |
| 阅读模式 | 页面内提取正文，去掉装饰，支持字号缩放 |
| 隐私与安全 | 本页追踪器拦截计数、HTTPS 状态、按主机名拦截常见统计/广告域名（35 条规则） |
| 设置 | 外观、隐私与安全、搜索、下载、无障碍、关于 |
| 分享 | 系统分享面板分享当前网址 |
| 快捷动作 | 粘贴并前往、语音搜索、扫码、无痕（都接系统能力，缺应用时给出提示而不是静默失败） |
| WebView 加固 | 外部 scheme（tel:/mailto:/intent:）交给系统；关闭文件与 content:// 访问、多窗口；证书错误一律拦截；主文档加载失败有整页兜底与重试 |
| 浏览状态机 | 地址栏聚焦 / 标签网格 / 网页三态与返回键决策（关面板 → 退地址栏 → 网页后退 → 双击退出） |
| 持久化 | 书签、历史与偏好存 `SharedPreferences`（`org.json` 序列化），损坏数据当空列表处理，不在启动路径上崩 |

## 明确没做的

这些不是「以后再说」，而是当前版本确实没有的能力，写在这里免得装完才发现：

- **扩展**：Android WebView 没有扩展 API，`.crx`/`manifest.json` 那套只存在于桌面 Chromium。这不是排期问题，是平台不支持，所以菜单里没有扩展入口。
- **Chromecast 原生投屏**：没有接入 Google Cast SDK。它要求注册 Cast app id 并依赖 Google Play 服务，在没有 GMS 的设备上直接不可用，还会把闭源依赖引进来。也试过 `androidx.mediarouter` 的设备选择器，但它的 `MediaRouteChooserDialog` 继承 `AppCompatDialog`，要整个应用套上 AppCompat 主题——对一个纯 Compose 应用来说是为一颗按钮换掉主题体系，不划算，于是改成零依赖实现：屏幕镜像交给系统的投屏页，视频交给能播 `video` 通配 MIME 的应用（各类投屏播放器都在此列）。
- **逐站权限授权**：相机 / 麦克风 / 定位当前一律拒绝，没有「本站允许」的授权弹窗。唯一的例外是 DRM 的 protected media id——全屏播放加密流要用，且不是用户可见权限。
- **标签持久化**：进程被系统回收后标签页不会恢复，重启是全新一个标签页。
- **同步、账号、查找页内文字、桌面模式、翻译、拦截规则在线更新**：都没有。
- **明文 HTTP 是放行的**：这是个浏览器，打不开 `http://` 站点等于不能用；地址栏的「不安全」标识负责提醒。

## 验证到什么程度

- **单元测试 226 例全绿**（JUnit + Robolectric + Compose UI Test），覆盖状态机、标签仓库、地址栏解析与建议、书签/历史/下载仓库、追踪拦截名单、WebView 池与容器、视频全屏控制器、视频源提取、投屏入口，以及全部界面。
- **每个测试类跑在独立 JVM 里**（`forkEvery = 1`）。Robolectric 按 `@Config` 复用沙箱，Compose 测试留下的组合会让同一批里后面的 Compose 测试永远等不到 idle（`AppNotIdleException`，单独跑却全绿）——隔离之后 31 个假失败一起消失，剩下的是真结果。
- **Lint 无 error**：31 条 warning 全部与业务代码无关——24 条依赖有新版本、3 条 AGP 版本提示、2 条图标矢量路径偏长、1 条 targetSdk 未追到最新、1 条自适应图标目录 `-v26` 限定符冗余。
- **在雷电模拟器（Android 14 / API 34，1080×1920）上完整跑过一遍**：主页与快捷入口、关键词搜索落到搜索结果页、地址栏网址识别、书签星标与书签/历史库、菜单入口、阅读模式正文提取、隐私面板拦截计数、设置全部开关、无痕标签隔离与提示条、标签网格/列表、下载落盘与打开、系统分享面板。下面这些「只有真机才能验」的路径**已经在模拟器上确认可用**：下载落盘（文件确实进了 `/sdcard/Download/`，点开能唤起系统看图应用）、阅读模式正文提取（导航栏与广告位被剔除）、无痕隔离（顶部提示条 + 独立 WebView）、追踪器拦截（自建页面发起的 4 个请求里命中名单的 3 个被拦下，不在名单里的 1 个放行）。
- **仍未验证的**：语音搜索与扫码需要真机的语音/扫码应用（模拟器里没有，代码路径会走到「没有可用的扫码应用」提示）；相机/麦克风/定位授权当前一律拒绝，没有逐站授权可测；长按与边缘滑动返回手势没有自动化手段，只做了人工点击验证；投屏只能验到「系统投屏页能打开、无接收端时给出提示」——模拟器里没有电视可投，真实投屏画面没法验。

### 这一轮（1.3）在模拟器上确认的四个改动

四项都在自建页面上跑通，页面里放了两个 `<video>`（一个挂 `media.mp4` + `media.webm` 两个 `<source>`，一个用 `MediaSource` 喂 `blob:` 地址）和一个 `requestFullscreen()` 按钮：

- **主页不再有「为你推荐」**：快捷入口网格下方直接是空白，设置页也没有了对应的「主页信息流」开关。
- **视频真全屏**：点「进入全屏」后内容铺满整块 1080×1920，状态栏与导航栏一并隐藏；按返回键退出全屏并留在原页面，不会连带后退一步。
- **视频下载**：2 个 `<source>` + 1 个 `blob:` 被正确提取；选 `media.mp4` 后 `/sdcard/Download/media.mp4` 大小 98304 字节，与源文件一致；`blob:` 那行标成「不支持」且点不动。
- **投屏**：面板分「屏幕镜像」与「投屏播放视频」；屏幕镜像经 `android.settings.CAST_SETTINGS` 打开系统投屏页（模拟器显示「未在附近找到设备」）；投屏播放视频列出同一批视频源，本机没有应用声明 `video/*` 时给出「没有能投屏播放该视频的应用」，不崩也不假装成功。

### 这一轮在模拟器上发现并修掉的缺陷

| 缺陷 | 根因 |
|---|---|
| 网页视频点全屏只是把画面拉大，状态栏和地址栏都还在 | 没实现 `WebChromeClient.onShowCustomView` / `onHideCustomView`：WebView 的全屏请求没有宿主接管，视频只能停在原来的内联位置 |
| 视频没有下载入口 | HTML5 `<video>` 是内联渲染的，`DownloadListener` 根本不会触发；只能注入 JS 进页面里找 `<video>` / `<source>` 的地址 |
| blob/MSE 视频源在列表里显示成三行随机 UUID | `labelOf` 把 `blob:` 的路径当文件名用，而那段路径是会话 UUID；改成固定显示「流式视频」+「blob 地址 · 无法下载」 |

### 上一轮（1.2）发现并修掉的缺陷

| 缺陷 | 根因 |
|---|---|
| 地址栏「粘贴并前往」被裁成「粘贴并前」 | 快捷动作标签没指定字号，落到默认 `bodyLarge`(16sp)，窄屏四个等宽块放不下 |
| 长页面滚到底时最后一行永久停在悬浮工具栏底下 | 工具栏浮在网页之上，而网页文档末尾没有留白；改成注入一段尾部滚动空间（对齐原型 `.page{padding-bottom:110px}`） |
| 收藏星标点完不变色，要等别的操作触发重组才补上 | `vm.isBookmarked()` 内部读 `StateFlow.value`，组合期读不出订阅；改用已 `collectAsState` 的列表判断 |
| 设置页与隐私页的页头（含唯一的应用内返回入口）会跟着内容滚走 | 这两个界面的 `ScreenHeader` 被放进了滚动容器内；书签/下载/阅读三个界面一直是固定的，只有这两个漏了 |
| 标签列表视图里新标签页的标题和副标题都是「新标签页」 | 副标题在地址为空时兜底成「新标签页」，与标题重复；改为无地址就不画副标题 |

## 下载

从 [Releases](https://github.com/lengyueya2000/aurora-browser/releases) 下载 `aurora-browser-1.3-release.apk` 直接安装（需允许「安装未知来源应用」）。与 1.0 / 1.1 / 1.2 使用同一签名，可直接覆盖升级。

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 35）。

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# 调试包
./gradlew :app:assembleDebug

# 发布包（需提供签名配置，见下）
./gradlew :app:assembleRelease
```

发布包签名通过根目录的 `keystore.properties`（已 gitignore）注入，缺失时 release 构建产出未签名 APK：

```properties
storeFile=.signing/aurora-release.jks
storePassword=******
keyAlias=aurora
keyPassword=******
```

图标由 `node tools/phosphor_to_drawable.mjs` 生成（需要联网）。脚本会先校验自己的名单与 `AuroraIcons.kt` 的 `byName` 一致，不一致直接失败；生成后清掉不在名单里的旧 drawable。

## 文档

- 设计规格：[docs/superpowers/specs/2026-10-11-android-browser-design.md](docs/superpowers/specs/2026-10-11-android-browser-design.md)
- 实施计划（只覆盖 M1/M2，当前代码已超出它）：[docs/superpowers/plans/2026-10-11-aurora-browser-m1-m2.md](docs/superpowers/plans/2026-10-11-aurora-browser-m1-m2.md)
- 高保真原型：[prototype/index.html](prototype/index.html)

## 协议

[MIT](LICENSE)
