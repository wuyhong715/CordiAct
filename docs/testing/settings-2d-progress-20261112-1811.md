# 设置中心 2c/2d 进度（2026-11-12 18:11 起）

仓库 `/workspace/CordiAct`，分支 `feat/composer-batch3`，未提交/推送。仍不是整份设计的最终验收。

## 本轮实现
### B5 MCP
- 凭据库新增 `mcp` 命名空间；`McpCredentialStore` 只给执行器读明文，UI 只见“已设置”。
- `McpPlugin` 增加 `credentialProvider`，每次启用/重连重新读取，轮换后无需重装。
- `AndroidPluginManagerService.install`：MCP spec 先 `connect + listTools`，失败不注册、不保留凭据；headers 禁止进插件配置；卸载回调删除凭据。
- `PluginManagerTools.add_mcp` 改为“模型提议 → `ApprovalGateway` 明确确认 → 写凭据 → 探测 → 安装”。
- 新增 `McpSettings`/`AndroidMcpSettings`/`McpSettingsPage`：列表、连接并预览工具、确认安装、启停、重连、删除；仅 HTTPS 或本机 HTTP。

### B10 搜索
- `WorkspaceSearchPolicy` 增 `providerOrder`、`enabledProviders`；策略损坏不再静默回落默认值（fail-closed）。
- `DefaultSearchService` 按声明顺序选首个可用第三方；禁用项即使 pin 也拒绝；AUTO 仍原生优先，仅对可回落失败尝试第三方。
- `DynamicSearchProvider` 让地址修改即时生效，无需重启。
- 新增 `SearchSettings`/`AndroidSearchSettings`/`SearchSettingsPage`：工作区模式与排序、全局 Key/地址（SecretField，不回显）、测试搜索（明确提示消耗额度）。
- 删除死代码 `app/.../search/SearchSettingsDialog.kt`（设计 B10 指定）。

### 能力与连接
- 修正能力映射到真实 owner（`com.cordiact.ssh` / `com.cordiact.termux` / `com.cordiact.screen` / `com.cordiact.browser` / `com.cordiact.priv` 等）并补测试；此前用 `core.ssh` 等名称会漏掉过滤。
- MCP 工具纳入工作区能力表，列表与调用两处检查。
- 连接页：工作区选择器、Termux 授权/复制配置命令/连通测试、无障碍测试读取、Shizuku 打开与授权、Root 检测（要求完全权限档且已开启 Root，仅在显式点击时请求 su）。
- 搜索页与连接页共用工作区作用域；返回应用只在连接页刷新就绪状态，避免覆盖未保存表单。

### SSH 补充
- SAF 私钥文件导入（≤256 KiB、校验 PEM 头、入凭据库、用完清理），编辑页不回显秘密，留空保留原认证。

## 验证
- core 588、model-providers 108、search-providers 82、mcp-client 23、插件管理 JVM 5、设置逻辑 JVM 15，全部 0 失败。
- `git diff --check` 通过；未改动未跟踪 `ui/`、`drawable/`。
- 完整 Kotlin 检查仍只报未跟踪原型：`ui/composer/DynamicActivityPill.kt:59` 动画类型、`ui/window/preview/ScreenshotView.kt` Coil 未解析。**不声称 Android 构建成功。**
- 未做真实 MCP/搜索联网、设备凭据迁移或真机验收；APK 仍应由 GitHub Actions 产出。

### 收尾修正
- `isEnabled` 的 `||` / `?:` 加括号，行为不变但不再依赖优先级推断；原生 provider 不受第三方 allowlist 影响这一语义已由测试覆盖。
- `SearchProviderSummary` 增加 `native`；排序／启用列表只列第三方，原生 `deepseek-search` 不再混入第三方顺序。
- 服务端再次收紧：`providerOrder`／`enabledProviders`／单个服务配置与测试仅接受第三方 ID。

## 仍未完成
- 内置能力统一纳入 `PluginInventory`；全部设置写入统一 revision／审计／Flow 的逐条核查。
- MCP 已装服务器工具详情与鉴权更新；SSH 最近连接时间、导入中断后临时凭据清理。
- 拖动排序、撤销 Snackbar、200% 字体／TalkBack、双栏布局验收。
- 编译阻碍解决后的 CI 出包与重启持久化、授权返回、指纹变化拒绝等真机验证。

## 续接 2026-11-12（约20:20）
前台续接用户上传设计 /upload/409b1779-b923-4806-b9cd-b07381d1f42a.md。新增 SettingsNavigation.kt + SettingsNavigationTest.kt：真实栈、内部深链白名单、恢复、8测试通过；SettingsHost 双栏完整菜单、统一返回和脏表单确认；SecuritySettingsPage 移除重复BackHandler；Connections支持深链目标bringIntoView；readiness route细化。MCP新增工具描述列表与重连；Android重连凭据仅执行器读取，enabled插件先probe再disable/enable，disabled仅probe；策略抽取 reconnectMcpTools + 6项测试文件 McpReconnectPolicyTest.kt。
已核实：.verify-settings 23测试（导航8/角色4/特权10/审计1）通过；core588/model108/search82/mcp23；SSH10；.verify-settings2d实际只含PluginManagerSettingsTest 5通过。警告：不要按目录名判断测试覆盖，.verify-settings2d 不含新MCP/附件测试；新6项MCP测试未实际运行。存储13项Robolectric Linux aarch64 native runtime失败（环境阻断）。Android Kotlin-only compile_clean 最后成功在MCP策略抽取之前；脚本临时排除未跟踪ui并用AIDL stub，不等于标准Android build。随后verify重复保护阻止第5次同参编译，已明确选(3)收束；不得绕过。最终源码未做整合编译。git diff --check通过（抽取前后均通过）。
待办：为新增MCP测试接入正确harness并运行；最终整合compile/CI；MCP鉴权更新；所有设置revision/audit/Flow覆盖核查；PluginInventory统一；聊天错误到深链入口；UI/200%/TalkBack/设备持久化验收。未commit/push/出APK/连真MCP/迁设备凭据，未创建mission。子代理4476846a只有接口半成品，parent接手完成；其状态SUCCEEDED不能视为已验证。

## 续接 2026-11-12 20:30（按用户要求：减少测试，优先功能）
本轮未跑回归、未新增测试文件，只做功能实现 + 一次 Kotlin 整合编译（BUILD SUCCESSFUL）。
已实现：
1. MCP 鉴权更换/清除：`McpSettings.updateAuthentication(name, header, secret)`。流程=先用新凭据 connect+listTools 验证→暂停已启用插件→写凭据库→重启插件；任一步失败则回滚旧凭据并 best-effort 恢复插件，绝不报成功。UI 在服务卡片内「更换 / 清除鉴权」，清除需 DangerConfirmDialog（确认文案「确认清除」，DangerConfirmDialog 新增可选 confirmLabel，参数位于 onConfirm 之前，调用方改为具名 onConfirm）。
2. SSH 放弃编辑清理：`SshSettingsPage` 增加 onDiscard 回调 + 「取消修改」确认；宿主在“放弃修改？”确认时先执行清理（清除临时导入私钥），清理失败则不关闭对话框、不丢弃修改。
3. 聊天/工具失败深链到设置：`ErrorStrip` 增加「设置」；`CompactInspector` 在工具失败时按工具名映射到 settings://ssh|connections/termux|connections/browser|mcp|connections/privilege|connections/screen|search；ExperienceScreen 用 settingsLink 传入 SettingsHost(initialLink)。
4. 关于页 `AboutSettingsPage.kt`：版本名/构建号/包名 + Apache-2.0 与 QuickJS-ng MIT 许可原文（从 assets/licenses 读取，缺失时报错而不是空白）。新增 assets/licenses/{CordiAct-Apache-2.0.txt,quickjs-ng-MIT.txt}。
5. 回前台刷新：SettingsHost 观察 ON_RESUME 调 vm.reload()（忙碌时跳过），不轮询。
6. 能力开关“已更新 · 撤销”：SettingsHost 提供 SnackbarHost，ConnectionSettingsPage 新增 onToggle 回调，切换后 5s 内可撤销（撤销即写回旧值）。
7. 清理：删除 ExperienceViewModel 中已无调用方的 legacy `addProvider`（B7 的一次性写入全部发现模型路径），避免绕过模型选择。model-providers 的 `discoverAndAddProvider` API 本身未删（改它需同时改该模块测试）。
验证：仅 `/workspace/spike/compile_clean.sh`（排除未跟踪 ui/ 原型、AIDL stub 的 Kotlin-only 编译）通过；非标准 Android 构建、无 APK、无真机验证；未跑单测/回归。

## 续接 2026-11-12 21:30（按用户“按顺序实现”：B6 → 凭据命名空间 → 审计缺口）
1) B6 内置能力纳入清单（已实现，编译通过）
- `PluginInventoryEntry` 新增工作区投影字段：capabilityId / workspaceId / workspaceEnabled / readiness。
- `PluginManagerService` 新增 `inventory(workspaceId)`（默认回退全局）；`PluginManagerPresenter` 输出 capability/workspace_id/workspace_enabled/readiness 行。
- 新 `app/plugin/BuiltinCapabilityInventory.kt`：把已注册内置工具按 `WorkspaceCapabilityPolicy` 归到 ssh/termux/screen/browser/privilege/search/mcp.<server>，生成 BUNDLED_SYSTEM 只读清单行，并按工作区投影启用状态与 Readiness（MCP 行按插件状态给出 READY/NEEDS_ACTION）。
- `AndroidPluginManagerService`：构造注入 bundledInventory + workspaceProjection；内置 id 的 install/setEnabled/remove 返回 OVERRIDDEN，理由指向“到设置里改该工作区能力”，内置能力不可全局卸载。
- `AndroidSettingsFacade` 的能力开关列表改为由 `inventory(workspaceId)` 派生（原 mcpCapabilities 拼接移除），保证开关/依赖/就绪三者同源。
- 工具调用 `plugin_manage list` 改为按 `context.workspaceId` 投影。
2) 凭据命名空间覆盖：凭据页候选集补齐 SSH 主机名与 MCP 服务器名，模型/搜索/SSH/MCP 四个命名空间都能列出并清除；仍只暴露 CredentialMeta。
3) 审计缺口核查（结论：已覆盖，未改代码）：capability/attachment/search policy/permission/privilege/roles/ssh/mcp/provider/credential 清除均经 SettingsWriter 或 audited(...) 包装，失败通过 takeWriteWarning 暴露；设置 UI 无明文读取路径（仅 set/has）。
验证：仅 Kotlin-only 编译通过（3 次，最后一次 BUILD SUCCESSFUL）；未跑测试、无 APK、无真机。
未做：内置能力在能力页的“依赖未满足即禁用”UI 复核；MCP 行在能力页的启用开关是否需隐藏（目前允许按工作区关闭 MCP 工具可见性）。
