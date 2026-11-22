# CordiAct 长期开发路线图：开放的运行时，可验证的开发工作台

**状态：建议方案，尚未等同于产品承诺。** 基线：CordiAct `71e26bc09aed`，DeepSeek Harness `d98f5d685192`，Codex `357fe240c915`。阶段按退出条件推进；周期按一名主力开发者的相对工作量估算，不是交付日期。环境与复跑方法见[开发环境](development.md)。

## 1. 要做成什么

**CordiAct 是 Android 上能完成开发闭环的工作区，不是另外两个 Agent 的套壳。** 用户在手机选项目、交代目标，看到检索、计划、差异、审批、命令和测试证据；能在本地做安全的小改动，也能把需要完整工具链的任务交给自己的电脑或服务器。断线回来先核实做到了哪一步，而不是再执行一遍。

- **开放性看能力能否组合和替换**：某种模型、搜索、工具、工作流或执行后端可独立接入、明确声明权限、按工作区启停、撤销后不再接受调用；不以「能在手机安装任意 npm 包」衡量开放性。
- **开发能力看闭环能否交付**：从可定位的代码与项目规则，到可审阅变更、真实验证、明确失败和可回退。首先保证单个受控项目的正确性，其次才追求并行覆盖和速度。
- **首个纵向演示**：对受控样例仓库提出一个跨两个文件的小修复。模型先给定位与证据；用户可审阅 patch；实际变更只进任务 worktree；验证命令给出真实 exit code 和日志；中途断网重连，状态对账后可导出 diff 或继续审阅，不会重复改文件或运行测试。手机本地先做单文件，不强求等价能力。

## 2. 三方对照：借鉴什么，不照搬什么

| 能力 | 上游证据 | CordiAct 的取舍 |
|---|---|---|
| 扩展、配置和生命周期 | Harness [架构与配置层](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/docs/architecture.md#L7-L29)：服务、类型化事件、可撤销注册；profile、bundle 和有序配置层 | 沿用 [PluginKernel](../core/src/main/kotlin/com/cordiact/core/plugin/PluginKernel.kt#L3-L55) 与[持久生命周期桥](../core/src/main/kotlin/com/cordiact/core/plugin/ContextManagedPlugin.kt#L3-L12)。先迁移可信内置贡献，再开放声明式扩展。保留精确命名空间；不采用隐式父级能力继承，也不让配置覆盖权限。 |
| 可追踪会话和恢复 | Harness [投影与扩展点](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/docs/architecture.md#L131-L162)、[Gateway 恢复语义](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/gateway/README.md#L60-L68)；Codex [线程与回合操作](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/app-server-protocol/src/protocol/common.rs#L528-L592) | 复用 CordiAct 现有消息、Job、子 Agent 通知和压缩。新增开发活动的可回放记录和 UI 投影，不先重写全局持久化。适配器各自实现恢复，不创造两家都没有的统一「全局事件序号」。 |
| Harness Gateway、SDK、MCP | [Gateway Peer 与复用流](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/gateway/README.md#L25-L51)；[DSH SDK](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/sdk/README.md#L10-L31) 是宿主 stdio JSON-RPC；[MCP 客户端传输](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/mcp/mcp-client/README.md#L84-L109) | 保留现有 Android HTTP MCP。与 Harness 连接优先做受限只读 Gateway 适配，而不是移植 Node 或把 SDK 当 WebSocket 库；stdio MCP 与依赖宿主 OS 的插件留在对应宿主。 |
| 项目指令、技能 | Codex [受信门禁和预算](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/core/src/agents_md.rs#L58-L101)、[目录优先级与范围](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/core/src/agents_md.rs#L141-L275) | 在专用项目根到会话 cwd 的范围内按顺序读取，并显示来源与预算。先复用[现有技能索引](../core/src/main/kotlin/com/cordiact/core/skill/SkillContracts.kt#L17-L62)。项目内容默认作为数据；只有用户信任明确项目后才提供为项目指令，始终不能授予工具权限。 |
| 补丁、终端和验证 | Codex [补丁写入及部分结果](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/apply-patch/src/lib.rs#L391-L469)；[App Server 命令与文件审批](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/app-server-protocol/src/protocol/v2/item.rs#L1692-L1795) | 本地 MVP 只编辑一个现有 UTF-8 文件，使用基线修订与同目录原子替换，无法保证时拒绝；多文件、Git worktree、真正编译由隔离宿主负责。UI 明确区分部分应用、测试失败、未测试与结果未知。 |
| 远程 Codex | [App Server](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/app-server/README.md) 提供双向 JSON-RPC；[TS SDK](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/sdk/typescript/README.md#L3-L42) 包装 CLI 子进程；[恢复时重发仍待完成的服务端请求](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/app-server/src/request_processors/thread_lifecycle.rs#L1363-L1404) | 固定版本，通过认证接入 App Server 的适配器；不把 TS SDK 当移动协议。恢复先读宿主状态再呈现仍有效的审批；补发审批请求不是重发批准动作。`thread/revert` 是对话回退，不是文件回滚。 |
| 审核、并发与续跑 | Harness [实验 Auto Review 的限制](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/experimental/auto-review/README.md#L66-L82)、[调度至少一次](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/schedule/schedule/README.md#L175-L179) | 模型审核可解释风险，不能授予命令执行权限、代替隔离或取消人工确认。先做单任务、显式取消与恢复，再做预算化并发；不能把调度投递等同于外部动作恰好执行一次。 |

上游链接固定到本次检视的提交，历史设计另见[上下文控制](research/0014-context-control-and-compaction-design.md)、[后台任务](research/0015-background-jobs-and-async-subagents-design.md)和[插件内核进度](research/0027-plugin-kernel-progress.md)。后续升级上游应重跑协议夹具，不根据产品名称猜测支持项。

## 3. 目标架构：一个工作区身份，多种有边界的后端

| 层 | 所有者 | 信任与数据归属 |
|---|---|---|
| Android 工作台（Compose） | CordiAct | 本地工作区是项目、会话、授权和审计的权威；工具权限归工作区/会话，不由 UI 切页或模型选择改变。展示后端、目标、目录、审批、差异和证据。 |
| Kotlin 能力与策略层 | CordiAct | 在现有 [ToolManager](../core/src/main/kotlin/com/cordiact/core/tool/ToolManagerContracts.kt#L505-L585) 上扩展。能力注册是可撤销的；是否准入由执行路径复核，不靠提示词或工具是否可见。 |
| 受限本地项目后端 | CordiAct | 显式导入到独立的 app 私有项目根；代码搜索、分段读取、diff 与单文件写入。不复用 [仅管理 `memory/` 的 JGit 服务](../core/src/main/kotlin/com/cordiact/core/workspace/WorkspaceGitContracts.kt#L23-L49)，也不开放任意目录、设备权限和本机 shell。 |
| 自有宿主开发后端 | 用户的电脑或 CI runner | Git、构建、测试、LSP、索引、独立 worktree。Android 的一次授权不是宿主通用权限；宿主必须独立校验 workspace 映射、可用操作与数据外发。初期选一个明确的任务 API，不把裸 SSH 作为等价隔离层。 |
| Harness / Codex 适配器 | 各宿主拥有真实执行状态，CordiAct 持本地映射与投影 | `(workspaceId, projectId, hostId, remoteRef, capabilityVersion)` 显式绑定，不把远端路径当本地 ID；DSH 与 Codex 不共享审批或身份。首版无自动双向同步。没有独立宿主隔离、范围凭据或可靠身份核验时不开放写操作。 |

最初仅为有现成消费者的能力定义接口：`ProjectRead`、`PatchPreview/Commit`、`VerificationRun`、`RemoteSessionFollow`。先做一个带夹具的实现，再抽公共层；不先设计通用插件语言。公开 DTO 用显式 `backendId / projectId / runId / operationId`，来源和目标可见。开发动作区分 `WAITING_APPROVAL`、`RUNNING`、`STALE`、`APPLIED`、`FAILED`、`CANCELLED`、`INTERRUPTED`、`UNKNOWN`；验证回执另记 `PASSED`、`FAILED`、`TIMEOUT`、`NOT_RUN`、`UNKNOWN`，绑定实际输入修订、验证预设和可核对结果。取消请求与底层确认分开；不能观测是否有副作用时标 `UNKNOWN`，禁止自动变更、批准或重新发送，先查询宿主和文件版本。

一次审批至少绑定目标、会话、操作 ID、代次、参数摘要以及工作目录/文件版本；过期、范围改变、连接切换或资料缺失时拒绝。远端命令审批必须看到实际命令、目标、数据和授权边界，文件审批必须关联同一 `thread/turn/item` 的实际差异；没有 diff 不批准。审批与预览不含凭据；日志有体积和保留周期，外部内容不能成为宿主指令。插件禁用/撤权后阻断新调用并清理注册；无法确认已经发出的副作用时记录事实，不声称撤销成功。

## 4. 阶段、依赖与验收

| 阶段 | 计划交付 | 退出条件 | 优先级/估算 |
|---|---|---|---|
| M0 开发基线 | 独立 JVM 入口复用真实模块；工作区固定工具链；APK 仅由 GitHub Actions 打包；建立代码任务和连接失效基准 | 清洁检出能跑 JVM；报告可区分 Android CI、本机和真机；记录每个夹具版本和结果，不依赖密钥 | P0，约 1 周 |
| P 插件化与 DIY（先做） | [插件路线](architecture/plugin-extension.md)：作用域、注册代次与撤销；[Agent 编写插件](testing/authored-plugins.md)，由用户检查并批准一个具体版本 | 既有全局工具不受污染；作用域与撤销通过可重复测试；Agent 能写、验证、申请启用和使用自己编写的插件；未知扩展不获得权限 | P0，M0 之后、M1 之前 |
| M1 手机原生开发模式 | 独立 project binding；只读检索与项目指令；带预览和修订校验的单文件修改；一屏串联活动与证据 | 详见下节，锁屏/切会话/进程重启不误批；旧版本写入不生效；无需真实模型的端到端夹具可跑 | P0，约 3–5 周，依赖插件化阶段的用户 DIY 闭环 |
| M2 远端开发闭环 | 用户可控 x86_64 runner，任务独占 worktree；显式命令审批、流式验证、diff 导出；单独追加固定版 Codex App Server 适配 | 勿写用户原 worktree；取消/断线/部分写入有真实证据；同仓两个 run 不串目录；可完成首个纵向演示，真实设备重连不重复执行 | P0，约 4–8 周，依赖 M1；Codex 复杂流程不阻塞基础 runner |
| M3 Harness 互操作与扩展 | 先同版本只读会话跟随（可与 M1 并行做夹具）；再扩为经过授权的提交、取消、白名单能力；两种可独立启停的内置贡献作为插件 API 样板 | 非 owner 且限定资源；断线、重复帧和缺口用快照/日志对账；未激活观察不启动冷会话；工作区 B 无法看到 A；插件撤回移除贡献，迁移可回退 | P1，约 4–8 周，写入依赖 M2 的恢复与审核经验 |
| M4 开放交付与长期可靠性 | 签名声明式 bundle、开发预设、受控 MCP 安装、版本兼容与升级策略；多任务工作区、预算、可暂停目标与 LSP/索引 | 至少两个不同贡献经过真实 App 装配和迁移；限额/撤销/可达性有协议测试；API 版本与兼容矩阵公开，有真机验证与演练记录 | P1–P2，按证据迭代；不承诺任意 JS/Cordis/npm 本地运行 |

**Harness 首条协议线**：选择版本固定且有资源限制的 Peer，经 TLS 读取明确白名单。不要把浏览器 owner 配对直接发给手机、不要用全权会话做只读。先建立监听再取快照/历史分页，用同一快照游标拼接已缓冲的增量；按协议去重、补缺和丢弃旧代数据。订阅断开不等于远端取消。分开考察 `session/prompt` 按请求 ID 的去重和真正的工具副作用；前者即使经夹具验证，也不能推广到别的动作。远端附件、宿主文件、审批转发不进第一版。

**Codex 首条协议线**：宿主以自己的固定版本启动 App Server；Android 经认证的 TLS 边界转发最小 JSON-RPC 方法集，不把裸 WebSocket 暴露到公网。以 `threadId` 恢复并重读，不依赖实验文件路径恢复或通知全量补发。区分“服务端仍 pending 的审批”与已经结算的审批，绑定当次 server request ID。该快照的 [`turn/start`](https://github.com/openai/codex/blob/357fe240c91563c1e6c9690bb27a2e609282d615/codex-rs/app-server/src/request_processors/turn_processor.rs#L727-L777) 也可能给活动回合追加输入，丢回执后不能盲重试。没有验证传输安全、授权显示和断线恢复，客户端只允许观察，不允许批准或执行。

**开放扩展的先后顺序**：可信内置贡献 → 可撤销的 HTTP MCP 连接 → 有版本和声明能力的签名配置包 → 受控宿主插件。发布 SDK 前用两个不同贡献方验证安装/升级/停用；将声明权限和实际授权分开，更新新增能力时重新同意，撤销后旧连接与旧注册失效。工作流先支持有界声明式步骤，区分目标、日程、Job 和子 Agent，不以脚本执行能力作为编排前提。

## 5. M1 可直接拆成 PR 的工作清单

1. **PR-1 · 项目只读入口**：在 `core` 定义项目 ID、根及信任状态，不把普通工作区直接变成 Git 工作区。用受控小型项目夹具验证路径域、分页搜索、UTF-8 读取、大小限制、暂停/撤销与会话切换。App 只读，不能执行项目脚本。
2. **PR-2 · 项目上下文**：按显式 cwd 和受信设置加载项目指令；预算、优先级、路径和修订可检查；沿用现有技能目录，不为此给通用文件工具开放隐藏目录。保留来源与模型实际看到的指令引用；不让项目内容配置工具授权。
3. **PR-3 · 单文件 Diff**：只允许已有普通 UTF-8 文件与有界 patch。复用[现有文件后端](../app/src/main/java/com/cordiact/app/file/AndroidWorkspaceFileBackend.kt#L87-L126)的修订机制，但 Coding 严格模式不走非原子 fallback。按当前状态预览、人工批准、执行前比较修订、写后报告实际范围/新修订；任一步不确定则重读。Coding 模式的通用写工具不能成为绕开此路径的入口：Tool Manager 层也要测试拒绝。
4. **PR-4 · 证据显示和回归**：补丁 ID、目标、旧/新修订、审批结果、从未执行/已应用/未知状态可从日志恢复。Compaction 不能删原始证据。模型回复不能把未运行的验证标为已通过。至少覆盖拒绝、取消、陈旧、两会话切换、低存储与重启的 Compose/设备测试。
5. **PR-5 · 首个远端只读 Spike（可独立）**：本地夹具跑受限 Harness handshake/snapshot/follow 和 Codex thread/read/resume；输出两份协议探测结果、拒绝矩阵和选择理由。仅在有已授权宿主时做真实连接，不把容器配置等同于产品远端集成。

## 6. 量化与风险管理

- **每次增量的硬门槛**：无越权写入、无隐式权限提升、无在断线后重复外部动作。JUnit 测数据和状态机，fake host 测 wire 协议和重连，主 Android CI 测 Compose/打包；真机单独看拒绝通知、省电、前后台切换、杀进程和升级。任何一个结果未知都不可记为通过。
- **M0 建立 12 个可重复场景**：检索/定位 3，补丁/冲突 3，真实验证与失败诊断 2，拒绝与隔离 2，断线/重启 2。阶段性记录成功数、无效写次数、重复副作用次数、验证证据完整率以及 p50/p95 耗时；相同样例、模型版本和预算才做趋势对比。建议发布门槛：功能场景至少 10/12，越权与重复副作用均为 0，所有宣称运行过的验证都有机器可读结果；性能在有实测基线后设阈值。
- **产品能力不由开关名背书**：Android 后台运行受 OS 和厂商限制；跨进程或网络断开不保证续执行。当前 QuickJS 为 PoC；有审核、隔离和升级测试之前不开放通用 JS 插件。上游接口是版本相关的证据，不是稳定承诺。失败时保留最近的只读快照、停止提交、暴露修复路径，不静默换到权限更大的后端。
- **范围控制**：先交付“打开一个项目、改一处、验证并看到证据”；最后才扩多宿主、多 Agent、市场与离线 JS。允许常规软件开发与防御性检查，不将自动利用漏洞、入侵或攻防编排作为执行场景。除非用户另行授权，不部署宿主、不交出设备权限、不推代码到外部目标。
