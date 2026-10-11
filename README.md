# Aurora Browser（极光浏览器）

一款「全能功能型」安卓浏览器：在单手可及的界面上，把标签管理、拦截、阅读、下载、书签、扩展这些能力收进一个克制的深色界面里。

- **技术栈**：Kotlin + Jetpack Compose（Material 3）+ Android WebView
- **平台**：Android 8.0+（API 26+），targetSdk 35
- **视觉**：现代简约 · 深色优先（Dark Mode / OLED）

## 当前进度（M1 / M2）

已完成并可运行的部分：

| 模块 | 说明 |
|---|---|
| 设计系统 | 语义化令牌（颜色/间距/圆角/动效）+ 深色主题，全部界面不出现裸色值 |
| 图标 | 34 个 Phosphor 图标生成 VectorDrawable，无第三方图标库依赖 |
| 底部悬浮工具栏 | 前进/后退/主页/标签/菜单，触摸目标 ≥48dp，相邻目标间距 ≥8dp |
| 地址栏 | 域名 + 站点身份展示，加载中切换为「停止加载」 |
| 标签管理 | 卡片/列表两种布局，切换、关闭、关闭全部、新建 |
| 主页 | 搜索入口、快捷入口网格、推荐信息流 |
| 浏览状态机 | 地址栏聚焦 / 标签网格 / 网页三态与返回键决策（双击退出） |
| WebView 封装 | 进度、标题、真实地址回调；切换界面不销毁 WebView，保留滚动位置与前进/后退历史 |

**尚未实现（计划在 M3/M4）**：地址栏输入的关键词搜索（当前输入非 URL 文本不会走搜索引擎）、书签、历史、下载、阅读模式、分享、无痕、扩展、设置页，以及 WebView 加固（外部 scheme 跳转、明文 HTTP 策略）。

- 设计规格：[docs/superpowers/specs/2026-10-11-android-browser-design.md](docs/superpowers/specs/2026-10-11-android-browser-design.md)
- 实施计划：[docs/superpowers/plans/2026-10-11-aurora-browser-m1-m2.md](docs/superpowers/plans/2026-10-11-aurora-browser-m1-m2.md)
- 高保真原型：[prototype/index.html](prototype/index.html)

## 下载

从 [Releases](https://github.com/lengyueya2000/aurora-browser/releases) 下载 `aurora-browser-1.0-release.apk` 直接安装（需允许「安装未知来源应用」）。

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 35）。

```bash
# 单元测试（80 例，Robolectric）
./gradlew :app:testDebugUnitTest

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

## 协议

[MIT](LICENSE)
