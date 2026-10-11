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
| 地址栏 | 关键词搜索与网址自动识别；输入框建议含搜索/网址/书签/历史四类；域名 + 站点身份展示；加载中切换为「停止加载」 |
| 搜索引擎 | 默认 Bing（境内境外都可达），可在设置里换成百度 / Google |
| 标签管理 | 卡片/列表两种布局，切换、关闭、关闭全部、新建；**每个标签独立 WebView**，前进/后退历史互不干扰 |
| 无痕标签 | 标签级隔离：不写历史、关闭 DOM 存储与缓存、禁用第三方 Cookie，界面顶部常驻无痕提示条 |
| 主页 | 搜索入口、快捷入口网格、推荐信息流（可在设置里关闭） |
| 书签与历史 | 收藏星标、书签/历史分段列表、过滤、删除、清空；历史上限 500 条 |
| 下载管理 | 交给系统 `DownloadManager`（后台下载 + 通知栏进度），进行中/已完成分段，支持打开与删除 |
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
- **逐站权限授权**：相机 / 麦克风 / 定位当前一律拒绝，没有「本站允许」的授权弹窗。
- **标签持久化**：进程被系统回收后标签页不会恢复，重启是全新一个标签页。
- **同步、账号、查找页内文字、桌面模式、翻译、拦截规则在线更新**：都没有。
- **明文 HTTP 是放行的**：这是个浏览器，打不开 `http://` 站点等于不能用；地址栏的「不安全」标识负责提醒。

## 验证到什么程度

- **单元测试 190 例全绿**（JUnit + Robolectric + Compose UI Test），覆盖状态机、标签仓库、地址栏解析与建议、书签/历史/下载仓库、追踪拦截名单、WebView 池与容器、以及全部界面。
- **Lint 无 error**：余下 16 条 warning 全是「有新版本可用」这类依赖升级提示（12 条）、图标矢量路径偏长（2 条）、targetSdk 未追到最新（1 条）、自适应图标目录带 `-v26` 限定符而 minSdk 已是 26（1 条，冗余但无害）。
- **没有在真机或模拟器上跑过**：构建环境里没有可用设备，所以下载落盘、阅读模式正文提取、无痕隔离、扫码/语音这几条「只有真机才能验」的路径属于**未验证**。上面那些测试能保证逻辑分支正确，但不能替代一次真机冒烟。

## 下载

从 [Releases](https://github.com/lengyueya2000/aurora-browser/releases) 下载 `aurora-browser-1.1-release.apk` 直接安装（需允许「安装未知来源应用」）。与 1.0 使用同一签名，可直接覆盖升级。

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
