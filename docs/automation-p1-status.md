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
| display 驱动 | `app/display/BackgroundScreenDriver.kt` | 调用 shell 侧 `PrivilegedUserService` 建公开屏/启动/注入；树读取暂返回 `text_connection_unavailable` |
| 接线 | `app/automation/AutomationRuntime.kt`、`CordiActApplication`、`BundledConversationPluginCatalog`、Manifest | 四插件注册、工具注册、AccessibilityService 声明、ShizukuProvider、无障碍配置 |

## 已验证

- `:core:test` 本地通过（含 `ScreenFormatTest`、`ScreenSafetyTest`、`ShellCommandPolicyTest`）。
- CI `Android build` 编译结果见 PR #33（推送后自动触发）。

## 尚未完成（明确留给下一步）

1. **display 的公开屏创建**：`PrivilegedUserService.createDisplay` 已实现 `DisplayManager.createVirtualDisplay` + 公开 flags + ImageReader；尚未在真机验证（E1/E2）。
2. **虚拟屏树读取**：`dumpDisplay` 当前返回 `text_connection_unavailable`；需要 UiAutomation 绑定 display 的通道（Android 14+）。
3. **Shizuku 权限 UI**：尚无引导页/授权按钮，`priv_status` 会返回 `shizuku_permission_denied` 与恢复文案。
4. **屏幕设置页**：插件开关走现有插件管理页；无障碍/Shizuku 的专门引导页未做。
5. **CDP**：按计划 P2，不在本批。
6. **中文输入**：`input text`/`ACTION_SET_TEXT` 对 CJK 的支持待真机验证，可能需要 IME 方案。

## 下一步验证清单（P0）

- E1：Shizuku UserService 建公开屏 → `dumpsys display` 无 `FLAG_PRIVATE`
- E2：`launchOnDisplay` 启动 Settings → `captureDisplay` 非黑
- E3：UiAutomation 绑定 display 读树（Android 14+）
- E4：`input -d` 点击命中
- E5：IME HIDE 主屏不弹输入法
- E6：双屏并发/孤儿清理
