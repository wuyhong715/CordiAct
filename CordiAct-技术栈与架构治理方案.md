# CordiAct 技术栈与架构治理方案

## 1. 审计范围与基线

本报告以 CordiAct 工作树 `codex/plugin-turn-admission` 的提交 `d4d51535fbf16717bd086774b003e61d054a5ef9` 为基线。源码位于本工作区的上一轮克隆目录，当前提交工作树无未提交改动。

本次只做静态架构审计，未在本机编译、未打包 APK、未触发 GitHub Actions，也未执行真机测试。项目指令要求编译和测试在手机或 GitHub Actions 上完成，因此后续验证按本文的验证矩阵执行。

关键证据位置如下，行号对应审计基线工作树。

| 结论 | 证据 |
|---|---|
| 根模块图包含 app 与 7 个 JVM 模块 | `settings.gradle.kts:6-7` |
| Kotlin、Compose、AGP 版本在根脚本内声明 | `build.gradle.kts:1-5` |
| Android API、NDK、CMake、AIDL、Compose 和全部 JVM 模块由 app 直接装配 | `app/build.gradle.kts:11-42,72-108` |
| `core` 同时依赖 Coroutines、Gson、OkHttp、TOML、JGit、JSch | `core/build.gradle.kts:5-20` |
| JVM 验证入口重新定义模块映射 | `verification/jvm/settings.gradle.kts:15-20` |
| UI 验证入口抽取 app 源集 | `verification/ui/build.gradle.kts:1-20` |
| 本地 JS 沙箱当前显式拒绝启动 | `core/src/main/kotlin/com/cordiact/core/sandbox/SandboxContracts.kt:209-214` |
| Remote Harness 尚未被 app 消费 | `README.md:11-18` |
| Android、模拟器和 Release 探针检查由 Actions 执行 | `.github/workflows/android.yml:31-85,87-160` |

## 2. 当前技术栈盘点

| 层次 | 当前技术 | 现状判断 |
|---|---|---|
| Android 宿主 | Kotlin 2.4.21、AGP 9.4.1、Gradle 9.8.2、Java 17 toolchain、Android API 36、minSdk 26 | 版本已固定，构建入口分裂 |
| UI | Jetpack Compose、Material 3、Activity Compose、Lifecycle Compose、Haze、Markwon | UI 集中在 `app`，部分屏幕与运行时编排耦合 |
| 核心运行时 | Kotlin/JVM、Coroutines、Gson、TOML、OkHttp 5、JGit、JSch | `core` 同时承载契约、策略、工作区、执行和大量业务模型 |
| Android 能力 | WorkManager、WebView、Shizuku、AIDL、JNI | 能力适配集中在 `app`，安全边界已有统一审批入口 |
| 脚本与原生 | QuickJS-NG、CMake、NDK 27.2、C/C++ JNI | 已有固定脚本 PoC，生产 Sandbox Runtime 仍 fail-closed |
| 扩展与远端 | `plugin-sdk` Node 24，DeepSeek Harness Node 测试与 Kotlin 契约 | SDK 有测试，Remote 适配器尚未接入 app |
| 验证 | JVM 独立 Gradle 入口、Android CI、API 29/35 模拟器、真机取证流程 | 证据链清楚，但入口和模块图存在重复定义 |

当前源码规模约为 `app` 221 个 Kotlin 文件、`core` 156 个 Kotlin 文件，另有 7 个 JVM 业务模块。`app` 中最大的实现文件约 66 KB，`core` 中最大的实现文件约 64 KB，说明边界治理优先级高于继续增加功能。

## 3. 架构判断

### 3.1 已形成的基础

- Tool Manager、审批网关、执行描述和敏感数据分类已经形成统一的动作入口。
- Workspace、插件清单、签名目录、版本迁移和回滚契约已有测试覆盖。
- `core` 与 provider、MCP、subagent 等 JVM 模块之间的依赖方向总体清晰。
- Android 主工作流包含单测、Debug APK、Release 探针检查和模拟器隔离服务验证。
- ADR 已明确 native-first、多运行目标、远端 Harness 边界和 Sandbox Manager 的 fail-closed 原则。

### 3.2 必须先治理的结构性问题

1. **目标模块与实际模块不一致**。架构文档规划了 `plugins-android`、`runtime-cordis`、Model Adapter、Session Manager 等边界，实际实现仍主要集中在 `app` 和 `core`。文档中的目标结构不能作为当前可用能力声明。
2. **`app` 是应用壳、Android 适配、插件注册、工具实现、会话运行时和大量 Compose 页面的一体化模块**。它直接依赖全部主要 JVM 模块，任何 UI 改动都容易牵动运行时装配。
3. **`core` 不是纯领域内核**。它同时依赖 OkHttp、JGit、JSch、TOML 和 Gson，并承载执行后端、工作区文件规则、插件生命周期、会话、记忆、附件和工具管理。核心契约与基础设施的变化会互相放大。
4. **构建存在三套入口**。根项目、`verification/jvm` 和 `verification/ui` 各自定义模块图或源集映射。它们适合验证，但没有单一的规范模块图，容易出现“验证通过但主构建未覆盖”的情况。
5. **依赖版本没有集中治理**。根项目和各模块重复声明 Kotlin、JUnit、Gson、Coroutines 等版本，仓库没有版本目录、依赖更新策略或静态分析配置。
6. **Remote 与 Sandbox 仍处于契约或 PoC 阶段**。`deepseek-harness` 当前未被 app 消费，`UnavailableIsolatedSandboxRuntime` 会明确抛出未实现异常。两者必须在产品状态、UI 状态和发布门槛中保持“不可用”的真实表达。

风险优先级按“影响安全边界、影响构建可信度、影响后续迁移成本”的顺序排列。G0 和 G1 处理前，不建议将新的运行目标或新的持久化域加入 `core`。

## 4. 治理后的目标架构

治理目标是稳定依赖方向，不要求立即重写实现。推荐采用以下五层，并在现有模块上渐进迁移。

```text
Android UI / Android adapters
              |
        application runtime
              |
 policy + orchestration + plugin lifecycle
              |
      domain contracts and data models
              |
       platform-neutral primitives
```

### 4.1 层职责

| 层 | 允许内容 | 禁止内容 |
|---|---|---|
| `domain-contracts` | Workspace、Session、Tool、Plugin、Action、Audit、Capability 的不可变模型和接口 | Android、网络客户端、文件系统实现、具体数据库 |
| `runtime` | 回合编排、插件生命周期、工具调用、审批协调、取消和恢复 | Compose、Activity、Android Service、直接读取 UI 状态 |
| `policy` | 能力、作用域、风险、审批和审计判定 | 执行 shell、直接发送网络请求、直接操作 UI |
| `adapters` | OkHttp、JGit、JSch、MCP、模型 provider、搜索、Termux、SSH、Shizuku、WebView | 修改领域契约、绕过 policy、保存未授权的跨工作区状态 |
| `app` | Android 入口、Compose、生命周期、依赖装配、Android 存储和服务适配 | 定义新的通用业务规则、绕过 Tool Manager 或重复实现审批 |

第一阶段不强制新增五个 Gradle module。先在 `core` 内建立包级边界和依赖规则，等规则稳定后再把 `domain-contracts` 与 `runtime` 提升为独立模块，降低迁移风险。

### 4.2 运行目标

所有插件描述必须明确以下字段，并由宿主判定是否可运行：

- `executionTarget`: `mobile-native`、`mobile-cordis` 或 `harness-hosted`
- `apiVersion` 与宿主兼容范围
- 所需 capability、Workspace scope、数据类别和审批等级
- 安装来源、签名状态、保护级别和可撤回状态
- 运行状态、不可用原因、最近一次失败和恢复动作

`mobile-cordis` 在 Sandbox Manager 生产门槛通过前只能显示为不可用或测试能力。`harness-hosted` 必须经过认证传输，远端返回只能作为数据进入本地流程。

## 5. 技术栈治理决策

### 5.1 保留的基线

- 保留 Kotlin/JVM、Compose、Coroutines、OkHttp、JGit、JSch、WorkManager、Shizuku 和 QuickJS-NG。
- 保留 Android API 36、minSdk 26 和 Java 17 JVM toolchain。Gradle 启动 JDK 由 CI 与开发环境显式提供 Java 21。
- 保留 Node 24 作为 `plugin-sdk` 与 Harness 互操作测试版本。

### 5.2 立即统一的工程设施

- 在根目录建立版本目录，统一 Kotlin、Compose、AndroidX、JUnit、Gson、Coroutines 和测试运行器版本。
- 保留一个规范的根模块图。`verification/jvm` 只复用 JVM 模块，不复制生产源，不再新增平行生产模块。
- 将 `verification/ui` 标记为抽取测试入口，并禁止它成为业务源码的第二编译真相。
- 增加 Kotlin 编译告警基线、格式检查和依赖锁定。静态检查先以非阻断报告运行，清理现存问题后再转为合并门禁。
- CI 按证据类型分成 JVM、Android 编译、模拟器、Node SDK/Harness、Release 安全检查五类，并为每类产物保留报告。

### 5.3 数据与状态治理

- Workspace 是所有会话、插件配置、工具偏好、附件和记忆的显式 owner。
- Android 存储实现只能通过 `core` 定义的接口接入。UI 不读取数据库、密钥或加密载荷。
- 跨进程、远端和后台任务必须使用带版本的事件或请求模型，禁止传递 Android 类型和未界定的 `Map<String, Any>`。
- 每次插件、工具和 provider 配置变更都产生 revision。等待审批的请求必须校验 revision，过期请求拒绝执行。

## 6. 依赖规则

以下规则从下一阶段开始执行。

1. `domain-contracts` 不依赖 Android、网络、数据库和具体 provider。
2. `runtime` 只能依赖领域契约和抽象接口，不允许 import `android.*`、Compose、WebView、Shizuku 或具体 HTTP 客户端。
3. `policy` 是所有可执行动作的唯一授权入口。工具、subagent、MCP、Termux、SSH 和远端动作不得自行放行。
4. provider 模块只实现 provider 协议适配，不向 `core` 写入 provider 特有字段。
5. `app` 负责装配具体实现，不向 JVM 模块反向依赖 Android 类型。
6. `deepseek-harness` 在完成认证、重连、取消、背压和远端错误测试前，不接入默认 app 路径。
7. Sandbox Runtime 在真实隔离、资源限制、进程恢复和 capability broker 验证前，不允许提供进程内回退。

## 7. 分阶段执行计划

### G0：冻结与可见性，1 个迭代

- 发布当前架构基线和模块依赖图。
- 给每个能力标记 `implemented`、`contract-only`、`poc-only` 或 `unavailable`。
- 记录 JVM、Android CI、模拟器、真机、Node 互操作五类验证证据。
- 禁止在没有 owner、数据范围和执行目标的情况下新增插件能力。

### G1：构建和依赖治理，1 个迭代

- 引入版本目录和依赖锁定。
- 统一根构建与 `verification/jvm` 的 Kotlin/Java 版本声明。
- 增加依赖方向检查、API/ABI 检查和静态分析报告。
- 让 CI 明确报告 `NOT_RUN`、`FAILED`、`UNKNOWN`，不把缺失设备证据视为通过。

### G2：核心边界收敛，2 个迭代

- 从 `core` 先抽出契约包，再迁移 policy、runtime 和 adapter 接口。
- 将网络、SSH、Git、MCP 和 Android 存储实现移到 adapter 包或独立模块。
- 给 Tool Manager、Plugin Lifecycle、Workspace、Session 和 Audit 建立稳定的版本化 API。
- 将超过约 800 行或同时包含 UI、状态机、持久化的文件拆成按职责命名的组件。现有大文件先建立 owner 和拆分顺序，不进行无测试的大规模重排。

### G3：宿主与扩展边界，2 个迭代

- 让 app 只保留 Android 入口、依赖装配和 Android capability adapter。
- 将 Model Adapter、Session Manager、Sandbox Manager 的实现状态与插件目录状态一致化。
- 完成签名扩展包的兼容性矩阵和迁移测试。
- Remote 适配器单独以 feature flag 和诊断入口接入，默认关闭，经过互操作门禁后再开放。

### G4：发布门槛

- JVM 全量测试通过。
- Android Debug、Release 和 instrumentation APK 由 GitHub Actions 生成。
- Release 合并清单、APK 和 DEX 均不含 debug probe。
- API 29/35 模拟器隔离测试通过，真实设备测试有独立报告。
- 对话、Workspace、受控工具、审批、拒绝零执行、root 开关、Termux 和 SSH 各有可复核证据。

## 8. 下一阶段的合并门禁

每个涉及运行时的 PR 必须回答以下问题。

- 新代码属于哪一层，依赖方向是否满足规则。
- 新增状态的 owner、Workspace scope、revision 和迁移策略是什么。
- 是否经过统一 policy，拒绝、超时、取消和参数变化是否 fail-closed。
- JVM 测试覆盖了哪些纯契约，Android CI 覆盖了哪些适配，未执行的验证是否明确标记。
- 如果涉及插件、远端或脚本，execution target、签名、能力和撤回行为是否有测试。
- 是否改变了发布包身份、权限、调试组件或密钥处理。

## 9. 治理结论

CordiAct 当前适合进入“边界收敛阶段”，不适合继续以新增功能模块的方式扩张。下一阶段的首要交付应是 G0 和 G1，然后以 Tool Manager、Plugin Lifecycle、Workspace 和 Session 为主线收敛 `core` 与 `app` 的职责。Remote Harness 和本地 JS 沙箱继续保持显式 gated 状态，直到各自的运行时证据达到发布门槛。

