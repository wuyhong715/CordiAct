# Agent 预配置与 Agent 记忆：设计提案

**状态：** 设计提案，未实现
**日期：** 2026-11-02
**范围：** 为 CordiAct 增加两个受保护系统插件——`core.agent-profile`（Agent 预配置）与 `core.agent-memory`（Agent 记忆）。只做设计，不含 UI、不含实现。
**参考实现：** `deepseek-harness`（v0.2.2-alpha.2）、`codex`、`kimi-code`。关键事实见文末附录，正文只引用与决策直接相关的结论。

---

## 零、已定的产品决定

本版设计基于以下决定，它们不是待议选项。

**来自产品方：**

1. **Agent 绑定工作区**。Agent 预配置属于某个 Workspace，不是全局实体。
2. **记忆不跨工作区**。每条记忆都属于且只属于一个 Workspace。
3. **跨工作区访问只能通过既有 `@` 授权**，且对象是另一个 Workspace 的**记忆文件**，不是记忆库。
4. **预配置是声明式的**：实际能拿到什么由工具管理器、文件管理器决定，**禁止提权**；完全由预配置决定的只有提示词、模型、请求参数。

**来自用户问卷（2026-11-02）：**

5. **记忆要对齐 codex 的体验**——自动学习、自动合并、下次会话直接可用。这条推翻了"先只做显式记忆"的保守安排，见 3.6。
6. **`@` 的授权范围由路径表达**：`@xx工作区` 授权整区，`@xx工作区：记忆` 只授权记忆。授权不再是一个独立的能力开关，而是引用路径的副产品，见 3.5。

**范围边界：**

7. **上下文控制是独立插件，不在本批**。Codex 的 `_context` 查询、`new_context` 交接、旧窗口可检索档案都属于上下文控制，与记忆插件分开设计，本文不涉及。

第 4 条把"预配置"的职责边界划得很清楚；第 6 条则让"跨区"从"要不要开放"变成一个可精确表达的范围问题。两者都使得本设计与三家参考实现的分歧点变得具体——见第三节。

---

## 一、由这些决定推出的整体形状

```
Workspace ──┬── AgentProfile（声明式，可能多条）
            ├── memory/（记忆制品：摘要 / 手册 / 证据）
            ├── SQLite（协调状态：租约 / 水位线 / 用量）
            └── Files
                     ▲
另一个 Workspace 的会话 ──@───┘（授权范围由路径表达，读取是快照）
```

四个要点：

- **记忆的内容权威是文件**，`memory/` 下的三层制品就是记忆本体；SQLite 只存协调状态与用量计数。见 3.2。
- **记忆是一条异步编译管线**，不是"会话结束时摘要一下"。抽取与合并都可失败、可丢弃、可重来。见 3.4。
- **`@` 是唯一的跨区通道**，授权范围由路径表达（`@工作区` / `@工作区：记忆`），读取是快照而非挂载。
- **预配置不持有任何权限**。它能决定的只有提示词、模型引用、请求参数。工具能拿到什么，取决于 `ToolManager` 与 `FileManager`。

---

## 二、`core.agent-profile`：Agent 预配置

### 2.1 核心决策：声明式 + 只减不增

参考实现在这一点上高度一致：**没有一家允许 agent 定义放宽权限**。codex 靠 `AgentRoleOverrides` 的字段缺失保证（结构体里根本没有 sandbox/approval 字段），kimi 靠"subagent 继承 main agent 权限，不重新审批"。

CordiAct 采用同样的原则，但实现方式不同——因为我们的能力模型是 `requestedCapabilities: Set<String>` 而不是结构体：

```kotlin
data class AgentProfile(
    val id: AgentProfileId,
    val workspaceId: String,
    val version: Int,
    val displayName: String,
    val description: String?,

    // ---- 完全由 profile 决定的三项 ----
    val instructions: List<InstructionSource>,
    val modelRef: ModelSelection?,
    val generation: GenerationParameters?,

    // ---- 声明需求，不授予权限 ----
    /** Tools this profile wants. Intersected with the enabled set; never grants. */
    val toolRefs: Set<ToolId>,
    /** Capabilities required for the profile to be usable at all. */
    val requiredCapabilities: Set<String>,

    val origin: ProfileOrigin,
)
```

逐条说明为什么这样切：

| 字段 | 谁能改 | 语义 |
|---|---|---|
| `instructions` | profile | 提示词，完全由 profile 决定 |
| `modelRef` | profile | 模型选择，完全由 profile 决定；null = 继承会话默认 |
| `generation` | profile | 请求参数默认值，单轮覆盖仍可压过 |
| `toolRefs` | **交集** | 只是"我想要这些"；实际可用 = `toolRefs ∩ resolveEnabled(workspaceId)` |
| `requiredCapabilities` | **校验** | 不满足则 profile 不可用（fail-closed），但满足也不会多给任何能力 |

`toolRefs` 用交集而不是并集，是"禁止提权"在类型层面的表达：**profile 没有任何路径让一个未启用的工具变得可用**。

`requiredCapabilities` 是 CordiAct 独有的字段。harness 明确说自己的 preset "not security sandboxes"（`agent-preset-registry/README.md:99`），所以它不需要；我们的插件模型是 fail-closed，必须在装载前就判定。

### 2.2 优先级：显式编号，整体替换

```
BUNDLED(0) < WORKSPACE(10) < IMPORTED(20) < SESSION_OVERRIDE(30)
```

沿用 codex 的做法（`ConfigLayerSource::precedence()` 用整数写死），但语义取 harness 的：

- 同 `id` 多来源时，高优先级**整体替换**，不深合并。harness 的原文："A user override replaces the complete child list and does not automatically merge future changes to the builtin list."
- **不支持 `extends` / 继承**。三家都没有人格继承。
- profile 选定后**对会话冻结**（对应 harness 的 `agent-preset/locked`）。

选择整体替换而非深合并的理由：深合并在"工具集合"这类列表字段上会产生不可解释的中间态——父列表和子列表究竟谁生效？整体替换让"当前 profile 到底是什么"永远可回答。

### 2.3 服务契约与 fail-closed 点

```kotlin
const val AGENT_PROFILE_PLUGIN_ID = "core.agent-profile"

interface AgentProfileService {
    fun list(workspaceId: String): List<AgentProfileSummary>
    fun get(workspaceId: String, id: AgentProfileId): AgentProfile?
    /** Fails closed when any reference is dangling. */
    fun resolveForSession(workspaceId: String, sessionId: String): ResolvedAgentProfile
    fun save(workspaceId: String, profile: AgentProfile): AgentProfile
}
```

`resolveForSession` 只做三件事，任一步失败**抛异常**而不是降级：

1. `requiredCapabilities ⊆ 宿主已授权能力`，否则拒绝。
2. `toolRefs` 每个 id 必须存在于 `ToolManager` 已注册工具中；**悬空引用直接失败，不静默丢弃**。
3. `modelRef` 指向的 provider 仍已注册。

返回值是**不可变快照**：指令文本已解析（`WorkspaceFile` 已读取）、工具集合已取交集、实际生效的 `GenerationParameters` 已算出。

关键一点：**`toolRefs` 的悬空校验必须在 resolve 时做，而不是在 invoke 时**。否则用户会看到一个"能用但静默少工具"的 profile，这比直接失败更难排查。

### 2.4 指令装配

`InstructionSource` 两种：

```kotlin
sealed interface InstructionSource {
    data class Inline(val text: String) : InstructionSource
    /** Must resolve inside the profile's own Workspace. Cross-Workspace paths are rejected. */
    data class WorkspaceFile(val path: String) : InstructionSource
}
```

装配顺序参考两家共识（harness "broad-to-specific"、codex "root→cwd 顺序拼接"）：**按 `instructions` 列表顺序拼接**，不重新排序、不去重。

`WorkspaceFile` 的路径校验：必须在 `profile.workspaceId` 对应的根内。这是"Agent 绑定工作区"的直接推论——**profile 的指令文件不能跨区**。

### 2.5 明确不做

- 不深合并、不继承、不热更新到进行中的会话。
- 不在 profile 里内联工具定义或工具权限。
- 不在 profile 里存任何凭据字段。
- **不允许 profile 声明沙箱策略**（那是 `SandboxManager` 的事）。

---

## 三、`core.agent-memory`：Agent 记忆

### 3.1 定位修正：记忆是编译产物，不是对话摘要

上一版把记忆理解成"存一批条目、按预算注入"。Codex 的实际做法推翻了这一点，而且推翻得比我预想的彻底：

**记忆是一条受控的后台编译管线。** "从历史中学习"被拆成可协调、可校验、可丢弃的独立阶段，而不是一次摘要调用。

三条由此确立的性质：

1. **异步**。抽取不在会话内发生，不在用户等待的路径上。
2. **可丢弃**。每个阶段产物都是中间件，失败可以重来，不污染已生效的记忆。
3. **可校验**。一次合并是否成功，由产物结构判定，而不是由模型自述。

这三点决定了后面所有设计：不能用"会话结束时顺手总结一下"来实现。

### 3.2 架构：SQLite 管状态，文件管内容

上一版说"SQLite 为权威、文件为导出视图"。这个说法只对了一半，需要修正：

- **内容权威是文件**。`MEMORY.md`、`memory_summary.md`、`rollout_summaries/` 是记忆本体，`@` 引用它们、citation 指向它们、用户读的也是它们。
- **进度权威是 SQLite**。租约、水位线、阶段产物、用量计数——这些是协调状态，不是记忆内容。

Codex 本身就是这个结构：SQLite 作为分布式状态机，文件作为制品。我之前的错误是把两者混成一个"谁权威"的问题。

| 层 | 载体 | 内容 |
|---|---|---|
| 协调状态 | SQLite | 任务租约、水位线、阶段一产物、用量计数、重试队列 |
| 记忆制品 | 文件 | `memory_summary.md`、`MEMORY.md`、`rollout_summaries/*.md` |
| 可追溯性 | 文件内嵌 | `<cordiact-mem-citation>` 块，`file:line` + 会话 id |

表设计沿用现有 `AndroidConversationSessionStore` 的模式（`workspaceId` 外键 + `ON DELETE CASCADE`）：

```sql
memory_jobs(workspace_id, session_id, stage, lease_owner, lease_expires_at,
            watermark, attempts, next_attempt_at, PRIMARY KEY(workspace_id, session_id, stage))
memory_stage1(workspace_id, session_id, raw_memory, rollout_summary, rollout_slug,
              generated_at, PRIMARY KEY(workspace_id, session_id))
memory_usage(workspace_id, memory_key, usage_count, last_used_at, generated_at,
             PRIMARY KEY(workspace_id, memory_key))
```

**没有 `memory_entries` 表**——记忆内容不存表，存文件。表只记"这条记忆被用过几次"，因为那是遥测，不是内容。

### 3.3 制品层级：渐进式披露

Codex 的读取路径是"越常用的越精简"，且**完全没有向量搜索或知识图谱**，检索就是 grep 加按需文件访问。CordiAct 直接采用：

| 层级 | 文件 | 加载方式 | 预算 |
|---|---|---|---|
| 常驻摘要 | `memory/memory_summary.md` | 每次会话自动注入 | 约 5K tokens |
| 可搜索手册 | `memory/MEMORY.md` | 按需 grep | 不预加载 |
| 会话证据 | `memory/rollout_summaries/*.md` | `MEMORY.md` 指向时才读 | 不预加载 |
| 程序性记忆 | `memory/skills/` | 需要时读取 | **本期不做**，见 3.7 |

这个层级与 3.5 的 `@` 授权天然对齐：`@xx工作区：记忆` 的 `authorizedPrefix = "memory/"`，正好覆盖前两层；跨区默认只注入摘要，证据层按需。

**为什么摘要要常驻而不是按需**：摘要的作用是让模型知道"记忆里有什么"，从而决定要不要去 grep。如果连摘要都按需，模型就不知道有什么可查——这层是索引，不是内容。

### 3.4 两阶段编译管线

#### 触发条件

只在**根会话**启动时异步触发。跳过：临时会话、子代理会话、以及 `polluted` 会话（碰过外部上下文的）。

#### Phase 1：逐线程抽取

| 项 | Codex | CordiAct | 理由 |
|---|---|---|---|
| 并发 | 最多 8 | **最多 2** | 手机内存与电量 |
| 模型 | 轻量模型，低推理 | 由 provider 指定的"抽取模型" | 见开放问题 |
| 认领 | SQLite lease + ownership token | 同（简化版） | 崩溃恢复 |
| 输出 | 严格 JSON Schema | 同 | 可校验 |

**输入过滤**是本阶段最关键的一步。Codex 剥离 developer message、注入的 AGENTS.md 块、Skill 注入块，只保留用户消息与工具交互。

CordiAct 还必须多剥一类：**我们自己的注入块**。`ConversationTurnService` 会注入 referenced file 包裹、记忆摘要包裹、profile 指令。如果这些不被剥离，Phase 1 会从"上一轮注入的记忆"里再学一遍——形成自我强化的反馈回路，几轮之后记忆就会漂移成对自身的复述。

所以过滤规则是白名单式的：**只保留用户原始消息与工具调用/结果**，其余一律丢弃。

输出 schema：

```json
{
  "raw_memory": "详细记忆正文",
  "rollout_summary": "路由摘要，供 MEMORY.md 索引",
  "rollout_slug": "可选，稳定标识"
}
```

写入前**强制**过 `redact_secrets()`。这不是可选项——CordiAct 的 API key 在 Keystore 里，但用户消息里可能贴过 token、密码、内网地址。一个把密钥写进记忆并常驻注入的系统，比不记忆更危险。

#### Phase 2：全局合并

| 项 | 做法 |
|---|---|
| 互斥 | 全局单写者锁（SQLite 事务），同一 Workspace 同时只有一个合并进程 |
| 排序 | `usage_count` 优先，其次 `last_used_at`；从未用过的回退到 `generated_at` |
| 淘汰 | 超过 `max_unused_days` 未使用的剔除 |
| 遗忘 | 接收线程 id 差异集（新增/保留/移除），移除触发**针对性清理**而非追加 |
| 编辑沙箱 | 合并代理在受限目录内编辑 `MEMORY.md`，**禁网**，**禁用记忆工具**（防递归写入） |
| 校验 | 产物结构校验 + citation 可解析 + 尺寸上限 |

**校验替代 git diff**：Codex 用 git diff 确认一次 consolidation 是否真正成功。CordiAct 没有 git，改用三重校验：

1. `MEMORY.md` 的每条索引都能解析出 `file:line` 形式的 citation
2. 每个 citation 指向的 `rollout_summaries/*.md` 实际存在
3. 文件尺寸在预算内

任一失败则本次合并作废，保留上一版制品。这是 fail-closed 在记忆上的体现。

**租约简化为过期时间**：Codex 用 1 小时租约 + 90 秒心跳，因为它可能有多 Worker。CordiAct 的记忆任务跑在 WorkManager 的唯一任务里，单进程，所以不需要心跳——只需要 `lease_expires_at` 处理崩溃后的接管。

#### Citation

每条被编译的记忆都能回溯到证据来源：

```xml
<cordiact-mem-citation>
  <entry file="memory/MEMORY.md" line="12" session="s-abc123" />
</cordiact-mem-citation>
```

可被程序化解析，用于归因与遥测。这也是"可审计的遗忘"能成立的前提——没有 citation，就无法判断一条记忆该不该被淘汰。

### 3.5 跨工作区：路径即授权范围

问卷结论改变了这一节的方向：多数人希望 `@` 的授权范围**由路径本身表达**，而不是由一套额外的权限开关表达。

- `@xx工作区` —— 授权该工作区的完整范围
- `@xx工作区：记忆` —— 只授权该工作区的记忆

这比"给一个 `cross-workspace` 能力"更好，理由有三：

1. **授权范围与用户点选的引用对象是同一个东西**。用户不需要理解第二套权限概念。
2. **范围天然可嵌套、可收窄**。`@工作区：记忆` 是 `@工作区` 的子集，不需要额外定义包含关系。
3. **审计可读**。授权记录里写的是用户实际点选的路径，而不是一个抽象能力名。

#### 语法

```
reference := "@" workspace ( ":" segment )*
segment   := "记忆" | <path segment>
```

- 分隔符同时接受全角 `：` 与半角 `:`，解析时归一化。
- 省略 segment 时授权整个工作区。
- `记忆` 是保留段名，映射到该工作区的记忆导出目录 `memory/`。

#### 描述符

`MentionDescriptor` 增加授权前缀字段：

```kotlin
data class MentionDescriptor(
    val id: String,
    val type: MentionType,
    val label: String,
    val workspaceId: String,
    val parentId: String? = null,
    val relativePath: String? = null,
    /** The subtree this descriptor authorizes. Null = the whole Workspace. */
    val authorizedPrefix: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
)
```

- `@xx工作区` → `type = WORKSPACE`, `authorizedPrefix = null`（整区）
- `@xx工作区：记忆` → `type = MEMORY`, `authorizedPrefix = "memory/"`

#### 授权 ≠ 注入

这是最容易混淆的一点，必须写死：

- `@xx工作区` **不会**把整个工作区塞进上下文。它只授权本会话在该范围内按需读取。
- `@xx工作区：记忆` 是例外：记忆导出视图（`memory/MEMORY.md`）**默认注入**，因为摘要本身就是给模型看的。
- 其余文件一律按需读取，不预加载。

否则一个 `@工作区` 就能把上下文撑爆，这与 3.4 的预算约束直接冲突。

#### 授权规则

`WorkspaceInteractionAuthorization` 现在是"跨区一律拒绝"（第 40 行）。改成：

1. **同区引用**：行为不变。
2. **跨区引用**：允许，但**只能引用作用域节点**（`WORKSPACE` / `MEMORY`），不能跨区直接引用单个文件。要读具体文件，先引用范围、再在范围内取。
3. **授权范围** = `descriptor.authorizedPrefix`。后续任何读取必须落在该前缀内，否则拒绝。
4. **授权是逐轮的，不是逐会话的**。与现有 referenced file context 的 transient 语义一致：本轮结束即失效。
5. **跨区读取一律是快照**。

第 2 条是刻意的收窄。如果允许 `@xx工作区:某个文件` 直接引用单文件，那么"整区授权"和"单文件授权"就混在同一个语法里，路径语义变得不可读，审计也无从判断用户到底授权了多大范围。分成两步——先给范围，再在范围内取——每一步都可审计。

#### 路径安全

- 归一化后再比较前缀，防 `memory/../secrets`
- 前缀比较按**路径段边界**，不按字符串前缀：`memory` 不能匹配 `memory-backup/`
- 拒绝符号链接逃逸，复用 `WorkspaceFileService` 已有校验
- 跨区只读，不可写

#### 需要改的四处

| 位置 | 改动 |
|---|---|
| `WorkspaceInteractionAuthorization.kt:40` | 跨区拒绝 → 作用域校验 + 前缀校验 |
| `AndroidInteractionDirectory.children` | 根层列出其他工作区，作为作用域节点 |
| `AndroidInteractionDirectory.find` | 支持 `ws:<id>` 形式的作用域 id |
| `AndroidInteractionDirectory.readText` | 跨区仅限落在授权前缀内的读取 |

**快照而非实时**：harness 的 `file-reference` 是实时指针（README:101 明确 "No file-content reference object"），`session-reference` 是快照。跨区记忆属于后者——源 Workspace 可能被归档、记忆可能被修改，引用必须可复现。

### 3.6 分期

| 阶段 | 内容 | 状态 |
|---|---|---|
| **M1 制品与存储** | 三层制品 + citation 格式 + 显式写入 + `@` 跨区引用 | 本批 |
| **M2 编译管线** | Phase 1 抽取 + Phase 2 合并 + WorkManager 调度 + 淘汰 | 本批 |
| **M3 程序性记忆** | 从重复模式提取 skills | 阻塞：CordiAct 尚无 skills 系统 |
| **M4 向量检索** | — | 暂不做，grep 够用 |

M3 之所以阻塞：仓库里只有 `MentionType.SKILL` 这个枚举，没有 skills 的存储、装载、执行。程序性记忆的产出无处落地。

#### M2 与 Codex 的映射

| Codex | CordiAct M2 | 原因 |
|---|---|---|
| 会话启动时触发 | 同 | 与 Codex 一致 |
| 最多 8 并发 | 最多 2 | 手机 |
| 1 小时租约 + 90 秒心跳 | 仅过期时间 | 单进程，WorkManager 唯一 |
| Git diff 校验 | 结构 + citation + 尺寸三重校验 | 无 git |
| 无 WorkManager 依赖 | **需新增 `work-runtime-ktx`** | 当前项目没有 |

最后一行是实际工作量：M2 会引入一个新依赖，且需要定义充电/闲置约束。

### 3.7 明确不做

- **上下文控制**（`_context` 查询、`new_context` 交接、旧窗口档案）——是独立插件，不在本批范围。
- 跨 Workspace 的记忆合并或共享。
- "全局记忆"。
- 向量检索（M1/M2 阶段，grep 足够）。
- 记忆内容的自动改写（合并只做编译，不改写已有条目的语义）。
- skills 提取（无 skills 系统）。

## 四、两个插件的关系

| 交互点 | 规则 |
|---|---|
| profile 指定记忆策略 | profile **不能**指定摘要预算或注入开关。两者都是宿主策略 |
| profile 引用记忆文件 | 允许，通过 `InstructionSource.WorkspaceFile("memory/MEMORY.md")`，但只能引用**本区**的 |
| 记忆引用 profile | 无关系 |
| 会话启动顺序 | 先 resolve profile → 再注入摘要 → 最后装配上下文 |

第一行值得强调：如果允许 profile 控制摘要预算，就等于允许一个 profile 通过"注入大量记忆"挤掉真实上下文。预算必须由宿主持有。

另外，记忆的抽取输入要**剔除 profile 注入的指令块**——否则 profile 的提示词会被当成"用户说过的话"写进记忆，下一轮又以记忆的形式回到上下文，形成自我引用。见 3.4 的输入过滤。

---

## 五、实现顺序

每步可独立验证：

1. **`core.agent-profile` 契约 + 纯 Kotlin 解析器**。测试：优先级整体替换、悬空 `toolRefs` fail-closed、`toolRefs` 交集语义、`WorkspaceFile` 跨区拒绝。
2. **接 `ToolManager` / `ModelProviderRegistry`**：验证 `toolRefs ∩ resolveEnabled` 与 `generation` 作为默认值上一层。
3. **记忆制品层（M1）**：`memory/` 三层文件、citation 格式解析与校验、SQLite 协调表、显式写入。测试：跨 Workspace 隔离、citation 解析失败即作废、制品尺寸上限、`workspaceId` 外键级联。
4. **跨区 `@` 授权**：路径语法解析、`authorizedPrefix`、改四处 `AndroidInteractionDirectory` / 授权、快照注入。测试：`@工作区：记忆` 只可读记忆、`@工作区` 可读整区、跨区单文件引用被拒、`../` 逃逸被拒、前缀段边界（`memory` 不匹配 `memory-backup/`）。
5. **Phase 1 抽取**：输入过滤（含剥离自身注入块）、严格 JSON Schema、`redact_secrets()`。测试：注入块被剥离、脱敏命中、schema 不符即丢弃。
6. **Phase 2 合并**：单写者锁、用量排序、差异集遗忘、禁网沙箱、三重校验。测试：并发合并互斥、移除触发清理、校验失败保留上一版。
7. **调度（M2）**：新增 `work-runtime-ktx`、充电/闲置约束、租约过期接管、指数退避。测试：约束不满足不运行、崩溃后接管、退避不退化成热循环。

1–2 一个 PR，3–4 一个 PR，5–6 一个 PR，7 单独一个 PR（引入新依赖与后台任务，需单独评审电量影响）。

---

## 六、仍然开放的问题

1. **`@工作区` 整区授权在单轮内的读取上限**：整区授权意味着可以读任意多文件，需要一个每轮的文件数与字节上限，否则等于给了一个上下文炸弹。这个上限放授权层还是装配层还没定。
2. **抽取用哪个模型**：Codex 用 `gpt-5.1-codex-mini` 做抽取、`gpt-5.3-codex` 做合并，因为它自己管模型清单。CordiAct 是多 provider，需要定义"抽取模型"这一角色——是 provider 配置里的一个字段，还是用户显式指定？合并阶段用更强模型的成本也需要估算。
3. **`redact_secrets()` 的规则集**：只做正则（API key 形态、私钥头、`Bearer`）还是要接 Keystore 已知密钥做精确匹配？后者更可靠但耦合更紧。
4. **摘要的 5K token 上限如何换算成字符**：多 provider tokenizer 不同。可能需要在装配层按 provider 取一个保守系数。
5. **`polluted` 的判定边界**：碰过联网工具、MCP、还是只要引用了外部文件就算？过严会让记忆几乎学不到东西，过松会写入不该记的内容。
6. **profile 能否引用另一 Workspace 的 profile**：我认为不能（同样是提权面），但没写进"不做"名单。
7. **M3 的阻塞解除条件**：程序性记忆要落到 skills，但 CordiAct 没有 skills 系统。是先做 skills 再做 M3，还是 M3 直接跳过？


## 附录：三份参考实现的关键事实

### A. deepseek-harness

- `PresetDefinition` 只有 5 个字段，`AgentPreset` 投影多一个 `broken?: string`。**没有** systemPrompt / tools / model / version / extends。
- 运行期注册，重名直接抛错；装载失败以 `broken` 保留在 roster 但拒绝新绑定。
- 版本化只是进程内 `Generation` 引用计数，重启即丢。原文："Old revision implementations are not retained across process restarts."
- 记忆**不存在**。替代：`agent-instructions`（AGENTS.md）、skills、`session-reference`（快照）、`session-query`（只读检索）。
- `agent-instructions` 预算：user-global 与 project **各自 65,536 字节**；项目链从根到 cwd，broad-to-specific；超预算先省略更宽的文件。
- 压缩三层递进：`compaction-tool-result-pruner`（8192 字符以上保留头 4096 / 尾 1024，零模型调用）→ `compaction-basic`（阈值 0.8 / 保留 16% / headroom 65,536）→ `compaction-image-offload`（仅在收到 `IMAGE_OFFLOAD_REQUIRED` 时把最老图片替换为占位符）。
- 迁移按边拆包（`session-format-v0-to-v1` … `-v3-to-v4`），`session-format-catalog` 在模块初始化时校验链无缺口。
- 引用二分：**file-reference 是实时指针**（README:101 明确"No file-content reference object"），**session-reference 是快照**。

### B. codex

- 配置 11 层，`ConfigLayerSource::precedence()` 用整数编号写死（PackagedDefaults -10 … LegacyManagedConfigTomlFromMdm 50）。表递归深合并，**数组和标量整体替换**。
- `AgentRoleToml` 只有 `{description, config_file, nickname_candidates}`；真正的配置在 `config_file`。
- 角色只能**收紧**能力，由 `AgentRoleOverrides` 的字段缺失保证（没有 sandbox / approval 字段），且 features/skills 只在"关闭"时生效。
**记忆管线（两阶段异步编译）**

- 触发：**仅在根会话启动时**异步执行；跳过临时会话、子代理会话。
- Phase 1（逐线程抽取）：最多 **8 个任务并行**，用轻量模型 `gpt-5.1-codex-mini`（低推理）；每个任务通过 SQLite 的 lease + ownership token 认领，防多实例重复处理。
- Phase 1 输入过滤：剥离 developer message、注入的 AGENTS.md 块、Skill 注入块，**只保留用户消息与工具交互**。
- Phase 1 输出：严格 JSON Schema，含 `raw_memory` / `rollout_summary` / 可选 `rollout_slug`；写库前强制过 `redact_secrets()`。
- Phase 2（全局合并）：能力更强的 `gpt-5.3-codex`（中等推理），SQLite 单一全局任务锁保证同一时间只有一个合并进程。
- Phase 2 筛选：按 `usage_count` 排序，其次看最近使用；超过 `max_unused_days` 未使用即剔除；从未用过的回退到 `generated_at`。
- Phase 2 遗忘：接收线程 id 的**差异集（新增/保留/移除）**，移除触发针对性清理，而非简单追加。
- Phase 2 编辑沙箱：合并代理在受限文件工作区编辑 `MEMORY.md`，**不能联网、禁用记忆工具**（防递归写入）；靠 **Git diff + 制品校验**确认合并是否成功。

**协调层（SQLite 作为分布式状态机）**

- 租约 1 小时，每 **90 秒**心跳续约，超时任务可被重新认领。
- `watermark` 追踪处理进度；失败任务进入指数退避重试队列，避免热循环。
- Phase 1 输出存 `stage1_outputs` 等表，Phase 2 从表中按规则加载——数据库是两阶段之间唯一的状态桥梁。

**制品层级（渐进式披露，无向量检索）**

| 层 | 文件 | 加载 |
|---|---|---|
| 常驻摘要 | `memory_summary.md` | 每次会话自动注入，约 5K tokens |
| 可搜索手册 | `MEMORY.md` | 按需 grep，是查询的主要入口 |
| 会话证据 | `rollout_summaries/` | `MEMORY.md` 指向时读取 |
| 程序性记忆 | `skills/` | 从 `repeats > 1` 的模式提取 |

**其它**

- 阈值：每次启动最多 2 条 rollout、10 天内、闲置 ≥6 小时、合并上限 256 条、30 天未使用淘汰。
- 摘要硬上限 **2500 token**；`memory_summary.md` 首行必须是 `v1`。
- Citation：`<oai-mem-citation>` XML 块，含 `file:line` 与 rollout id，可程序化解析用于归因与遥测。
- `disable_on_external_context`：碰过外部上下文的会话标记 `polluted`，不参与学习。
- AGENTS.md：项目根 → cwd 顺序拼接，总预算 32 KiB，**非受信项目整段跳过**。
- 压缩阈值 = contextWindow × 0.9，配置只能调低；保留 = 初始上下文 + 摘要 + 从新到旧最多 20,000 token 用户消息。

### C. kimi-code

- 一个 Agent = 一个 Markdown 文件，frontmatter 声明 `name/description/工具权限`，正文即系统提示词。
- 作用域发现优先级：**显式 `--agent-file` > 项目 > 额外 > 用户 > Plugin > 内置**；同名高优先级胜出。
- 内置三个 subagent：`coder`（全工具）、`explore`（只读）、`plan`（连 Shell 都不给）。三者都不能再派发 subagent，因此**委派链默认必然终止**。
- subagent 权限**继承自 main agent**，不重新审批；要进一步收紧必须改 main agent 的规则。
- Skills 与 Agent 分离：Skill 用 frontmatter 的 `type: prompt` / `whenToUse` / `disableModelInvocation`，可被模型自动调用或斜杠命令加载。
- 无独立记忆子系统。

### D. 三家的共同点（最值得注意）

1. 都**没有**把"人设 + 工具 + 模型 + 记忆"塞进一个对象。
2. 都**没有**允许 agent 定义放宽权限。
3. 都用**引用或文件**承载大体量文本，而不是配置文件内联。
4. 都在某个层面**限制递归**（kimi 的委派链必然终止、harness 的 `collect()` 引用计数、codex 的 sub-agent 禁 collab）。
