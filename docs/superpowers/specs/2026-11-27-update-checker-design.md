# 版本更新检测设计

## 目标

应用能检测 GitHub Releases 上的新版本，提示用户前往下载；支持启动时自动检查与设置页手动检查；用户可选择忽略某个版本。

## 约束与事实

- 当前版本 `1.22`（versionCode 23），tag 格式 `v1.22`。
- 仓库公开，地址 `https://github.com/lengyueya2000/aurora-browser`。
- 已有 `INTERNET` 权限，无需新增。
- 无 OkHttp/Retrofit，使用 `HttpURLConnection` + `org.json`。
- **`/releases/latest` 会忽略 pre-release**：实测返回 v1.17，而 v1.18–v1.22 全是 pre-release。必须用 `/releases` 列表取第一个非 draft。
- 现有所有发布都是 pre-release，不能按 `prerelease` 字段过滤。

## 组件

### 1. `domain/UpdateChecker.kt`（纯逻辑，无 Android 依赖）

```
data class ReleaseInfo(val version: String, val notes: String, val pageUrl: String)
```

- `parseVersion(tag: String): IntArray?`
  - 兼容 `v1.22` 与 `1.22`，大小写不敏感（`V1.22` 也可）。
  - 严格三段数字 `X.Y.Z`；更多段（如 `1.2.3.4`）取前三段；不足三段无效。
  - 任何非数字段 → null。
- `isNewer(candidate: String, current: String): Boolean`
  - 逐段整数比较，`1.10 > 1.9`（字符串比较会错）。
  - 任一解析失败 → false（宁可不提示，不能误报）。
- `parseLatestRelease(json: String): ReleaseInfo?`
  - 取数组第一个非 draft 元素。
  - `tag_name` 解析版本；`body` 作更新说明；`html_url` 作页面地址。
  - 缺任一必要字段 → null。
- `check(currentVersion: String, client: (url) -> String?): CheckResult`
  - 纯函数注入 HTTP client，便于单元测试。
  - 返回 `NewVersion(info)` / `UpToDate` / `Failed`。

### 2. `data/UpdatePreferences.kt`

- `lastCheckAt: Long`（用 `SharedPreferences`），默认 0。
- `ignoredVersion: String?`
- `shouldAutoCheck(now: Long): Boolean` — 距上次 ≥ 24 小时。
- `markChecked(now: Long)` / `ignore(version)` / `clearIgnored()`

### 3. `ui/settings/SettingsScreen.kt`

- 「关于」分组加 `SettingRow(title = "检查更新", subtitle = ..., onClick = ...)`。
- 新增参数 `updateBadge: Boolean`、`onCheckUpdate: () -> Unit`、`updateState: UpdateUiState`。
- 手动检查时显示状态：「正在检查…」「已是最新版本」「检查失败」。

### 4. `MainActivity.kt`（启动检查 + 弹窗宿主）

- `LaunchedEffect(Unit)` 中：若 `shouldAutoCheck()` 则后台检查，标记已检查。
- 结果比当前版本新且未忽略 → 弹窗。
- 弹窗内容：当前版本 → 新版本、更新说明（body 前 400 字符）、两个按钮：
  - 「前往下载」→ `ACTION_VIEW` 打开 `html_url`
  - 「忽略此版本」→ `ignore(version)` 并关闭
- 网络失败静默，不弹任何东西。

### 5. `domain/UpdateRepository.kt`（网络层，薄封装）

- `fetchReleases(): String?`
  - `HttpURLConnection` GET `https://api.github.com/repos/lengyueya2000/aurora-browser/releases`
  - Header: `User-Agent: aurora-browser`, `Accept: application/vnd.github+json`
  - 10 秒连接/读取超时；非 200 返回 null。
  - 在 `Dispatchers.IO` 调用。

## 数据流

```
冷启动 → UpdatePreferences.shouldAutoCheck()
       → UpdateRepository.fetchReleases() (IO)
       → UpdateChecker.parseLatestRelease(json) + isNewer(...)
       → 新版本且未忽略 → 弹窗

设置页点「检查更新」→ 同上，但不写 lastCheckAt，直接展示结果
```

## 测试

- `UpdateCheckerTest`
  - `parseVersion`：`v1.22` / `1.22` / `V1.2.3` / 非法输入
  - `isNewer`：`1.10 > 1.9`、相等、更旧、非法
  - `parseLatestRelease`：跳过 draft、字段缺失
  - `check`：注入假 client 覆盖三种结果
- `UpdatePreferencesTest`：24 小时节流、忽略版本
- `SettingsScreenTest`：「检查更新」行存在且可点

## 不做

- 不自动下载/安装 APK（权限与兼容性成本高）。
- 不解析 pre-release 标记（当前全是 pre-release）。
- 不显示完整的 markdown 更新说明（只取纯文本前 400 字符）。
