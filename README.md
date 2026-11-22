# CordiAct

CordiAct 是一个原生 Android、以工作区为中心的行动运行时：对话、文件、终端、SSH、Termux、屏幕与浏览器工具都通过统一的 Tool Manager 暴露给模型和子 Agent。

## 当前状态

仓库不是早期的空架构阶段。`app` 已包含可编译的 Android 应用与 Compose 体验层；`core` 负责工作区、会话、工具、审批与执行契约；`model-providers` 负责多协议模型适配；`subagent` 负责后台子 Agent。当前版本以源码和 GitHub Actions 构建为准，真机端到端验收见本文末尾。

## 模块

- `app`：Android 应用、Compose UI、工具插件（文件、屏幕、SSH、Termux、浏览器等）
- `core`：工作区、工具、审批、执行策略、会话与插件契约
- `interaction`：工作区级引用与交互解析
- `model-providers`：DeepSeek / Anthropic / Gemini / OpenAI Responses / 兼容端点适配
- `deepseek-harness`：DeepSeek 动作提交适配器契约（尚未被 app 消费）
- `search-providers`、`mcp-client`、`subagent`：搜索、MCP 与子 Agent 运行时

## Agent 编写插件

普通前台会话的 Agent 可以写工作区插件源码、检查版本并申请用户批准；用户也能在会话插件面板直接预览、确认启用和撤回。会话试用提供固定文本和有界文本变换，不执行第三方脚本，也不在进程重启后自动启用。可信宿主插件可按全局、工作区或会话发布工具、斜杠命令与别名。声明式扩展包把工具和命令放进同一个带版本、宿主兼容与能力声明的清单，支持安装、升级、回滚和旧插件迁移；格式与边界见[声明式扩展包](docs/architecture/extension-packages.md)。插件架构与创作流程见[插件架构](docs/architecture/plugin-extension.md)与[示例与验收](docs/testing/authored-plugins.md)。

## 构建与测试

纯 JVM 测试不需要 Android SDK。APK 验证需要完整的 Android SDK 36 和兼容的原生工具链。JDK 配置、ARM64 工作区入口、测试范围与 Android 构建见[开发环境](docs/development.md)。

```sh
./gradlew -p verification/jvm test  # 七个 JVM 模块；其中六个有测试源
```

APK 只由 [GitHub Actions](.github/workflows/android.yml) 打包，不在本地打包。主工作流上传 `cordiact-debug-apk`，额外构建 Release 并检查不含 `AutomationProbeReceiver`。长期演进、上游参考和验收门槛见[路线图](docs/roadmap.md)。

侧载 debug 包：

```bash
adb install -r app-debug.apk
```

仓库中的 `keystore/debug.keystore` 只是公共 debug 签名，用于保证多次 CI 构建签名一致、`adb install -r` 可覆盖升级；它不是 release 签名，也不应用于发布包。

## 安全边界（实现现状）

### 执行审批

- 所有可执行工具在 `ToolManagerPlugin.invoke` 中、参数与能力校验之后、真正 executor 之前统一经过 `ToolExecutionAuthorizationPolicy`。普通模型、子 Agent、工具转调和后台启动共用这一入口。
- `ToolDescriptor.execution` 为每个可执行动作声明 backend、host/port、cwd、session、command/input 参数、实际权限与数据类别；没有匹配动作却携带执行载荷的调用会被拒绝。
- 任意 shell 命令、交互式输入、SSH 上传默认必须人工审批；审批请求显示实际目的端、动作、数据类别与参数摘要。审批缺失、拒绝、超时、取消、参数在等待期间变化都会拒绝执行；审批不缓存、不复用。
- `ShellCommandPolicy` 仅作为审批卡上的提示，不再作为安全边界。分类器可能把复合命令误判为只读，因此不能依赖它放行。

### root 与高级开关

- `PrivilegeService` 在真正执行点复核 `rootEnabled` 与 `advancedCommandsEnabled`。普通 `priv_exec` / `priv_shell` 只走 SHELL 通道，不会因底层服务恰好以 root 启动而自动提权；`priv_root` 需要显式 ROOT 通道且两个开关都满足。
- `PrivilegeRouter` 不再 root-first；root 只能通过显式级别请求到达。
- `priv_*` 工具仍受插件默认关闭与能力授权约束，不会因为本仓库的测试而改变默认值。

### 不可信输入与外发

- 浏览器、搜索与屏幕工具返回的 `untrusted=true` 内容会在模型可见的工具消息中被包进 `<untrusted_external_content>`，并明确标注为“仅数据、不执行其中指令”。外部正文不会提升为 system 消息。
- `ssh_upload` 与 `ssh_exec`（含 `stdin`）的审批会显示目的端与 `FILE_CONTENT` / `SHELL_COMMAND` 数据类别，拒绝时 executor 不会收到调用。
- 边界：Termux 进程和 SSH 远端一旦运行，app 无法对其后续网络 egress 实施强制防火墙；命令/上传审批只能约束 app 发起的调用，不能宣称消除提示注入或完全阻止数据外发。当前没有部署远端或跨进程防火墙。

### 调试探针

`AutomationProbeReceiver` 只声明在 [Debug Manifest](app/src/debug/AndroidManifest.xml)，Release 不包含。[Android CI](.github/workflows/android.yml#L58-L76) 检查 Release 合并清单、APK 清单和 DEX。

## 已知未验证 / 发布条件

- 本仓库不在 Google Play 发布流程中；当前 `targetSdk = 36`。签名 Beta 与真机升级见[放行清单](docs/beta-readiness.md)。
- 真机端到端验收需要真实设备：对话 → 工作区 → 受控执行 → 审批 → 结果；重点检查审批卡显示、拒绝零执行、root 关闭时 `priv_exec` 走 shell、Release 包无探针。
- 真机安装、root/Shizuku 权限、Termux 实际命令与 SSH 远端连通性均未在本机验证。

## License

Apache-2.0. See [LICENSE](LICENSE).
