# R8 规则。
#
# 大部分该保留的东西 AGP 自带的 proguard-android.txt 与各依赖的 consumer 规则已经覆盖
# （Compose、Media3 都自带规则）。这里只写「本项目特有、且不写就会在 release 上炸」的几条，
# 每条都注明为什么——没有来由的 keep 规则会越堆越多，最后把裁剪效果吃光。

# ── 注入网页的 JS 接口 ──────────────────────────────────────────
# `MediaBridge.onMediaAction` 是页面里的 JS 通过名字调的（`window.AuroraMedia.onMediaAction`）。
# 名字由 WebView 在运行时按字符串查表，编译期没有任何引用——不 keep 的话 R8 会把类名和方法名
# 混淆掉，页面上那三个按钮（全屏 / 下载 / 投屏）就全部静默失效：不报错，点了没反应。
-keepclassmembers class dev.aurora.browser.ui.browser.MediaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
# 类本身也要留住：`addJavascriptInterface` 拿到的实例要通过反射找方法。
-keep class dev.aurora.browser.ui.browser.MediaBridge { *; }

# ── 崩溃日志可读性 ──────────────────────────────────────────────
# CrashLog 把堆栈写进文件、下次启动时交给用户。行号表被裁掉的话，堆栈里只剩
# `SourceFile: null`，那份日志就没有排查价值了——这个功能的存在意义就是可读的堆栈。
-keepattributes SourceFile,LineNumberTable
# 保留原始源文件名，别把 SourceFile 换成 `a.java`。
-renamesourcefileattribute SourceFile

# ── 枚举 ────────────────────────────────────────────────────────
# `OfflineItem.State` 等枚举有 `when` 分支 + `values()` 用法；R8 的枚举优化默认已按
# AGP 规则处理，这里只显式声明不裁掉枚举的 valueOf/values，防止某些 AGP 版本下
# 优化过头导致 `when` 落到 `else` 分支（表现为状态显示成「已暂停」）。
-keepclassmembers enum dev.aurora.browser.media.OfflineItem$State {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
