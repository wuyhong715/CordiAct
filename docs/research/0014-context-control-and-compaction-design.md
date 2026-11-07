# 0014 上下文控制与会话压缩：调查与设计

- 日期：2026-11-07
- 参照快照：`deepseek-harness`（工作区本地副本）、`codex`（工作区本地副本）
- 上游相关包：`packages/compaction/{compaction,compaction-basic,compaction-tool-result-pruner,compaction-image-offload,command-compact}`；`codex-rs/core/src/{compact.rs,compact_remote_v2.rs,compact_token_budget.rs,compact_retained_tool_outputs.rs}`、`codex-rs/core/src/session/{context_window.rs,async_compaction.rs}`、`codex-rs/prompts/templates/compact/prompt.md`
- 状态：设计待实现

## 0. 结论

上下文控制不是“超长就砍掉最前面几条”。两家的实现把这个动作拆成了**四件互相独立的事**，而且顺序固定：

1. **先记账**——每一轮请求前知道“这次会发多少 token、离上限还有多少”。DSH 用 `tokenMeter`，Codex 用 `context_window.rs` 对 `model_auto_compact_token_limit` 计费。没有这一步，后面所有阈值都是拍脑袋。
2. **先修剪，再总结**——真正花模型钱的只有最后一步。DSH 把 `compaction-tool-result-pruner`（无模型、纯规则地砍历史工具输出）和 `compaction-image-offload`（把图片换成引用）排在 LLM 摘要**之前**；Codex 的 `TokenBudgetCompaction` 甚至允许完全不调模型，直接开新窗口灌 `WorldState`。**规则能解决的，绝不交给模型。**
3. **只改“投影”，不改日志**——DSH 的 compaction 事件是 **log-only**：`compaction/start|summary|prune|end` 只进会话日志，真正生效靠紧随其后的一条 `user/message` 事件携带 `surfaceOp: { op: 'replace', startSeq, endSeq }`。原始消息一条不删，UI 可回看，审计可复盘。Codex 同样保留原始 history，压缩只影响下一次请求的 `ResponseItem` 列表。
4. **失败必须降级，不能失败**——摘要调用可能超时、限流、被用户切走。DSH 用 `compaction/summary-error` 事件记录失败并保留原 surface；Codex 有 `CompactionFallback` 与 `MAX_REMOTE_COMPACTION_V2_STREAM_RETRIES = 2`。**压缩失败最多让上下文变大，绝不能让这一轮起不来。**

CordiAct 的现状与两家都不同，照搬会踩空：

- **回合是同步的**：`ConversationTurnService.submitWithTools` 一次跑完 `ToolLoopPlugin.run` 才返回。没有 DSH 的 `agent/pre-step` 异步边界，也没有 Codex 的 turn 调度器可以做“后台候选压缩”。所以 P0/P1 的压缩必须**在请求前同步完成，并且有严格超时**。
- **工具消息不跨轮持久化**：`ConversationTurnService` 组装历史时只取 `SYSTEM/USER/ASSISTANT`（见 `ConversationTurnService.kt` 第 74–76 行）。这意味着**跨轮**上下文膨胀主要是用户与助手的自然语言，而**单轮内**的 `ToolLoopPlugin` 才是真正的火山口——默认 `maxRounds = 12`、`maxToolCalls = 24`，每次工具结果原样进 `messages`。
- **`maxResultChars` 是死参数**：`ToolLoopLimits.maxResultChars = 32_000` 在 `ToolLoopContracts.kt:36` 声明并校验，但 `ToolLoopPlugin.run` 从未读取它，`result` 直接 `messages += ModelMessage(MessageRole.TOOL, result)`。这是本次要顺手修掉的既有缺陷（与 0006 记录“四个既有缺陷”同一类）。
- **没有 context window 元数据**：`ModelDescriptor` 只有 `id / displayName / supportsStreaming`（`ConversationContracts.kt:13–17`）。预算无从算起。设计必须补一个带 provider 默认表的 `contextWindowTokens`。
- **有一个可用的校准源**：`ModelEvent.Completed(inputTokens, outputTokens)` 已经能把 provider 回报的真实用量带回来（`ConfigurableModelBackend.kt:493`）。这意味着我们**不需要在 Android 上塞一个 tiktoken**，用字符类估算 + 真实用量在线校准即可。

所以本设计落成一条预算线（G0）、四道防线（G1–G4）和一个“投影层”：

```
                 ┌──────────────────────────────────────────────┐
每一轮请求前       │ G0 预算   TokenEstimator + usage 校准          │
                 └───────────────────┬──────────────────────────┘
                                     ▼
                 ┌──────────────────────────────────────────────┐
每一次工具返回     │ G1 溢出   超限结果落盘 + 头尾截断 + 分页读取工具 │
                 └───────────────────┬──────────────────────────┘
                                     ▼
                 ┌──────────────────────────────────────────────┐
组装 messages 时  │ G2 折叠   历史工具结果压成一行 stub            │
                 └───────────────────┬──────────────────────────┘
                                     ▼
                 ┌──────────────────────────────────────────────┐
                  │ G3 压缩   Head / Body / Tail 三区 + 结构化摘要 │
                 └───────────────────┬──────────────────────────┘
                                     ▼
                 ┌──────────────────────────────────────────────┐
provider 返回 400  │ G4 自愈   紧急压缩 + 再试一次                  │
                 └──────────────────────────────────────────────┘
```

分期：**P0 预算 + G1 溢出 + 修 `maxResultChars`**；**P1 G3 压缩 + `/compact` + 压缩卡片**；**P2 G2 折叠 + G4 自愈 + 设置页**；**P3 后台候选压缩（WorkManager）**。P3 排最后，因为它需要把摘要挪出同步回合，而 P0–P2 的价值不依赖它。

## 1. 已核对的上游机制

### 1.1 DSH：compaction 是“日志事件 + surface 替换”，不是一个函数

DSH 的 `packages/compaction/compaction` 只定义 seam：`CompactionService`、`CompactionEngine`、事件类型与校验。真正的默认实现 `compaction-basic` 做三件事：

1. **选区间**：`selectCompactableRange(session, retainTokens)` 从 surface 尾部向前累计，直到达到 `retainTokens`；**system head 永不参与**——`systemHead()` 只认 surface 第 0 个节点且类型必须是 `system/message`。切点必须落在“平衡的 tool-pair 切点”上（`tool-pairing.ts`），即不能把一条带 `toolCalls` 的 assistant 消息和它的 tool 结果切开。
2. **生成摘要**：把被选中区间投影成消息，交给总结模型；摘要结果必须**严格小于**被遮蔽内容的 token 量，否则拒绝提交（防止“越压越大”）。
3. **提交替换**：先校验 `shadowedSeqs` 在生成期间没有被并发改动，再发 `compaction/summary`（log-only），最后发一条 `user/message`，其 `surfaceOp` 是 `{ op: 'replace', startSeq, endSeq }`。从那以后，surface 投影出来的就是“摘要 + 保留尾部”，而日志里原始事件完好无损。

默认参数：`DEFAULT_THRESHOLD_RATIO = 0.8`、`DEFAULT_RETAIN_RATIO = 0.16`（`compaction-basic/src/config.ts:20–23`）。也就是说：**用到 80% 就压，压完至少给最近 16% 的上下文留原文**。

两个容易被忽略但很关键的细节：

- **KV cache 前缀**：`region.ts` 在重建压缩后的消息列表时，先放 `session.requestHeader()`（含 tool schemas），再放 system head，再放区间派生消息。原因写在注释里：**tool schemas 和 system head 是稳定的前缀，先放它们才能命中 prompt cache**；把摘要插到最前面会让整个前缀失效，省下的 token 还不够赔缓存的钱。
- **图片单独一条通道**：`compaction-image-offload` 把内联图片挪到外部存储、上下文里留引用。图片的 token 计价与文本完全不同（一张截图往往 1000–2000 tokens），不能靠“字符数”估算。

### 1.2 DSH：修剪器是 model-free 的第一道闸

`compaction-tool-result-pruner` 在摘要之前运行，对**历史**工具结果做规则裁剪：保留可读的头部与尾部，中间折掉，并标注省略量。它不调模型、不产生新事实，因此没有幻觉风险，成本为零。顺序很重要：**先修剪能显著降低摘要请求本身的输入**，否则你为了压缩 200k 上下文，先发了一次 200k 的摘要请求——钱花了两次。

### 1.3 Codex：三种压缩并存，按“要不要模型”分层

`codex-rs/core/src/` 下有四套相关实现，各自解决不同问题：

| 文件 | 机制 | 何时用 |
| :--- | :--- | :--- |
| `compact.rs` | 本地摘要：把历史投影成一条 compaction prompt，调用模型生成 handoff summary | 需要语义保真时 |
| `compact_remote_v2.rs` | 远端压缩 v2：服务端返回压缩后的 history；保留预算 `RETAINED_MESSAGE_TOKEN_BUDGET = 64_000`、单条 agent 消息 `MAX_RETAINED_AGENT_MESSAGE_TOKENS = 10_000` | 服务端支持时优先 |
| `compact_token_budget.rs` | 纯预算裁剪：按 token 直接砍，不调模型 | 摘要不可用/太慢/太贵 |
| `compact_retained_tool_outputs.rs` | 保留“客户端撰写”的工具输出（`client_authored` + `FunctionCallOutput { call_id: None, name: Some(_) }`），整条保留或整条丢弃，不截半条 | 关键证据不能失真时 |

触发侧在 `session/context_window.rs`：`token_limit_reached()` 把当前用量与 `model_auto_compact_token_limit` 比较，**在下一轮请求前**决定是否自动压缩；`async_compaction.rs` 还会在空闲时预生成一个“候选压缩”，等真正需要时直接安装，装不上再回退到同步压缩。

摘要 prompt（`codex-rs/prompts/templates/compact/prompt.md`）只有四句话，但结构是刻意的：

```
You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.
Include:
- Current progress and key decisions made
- Important context, constraints, or user preferences
- What remains to be done (clear next steps)
- Any critical data, examples, or references needed to continue
Be concise, structured, and focused on helping the next LLM seamlessly continue the work.
```

它不要求“总结对话”，而要求“**给接手的另一个 LLM 写交接单**”。这个措辞差异直接决定了摘要里会不会留下“下一步”和“关键约束”——普通的“请总结以下对话”几乎总会丢掉这两样。

### 1.4 Codex：裁剪是逐条预算，不是整段丢弃

`truncate_retained_messages` 的顺序是：**先按消息逐条裁剪到单条上限，再决定丢哪些消息**；如果一条消息本身超过 `MAX_RETAINED_AGENT_MESSAGE_TOKENS`，从文本尾部截断并保留 metadata sidecar。这样做的原因是：一条 50k token 的 agent 输出往往只有最后几段有用，整条丢掉比截断更浪费。反过来，先丢消息再裁剪，会把“本可以救回来的一条”直接扔掉。

### 1.5 社区主流做法（公开资料，未在本地快照中核对）

| 项目 | 机制 | 值得抄的部分 |
| :--- | :--- | :--- |
| **Aider** | repo map（tree-sitter 符号图 + PageRank）按剩余 token 动态缩放；历史超限时用弱模型总结 | **按预算缩放结构，而不是按预算删结构**；总结用便宜模型 |
| **Cline / Roo-Code** | 滑动窗口 + 工具输出折叠；任务完成后把历史 tool result 压成一行 | **post-turn folding**：动作闭环后，结果退化为“证据”，不必再占注意力 |
| **OpenHands** | `Condenser` 家族：`RecentEventsCondenser` / `ObservationMaskingCondenser` / `LLMSummarizingCondenser`；事件流 + view | 压缩器可组合、可替换；先 mask observation，再 LLM summarize |
| **SWE-agent** | `HistoryProcessor` 链：`LastNHistoryProcessor` + 观测截断 | 处理器链的顺序即优先级，规则在前、模型在后 |
| **Claude Code** | `/compact` 手动 + 自动阈值；超大工具输出落盘，配 `tool_output_read` 分页读 | **落盘 + 可回读**是比“截断并丢失”高一个档次的方案 |
| **Letta / MemGPT** | core / recall / archival 三层记忆，模型可自编辑 core memory | 长期事实与短期上下文分离；压缩前先把“必须记住的”写进稳定层 |
| **LangChain** | `ConversationSummaryBufferMemory`、`trim_messages` | 按 token 而非条数裁剪；保留 system + 最近若干条 |

共同的五条，也是本设计直接采纳的：

1. **锚点不可丢**：system 指令、首条用户目标、最近 N 轮原文。
2. **工具输出分级**：当轮全量、历史折叠、超大落盘。
3. **规则优先于模型**：能确定性修剪的，不花一次模型调用。
4. **非破坏性**：原文永远在库里，压缩只是投影。
5. **溢出兜底**：无论预算多保守，都要接住 provider 的 context-length 错误并重试。

### 1.6 采纳矩阵：哪些照抄，哪些改造，哪些不做

| 机制 | 出处 | CordiAct 的处置 |
| :--- | :--- | :--- |
| 80% 阈值 + 16% 尾部保留 | DSH | **直接采纳**，默认值不变 |
| log/surface 分离、原文不删 | DSH | **采纳**为 `context_compactions` 投影表 |
| tool-pair 安全切点 | DSH | **采纳**；`ModelMessage` 已带 `toolCalls/toolCallId`，可直接判断 |
| model-free pruner 先于摘要 | DSH / OpenHands | **采纳**为 G1 + G2 |
| 图片 offload | DSH | **采纳**，P1 起对截图生效 |
| 三种压缩并存 | Codex | **改造**为 L0/L1/L2 降级阶梯：移动端不能“选择不压缩”，只能选择“降级到哪一级” |
| 64k 保留预算 / 单条 10k | Codex | **不照搬数字**（窗口可能只有 32k），采纳“按绝对 token 算预算”的思路 |
| handoff summary 四段 | Codex | **采纳并扩为七节**（补工具索引、当前状态、必须保留原文） |
| retained tool outputs | Codex | **改造**为 spill + `tool_output_read`：更省空间，且可回读 |
| 后台候选压缩 | Codex | **推迟到 P3**：同步回合没有空闲窗口，P0–P2 不引入 WorkManager |
| repo map 按预算缩放 | Aider | **不适用**（CordiAct 是手机 agent，不是代码库编辑器）；但“按预算缩放结构”的思路用于技能索引裁剪 |
| post-turn folding | Cline | **采纳**为 G2 |
| Condenser 可组合 | OpenHands | **采纳**为 L 阶梯 + 可替换接口 |
| spill + `tool_output_read` | Claude Code | **采纳**为 G1 |
| 分层记忆 | Letta | **不重复做**：0003 的记忆插件已覆盖，上下文控制只负责“压到预算内” |

## 2. 由 CordiAct 现状推出的约束

### 2.1 两个不同的膨胀面

CordiAct 的上下文有两条独立的增长曲线，必须分开处理：

- **单轮内（intra-turn）**：`ToolLoopPlugin.run` 从 `initialMessages` 开始，每轮把 assistant 的 tool call 和 tool result 追加进去。最坏情况 24 次工具调用 × 32k 字符 ≈ 768k 字符，约 200k tokens——**在第一次跨轮压缩之前就已经炸了**。这解释了为什么 P0 必须是 G1（单次结果溢出），而不是先做摘要。
- **跨轮（inter-turn）**：`ConversationTurnService` 只把 `SYSTEM/USER/ASSISTANT` 写进请求，工具消息本来就不落库、不回放。所以跨轮增长是“每轮用户输入 + 助手最终回答”的累加，速度慢得多，但长会话（几百轮）依然会顶到窗口。这是 P1 的 G3 要解决的。

两者共用一个 `TokenEstimator` 和一个 `ContextPolicy`，但触发点不同：G1 在 `ToolLoopPlugin` 内逐结果判断；G3 在 `ConversationTurnService` 组装完历史后、发起请求前判断。

### 2.2 Android 的硬约束

| 约束 | 后果 | 设计应对 |
| :--- | :--- | :--- |
| 没有 Node / Python 运行时 | 不能直接用 tiktoken / transformers 分词器 | 纯 Kotlin 字符类估算 + `ModelEvent.Completed` 在线校准 |
| 内存与电量敏感 | 不能为了估算把整个会话复制多份；不能频繁唤醒 | 估算 O(n) 单遍；摘要只在阈值触发时做一次；P3 才引入 WorkManager |
| 弱网 / 移动网络 | 摘要请求可能 10s 超时或直接失败 | 摘要硬超时 20s；失败走确定性降级阶梯（见 3.7） |
| 用户可能随时切走 | 回合被取消时不能留下半条压缩记录 | 压缩记录单事务提交；取消即回滚，不留 `active` 记录 |
| 截图/无障碍树是大户 | 一张 PNG 或一棵 3000 节点的树轻松上万 token | G1 对图片走 offload（引用替代），对树走结构化裁剪（只保留可交互节点 + 文本） |
| 模型由用户配置，tokenizer 未知 | 同一段文字在不同 provider 下 token 数差 30%+ | 估算值乘 provider 安全系数，并用真实 usage 做 EWMA 校正 |

### 2.3 现状里必须顺手修的四个缺陷

1. **`ToolLoopLimits.maxResultChars` 未被使用**（`ToolLoopContracts.kt:36` 声明，`ToolLoopPlugin.run` 无引用）。P0 让 G1 真正消费这个参数，并把默认值从“字符”改成“字符 + 落盘”的二元语义。
2. **`ExecutionResult.truncateOutput` 只留头**（`ExecutionContracts.kt:34–40`）：`substring(0, limit) + "...[truncated N chars]"`。命令输出的错误通常在**尾部**（堆栈最后一行、`exit code`），只留头等于把最有用的部分扔掉。P0 改成头 + 尾 + 中间省略标记，并附 `spill` 路径。
3. **`ModelDescriptor` 没有 `contextWindowTokens`**（`ConversationContracts.kt:13–17`）。P0 补字段 + provider 默认表；用户可在设置里覆盖（有些自建网关的窗口与模型名不一致）。
4. **`ModelEvent.Failed` 在工具循环里被吞掉**（`ConversationTurnService.kt:220` 的 `is ModelEvent.Failed -> Unit`）。后果是 provider 的 400/限流/鉴权失败在 `ToolLoopPlugin` 内退化成“模型这轮没说话”，既不会触发溢出恢复，也不会让用户看到真实原因。P2 的 G4 依赖它先被修好。

## 3. 设计：上下文控制插件（`core.context`）

### 3.1 形状

一个 bundled system 插件，id `core.context`，默认开启，设置项可调但**不提供“关闭全部”**——只提供 `enabled=false` 的逃生阀给调试用。它不发布面向模型的工具（除了 P1 的一个 `compact_context`），主要作为**两个集成点上的拦截器**存在：

```
ConversationTurnService
  ├─ 组装 history ──▶ ContextProjector.project(...)  ← G2/G3
  └─ 发起请求 ──────▶ ContextBudgetGuard.check(...)  ← G0/G4
ToolLoopPlugin.run
  └─ 每次 dispatch 返回 ──▶ ToolOutputGate.apply(...) ← G1
```

不新建独立模块的做法是刻意的：上下文控制必须看到**真实的请求消息列表**，如果做成一个旁路服务，就要把 `ModelMessage` 复制来复制去，Android 上不划算。实现放 `core` 的 `com.cordiact.core.context` 包，Android 侧只提供 `SpillStore`（文件）与设置读写。

### 3.2 预算模型

```
usableInput = contextWindowTokens
            − maxOutputTokens          // 默认 8192，且不超过窗口的 25%
            − safetyMargin              // max(2048, 窗口的 5%)

compactAt   = usableInput × thresholdRatio     // 默认 0.8
retainTail  = contextWindowTokens × retainRatio // 默认 0.16，且至少 2 个完整回合
```

四个数各有出处：`thresholdRatio=0.8` / `retainRatio=0.16` 取 DSH 默认；`maxOutputTokens` 必须预留，因为 provider 的 `max_tokens` 是**输入+输出**共享窗口；`safetyMargin` 是给估算误差的缓冲（首次请求没有 calibration 时尤其重要）。

`contextWindowTokens` 的解析顺序：

1. 用户在设置里显式覆盖（`contextWindowOverride`）；
2. `ModelDescriptor.contextWindowTokens`（provider 配置里带）；
3. 内置 provider 默认表（DeepSeek 128k、OpenAI 128k/200k、Anthropic 200k、Gemini 1M，按模型前缀匹配）；
4. 兜底 32k（最保守，宁可多压）。

预算不是全局一个数：**system context 单独限额**。`ConversationTurnService` 注入的 `systemContext`（技能索引、记忆、工作区规则）可能自己就很大。规则：`systemContext ≤ usableInput × 0.15`；超了先裁技能索引（保留名称 + 一行描述，丢弃全文），再裁记忆条目（按相关度排序取前 N）。这条不写进压缩记录，因为它每轮都重新生成，不占历史。

### 3.3 数据模型（DB v7）

现有 `conversation_messages` 表没有自增 `seq`，排序键是 `(created_at, message_id)`（`AndroidConversationSessionStore.kt:160` 的索引）。所以压缩区间用**消息 id 闭区间 + 时间戳**定位，不能用 DSH 的 `SessionSeq`。

```sql
CREATE TABLE context_compactions (
    id                TEXT PRIMARY KEY NOT NULL,
    session_id        TEXT NOT NULL REFERENCES conversation_sessions(session_id) ON DELETE CASCADE,
    start_message_id  TEXT NOT NULL,
    end_message_id    TEXT NOT NULL,
    start_created_at  INTEGER NOT NULL,
    end_created_at    INTEGER NOT NULL,
    summary           TEXT NOT NULL,
    tokens_before     INTEGER NOT NULL,
    tokens_after      INTEGER NOT NULL,
    reason            TEXT NOT NULL,          -- AUTO | MANUAL | OVERFLOW | BACKGROUND
    model_id          TEXT,
    fallback_level    INTEGER NOT NULL DEFAULT 0,  -- 0=LLM,1=规则摘要,2=硬裁剪
    created_at        INTEGER NOT NULL,
    superseded_by     TEXT,
    active            INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX context_compactions_session
    ON context_compactions(session_id, created_at);
```

不变量（可测）：

- `tokens_after < tokens_before`，否则不写入——与 DSH 的“摘要必须更小”同一条规则。
- 同一 session 的 `active=1` 记录**区间不重叠**；新记录覆盖旧记录时，旧记录置 `active=0, superseded_by=新 id`，摘要文本合并进新摘要（“上一份摘要 + 本次新增区间”），而不是丢掉。
- `start/end_message_id` 必须都存在于该 session；删除消息（目前没有此功能）时要级联清理。
- `fallback_level > 0` 的记录必须在 UI 上明确标注“规则压缩”，不能伪装成模型摘要。

**为什么不改 `conversation_messages`**：压缩是“视图”，不是“事实”。一旦原地改写，用户回看历史会看到被篡改的对话，导出、审计、复现全部失真。参考 DSH 的 log/surface 分离——它的 `compaction` 事件连 surface 都不进，只有替换指令进。我们的 `context_compactions` 表就是那条“替换指令”，原文永远在 `conversation_messages`。

### 3.4 契约

放 `core/src/main/kotlin/com/cordiact/core/context/ContextContracts.kt`：

```kotlin
/** 一次请求的预算快照。所有阈值都从这里取，避免各处重复算。 */
data class ContextBudget(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val safetyMarginTokens: Int,
    val thresholdRatio: Double = 0.8,
    val retainRatio: Double = 0.16,
) {
    val usableInputTokens: Int
        get() = (contextWindowTokens - maxOutputTokens - safetyMarginTokens).coerceAtLeast(1)
    val compactAtTokens: Int
        get() = (usableInputTokens * thresholdRatio).toInt()
    val retainTailTokens: Int
        get() = (contextWindowTokens * retainRatio).toInt()
}

data class ContextPolicy(
    val enabled: Boolean = true,
    val contextWindowOverride: Int? = null,
    val maxOutputTokens: Int = 8_192,
    val thresholdRatio: Double = 0.8,
    val retainRatio: Double = 0.16,
    val minRetainTurns: Int = 2,
    val maxInlineToolChars: Int = 8_192,
    val toolHeadChars: Int = 4_096,
    val toolTailChars: Int = 2_048,
    val foldAfterRounds: Int = 2,
    val summaryModelId: String? = null,   // null = 用当前会话模型
    val summaryTimeoutMs: Long = 20_000,
    val spillRetentionDays: Int = 7,
)

/** 估算器的唯一入口。实现必须 O(n)、无分配放大、可被单测覆盖。 */
interface TokenEstimator {
    fun estimate(text: String): Int
    fun estimateMessages(messages: List<ModelMessage>, toolsJson: String? = null): Int
    /** provider 回报真实用量后调用，更新按模型的校正系数；读数走 `ContextMetrics`。 */
    fun calibrate(actualInputTokens: Long, estimatedInputTokens: Int, modelId: String)
}

/** 压缩计划：只描述“打算怎么压”，不产生副作用，便于 dry-run 与测试。 */
data class CompactionPlan(
    val head: List<ModelMessage>,       // 永不压缩：system + 首条 user
    val body: List<ModelMessage>,       // 候选摘要区
    val tail: List<ModelMessage>,       // 原文保留：最近 N 轮
    val bodyTokens: Int,
    val tailTokens: Int,
    val reason: CompactionReason,
)

enum class CompactionReason { AUTO, MANUAL, OVERFLOW, BACKGROUND }

data class CompactionResult(
    val recordId: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val fallbackLevel: Int,             // 0=LLM 摘要，1=规则摘要，2=硬裁剪
    val summary: String,
)

interface ContextCompactor {
    /** 请求前调用。返回 null 表示无需压缩。 */
    suspend fun compactIfNeeded(
        sessionId: String,
        messages: List<ModelMessage>,
        budget: ContextBudget,
        reason: CompactionReason,
    ): CompactionResult?

    /** 400 之后调用。必须尽力而为，不再抛异常。 */
    suspend fun emergencyCompact(
        sessionId: String,
        messages: List<ModelMessage>,
        budget: ContextBudget,
    ): CompactionResult?
}

/** 与 `context_compactions` 一行对应。投影器只读它，不负责写入。 */
data class CompactionRecord(
    val id: String,
    val sessionId: String,
    val startMessageId: String,
    val endMessageId: String,
    val summary: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val fallbackLevel: Int,
    val createdAt: Long,
    val supersededBy: String?,
    val active: Boolean,
)

interface ContextProjector {
    /** 历史 + 已生效的压缩记录 → 真正发给 provider 的消息列表。 */
    fun project(sessionId: String, history: List<ConversationMessage>, records: List<CompactionRecord>): List<ModelMessage>
}
```

三个接口刻意分开：`TokenEstimator` 可替换（将来接 provider 的 tokenizer API 不必改调用方）；`ContextCompactor` 有副作用（写记录、调模型）；`ContextProjector` 是纯函数（给定输入必得同一输出），最容易做黄金测试。

### 3.5 三区切分与切点规则

`CompactionPlanner.plan(messages, budget)`：

1. **Head**：从头开始，收集所有 `SYSTEM` 消息，加上**第一条** `USER` 消息。第一条 user 是原始目标，改写它会改变任务本身。
2. **Tail**：从尾部向前累计 token，直到 ≥ `retainTailTokens`，且至少覆盖 `minRetainTurns` 个完整回合。回合边界定义为“一条 USER 消息到下一条 USER 消息之前”。
3. **Body**：Head 与 Tail 之间的全部消息。
4. **切点平衡**：如果切点落在一条带 `toolCalls` 的 assistant 消息之后、而其 tool 结果之前，把切点前移到该 assistant 消息之前（宁可多留一点，不能制造孤儿 tool result）。反向同理：不能把 tool result 留在 body 而 assistant 在 tail。这个函数与 DSH 的 `tool-pairing.ts` 同构：

```kotlin
/** 返回 <= desired 的最近一个“安全切点”。安全 = 不拆散 tool call/result 对。 */
internal fun safeCutIndex(messages: List<ModelMessage>, desired: Int): Int
```

好消息是 `ModelMessage` 已经带齐配对信息：`toolCalls: List<ModelToolCall>`、`toolCallId: String?`、`toolName: String?`（`ConversationContracts.kt:48–72`），且 `init` 强制 TOOL 消息必须带 `toolCallId`。真正的问题是**历史投影把它们丢了**：`ConversationTurnService` 用 `ModelMessage(it.role, it.content)` 重建历史（第 74–76 行），只保留 role/content。P1 的 `ContextProjector` 如果将来要投影跨轮 TOOL 消息，必须同时还原配对字段（`metadataJson` 是否存了这些，见第 9 节待核实项）；当前跨轮不落 TOOL 消息，所以实际只需保证**单轮内**的压缩切点不拆对——`ToolLoopPlugin` 的 `messages` 里这些字段是全的。

### 3.6 G1：工具结果溢出（`ToolOutputGate`）

每次 `ToolDispatcher.dispatch` 返回后、`messages +=` 之前执行：

```
if (result.length <= maxInlineToolChars) 原样返回
else:
  1. 全文写入 spill 文件（UTF-8，流式，单文件上限 8MB）
  2. 返回 head(toolHeadChars) + "\n…[" + omitted + " 字符已省略；全文见 " + uri + "，用 tool_output_read(offset, limit) 分页读取]\n" + tail(toolTailChars)
  3. 在 metadata 里记 spill 路径、原始字节数、行数、sha256 前 8 位
```

三条规则：

- **头尾都要留**。命令输出的错误在尾部，文件内容的 imports 在头部；只留头会让模型看不到失败原因。
- **必须可回读**。只截断不给路径，等于把信息扔了；给了路径 + 分页工具，模型可以按需取回，这是 Claude Code 的 `tool_output_read` 模式。
- **图片不进这个通道**。`ToolResult` 里的图片走 `ImageOffload`：写入 `filesDir/context-images/`，消息里替换为 `[image: <path>, 1280×720, 已离线]`；如果模型必须在当轮看图，保留最近 1 张，其余引用化（DSH 的 `compaction-image-offload` 同思路）。

`tool_output_read` 是 P0 就要发布的只读工具（名称、参数与 0006 的 `tool_output_read` 保持一致的风格）：

```json
{"file":"spill://<sessionId>/<id>.txt","offset":0,"limit_chars":8000}
```

它必须做**路径白名单**：只允许读 `spillRoot` 下的文件，拒绝 `..`、绝对路径、符号链接逃逸。这是安全边界，不是礼貌检查。

### 3.7 G3：摘要与降级阶梯

摘要 prompt 采用 Codex 的“交接单”结构，并按 CordiAct 的场景补足“工具产出索引”与“下一步”两节（第 5、6 节）：

```
你在为另一个 LLM 写上下文交接单，它将继续完成用户的任务。不要写寒暄，不要重复原文。
必须包含以下七节，每节用二级标题：
## 1. 任务与目标      原始用户请求（可改写成一句话，但不得改变意图）
## 2. 已完成与关键决策  做了什么、为什么这样选、放弃了什么
## 3. 当前状态        工作区、打开的文件/页面、会话 id、未完成的命令
## 4. 重要事实与约束   用户偏好、环境限制、权限边界；密钥只写“已配置”，不写明文
## 5. 工具产出索引     spill 文件路径 + 一行内容摘要 + 何时需要重读
## 6. 下一步          可执行的、有顺序的步骤
## 7. 必须保留的原文   短引用（每条 ≤ 200 字符）
```

**降级阶梯**（这是移动端与桌面端最大的差异点）：

| 级别 | 触发条件 | 做法 | 成本 |
| :--- | :--- | :--- | :--- |
| L0 | 正常 | LLM 摘要，超时 20s | 1 次摘要调用 |
| L1 | 摘要模型不可用 / 超时 / 返回为空 / 摘要不比原文小 | **规则摘要**：逐回合抽取 user 原文（截断到 300 字）+ assistant 首尾各 2 句 + 工具 stub 列表 | 0 |
| L2 | L1 仍超预算 | **硬裁剪**：Head + 最近 `minRetainTurns` 轮 + 一行“早期 N 轮已省略（原因：超出上下文）” | 0 |

L1 的规则摘要必须**确定性**：同样的输入必得同样的输出，便于测试与复现。它不追求文采，只保证“目标、已完成、下一步”三样还在。

摘要结果校验（三条，全部通过才写入）：

1. `summaryTokens <= bodyTokens × 0.5`（压不下去就说明摘要失败，降级）；
2. 七节标题齐全（缺节说明模型没按格式来，降级）；
3. 不包含 `spill://` 之外的绝对路径（避免把内部路径写进摘要）。

### 3.8 G4：溢出自愈

先说一个现状缺陷：provider 的失败不是异常，而是 `ModelEvent.Failed(code, message, retryable)`（`ConversationContracts.kt:131`）。但 `ConversationTurnService.buildRound` 里写的是 `is ModelEvent.Failed -> Unit`（第 220 行），然后照常返回一个空 `ToolLoopRound`——**工具循环内的 context 溢出根本传不到上层**。所以 G4 的第一步不是写分类器，而是让 `Failed` 穿过 `ToolLoopRound`（加 `failure: ModelFailure?` 字段），再由 `ConversationTurnService` 统一分类。

分类后进入 `OverflowClassifier`（按 provider 分别维护，放 `core/context/OverflowClassifier.kt`）：

```kotlin
sealed interface ModelFailure {
    data class ContextOverflow(val providerCode: String?, val message: String) : ModelFailure
    data class RateLimited(val retryAfterMs: Long?) : ModelFailure
    data class Other(val code: String, val message: String) : ModelFailure
}
```

匹配规则：

- OpenAI 系：HTTP 400 + `code == "context_length_exceeded"`，或 message 含 `maximum context length`；
- Anthropic：HTTP 400 + `invalid_request_error` + `prompt is too long`；
- Gemini：HTTP 400 + `INVALID_ARGUMENT` + `exceeds the maximum number of tokens`；
- 兜底：HTTP 400/413 且 message 含 `token` 与 `context|length|too long` 任一。

自愈流程（最多一次重试，避免死循环）：

```
emergencyCompact(reason = OVERFLOW)
  → 预算按 retainRatio × 0.5、thresholdRatio = 1.0 重算
  → 先跑 G1 全量修剪（所有可落盘结果落盘）
  → 再跑 L2 硬裁剪（不等 LLM，避免二次超时）
  → 重试请求；仍失败则把原始错误抛给用户，附“已尝试压缩，仍超出”
```

### 3.9 用户与模型可见面

- **用户**：输入框发送 `/compact` 触发手动压缩（P1）；压缩后聊天流里插入一张**压缩卡片**：“已压缩 12 轮 · 38.2k → 3.1k tokens · 展开原文”。点击展开只读原文（来自 `conversation_messages`）。这与 DSH 的 UI 折叠节点、Codex 的 compaction turn 是同一交互。
- **模型**：P1 发布 `compact_context` 工具，参数 `{ "instruction": "可选：保留重点" }`。它只允许压缩**当前会话**，不允许指定别的 session；返回 `{ before, after, fallbackLevel }`。这是给长任务代理的“主动整理桌面”能力，Claude Code 的 `/compact` 与 Codex 的 `compact` 工具都提供。
- **设置页**：`设置 → 上下文`，字段对应 `ContextPolicy`；高级项折叠。显示当前模型的窗口、最近 20 次压缩的平均压缩比与估算误差。

## 4. 集成点与改动清单

| 位置 | 改动 | 风险 |
| :--- | :--- | :--- |
| `core/.../conversation/ConversationContracts.kt` | `ModelDescriptor` 加 `contextWindowTokens: Int?` | 低；所有构造点有默认值 |
| `core/.../toolloop/ToolLoopContracts.kt` | `ToolLoopPlugin.run` 接 `ToolOutputGate`；`maxResultChars` 真正生效 | 中；需保证 gate 不改变 `ToolLoopOutcome.evidence` |
| `core/.../context/*` | 新增包：契约、估算器、规划器、摘要器、投影器、溢出分类器 | 低 |
| `model-providers/.../ConversationTurnService.kt` | 组装完 history 后 `projector.project` + `compactor.compactIfNeeded`；把 `ModelEvent.Failed` 从 `buildRound` 透出并交给 `OverflowClassifier` | 中；同步回合增加一次摘要延迟（有 20s 上限） |
| `app/.../conversation/AndroidConversationSessionStore.kt` | DB v7 迁移 + `ContextCompactionStore` 实现 | 中；迁移必须幂等 |
| `app/.../tool/AndroidToolDispatcher.kt` | 注册 `tool_output_read`（只读、白名单） | 低 |
| `app/.../ui` | 压缩卡片、设置页、调试页 | 低 |

DB 迁移 `migrateV6ToV7`：只 `CREATE TABLE IF NOT EXISTS` + 索引，不动现有表。这与 v5 的“只加列”原则一致：**迁移必须能在旧库上重复执行而不产生副作用**。

## 5. 移动端工程细节

### 5.1 估算器（TokenEstimator）

不做完整 BPE，做**字符类加权**：

```kotlin
private fun weight(cp: Int): Double = when {
    cp in 0x4E00..0x9FFF || cp in 0x3040..0x30FF || cp in 0xAC00..0xD7AF -> 1.0   // CJK：1 字 ≈ 1 token（保守）
    cp in 0x30..0x39 || cp in 0x41..0x5A || cp in 0x61..0x7A -> 0.25               // ASCII 字母数字：4 字符/token
    cp == 0x20 || cp == 0x0A || cp == 0x09 -> 0.10                                  // 空白
    cp < 0x80 -> 0.5                                                                // 标点/符号
    else -> 0.75                                                                    // 其他 Unicode
}
```

再加两项修正：

- `providerFactor`：默认 1.10（安全系数），DeepSeek 1.05、Anthropic 1.15、Gemini 1.10（不同 tokenizer 对中文与代码的切分差异）；
- `toolsJson`：每次请求加 `toolsJson.length / 4 + 200`，工具 schema 往往被忽略，但十几条工具的 JSON Schema 很容易上千 token。

校准：每次 `ModelEvent.Completed(inputTokens)` 后计算 `ratio = actual / estimated`，`factor = 0.8*factor + 0.2*ratio`，clamp `[0.8, 1.6]`，按 `modelId` 存内存（进程内即可，重启丢失可接受）。调试页显示最近 20 次的估算/实际对比，误差 > 25% 时提示“该模型的 tokenizer 与估算差异较大”。

### 5.2 Spill 存储

- 根目录：`context.noBackupFilesDir/spills/<sessionId>/`（**noBackup**：不参与云备份，避免把会话内容同步出去）；
- 文件命名：`<timestamp>-<toolId>-<sha8>.txt`；
- 写入：`Dispatchers.IO`，`BufferedWriter` 流式，单文件上限 8MB，超过则截断并在末尾注明；
- 清理：会话删除时级联删除目录；启动时清理 `> spillRetentionDays` 或总量 `> 200MB` 的最旧文件（LRU）；
- 权限：文件仅 app 私有；`tool_output_read` 只接受 `spill://` scheme，解析后必须 `canonicalPath.startsWith(spillRoot.canonicalPath)`。

### 5.3 摘要调用的参数

- 用 `summaryModelId ?: 当前模型`；设置页建议默认选便宜模型（如 DeepSeek Chat / Gemini Flash）；
- `maxOutputTokens = 2_000` 足够，七节摘要 rarely 超过 1.5k；
- `temperature = 0.2`，减少摘要跑偏；
- 不传 tools（`tools = emptyList()`），避免模型在摘要时试图调工具；
- 超时 20s；超时即取消请求（`ModelBackend` 的取消通道），走 L1。

### 5.4 与记忆插件的边界

`0003` 的记忆插件已经会注入 `systemContext`（技能索引、记忆条目）。上下文控制**不重复做记忆抽取**：它只负责“把已有内容压到预算内”。两者的接口是 `systemContext` 的 token 上限（3.2 的 15% 规则）。如果记忆注入本身超限，由记忆插件先裁，而不是上下文插件把 system 消息也摘要掉——**system 消息永不参与摘要**，这是硬不变量。

## 6. 观测、隐私与可解释性

### 6.1 指标

每次请求记录一条 `ContextMetric`（放内存环形缓冲 + 可选落 DB）：

```
sessionId, turnIndex, estimatedInput, actualInput, compactedTokens,
spillCount, foldCount, compactDurationMs, fallbackLevel, estimatorFactor
```

四个核心比率：

- **压缩比** `tokensAfter / tokensBefore`，健康值 0.15–0.35；长期 > 0.6 说明摘要 prompt 或预算参数有问题；
- **估算误差** `|actual − estimated| / actual`，目标 < 15%；
- **降级率** `fallbackLevel > 0` 的占比，目标 < 10%（弱网用户可放宽）；
- **压缩延迟** P95 < 8s（20s 超时是上限，不是目标）。

### 6.2 隐私

- 摘要文本与原文同等敏感：存 `context_compactions.summary`，随会话删除而级联删除；
- 摘要请求走用户自己的模型配置，不额外上传到任何第三方；
- 摘要 prompt 明确要求“密钥只写已配置，不写明文”；落地前用正则扫一遍常见密钥格式（`sk-`、`ghp_`、`AKIA`、私钥头），命中则替换为 `[redacted]`；
- `context_compactions` 不参与任何“分享会话”导出？——**要参与**，否则导出后对方看不懂压缩卡片；导出时把摘要作为正文的一部分，原文仍按现有导出逻辑处理。

### 6.3 可解释性

三处必须让用户看懂“为什么上下文变了”：

1. 压缩卡片上的 `fallbackLevel` 徽标（“模型摘要” / “规则摘要” / “硬裁剪”）；
2. 设置页的“上次压缩”详情：压缩前/后 token、耗时、使用的模型、覆盖的消息条数；
3. 调试页的“估算 vs 实际”曲线——这是排查“为什么 80% 就报了 400”的唯一手段。

## 7. 分期与验收

### P0 — 预算 + 溢出 + 修死参数（约 1.5 天）✅ 已实现（2026-11-07，PR #38）

- `TokenEstimator` + `ContextBudget` + `ContextPolicy`；
- `ToolOutputGate` 接入 `ToolLoopPlugin`，`maxResultChars` 真正生效；
- `ExecutionResult.truncateOutput` 改头+尾；
- `tool_output_read`（只读、白名单、分页）；
- `ModelDescriptor.contextWindowTokens` + provider 默认表。

**验收**：单测覆盖估算器（中英混排、代码、空白、emoji 的加权与单调性）；一个 24 次工具调用、每次 50k 字符的合成回合，在 mock provider 下不抛 400，且每次 tool result 都能通过 `tool_output_read` 完整取回；`maxResultChars` 有测试证明被读取（改小后输出变短）。

### P1 — G3 压缩 + `/compact` + 压缩卡片（约 2 天）✅ 核心已实现（2026-11-07，PR #40）；压缩卡片 UI 待做

- `CompactionPlanner`（Head/Body/Tail + `safeCutIndex`）；
- `SummaryCompactor`（七节 prompt + 三条校验 + L1 降级）；
- DB v7 + `ContextCompactionStore` + `ContextProjector`；
- `/compact` 命令 + 压缩卡片 UI。

**验收**：构造 300 轮、每轮 2k tokens 的会话，在阈值处触发一次压缩，压缩后请求 token < 阈值的 50%；`ContextProjector` 黄金测试：给定 history + 记录，输出稳定；摘要失败（mock 超时）时走 L1 且回合仍成功；压缩卡片能展开原文且原文与 DB 一致；DB v6→v7 迁移在旧库上幂等。

### P2 — 折叠 + 自愈 + 设置页（约 1.5 天）✅ 折叠与自愈已实现（2026-11-07，PR #43）；设置页 UI 待做

- `ToolResultFolder`：历史工具结果（同一回合内超过 `foldAfterRounds` 的）压成一行 stub；
- `OverflowClassifier` + `EmergencyCompactor` + 重试一次；
- 设置页 + 调试页。

**验收**：模拟 provider 返回 400 `context_length_exceeded`，第一轮触发紧急压缩、第二轮成功，用户只看到一条“已自动压缩”提示；分类器对四家 provider 的样例错误各自命中，且普通 400（参数错误）不被误判为溢出；设置项修改后下一次请求生效，无需重启。

### P3 — 后台候选压缩（约 2 天）

- 在回合结束、`WorkManager` 约束满足时预生成候选摘要（对应 Codex `async_compaction.rs`）；
- 下次请求前若候选仍然有效（覆盖区间未被新消息插入打断）则直接安装，否则丢弃重算；
- 约束：`batteryNotLow = true`、`requiresCharging = false`（与 `0003` 的记忆调度一致：充电不是必要条件，热压才是）。

**验收**：候选摘要在后台生成后，前台请求的 P95 延迟增加 < 200ms；候选失效（区间被新消息覆盖）时自动回退到同步压缩且结果正确。

## 8. 明确不做

- **不做逐 token 的精确分词器**。Android 上引入 tiktoken/transformers 的收益（估算误差从 ~15% 降到 ~3%）不值得包体、内存与启动成本；用 usage 校准足够。
- **不做“压缩即删除”**。原文必须保留；用户回看、导出、审计都依赖它。
- **不做跨会话的全局压缩**。每个 session 独立预算；跨会话共享的只有估算器校准系数。
- **不在 P0–P2 做后台压缩**。同步回合里加一个 WorkManager 依赖会把首版复杂度翻倍；P3 再评估。
- **不对 system 消息做摘要**。system context 超限由技能索引/记忆插件先裁；上下文插件只对 Body 动手。

## 9. 未验证 / 待核实

1. **CordiAct 各 provider 的真实窗口**：设计里的默认表（128k/200k/1M/32k）基于公开资料，需在实现时核对每个 `ModelDescriptor` 的实际值；自建网关可能更小。
2. **历史投影是否保留 tool 配对信息**：`ModelMessage` 本身有 `toolCalls/toolCallId/toolName`（`ConversationContracts.kt:48–72`），但 `ConversationTurnService` 重建历史时只传 role/content（第 74–76 行）。P1 的投影器若要跨轮压缩，需确认 `metadataJson` 是否存了配对信息；如果没存且历史里也不会有 TOOL 消息，则只保证单轮内切点安全即可。
3. **摘要模型对七节格式的遵从率**：不同小模型差异很大；L1 降级是兜底，但若降级率 > 30%，应改为更短的三节 prompt（目标 / 进度 / 下一步），此点需用真实模型跑一批样本后再定。
4. **`conversation_messages` 的排序稳定性**：`(created_at, message_id)` 在并发追加时是否严格单调，需核对 `append` 的实现；如果不保证，压缩区间可能错位，需要加 `seq` 列（DB v8）。
5. **社区做法**：Aider/Cline/OpenHands/Claude Code/Letta 的机制来自公开文档与仓库，**未在本地快照中逐行核对**；引用时只作为模式参考，不作为实现依据。

## 附录 A：上游事实索引

| 事实 | 位置 |
| :--- | :--- |
| DSH compaction 默认阈值 0.8 / 保留 0.16 | `deepseek-harness/packages/compaction/compaction-basic/src/config.ts:20–23` |
| DSH 选区间从尾部累计、system head 不参与 | `.../compaction-basic/src/region.ts:96–139` |
| DSH tool-pair 安全切点 | `.../compaction/src/tool-pairing.ts` |
| DSH 压缩后重建前缀的顺序（tool schemas → system → 区间） | `.../compaction-basic/src/region.ts:535–560` |
| Codex 保留预算 64k / 单条 agent 10k | `codex/codex-rs/core/src/compact_remote_v2.rs:87–88` |
| Codex 自动压缩触发 | `codex/codex-rs/core/src/session/context_window.rs` |
| Codex 摘要 prompt 四段 | `codex/codex-rs/prompts/templates/compact/prompt.md` |
| Codex 保留工具输出（client_authored） | `codex/codex-rs/core/src/compact_retained_tool_outputs.rs` |
| CordiAct `maxResultChars` 声明未使用 | `CordiAct/core/src/main/kotlin/com/cordiact/core/toolloop/ToolLoopContracts.kt:36` |
| CordiAct 历史只取 SYSTEM/USER/ASSISTANT | `CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ConversationTurnService.kt:74–76` |
| CordiAct `ModelMessage` 已有 tool 配对字段 | `CordiAct/core/src/main/kotlin/com/cordiact/core/conversation/ConversationContracts.kt:48–72` |
| CordiAct `truncateOutput` 只留头 | `CordiAct/core/src/main/kotlin/com/cordiact/core/execution/ExecutionContracts.kt:34–40` |
| CordiAct DB v6、消息表无 seq | `CordiAct/app/src/main/java/com/cordiact/app/conversation/AndroidConversationSessionStore.kt:207–217, 682` |

## 附录 B：默认参数表

| 参数 | 默认值 | 出处 / 理由 |
| :--- | :--- | :--- |
| `thresholdRatio` | 0.8 | DSH `DEFAULT_THRESHOLD_RATIO` |
| `retainRatio` | 0.16 | DSH `DEFAULT_RETAIN_RATIO` |
| `minRetainTurns` | 2 | 至少覆盖“当前动作 + 上一次结果” |
| `maxOutputTokens` | 8192 | 常见模型的输出上限；且 ≤ 窗口 25% |
| `safetyMargin` | max(2048, 窗口 5%) | 估算误差缓冲 |
| `maxInlineToolChars` | 8192 | DSH `thresholdChars` 默认值；约 2k tokens，单次结果不至于淹没注意力 |
| `toolHeadChars` / `toolTailChars` | 4096 / 2048 | 头取 DSH 的 `headChars`；尾比它的 1024 大一倍，因为 shell 的报错在尾部 |
| `foldAfterRounds` | 2 | 当前轮 + 上一轮的 tool result 保持原文 |
| `summaryTimeoutMs` | 20000 | 移动端可接受的等待上限 |
| `spillRetentionDays` | 7 | 与临时文件的生命周期一致 |
| `providerFactor` | 1.10 | 保守；按模型校准后回落 |
