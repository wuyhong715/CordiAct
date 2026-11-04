# 0006 子 Agent 与子 Agent 编排：调查与设计

- 日期：2026-11-04
- 参照快照：`deepseek-harness`（工作区本地副本）、`codex`（工作区本地副本）
- 上游相关包：`packages/subagent/*`、`packages/workflow/*`、`packages/jobs/*`；`codex-rs/core/src/agent/*`、`codex-rs/core/src/tools/handlers/multi_agents*`、`codex-rs/agent-roles`

## 0. 结论

两家的做法在**模型可见面**上几乎相反，但骨架上是一件事：

- **DeepSeek-Harness**：一个显式的 `SubagentProvider` seam，本地 `spawn`（空上下文）/ `fork`（父会话已完成轮次做种子）两个 provider，`tool-subagent` 只挂 `subagent` / `send_message` / `interrupt_agent` 三个工具，委派立刻返回子会话 id，结果靠**完成通知**回传。编排不是工具，`workflow-ptc` 是**同一个 seam 的第二个消费者**——模型写一段 JS，脚本里的 `agent()` 逐个调用同一个 `startActivation`，`delivery: 'caller'`（只收结果，不注入父代理）。
- **Codex**：V2 是**邮箱 + 状态机**的一整套工具（`spawn_agent` / `send_message` / `followup_task` / `interrupt_agent` / `wait_agent` / `list_agents`），`send_message` 只入队不触发回合，`followup_task` 才触发；角色（`agent-roles` 的 TOML）**只能收窄父权限，不能替换父授权**，`features` 只允许写 `false`。

这两套都建立在一个 CordiAct 现在**没有**的前提上：回合是异步的、可被事件唤醒的调度器。而 CordiAct 的回合是同步的——`ConversationTurnService.submitWithTools` 一次性跑完 `ToolLoopPlugin.run` 才返回，`ToolDispatcher.dispatch` 是阻塞调用，而且每个会话同时只允许一个回合（`activeSessions` 集合）。

所以本设计不照搬任何一侧，落成三件事：

1. **会话树**——子代理就是 Session，需要 DB v5 加上 `parent_session_id` / `session_kind` / `delegation_depth`。这是两个插件的共同地基。
2. **`SubagentRuntime`（JVM 模块 `subagent`）**——一个同步的 `start()` 原语：给定父会话、标签、提示词、深度，起一个子会话、跑完一个完整回合、返回结果。
3. **编排 = 在该原语上的声明式 DAG**，不用 JS。CordiAct 的 QuickJS 在隔离进程里、只跑 hash 固定的内置脚本，给模型一个 eval 面需要 S3 PoC 刻意没有开放的能力；而且脚本里的 `agent()` 会绕开工具管理器的逐次授权。声明式的 plan 是数据，可以被严格校验、事前估算成本、完整展示进度，并且每个子回合仍然走同一套 `ToolManagerService.invoke` 授权。

分期：**P1 会话树 + 阻塞委派**；**P2 编排**；**P3 可继续子代理（异步邮箱工具集）**。P3 排最后不是因为它不重要，而是它需要改动回合调度，而 P1/P2 的价值不依赖它。

## 1. 已核对的上游机制

### 1.1 DSH：seam 与两个 provider 形状

`SubagentProvider` 有两种能力形状，**方法是否存在就是能力声明**：

| 方法 | 语义 | 后续消息 |
|---|---|---|
| `start(request)` | 一次性外部执行，返回自有句柄 | 拒绝 |
| `prepareContinuable(request)` | 本地可继续子代理，只返回"是否用父历史做种子"这份数据 | 接受 |
| 两者都有 | 由 manager 选择本地路径 | — |

启动期能力由 `SubagentCapabilities` 声明并**在准备之前逐项校验**：`agentOptions`、`agentPreset`、`outputSchema`、`depthLimit`。关键的一条设计约束写在类型注释里：

> `ContinuableCreateSpec` 是 DATA，不是能力：它不携带 `Agent`、`AgentHandle`、提示词投递、结果、dispose 或 resume 操作，因为 continuation manager 在准备之后拥有子级的整个生命周期。

这条值得抄。它的反面是 provider 能自己动子会话，于是所有权、取消、清理三件事会在两个地方各写一遍。

**fork 的种子边界**：`subagent-fork-in-process` 只取父会话日志中**最后一个 `turn/end` 之前**的连续前缀。理由写得很直白——当前工具调用所在的回合是不平衡的，不能作为合法子会话重放。这直接决定了 CordiAct 的 fork 语义（见 3.5）。

**深度**：`maxDepth` 由 Host 设置提供默认值，**默认 1**；`0` 禁止工具委派；工具显式配置优先；`resolveMaxDepth` 每次委派时求值，所以改设置下一次生效。子级 computed depth 必须 `<= maxDepth`，否则启动即拒绝（`assertSubagentMaxDepth`）。

**投递策略**：`delivery: 'parent'` 结束后给父代理发通知（模型看得见）；`'caller'` 只把结果交还给调用方。workflow 用后者，所以编排跑完不会往父会话塞 N 条通知。

### 1.2 DSH：面向模型的工具面

`tool-subagent` 注册三件，且**描述文案随 provider 换**：

- `subagent` — 立刻返回子会话 id，附一句"运行时会在结束时通知你"。
- `send_message` — 给直接子级或自己的父级发消息；只有相邻本地代理可通信。
- `interrupt_agent` — 请求停止当前工作，调用不等待；本地子级之后还能 `send_message` 继续。

`tool-subagent-control` 另注册 `list_agents`，展示 `running` / `inactive`，并明确写了这两个值**不描述任务完成情况，也不保证 `send_message` 会成功**。

文案生成函数 `providerWording(inheritsParentContext)` 是这层最容易被忽视的设计：

- 继承上下文（fork）→ "它已经看到本对话的已完成轮次，只补充新信息"
- 不继承（spawn）→ "它不共享本对话的上下文，必须包含一切需要的信息"

这条必须抄。让模型对着一个空上下文的子代理说"如上一个问题"，是最高频的无效委派。

### 1.3 DSH：workflow 是第二个消费者，不是第二套机制

`workflow-ptc` + `tool-workflow` 的模型面是一个 `workflow` 工具，参数是 `meta`（`name` / `description` / `whenToUse` / `phases`）+ `body`（JS）。脚本可用的钩子只有六个：

- `agent(prompt, opts?)` — 跑一个子代理到完成；返回最终文本，给了 `opts.schema` 则返回校验过的对象；子代理失败解析为 `null`，便于 `.filter(Boolean)`。
- `pipeline(items, ...stages)` — 每个 item 独立穿过各阶段，阶段之间**没有栅栏**；某阶段抛错只把该 item 丢成 `null`。
- `parallel(thunks)` — 并发跑零参函数并等齐（真有栅栏时才用）；抛错的 thunk 解析为 `null`。
- `phase(title)` / `log(message)` — 进度。
- `args` — 工具参数原样。

执行在受限 Node 进程的 `vm.Context` 里，**没有文件系统、网络、定时器、Node API**。三道闸：`maxConcurrentAgents`（FIFO 等槽位）、`maxTotalAgents`（防跑飞的兜底，超了抛 `AGENT_CAP` 结束整个脚本）、`maxItemsPerCall`。

错误分级是这套设计里最精的一点：**普通子失败/阶段抛错 → 该 item 变 `null`；钩子误用、未知选项、不支持的 schema、超限 → 抛 `WorkflowError` 结束整个脚本**。基础设施故障（provider 拒绝）也被提升为 fatal，否则"provider 坏了"会被读成"子代理答失败"，进而在组合子里溶解成一个 `null`。

编排的落点：`startChild` 调的是 `subagents.startActivation({ delivery: 'caller' })`。所以编排和委派共享准入、取消、深度、结果捕获——世界上只有一套子代理。

### 1.4 Codex：两代工具，V2 是邮箱模型

V1 是一个命名空间 `multi_agent_v1`，V2 是平铺函数。V2 的语义分工：

| 工具 | 语义 |
|---|---|
| `spawn_agent` | 必填 `task_name`（小写字母数字下划线）与 `message`；子代理**默认继承当前模型**，显式模型覆盖最多 5 个 |
| `send_message` | 投递到邮箱，**不触发新回合** |
| `followup_task` | 投递给目标，**目标空闲则触发回合** |
| `interrupt_agent` | 打断当前回合，之后仍可收消息 |
| `wait_agent` | 等邮箱更新（排队消息 + 终态通知 + 被 steer 的用户输入），有最小超时防紧轮询，默认 30s，硬上限由配置给 |
| `list_agents` | 列当前 root 线程树中的活代理，可按 `task-path` 前缀过滤 |

`AgentStatus` 是封闭集合：`pending_init` / `running` / `interrupted` / `shutdown` / `not_found` / `completed{completed: string?}` / `errored{errored}`，`is_final` 决定等待何时返回。

`close_agent` 的描述里有一句运营性事实值得记：**已完成的代理保持打开并计入并发上限，直到被显式关闭**。

### 1.5 Codex：角色只能收窄

`agent-role-config.rs` 的角色文件是 TOML：`description`（必填，用于 spawn 工具的引导文案）、`config_file`（角色专属配置层）、`nickname_candidates`，其余字段 flat 进 `ConfigToml`。

`role.rs` 的 doc comment 把边界写死了：

> Roles may customize the child or reduce its capabilities, but never replace the parent session's authority.

实现上只有两条路径生效：`features` 里**只有 `enabled == false` 的项被写进覆盖层**（ShellTool / Apps / Plugins / MemoryTool / RequestPermissionsTool 等），`skills` 只做 `retain(|skill| !skill.enabled)` 收窄。也就是说角色层**没有任何一次写入能开出父层没有的东西**。

另一条：`agent_type` 省略 = 继承父代理类型并做 **full-history fork**（`SpawnAgentForkMode::FullHistory` 是唯一变体）；显式给类型时用 `default`。

### 1.6 三家的共同点（本设计直接采纳）

1. **子代理的上下文不会自动共享**；共享只通过显式 fork/seed，且只到父的最后一个完整回合。
2. **递归必须终止**：DSH 的 `maxDepth` 默认 1，kimi 的内置子代理干脆不再派发，codex 用角色 feature 收窄。
3. **子代理永远不能拿到父代理没有的权限**：DSH 的能力只用于"拒绝不支持的请求"，codex 的角色只写 `false`，CordiAct 0003 的 profile 只做交集。
4. **大体量文本用引用而非内联**：子代理的历史留在它自己的会话日志里，父级只收结果和我方会话 id。

## 2. 由 CordiAct 现状推出的约束

以下每条都已核对代码，不是推测。**注：C2 与 C3 之间的"回合在 UI 线程"已在 P1 落地时修正**（见 5.1 第 1 条），此处保留原始观察，因为约束推导是从它出发的。

**C1 回合是同步的。** `ConversationTurnService.submitWithTools` 顺序执行：追加用户消息 → `toolLoop.run(...)`（内部 `while (rounds < maxRounds)`）→ 取 `ToolLoopOutcome` → 落库最终文本 → 返回。`ToolDispatcher.dispatch` 签名返回 `ModelMessage`，阻塞。`ToolLoopService.run` 也是同步签名。
→ **委派工具必须阻塞到子回合结束**才能把结果作为工具结果返回。这不是妥协，是当前架构下唯一诚实的实现。

**C2 工具执行只有一条离线程路径，而回合本身跑在主线程上。** `MainActivity.submit()` 在 Compose 的点击回调里**同步**调用 `interactionService.submitOrdinary(...)`，没有切协程、没有 `Dispatchers.IO`；只有 `onEvent` 回调被 `handler.post` 回主线程。这条调用链一路到 `ToolLoopPlugin.run` → `ToolDispatcher.dispatch` → `ToolManagerService.invoke`。而 `executeWithTimeout` 只在 `timeoutMs != null` 时把 block 丢进 `newCachedThreadPool` 的 `FutureTask`。
→ **任何没有声明 `timeoutMs` 的工具，目前都在主线程上执行。** 对普通工具这不明显（毫秒级），对委派是致命的：一次委派会把 UI 冻结一整个子回合。所以 `delegate_task` 与 `orchestrate` 声明 `timeoutMs` 是**硬性阻断条件**，不是优化项。这也是一个已经存在的潜在缺陷（文件读写、搜索若未声明超时同样在主线程），只是还没人踩到。

**C3 一个会话同时只有一个回合。** `activeSessions: ConcurrentHashMap.newKeySet<String>()`，`check(activeSessions.add(sessionId))`。
→ 子代理天然有独立会话，所以并发安全。但也意味着**父会话在委派期间是"忙"的**，任何想往父会话塞通知的做法都会撞上这条检查。

**C4 会话表没有树。** DB v4 的 `conversation_sessions` 只有 `(session_id, workspace_id, title, created_at, updated_at)`，`workspace_id` 是 `ON DELETE RESTRICT` 外键；`list(workspaceId)` 直接列出该工作区全部会话。
→ 不做 schema 变更，子会话会污染侧边栏，且父子关系只能靠标题猜。

**C5 授权是逐次、在管理器侧复核的。** `ToolManagerService.invoke` 每次重新算 `ToolAvailability`（启用 / 属主启用 / 能力已授权），`@` 授权由 `ToolInvocationContext.grantedScopes` 承载。
→ 子回合的 `ToolInvocationContext` 必须由父上下文**推导**出来，而不是从存储里重新解析一遍。

**C6 没有子进程。** `SandboxManagerPlugin` 只接受 `ANDROID_ISOLATED_SERVICE`，且 `bundledVerifier` 只信任 APK 内置插件。
→ DSH 的 ACP / Codex / Claude Code 外部 provider 在 Android 上没有对应物，`start()` 那一路不实现。

**C7 沙箱是隔离进程 + 固定脚本。** S3 的 QuickJS 只跑 hash 固定的内置脚本，"No third-party plugin path is enabled"。
→ 编排不给模型 eval 面。

## 3. 设计：子 Agent 插件（`core.subagent`）

### 3.1 形状

```
Session(user, "帮我查三个竞品")            depth 0
├── Session(subagent, "竞品A定价")         depth 1   ← 阻塞委派
├── Session(subagent, "竞品B定价")         depth 1
└── Session(subagent, "汇总")              depth 1
```

子代理 = 一个有 `parent_session_id` 的普通会话。它有自己的消息、自己的回合、自己的工具列表。父级拿到的只有 `{childSessionId, text, stop, truncated}`——**以及一个可以打开的用户界面**，这是 DSH 的 catalog 在 CordiAct 里的等价物：用户能读子代理的完整轨迹，而不是只信父级转述。

### 3.2 数据模型（DB v5）

```sql
-- 会话树。self-FK，与外键开关配合，删父会话连带删子树。
ALTER TABLE conversation_sessions ADD COLUMN parent_session_id TEXT
    REFERENCES conversation_sessions(session_id) ON DELETE CASCADE;
ALTER TABLE conversation_sessions ADD COLUMN session_kind TEXT NOT NULL DEFAULT 'user';
    -- 'user' | 'subagent' | 'orchestration'
ALTER TABLE conversation_sessions ADD COLUMN agent_label TEXT;         -- 3-5 词显示名
ALTER TABLE conversation_sessions ADD COLUMN delegation_depth INTEGER NOT NULL DEFAULT 0;

CREATE INDEX conversation_sessions_parent ON conversation_sessions(parent_session_id, created_at);
```

迁移是加列而非重建表（现有 v2→v3 用的是 drop/rename，那是为了改外键，这里不需要）。`onConfigure` 已经 `setForeignKeyConstraintsEnabled(true)`，所以 `ON DELETE CASCADE` 自引用生效。

`ConversationSession` 增加对应四个字段（全部带默认值，避免破坏既有构造点）：
`parentSessionId: String? = null`、`kind: SessionKind = SessionKind.USER`、`agentLabel: String? = null`、`depth: Int = 0`。

**列表语义必须一起改**：`list(workspaceId)` 加 `AND session_kind = 'user'`。否则侧边栏会随一次编排炸出三十条"子代理"。子会话走新查询 `listChildSessions(parentSessionId)`。

### 3.3 契约（`core` 模块，`com.cordiact.core.subagent`）

```kotlin
const val SUBAGENT_PLUGIN_ID = "core.subagent"
const val SUBAGENT_CAPABILITY = "core.agents.delegate"

enum class ChildDelivery { FOREGROUND }   // P3 增加 BACKGROUND

data class ChildSpec(
    /** 与父相同：v1 禁止跨工作区委派，见 3.6。 */
    val workspaceId: String,
    val parentSessionId: String,
    /** 3-5 词显示名，写进 Session.agentLabel。 */
    val label: String,
    /** 自包含的任务描述。子级看不到父会话。 */
    val prompt: String,
    /** 到子级的相对深度；由运行时从父会话推导，调用方不能自报。 */
    val depth: Int,
    val model: ModelId? = null,
    val profileId: String? = null,
)

data class ChildResult(
    val sessionId: String,
    val text: String,
    val stop: ToolLoopStop,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** 结果被 maxResultChars 截断时为 true；完整轨迹在子会话里。 */
    val truncated: Boolean = false,
)

interface SubagentRuntimeService {
    val maxDepth: Int
    /** 从父会话读出深度。根会话为 0。 */
    fun depthOf(sessionId: String): Int
    /** 该上下文的父级还允许继续派发吗（depth < maxDepth）。 */
    fun canDelegate(context: ToolInvocationContext): Boolean
    /** 同步跑到子回合结束。任何失败都以 ChildResult.stop 表达，不抛。 */
    fun runChild(spec: ChildSpec, parent: ToolInvocationContext, deadline: Long): ChildResult
    fun listChildren(parentSessionId: String): List<ChildSessionInfo>
}
```

三条设计决定：

- **`runChild` 不抛异常。** 失败是 `ToolLoopStop.FAILED` / `CANCELLED` / `ROUND_LIMIT` 加一段说明。工具层永远能给模型一个结构化结果，与 `ToolDispatcher.dispatch` "Never throws"的既有约定一致。
- **深度由运行时推导，不由调用方传。** 参数里带 `depth` 只是让 `ChildSpec` 自解释；`runChild` 真实使用的是 `depthOf(parentSessionId) + 1`。模型不能通过参数抬高或降低预算。
- **`deadline` 是绝对时间戳**，由工具层从 `toolContext` 的剩余预算算出，不是毫秒数——避免嵌套委派把每一层都当成本层重新计时。

### 3.4 子回合怎么跑

`runChild` 内部复用既有装配，不新写一个"简化版回合"：

```kotlin
// 1. 建会话（带父链接）
val session = sessions.createSession(workspaceId, title = label, parentSessionId, kind = SUBAGENT, depth)

// 2. 工具列表 = 父的工具列表 ∩ profile.toolRefs，再减去委派工具自己
val specs = availableToolSpecs(workspaceId, model).filter { spec ->
    spec.name !in DELEGATION_TOOL_NAMES || depth < maxDepth
}

// 3. 上下文从父推导，不重新解析
val childContext = parent.copy(
    requestText = prompt,          // 子级自己的任务，不是用户的原话
    grantedScopes = parent.grantedScopes,   // 复制，不放大
)

// 4. 同一套回合服务
turnService.submitWithTools(session.id, model, prompt, specs, toolLoop, dispatcher, childContext, ...)
```

四个细节必须写进实现注释：

1. **`availableToolSpecs` 是同一个函数**，包括 `resolveEnabled` + `dispatchable` 过滤。子代理拿不到父代理拿不到的工具，因为算它用的是同一个判定。
2. **`grantedScopes` 是复制的**。子代理不会去查 `@` 记录的存储；授权随上下文走。这样"子代理能读什么"永远等于父回合开始时的授权，不会因为中途有人改了记录而变宽。
3. **`requestText` 换成子级自己的 prompt**。任何读 `requestText` 的工具（搜索的 `shouldExpose`、交互鉴权）看到的应当是它服务的那个代理的任务，不是用户写给别人看的话。
4. **`systemContext` 是父级同一份 skill 索引**。它是工作区级的，不是会话级的，继承正确且免费。

### 3.5 上下文继承：v1 只做"干净子代理"

| 形状 | 语义 | v1 |
|---|---|---|
| `spawn` | 零父上下文，prompt 必须自包含 | ✅ |
| `fork` | 用父会话**最后一个 `turn/end` 之前**的连续前缀做种子 | ❌ 推迟 |

理由不是偷懒：CordiAct 的父会话在工具调用发生时，**助手文本还没落库**——`submitWithTools` 只在回合成功结束后 `appendMessage`。也就是说"当前回合之前最后一条持久化消息"是用户输入，中间的工具来回全部在内存里。要在这种情况下拼一个合法的种子，得先把 `ToolLoopRound` 的中间消息持久化，那会推翻"中间工具流量不落库"这个刻意的决定（`ConversationTurnService` 的注释：*intermediate tool traffic is scaffolding, and storing it would make the transcript unreadable and replayed as history on the next turn*）。

所以 v1 采取 DSH 的 `spawn` 语义，并且**工具描述必须照 `providerWording` 的口径写清楚**：

> `prompt`: 完整的、自包含的任务。子代理不共享本对话的任何上下文，把你需要的一切都写进去：目标、已知事实、约束、期望的输出形状。

这句文案是委派质量的一半。省掉它，模型会写"就用刚才那三个链接"。

### 3.6 授权不变量（可测的那一条）

**子代理不能拥有父回合没有的东西。** 落地成三条可断言的规则：

1. 子级的工具集合 ⊆ 父回合的 `ModelToolSpec` 列表（同一函数算出，同一授权判定）。
2. 子级的 `grantedScopes` == 父级的 `grantedScopes`（值相等，不是超集）。
3. v1 **不做跨工作区委派**：`ChildSpec.workspaceId` 必须等于父的 `workspaceId`，否则拒绝。跨区能力保留在父级的 `@` 里由父级自己用工具读；让子代理跨区意味着要复制一份授权语义进去，而 0003 已经把"记忆不跨工作区、跨区只通过 `@` 授权到记忆文件"定死了。

第 3 条要在测试里显式覆盖：给父级一个 `GrantedScope(其他工作区)`，断言子级的上下文里有它（因为复制），但断言 `ChildSpec.workspaceId != parent.workspaceId` 的调用被拒。

### 3.7 深度与容量

| 项 | 默认 | 行为 |
|---|---|---|
| `maxDepth` | 1 | `depth >= maxDepth` 的子代理**工具列表里没有委派工具**；`runChild` 里另做一次 `check` |
| `maxConcurrentChildren` | 4 | 编排用；阻塞委派天然是 1 |
| `maxChildrenPerTurn` | 16 | 单个父回合累计启动的子会话上限，超了返回 `CHILD_LIMIT` |
| 子回合 `maxRounds` | 8 | 沿用 `ToolLoopLimits` 默认值（`maxRounds=8`, `maxToolCalls=24`），不单独开一套 |
| 子回合墙钟 | `deadline - now` | 由父工具的超时预算推下来 |

深度闸门做两处（工具列表 + 运行时 `check`）是故意的：工具列表决定"模型看不看得见"，运行时 check 决定"就算看见了也跑不起来"。DSH 与 codex 都是这个双重结构。

### 3.8 面向模型的工具（P1）

只注册一个：

**`delegate_task`**

```kotlin
ToolDescriptor(
    id = SUBAGENT_TOOL_ID,
    name = "delegate_task",
    description = """
        把一个自包含的任务交给子代理，在它自己的上下文里做完，返回它的最终结论。
        适合：可以独立完成、且过程不需要占据本对话上下文的局部工作（一次范围明确的调研、
        一次单文件实现、一份分析）。子代理看不到本对话，也不返回中间步骤——只有最终文本
        和它的会话 id，你可以用 list_children 找到它。

        不适合：需要本对话上下文才能做的后续（它看不见）；需要和你的步骤交替协作的任务
        （它是同步的，你会在它结束前拿不到控制权）。
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter("label", ToolValueType.STRING, required = true,
                description = "3-5 词的显示名，用于会话列表。"),
            ToolParameter("prompt", ToolValueType.STRING, required = true,
                description = "完整的、自包含的任务。子代理不共享本对话上下文：目标、已知事实、约束、期望输出形状，全部写进去。"),
        ),
        allowAdditional = false,
    ),
    ownerPluginId = SUBAGENT_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(SUBAGENT_CAPABILITY),
    timeoutMs = 300_000,       // C2：必须非 null，否则会内联在主线程上
    concurrencySafe = true,    // 子会话彼此隔离
)
```

描述里那句"它是同步的，你会在它结束前拿不到控制权"必须留着。不写，模型会设计出"先派三个，再逐个收"的计划，然后卡住。

**`list_children`**（P1 一起给，很便宜）

入参无；返回 `[{sessionId, label, stop, createdAtMillis}]`。这是父级唯一能"看见"子代理的通道，也是把 `childSessionId` 变成可用的关键——否则模型拿到 id 之后什么都做不了。

### 3.9 结果如何回到父级

工具结果是一个 JSON 对象，不是裸文本：

```json
{"childSessionId":"...","label":"竞品A定价","stop":"COMPLETED","text":"...","truncated":false}
```

- `text` 受既有的 `maxResultChars`（32K）约束；超了置 `truncated: true`。
- `stop != COMPLETED` 时**仍然返回** `text`（`ToolLoopOutcome.Stopped.lastText` 已经累积了部分输出），让父级能判断"子代理跑到轮次上限了，我拿到的是一半"。
- 不进 `metadataJson` 的证据链——子代理的轨迹在它自己的会话里，重复存一遍会让父会话的消息行变成垃圾场。

## 4. 设计：子 Agent 编排插件（`core.subagent-orchestration`）

### 4.1 为什么是声明式 plan 而不是脚本

DSH 的脚本钩子很强，代价是三件事：需要 JS 运行时（CordiAct 只有隔离进程的 QuickJS，且只跑固定脚本）；`agent()` 在脚本里调用，绕开了工具管理器的逐次授权上下文；模型写的脚本一旦有 `await` 顺序错误，用户看到的是"跑了 40 个子代理然后超时"。

CordiAct 的编排用**数据**表达同样的形状：

```kotlin
data class OrchestrationPlan(
    val name: String,                  // kebab-case，用于会话标签
    val stages: List<OrchestrationStage>,
)

data class OrchestrationStage(
    val id: String,                    // 本 plan 内唯一
    val task: String,                  // 提示词模板，可含 {{item}} 与 {{prev}}
    val items: List<String> = emptyList(),   // 空 = 单个子代理
    val mode: StageMode = StageMode.MAP,
    /** 仅 REDUCE：把上一阶段各 item 的输出合并后再交给一个子代理。 */
    val combine: Combine = Combine.JSON,
)

enum class StageMode { MAP, REDUCE }
enum class Combine { JSON, LINES }
```

语义（严格对应 DSH 的 `pipeline` / `parallel`）：

- `MAP`：为 `items` 里每一项起一个子代理，`{{item}}` 替换为该项。
- `REDUCE`：起**一个**子代理，`{{prev}}` 替换为上一阶段全部输出的合并文本。
- 阶段之间是**栅栏**（前一阶段全部结束才进下一阶段）。DSH 用 `pipeline` 刻意允许无栅栏的多阶段流水，但那需要脚本来表达"每个 item 独立推进"；声明式的 plan 里，阶段栅栏是唯一不会误导读者的语义。要真正的流水，就让每个 item 走完整条链——那本来就是一个 `MAP` 里放 N 个同构任务。
- **单个子代理失败 → 该 item 的输出为 `null` 并附诊断**，阶段继续。这与 DSH "ordinary child failures become per-item nulls" 一致。
- **plan 本身不合法（未知阶段 id、`REDUCE` 引用不存在的上一阶段、超出上限）→ 整个工具调用失败，一个子代理都不启动。** 这个"校验先于任何消耗"的顺序是重点：宁可在零成本时拒绝，也不要在花了 20 个子回合之后才发现第三阶段引用错了。

### 4.2 为什么它必须建立在同一个原语上

`orchestrate` 的每个子代理都调用 `subagentRuntime.runChild(...)`，不是另一条路径。于是自动得到：同样的工具子集规则、同样的授权推导、同样的深度闸门（编排的子代理在 depth 1，`maxDepth = 1` 时它们没有委派工具）、同样的会话持久化。

如果编排自己起会话，这四条会各写第二遍，然后第二遍总会先漂移。

### 4.3 并发与预算

```kotlin
data class OrchestrationLimits(
    val maxStages: Int = 8,
    val maxItemsPerStage: Int = 32,
    val maxTotalChildren: Int = 64,
    val maxConcurrentChildren: Int = 4,
    val wallClockMillis: Long = 900_000,
)
```

执行器是一个固定大小的池（`min(maxConcurrentChildren, items.size)`），每个任务是一个子回合。`maxTotalChildren` 是 DSH `maxTotalAgents` 的对应物——不是功能，是**跑飞兜底**：plan 写错成自引用时，用户付出的是 64 个模型回合的账单，必须是硬上限。

**成本事前可见**：`orchestrate` 的返回里带 `childrenStarted` 与累计 token 数。这是 DSH `WorkflowResult.agentsStarted` 的直接照搬，也是手机上唯一能让用户理解"这一次点了多少东西"的数字。

### 4.4 工具面

**`orchestrate`**

```kotlin
ToolParameter("plan", ToolValueType.STRING, required = true,
    "JSON 编排计划：{\"name\":\"...\",\"stages\":[{\"id\":\"a\",\"task\":\"...{{item}}...\",\"items\":[...]}]}")
```

plan 用**一个 JSON 字符串参数**，而不是嵌套的 `OBJECT` schema。原因是 `ToolInputSchema` 只表达顶层参数（嵌套、`oneOf`、`$ref` 都进不去），0005 已经为 MCP 加过 `rawInputSchemaJson` 这个逃生舱；但这里更简单的做法是让 Kotlin 侧用 Gson 解析并做**比 schema 更严的**校验（阶段唯一性、引用有效性、上限、模板占位符存在性）。一个字符串参数的失败信息能做到"第 2 阶段的 items 有 33 项，上限 32"，而 JSON Schema 只能给"不符合 schema"。

`timeoutMs` = `wallClockMillis + 60_000`。同样受 C2 约束：不声明超时就会内联执行。

### 4.5 进度

同步工具没有中间事件通道。P2 的进度可见性靠两件事：

1. **每个子会话在创建时就出现在会话树里**。用户在编排跑到一半时打开会话列表，能看到"竞品A定价（running）"。
2. **子会话标题带阶段前缀**：`<plan.name>/<stage.id>/<index>`，40 个会话排在一起能看出分组。

真正的实时进度条要么等 `ToolLoopService` 支持事件回传，要么等 P3 的异步工具。P2 不做假进度。

## 5. 分期与验收

### P1 — 会话树 + 阻塞委派 ✅ 已实现（2026-11-04）

落地时发现三处设计未预料的问题，都已修：

1. **回合根本不在后台跑。** `MainActivity.submit()` 在 Compose 点击回调里同步调用 `submitOrdinary`，而不是只把回调 post 回主线程。所以整个工具循环连同委派都在 UI 线程上——委派会冻结界面一个子回合。修法：提交本身丢到单线程执行器，`submitting` 与 `activeCall` 分成两个状态（后者只有在回合还没返回终态时才提供取消）。
2. **`submitWithTools` 拿不到模型输出。** `ModelAdapterService.generate` 提交到线程池就返回 `ModelCall`，事件稍后才到；但工具循环紧接着就读 `roundText` 构造 `ToolLoopRound`。结果是**只要工具列表非空，那个回合拿到的就是空文本 + 零工具调用**。新增 `sendRound`：`CountDownLatch` 等本轮终止事件（有 300s 兜底），才把 round 交回循环。
3. **声明了能力就等于工具永久不可用。** `ToolManagerPlugin` 默认 `ToolCapabilityGate { _, required -> required.isEmpty() }`，而应用从未授予任何能力，于是任何带 `requiredCapabilities` 的工具都不会 dispatch。修法：`BundledConversationPluginCatalog` 显式给出 `BUNDLED_GRANTED_CAPABILITIES`，只含 `core.agents.delegate`。

另外 `ToolInvocationContext` 增加了 `sessionId`——委派工具必须能寻址调用它的会话，否则深度检查和挂父都会读错行。

实现产物：

- `core/src/main/kotlin/com/cordiact/core/subagent/SubagentContracts.kt` — `ChildSpec` / `ChildResult` / `SubagentRuntimeService` / `ChildTurnRunner` / `ChildToolPlanner` / `childContext` / `SubagentLimits`。
- `subagent/` 新 JVM 模块 — `SubagentRuntime`（全部准入规则）+ `SubagentTools`（`delegate_task` / `list_children`）。
- `ConversationSession` 加 `parentSessionId` / `kind` / `agentLabel` / `depth`；store 加 `listChildren`；服务加 `createChildSession` / `listChildSessions`；`list()` 只返回 USER。
- DB v5：四条 `ALTER TABLE` + 父索引，不重建表。
- `ConversationTurnService.runTurnToCompletion` — 子回合复用同一条回合路径，只把结果返回而不流式输出。

验收（JVM，已跑通 21 个测试）：会话树三元组正确、`list()` 不含子会话、`maxDepth=1` 时子级无委派工具、嵌套委派被拒且给出深度诊断、子级 `grantedScopes` 与父相等、`requestText` 换成子级 prompt、跨工作区被拒且未创建会话、runner 抛异常转成 `FAILED`、父会话子预算、结果截断、token 回传、`depthOf` 边界、工具声明了超时与能力、空 label / 无会话被数据化拒绝。

**仍未验证**：`app` 模块本地不可编译（无 Android platform），DB 迁移、UI 线程切换与装配只能在 CI/设备上验。级联删除（验收第 6 条）也需要设备上验一次。

### P2 — 编排 ✅ 已实现（2026-11-04）

落地时的三处修正：

1. **工具超时上限是 300 秒**（`ToolManagerService.MAX_TIMEOUT_MS`），原设计的 900 秒运行预算会直接注册失败。改为 240 秒运行 + 60 秒收尾，并在 `OrchestrationLimits.init` 里断言——在单元测试里发现比在应用启动时发现好。
2. **两个上限互相打架。** `SubagentLimits.maxChildrenPerSession` 原本是 32，而编排允许 64，于是超过 32 的 plan 会通过校验、然后在中途被拒——钱已经花了。把它提到 128，并在注释里写明两个常量的从属关系；加了测试盯住「40 个 item 的编排必须能跑完」。
3. **失败的子代理曾被算作成功。** 原实现按「文本非空」判成败，于是一个 `stop=FAILED` 但有半截输出的子代理会贡献一段文本、`failedCount` 为 0，下游 REDUCE 就会把一个半成品折进一个自信的合计。改成按 `stop` 判定：失败的子代理输出恒为 `null`，部分文本只进 `error` 诊断。

实现产物：

- `core/.../core/subagent/OrchestrationContracts.kt` — `OrchestrationPlan` / `OrchestrationStage` / `StageMode` / `Combine` / `StageOutcome` / `OrchestrationResult` / `OrchestrationLimits`。
- `core/.../core/subagent/OrchestrationService.kt` — 服务接口 + `OrchestrationRejected`（「没启动」与「跑失败」是两种结果）。
- `subagent/.../OrchestrationPlanParser.kt` — 解析与关系校验，全部先于任何子代理。
- `subagent/.../OrchestrationRunner.kt` — 阶段执行器、固定池、`{{item}}` / `{{prev}}` 替换、位置化结果。
- `subagent/.../OrchestrationPlugin.kt` — `ManagedPlugin` 外壳，声明依赖 `core.subagent`。
- `subagent/.../OrchestrationTools.kt` — `orchestrate` 工具（plan 是单个 JSON 字符串参数）。

验收（JVM，23 个测试）：MAP 按 item 顺序生成子代理、**结果按位置而非完成顺序**、并发受 `maxConcurrentChildren` 约束、单项失败变 `null` 且其余存活（`stop=PARTIAL`）、REDUCE 收到 JSON 数组或 LINES、阶段栅栏、子会话 `kind=ORCHESTRATION` 且标题为 `<plan>/<stage>/<n>`、子会话 id 回传、token 合计、超容量/超 items/首阶段 REDUCE/缺 `{{item}}`/重复 id/坏 JSON/未知 mode 全部在零子代理时被拒、工具声明了合法超时与能力。

**仍未验证**：同 P1——`app` 侧的装配与 DB 迁移只能靠 CI 编译 + 设备验证。另外编排的**真实并发行为**（多个子代理同时打模型 API 的速率限制、SQLite 并发写）只有 JVM 假 runner 覆盖，真机上需要专门的观测。

### P3 — 可继续子代理（异步工具集）

这是唯一需要动回合调度的部分，所以单独一期。要点：

- `ChildDelivery.BACKGROUND`：子回合跑在自己的线程上，父回合不等待。
- 因为 C3（一会话一回合），完成通知**不能**直接往父会话追加消息再触发回合。必须走"待投递队列 + 父回合开始时注入"：`submitWithTools` 组装 `initial` 时，把该会话未消费的子结果作为前置的系统/工具消息注入。这就是 codex 的邮箱在同步架构下的最小等价物。
- 工具面按 codex V2 的四件套拆：`spawn_child`（不等待）、`wait_children`（带最小超时防紧轮询）、`list_children`（已存在）、`close_child`（因为已完成的孩子仍占并发额度）。
- `send_child_message` 在 P3 里只支持"投递到空闲子代理并触发新回合"——codex 的 `send_message` / `followup_task` 二分只有在子代理能同时收 steering 输入时才有意义，那时才有。
- Android 前台：后台子回合需要一个前台服务或至少是 `applicationScope` 上的协程 + 进程保活的现实预期。**P3 的验收必须包含"切后台 5 分钟后子回合仍在跑或已正确标记为中止"**，不能只测前台。

## 6. 明确不做

| 不做 | 原因 |
|---|---|
| DSH 的 JS workflow VM | C7：QuickJS 在隔离进程且只跑固定脚本；给模型 eval 面需要 S3 PoC 刻意未开放的能力 |
| DSH 的外部 provider（ACP / Codex / Claude Code） | C6：Android 无子进程 |
| codex 的 message board（频道/帖子/订阅/权限） | 那是一整个代理社交基座，与"父代理派活"不是同一问题；CordiAct 的会话树已覆盖父子通信 |
| codex 的 nickname / 角色文件体系 | 0003 的 `AgentProfile` 已经负责 persona 与模型；再加一层 nickname 是两套身份 |
| 子代理的实时流式输出到父界面 | 需要 `ToolLoopService` 支持事件回传；P2 用会话树代替 |
| 跨工作区委派 | 3.6 第 3 条 |
| fork（父历史做种子） | 3.5：父回合的中间消息不落库，拼不出合法种子；要先改持久化策略 |

## 7. 未验证 / 待核实

1. ~~`submitWithTools` 实际跑在哪个线程~~ — **已核实并已修**：`MainActivity.submit()` 在 Compose 点击回调里同步调用，整个回合在 UI 线程。提交已挪到单线程执行器；`submitting` 与 `activeCall` 分离。
2. ~~`submitWithTools` 能否拿到模型输出~~ — **已核实：不能。** `generate` 异步返回 `ModelCall`，原代码紧接着读空的 `roundText`。已加 `sendRound`（`CountDownLatch` + 300s 兜底）。
3. **子回合的轮次预算需要单独定**。`ToolLoopLimits` 默认 `maxRounds=8` / `maxToolCalls=24`，对"查三个竞品定价"这类委派偏低；但调高又会让跑飞的子代理烧掉更多。需要实测，不是拍数。
4. **`app` 模块本地不可编译**（`/workspace/android-sdk` 只有 cmdline-tools，无 platform）。DB v5 迁移、UI 线程切换、装配三处只做了人工核对，需要 CI 编译后再到设备上验。
5. **`ON DELETE CASCADE` 自引用的实际行为**。v1→v2 迁移会 drop/rename `conversation_sessions`；如果那条路径在任何设备上重放过，父删子的级联要在设备上验，不能只看 SQLite 文档。这是 P1 验收里唯一还没被 JVM 测试覆盖的一条。
6. **子会话的 UI 入口**。只做了"不计入侧边栏"，没有做只读轨迹界面：用户能从工具结果拿到 `childSessionId`，但还没有界面可以打开它。两个插件都受影响。
7. **编排的真实并发行为未经观测**。JVM 测试用的是假 runner，覆盖了调度与位置化结果；但四个子代理同时打模型 API 会怎样（供应商速率限制、连接池、SQLite 并发写）只有真机能回答。`maxConcurrentChildren = 4` 这个默认值也是拍的，需要在设备上按实际失败率调。
8. **子回合的 `ToolLoopLimits` 与编排叠加**。一个编排子代理自己有 8 轮预算，编排层再有 240 秒总预算；两层预算谁是瓶颈没有实测过。
