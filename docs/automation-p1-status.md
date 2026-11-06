# 自动化四插件 P1 实施状态（2026-11-06）

分支：`feat/automation-plugins`　PR：#33

## 已完成（本批提交）

| 模块 | 文件 | 说明 |
|---|---|---|
| core 契约 | `core/automation/AutomationContracts.kt` | `ScreenHandle`（s*/b*）、`ScreenElement`/`ScreenSnapshot`、`ScreenDriver`、`ScreenDriverRegistry`、错误码 |
| core 格式化 | `core/automation/ScreenFormat.kt` | 编号元素、顶/中/底分区、3000 字预算、`+`/`⚠`、密码永不渲染 |
| core 安全 | `core/automation/ScreenSafety.kt` | `ProtectedPages`（系统设置/安装器/权限控制器）、`AppPolicyTable`（银行 BLOCK、微信 CONFIRM）、`ConfirmationGate` |
| core 特权 | `core/automation/PrivilegeContracts.kt` | `PrivilegeLevel`、`PrivilegeStatus`、`PrivilegedChannel`、`ShellCommandPolicy`（危险命令识别） |
| screen 插件 | `app/screen/*` | `CordiActAccessibilityService`、`TreeCapture`、`MainScreenDriver`、`ScreenRegistryImpl`、`ScreenService`、`ScreenGuard`、`ScreenTools`（9 个统一工具）、`ScreenPlugin` |
| browser 插件 | `app/browser/*` | 离屏 `WebView`（无 WindowManager 权限）、`BrowserSessionPool`、10 个 `browser_*` 工具、`BrowserPlugin` |
| privilege 插件 | `app/privilege/*` | Shizuku `bindUserService` + AIDL、root `su` 通道、`PrivilegeRouter`、4 个 `priv_*` 工具、`PrivilegePlugin` |
| display 驱动 | `app/display/BackgroundScreenDriver.kt`、`app/display/DisplayTreeReader.kt`、`app/display/SnapshotBundle.kt` | 调用 shell 侧 `PrivilegedUserService` 建公开屏/启动/注入；树读取走 shell 进程内绑定该 display 的 UiAutomation，快照经 Bundle 过 binder |
| display 插件入口 | `app/display/DisplayPlugin.kt` | 四插件之一；无独立工具，向 screen 注册 `b*` 驱动 |
| 接线 | `app/automation/AutomationRuntime.kt`、`CordiActApplication`、`BundledConversationPluginCatalog`、Manifest | 四插件注册、工具注册、AccessibilityService 声明、ShizukuProvider、无障碍配置 |

## 已验证

- `:core:test` 本地通过（含 `ScreenFormatTest`、`ScreenSafetyTest`、`ShellCommandPolicyTest`）。
- GitHub Actions `Android build`（run 42157618887，commit `eb77bc4`）：**Build and test 通过**，产物 `cordiact-debug-apk`（约 12.7MB）。
- PR #33 已创建并持续更新。

## 尚未完成（明确留给下一步）

1. **display 的公开屏创建**：`PrivilegedUserService.createDisplay` 已实现 `DisplayManager.createVirtualDisplay` + 公开 flags + ImageReader；尚未在真机验证（E1/E2）。
2. ~~**虚拟屏树读取**：`dumpDisplay` 当前返回 `text_connection_unavailable`；需要 UiAutomation 绑定 display 的通道（Android 14+）。~~ **已实现，见下节 E3。**
3. **Shizuku 权限 UI**：尚无引导页/授权按钮，`priv_status` 会返回 `shizuku_permission_denied` 与恢复文案。
4. **屏幕设置页**：插件开关走现有插件管理页；无障碍/Shizuku 的专门引导页未做。
5. **CDP**：按计划 P2，不在本批。
6. **中文输入**：`input text`/`ACTION_SET_TEXT` 对 CJK 的支持待真机验证，可能需要 IME 方案。

## 下一步验证清单（P0）

- E1：Shizuku UserService 建公开屏 → `dumpsys display` 无 `FLAG_PRIVATE`
- E2：`launchOnDisplay` 启动 Settings → `captureDisplay` 非黑
- E3：UiAutomation 绑定 display 读树（Android 14+）— **已通过**（displayId=16，Settings 树 79 节点）
- E4：`input -d` 点击命中
- E5：IME HIDE 主屏不弹输入法
- E6：双屏并发/孤儿清理

## E3：虚拟屏树读取（已打通）

真机 spike（`app_process`，shell uid）已证明做法，随后落回产品代码：

- `new UiAutomation(displayContext, IUiAutomationConnection)`：**必须**传入 `context.createDisplayContext(display)` 得到的 display context；
  `connectWithTimeout(int)` 的第一个参数是 **flags** 而不是 displayId，传 displayId 会被当成 flag，连接到的仍是默认屏。
- `IUiAutomationConnection` 用 `Proxy` 手写：只实现 `asBinder/connect/disconnect/toString/hashCode/equals`，
  `connect` 里调 `IAccessibilityManager.registerUiTestAutomationService(token, client, info, userId, FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`。
  这样 disconnect 不会去恢复/重置用户自己的无障碍服务。
- 读树用 `getWindowsOnAllDisplays()`，按 displayId 取窗口列表，优先 `TYPE_APPLICATION`，再 `window.root` 走原有 `TreeCapture.captureFrom`。
- UiAutomation 的回调走创建它的线程的 Looper，所以 `PrivilegedUserService.dumpDisplay` 把整段读操作丢到独立 `HandlerThread` 上，
  binder 线程只用 `CountDownLatch` 等结果（15s 超时）。
- 证据（16:19，displayId=16，720×1280）：`display 16 windows=1` → `window type=1 root=com.android.settings/android.widget.FrameLayout` → 79 个节点，
  含「飞行模式」「WLAN」等真实文本，坐标全部落在 720×1280 内。同屏默认显示（display 0）也照常可读。
- 键位提示：虚拟屏要有真实 surface（ImageReader）+ 同一组 flags（PUBLIC/OWN_CONTENT_ONLY/SUPPORTS_TOUCH/DESTROY_CONTENT_ON_REMOVAL/TRUSTED/OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED），
  否则 `getWindowsOnAllDisplays()` 里该 display 的窗口列表为空；设备锁屏时目标 App 不会出现在该屏上，读到的只有 keyguard。
