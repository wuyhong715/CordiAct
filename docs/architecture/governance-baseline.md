# 架构治理基线

本文件是 CordiAct 下一阶段开发的治理入口。它把目标架构、当前实现状态和验证证据放在同一个可审阅位置。架构目标不能代替实现状态，`contract-only`、`poc-only` 和 `unavailable` 能力不得在产品界面中显示为可用。

## 基线快照

- 基线分支：`codex/plugin-turn-admission`
- 基线提交：`d4d51535fbf16717bd086774b003e61d054a5ef9`
- Android：Kotlin 2.4.21、AGP 9.4.1、Gradle 9.8.2、compile/target SDK 36、minSdk 26
- JVM：Java 17 toolchain，Gradle 启动使用 Java 21
- 原生：NDK 27.2.12479018、CMake 3.22.1、QuickJS-NG
- 扩展工具链：Node 24

## 模块职责

| 模块 | 当前职责 | 允许依赖 | 治理状态 |
|---|---|---|---|
| `core` | 领域契约、Workspace、Session、Tool、Plugin、Policy、Audit 和部分基础设施 | Kotlin/JVM 与已声明的基础库 | 收敛中，禁止继续加入 Android 代码 |
| `interaction` | Workspace 范围的引用、指令和交互解析 | `core` | 稳定边界 |
| `model-providers` | provider 协议适配和 provider 注册 | `core` | 稳定边界 |
| `search-providers` | 搜索 provider 适配 | `core` | 稳定边界 |
| `mcp-client` | MCP HTTP/JSON-RPC 客户端和工具桥接 | `core` | 稳定边界 |
| `subagent` | 后台子 Agent 和编排运行时 | `core` | 稳定边界 |
| `deepseek-harness` | Harness 互操作契约和只读跟随验证 | `core` | `contract-only`，未接入默认 app 路径 |
| `app` | Android 入口、Compose UI、Android adapter、插件装配 | 所有 JVM 模块及 Android 依赖 | 收敛中，禁止新增通用领域规则 |
| `plugin-sdk` | 声明式扩展包类型、打包、签名和 Node 测试 | Node 24 | 独立工具链 |

## 运行目标状态

| 运行目标 | 当前状态 | 发布条件 |
|---|---|---|
| `mobile-native` | `implemented` | 统一经过 Tool Manager 和本地 Policy |
| `mobile-cordis` | `poc-only` | 隔离进程、资源限制、Capability Broker、恢复和真机证据全部通过 |
| `harness-hosted` | `contract-only` | 认证传输、重连、取消、背压、远端错误和本地二次授权通过 |

## 依赖方向

```text
app
 ├── interaction ──┐
 ├── model-providers
 ├── search-providers
 ├── mcp-client     ├── core
 ├── subagent      ─┘
 └── deepseek-harness

plugin-sdk  (Node toolchain, independent)
```

禁止出现以下依赖方向：

- `core`、`interaction`、provider、MCP 或 subagent 依赖 `android.*`、Compose、Activity、WebView 或 Shizuku。
- `core` 的领域契约依赖具体 provider、Android 存储或 UI 类型。
- 任一工具或远端 adapter 绕过 `ToolExecutionAuthorizationPolicy`。
- Sandbox 在生产路径中回退到进程内执行。

## 当前已知边界

- `UnavailableIsolatedSandboxRuntime` 明确拒绝生产启动，不能当作 JS 插件可用证据。
- `deepseek-harness` 的 Kotlin 契约和 Node 互操作测试不能当作 app 已接入证据。
- `verification/jvm` 和 `verification/ui` 是验证入口，不得形成第二套生产源码真相。
- Debug probe 只允许出现在 debug manifest，Release 必须由 CI 检查合并清单、APK 和 DEX。

## 治理命令

从仓库根目录运行：

```sh
python3 tools/check-architecture-governance.py
```

该检查只验证模块图、禁止依赖、治理文件和能力状态，不代替 JVM、Android、模拟器或真机验证。

