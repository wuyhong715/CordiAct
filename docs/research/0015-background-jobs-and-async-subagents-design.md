# 0015 后台任务、任务窗口与子 Agent 异步回合：调查与设计

- 日期：2026-11-07
- 参照快照：`deepseek-harness`（工作区本地副本）、`codex`（工作区本地副本）
- 上游相关包：`packages/jobs/{jobs,jobs-local,tool-jobs}`、`packages/subagent/subagent/src/{manager,control-types,continuation-messages,inbox}.ts`、`.agents/notes/archived/architecture/2026-06-20-generic-long-running-tool-runtime.zh.md`、`.agents/notes/archived/bug-fix/2026-08-11-bounded-background-job-admission.zh.md`、`.agents/notes/archived/bug-fix/2026-08-17-subagent-message-settlement-ordering.zh.md`；`codex-rs/core/src/{thread_manager.rs,agent/control.rs,agent/control/{delivery,residency}.rs,agent_message_board.rs,context/subagent_notification.rs}`
- 状态：设计待实现
- 与 0014 的关系：0014 管"上下文太长"，本文管"任务太长、会话太多"。两者共用一个 spill 存储（见 3.4）。

## 0. 结论

先把三个被混在一起的词拆开，它们解决的是不同问题：

| 词 | 真正的问题 | 不解决会怎样 |
| :--- | :--- | :--- |
| **后台任务（Job）** | 一次工具调用可能跑 20 分钟，而模型的一次往返只有几十秒 | 用户等到超时，或者模型拿不到结果只能瞎猜 |
| **任务窗口（Window / Residency）** | 任务和会话在 App 切后台、锁屏、被 LMK 杀之后还在不在 | 记录永远停在 RUNNING，用户看到"僵尸任务" |
| **异步回合（Async turn）** | 子 Agent 有进展时，父 Agent 正在等一个长工具调用 | 关键线索要等到用户下次说话才能被看见 |

五条结论：

1. **后台任务的正确形状是"一个 seam + 三个动作"，不是给每个后端加一个 `background` 布尔参数。** CordiAct 今天恰好是后者：`ssh_exec(background=true)` 包一层 `nohup … > /dev/null 2>&1 & echo $!`，把输出直接丢进 `/dev/null`，只回一个 PID 字符串（`JSchSshService.kt:129–131`）；`termux_run_command(background=true)` 只是**不让 Termux 的窗口抢焦点**（`TermuxTools.kt:69`、`TermuxBridge.kt:181–184`），而调用本身仍然 `latch.await(timeoutMs)` 阻塞到超时。同一个参数名，三种互不兼容的语义——模型学不会，代码也统一不了。DSH 的答案是把它们收进 `ctx.jobs`，模型只看见 `job_list / job_output / job_kill`。
2. **"异步回合"不是"多开线程"，而是"父级有一个可被投递的收件箱 + 一个唤醒策略"。** CordiAct 现在已经有后台子 Agent 线程（`BackgroundSubagentRuntime`），但它对父级的影响**只发生在下一轮的开头**：`TurnNoticeSource.pending()` 在 `ConversationTurnService` 第 66 行与第 258 行被读一次，拼成一条 SYSTEM 块。父级正在跑一个 5 分钟的工具调用时，子 Agent 的任何发现都进不来。真正的异步化是**让投递发生在回合内部**。
3. **唤醒必须限额，否则子 Agent 会互相烧钱。** Codex 把"这条消息要不要触发新回合"压缩成一个布尔 `trigger_turn`（`agent/control/delivery.rs:18`），并且靠它决定线程能不能被卸载（`residency.rs` 的 `has_trigger_turn_mailbox_items`）。DSH 则用 `sendWaking` + `wake_generation` 只在父级**空闲**时拉起新一轮。两者都指向同一件事：**唤醒是一个有配额的策略，不是一个默认行为**。
4. **Android 上"后台"不是权限问题，是存活问题。** CordiAct 的 `AndroidManifest.xml` 里没有任何前台服务、没有 `POST_NOTIFICATIONS`、没有 `SYSTEM_ALERT_WINDOW`。后台子 Agent 跑在进程内线程池里，用户一锁屏，LMK 随时可以把这个进程连同 4 个正在推理的子 Agent 一起收走。`recoverAbandonedRuns()`（`CordiActApplication.kt:424`）能事后把记录改成 ABORTED，但**它救不回已经跑掉的 20 分钟**。
5. **存活语义必须显式建模。** 本地子 Agent 死了就是死了；`ssh_exec(background=true)` 起的 `nohup` 进程**在远端还活着**。启动对账时把后者标成 FAILED 是错的，标成 RUNNING 也是错的——它需要一个第三态：`DETACHED_UNKNOWN`，以及一个能重新探活的 `job_probe`。

所以本设计落成三块加一层自愈：

```
   ① core.jobs —— 统一后台任务 seam（JobRegistry + spill 输出 + 存活语义）
                    ▲                        ▲
                    │                        │
   ② 子 Agent 收件箱与唤醒 ◀───────────────▶ ③ 任务窗口（FGS / 面板 / 驻留）
                    │
                    ▼
   ④ 三层自愈：lease 心跳 → 启动对账 → 远程分离任务的探活对账
```

分期：**P0 `core.jobs` + DB + 三个工具（把 SSH/Termux 的后台语义统一）**；**P1 `AgentExecutionService` 前台服务 + 通知 + lease**；**P2 收件箱与唤醒（真正的异步回合）**；**P3 任务面板与悬浮窗**。

## 1. 已核对的上游机制

### 1.1 DSH：`ctx.jobs` 是一个 seam，不是一个工具集

`packages/jobs/jobs/src/types.ts` 定义的东西很少，但每一件都是踩过坑之后留下的：

- **`JobHandle`** 只暴露四件事：`id`（形如 `<kind>-<short-uuid>`）、`append(text)`（按 **UTF-8 字节偏移**写进输出环）、`updateProgress(line)`（一行"现在在干什么"，结算时清除）、`cancel(reason)`。
- **`cancel` 的契约是"同步且幂等，且最终必须 settle"**。原文注释的理由很直接：一个正在 `stopping` 的进程仍然占着准入配额，所以取消不能只发个信号就走——它必须自己负责把 `done` 落下来。
- **`JobSpec.owner` 是 `SessionId`**，并且"该 id 下当前注册的活 Agent 必须是它自己：Agent 被 dispose 时，取消并等待它的 job"。这句话把"会话生命周期"和"任务生命周期"钉死在一起，是后面所有对账逻辑的地基。
- **`outputLimitBytes`** 限制的是"单次面向模型的输出读取或结算通知"的大小，而不是总输出。总输出可以很大，因为它在 ring 里、在文件里；进模型的永远是一小段。

三个工具（`packages/jobs/tool-jobs/src/index.ts`）的设计细节值得照抄：

- `job_output(jobId, offset, wait, timeoutMs)`——**等待与读取合并**，不提供单独的 `job_wait`。理由：多一次模型往返就多一次 token 与一次出错机会。
- **超时不报错**：`wait=true` 且超时，返回的是 `{status: "running", new_output: …}` 这样一个正常观察对象，而不是 tool error。因为"还在跑"是**预期状态**，不是失败；报错会让模型触发重试与降级逻辑，反而把简单的等待变成一串错误处理。
- `job_kill(jobId)` 走的是同一个 `cancel` 契约。

### 1.2 DSH：有界准入，"取消不立即释放配额"

`2026-08-11-bounded-background-job-admission.zh.md` 记录的问题很典型：后台任务可以被模型无限启动（每次 bash 一个、每次 subagent 一个），一次会话就能把进程表撑爆。他们的做法：

- `maxConcurrentJobsPerOwner = 10`，**在同步调用栈里立即扣减**，装不下就当场拒绝，绝不排队——排队会把"拒绝"变成"稍后以更隐蔽的方式失败"。
- **`running` 与 `stopping` 都计入配额**。因为一个正在停的进程仍然占着内存和句柄；如果取消立刻归还配额，模型就可以"启动 10 个、取消 10 个、再启动 10 个"，实际并发翻倍。

### 1.3 DSH：子 Agent 的因果保序（Issue #2600）

这是本次调研里最有价值的一条缺陷记录（`2026-08-17-subagent-message-settlement-ordering.zh.md`）。它的价值不在于"修了一个 bug"，而在于它把"异步回合"最容易错的地方写清楚了。

**问题**：一个可继续的子 Agent 可以在运行中发消息（"我发现了一件会改变你下一步动作的事"），之后还会产生一条由管理器编写、无条件投递的**结算通知**。如果这两条消息进入**领取优先级不同的两个队列**，较晚的结算就可能先于较早的消息到达父模型。具体机制是：一个轮次的第一个 step 会先领完整批 `next-step`，再领一条 `next-turn`；于是"FIFO 的后续轮次发送"与"next-step 的结算"一混，因果就颠倒了。

**修法**（三条，缺一不可）：

1. **每条模型编写的相邻 Agent 消息都走固定 Steer 投递**，并且**模型没有"静默"或"next-turn"选项**。调用方不能选择投递模式，因此顺序与唤醒行为不会随调用点或部署变化。
2. **结算通知留在 `next-step` 队列**，不挪到 `next-turn`。理由很实在：结算批处理让"三个子 Agent 同时结束"只消耗父级一个 step，而不是三个轮次；挪走它是用延迟和模型工作量去换一个不需要的调度自由度。
3. **`sendWaking()` 与 Activation 的 wake generation 联动**：被接受的唤醒发送在**返回前**就更新接收方 Activation 的 wake generation，因此接收方不会"越过一条已被接受的唤醒"完成结算。

**验证过的顺序语义**（这正是我要抄的部分）：

- 运行中的父级：child 消息与其后的结算进**同一条 `next-step` FIFO**；如果父级在结算到达前变为空闲，它已经领走了 child 消息，结算随后开启后续轮次，观察顺序不会颠倒。
- 父级在 maintenance 时：child 消息占 `next-step` 并锁存一次唤醒，结算可能占 `next-turn`；初始领取仍然先取 next-step、再取排队轮次。

**被明确否决的两个方案**（对我很有用，因为我也想给"安静模式"）：

- **提供静默投递**：空闲父级停驻后可能永远不读静默消息；而且它会让"等价的模型编写消息"具有不同存活语义，把依赖部署的顺序问题重新引入。
- **提供 next-turn 投递**：较晚的 next-step 结算仍可能越过它，要保持顺序就得引入跨队列屏障，而收益撑不起这个机制。

**后果**：child 消息可以**延长**一个已经打开的父回合，但**绝不打断**活跃的模型请求或工具执行——agent loop 只在 step 边界接纳它。一起被接受的消息共享一个 next-step 批次，既保 FIFO 又限制轮次放大。

### 1.4 Codex：thread 即 agent，驻留即窗口

Codex 把每次 Agent 运行建模成一个 `Thread`，于是"后台任务"和"窗口"变成了两个已有概念的自然延伸：

- **`AgentStatus`** 有七个值：`PendingInit`、`Running`、`Interrupted`、`Completed(Option<String>)`、`Errored(String)`、`Shutdown`、`NotFound`（`codex-rs/protocol/src/protocol.rs:2023–2039`）。注意 `Interrupted` 与 `Completed` 是**两个不同的终态**——被打断的 Agent 还能继续收输入，这跟"完成"完全不是一回事。
- **驻留（residency）**：`agent/control/residency.rs` 维护一个 `VecDeque<ThreadId>` 的 LRU，只有子 Agent 会话是驻留候选；一个线程在 `PendingInit | Completed | Errored | Interrupted` **且**没有活跃 turn **且**没有未完成的 durable sleep **且**邮箱里没有 `trigger_turn` 条目时，才允许被卸载。换句话说：**"窗口关掉" = "从内存卸载"，而不是"任务结束"**。这就是"任务窗口"最准确的实现形态。

### 1.5 Codex：`trigger_turn` 是"要不要唤醒"的唯一开关

`InterAgentCommunication` 带一个 `trigger_turn: bool`，投递模式只有两种（`agent/control/delivery.rs:18`）：

- `QueueOnly` → `trigger_turn = false`：消息进邮箱，等父级下一次自己开始回合时再看见（对应 `MessageType::Message`）；
- `TriggerTurn` → `trigger_turn = true`：消息进邮箱，**并拉起一个回合**（对应 `MessageType::NewTask`）。

而子 Agent 的**完成**通知走的是 `trigger_turn = false`（`agent/control.rs:525`、`agent/control/completion.rs:117`）。这个不对称是刻意的：完成通知属于"运行时报告"，父级应当在下次自然开始时看到它，而不是被它叫醒去空转一轮。唤醒留给"新任务"这一类。

另外，通知本身的形状是**结构化片段**而不是散文（`context/subagent_notification.rs`）：

```xml
<subagent_notification>
{"agent_path": "subagent_01", "status": "completed"}
</subagent_notification>
```

role 是 `user`，`content_kind` 是 `multi_agent.subagent_notification`。用一个可被解析的标记包起来，父级和日志都能可靠地区分"这是运行时注入的"与"这是用户说的"。

### 1.6 社区做法（公开资料，未在本地快照中核对）

| 项目 | 后台任务 | 异步子 Agent | 唤醒/通知 |
| :--- | :--- | :--- | :--- |
| **Claude Code** | `run_in_background`，输出落文件，任务结束后在下个 prompt 头部注入 stdout 尾部 | 以子进程/子会话隔离 | 注入式，不主动唤醒 |
| **OpenHands** | EventStream 驱动，Bash 进程是异步事件生产者 | `DelegateAction` 派生 sub-event stream | 统一事件总线，父级订阅 |
| **AutoGen / CrewAI** | 不涉及进程 | GroupChat / 层级调度 | 中心化协调者轮询 |
| **moxw（本机宿主）** | `workspace_run_background` + `background_status` + `background_kill` | `subagent_dispatch(run_in_background=true)`，完成后以系统通知注入下一轮 | 通知式唤醒 |

四家的共同点：**后台任务的句柄必须能被列出来、读出来、杀掉**；**子 Agent 的完成必须变成父级可见的一条输入**。差别只在"要不要叫醒父级"和"输出放哪"。

### 1.7 采纳矩阵

| 机制 | 出处 | CordiAct 的处置 |
| :--- | :--- | :--- |
| `ctx.jobs` 统一 seam（list/output/kill） | DSH | **直接采纳**为 `core.jobs` |
| `cancel` 同步 + 幂等 + 必须 settle | DSH | **采纳**，写进契约注释与单测 |
| 有界准入、`stopping` 也占配额 | DSH | **采纳**；`maxRunningChildren = 4` 已有，扩到全类型 |
| `job_output(wait=true)` 超时返回 running | DSH | **采纳**，这是"少一次往返"的关键 |
| 单 FIFO 队列保序（#2600） | DSH | **采纳**，作为 P2 的硬不变量 |
| `agent-message` 与 `subagent-settled` 分型 | DSH | **采纳**；`TurnNotice` 现在只有一种，要加 `kind` |
| 后台候选 / step 边界吸收 | DSH | **采纳**；CordiAct 的 step 边界 = `ToolLoopPlugin` 的轮次边界 |
| thread 即 agent + 七态状态机 | Codex | **采纳**状态语义，不采纳 thread 抽象（Kotlin 里用 coroutine + 记录） |
| 驻留 LRU（窗口=内存驻留） | Codex | **采纳**为 P3 的会话驻留策略 |
| `trigger_turn` 布尔 | Codex | **改造**为 `WakePolicy`（见 4.4）：布尔不够，还要配额 |
| `<subagent_notification>` 结构化片段 | Codex | **采纳**，替换现在纯散文的 `noticeBlock` |
| 前台服务 + 通知 | 社区 / Android 平台 | **采纳**为 P1 核心 |
| WorkManager 跑实时任务 | — | **不采纳**：10 分钟上限 + 延迟不可控，只能做离线整理 |

## 2. 由 CordiAct 现状推出的约束

### 2.1 已经有的：`BackgroundSubagentService` 的七个方法

`core/subagent/BackgroundContracts.kt:139` 定义的接口已经相当完整，本次不是重写而是**扩展它的边界**：

```kotlin
interface BackgroundSubagentService {
    fun spawn(spec: ChildSpec, parent: ToolInvocationContext): ChildRun
    fun waitFor(parent: ToolInvocationContext, childId: String?, timeoutMs: Long): List<ChildRun>
    fun states(parent: ToolInvocationContext): List<ChildRun>
    fun close(parent: ToolInvocationContext, childId: String): ChildRun
    fun send(parent: ToolInvocationContext, childId: String, message: String): ChildRun
    fun pendingNotices(parentSessionId: String): List<ChildNotice>
    fun acknowledgeNotices(parentSessionId: String, ids: List<String>)
    fun recoverAbandonedRuns(): List<AgentRunRecord>
}
```

配额与预算也已经有了（`BackgroundLimits`，第 83–106 行）：`maxRunningChildren = 4`、`runTimeoutMillis = 600_000`、`waitFor` 的 `minWaitMillis = 1_000 / maxWaitMillis = 120_000`。这些数字**不需要重新发明**，P0/P2 直接复用。

### 2.2 五个缺口

1. **没有跨后端的任务抽象**。SSH、Termux、本地 Shell、子 Agent 各有一套"后台"概念，模型必须分别学习。`ssh_exec(background=true)` 包的是 `nohup sh -c … > /dev/null 2>&1 & echo $!`（`JSchSshService.kt:129–131`）——**输出被丢进 `/dev/null`**，返回的 PID 是个字符串，下一次调用既读不到它打印了什么，也没法用它杀掉（`SshTools.kt:222` 的描述只有 "return PID immediately"）。
2. **`termux_run_command(background=true)` 是假的异步**。`TermuxBridge.executeCommand` 在第 182 行 `startService` 之后，第 184 行仍然 `latch.await(timeoutMs)`；`background` extra 只影响 Termux 自己是否弹窗（`TermuxTools.kt:69` 的描述原文是 "without popping up the Termux window"）。一个跑 5 分钟的构建照样返回 `timedOut`。
3. **通知只在回合开头落地**。`TurnNoticeSource.pending()` 只在 `ConversationTurnService` 第 66 行（`submitWithTools`）与第 258 行（`submit`）各读一次。回合内部——也就是 `ToolLoopPlugin` 的 8 轮之间（`ToolLoopLimits.maxRounds = 8`）——没有任何投递点。
4. **没有前台服务，后台任务随进程一起死**。`AndroidManifest.xml` 中无 `foregroundServiceType`、无 `POST_NOTIFICATIONS`、无 `SYSTEM_ALERT_WINDOW`。`BackgroundSubagentRuntime` 用的是进程内线程池。
5. **对账只覆盖"进程重启"这一种死法**。`AgentRunStore.abortRunning()`（第 115 行接口、`recoverAbandonedRuns` 在 `BackgroundSubagentRuntime.kt:329`）在启动时把 RUNNING 批量改 ABORTED。但如果进程没死、只是协程被 `runTimeoutMillis` 之外的路径卡住（例如某个 provider 的 socket 永久挂起），记录会一直停在 RUNNING 且没有任何机制发现它。

### 2.3 Android 的三道关卡

| 关卡 | 触发条件 | 后果 | 应对 |
| :--- | :--- | :--- | :--- |
| **进程优先级降级** | 用户按 Home | 进程变 CACHED，LMK 内存吃紧时首选 | 前台服务把进程提到 FOREGROUND |
| **Doze / App Standby** | 息屏静止一段时间 | 网络与 CPU 被推迟，定时器不精确 | 前台服务 + 必要时申请电池优化豁免（需引导用户） |
| **API 34+ 前台服务类型** | `targetSdk ≥ 34` | 不声明 `foregroundServiceType` 直接抛 `MissingForegroundServiceTypeException` | 声明 `dataSync`（用户发起的有限时长工作）；若审核不接受则用 `specialUse` 并写清理由 |

CordiAct 是 `minSdk 26 / targetSdk 35 / compileSdk 35`（`app/build.gradle.kts:6,20,21`），所以**必须**处理第三条。

### 2.4 必须顺手修的三个缺陷

1. **`termux_run_command` 的 `background` 名不副实**（`TermuxBridge.kt:182–184`）。P0 让它真的异步：`background=true` 时不再 `latch.await`，而是登记一个 `TERMUX` job 并立即返回 `jobId`；要输出就 `job_output`。
2. **`ssh_exec(background=true)` 返回的 PID 没有归宿**（`JSchSshService.kt:129`）。P0 把 PID 记进 job 记录，并提供 `job_probe` 重新探活（`kill -0 <pid>`）。这是唯一能正确处理"App 重启但远端进程还活着"的路径。
3. **`noticeBlock` 是纯散文**（`ConversationTurnService.kt:373–384`）。模型无法可靠区分"子 Agent 说的话"与"运行时对结局的陈述"。P2 改成结构化片段（见 4.2），并给 `TurnNotice` 加 `kind`。

## 3. 设计：`core.jobs`（统一后台任务）

### 3.1 形状

```
                      ┌────────────────────────────┐
                      │        JobRegistry         │
                      │  admission / ring / spill  │
                      └─────────────┬──────────────┘
        ┌───────────────┬───────────┴────────┬────────────────┐
        ▼               ▼                    ▼                ▼
   [SHELL]         [SSH]              [TERMUX]          [SUBAGENT]
  本地进程      nohup + PID         RUN_COMMAND 服务    BackgroundSubagentRuntime
  PROCESS_LOCAL  REMOTE_DETACHED    PROCESS_LOCAL      PROCESS_LOCAL
```

四种后端共用一套：id 分配、准入、输出 ring、结算通知、对账。差异只有两个字段：`JobKind`（怎么启动/怎么杀）与 `survival`（进程死了它还在不在）。

### 3.2 契约

`core/src/main/kotlin/com/cordiact/core/jobs/JobContracts.kt`：

```kotlin
enum class JobKind { SHELL, SSH, TERMUX, SUBAGENT }

/** 进程死亡后这个任务还在不在。对账逻辑全靠它。 */
enum class JobSurvival {
    /** 与 App 进程同生共死：重启后只能标 ABORTED。 */
    PROCESS_LOCAL,
    /** 远端独立进程：重启后状态未知，必须探活，不能猜。 */
    REMOTE_DETACHED,
}

enum class JobState { PENDING, RUNNING, STOPPING, SUCCEEDED, FAILED, KILLED, ABORTED, DETACHED_UNKNOWN }

data class JobSpec(
    val kind: JobKind,
    val survival: JobSurvival,
    val label: String,
    val ownerSessionId: String,
    /** 单次面向模型的输出上限；总输出可以更大，它在 spill 里。 */
    val outputLimitBytes: Int = 64_000,
    /**
     * 取消。必须同步返回，必须幂等，并且自己负责把 [JobHandle.settle] 落下来。
     * 照抄 DSH 的契约：一个"正在停"的任务仍然占着配额，所以不能只发信号就走。
     */
    val cancel: (reason: String) -> Unit,
)

interface JobHandle {
    val id: String
    /** 追加输出。实现按 UTF-8 字节偏移记账，调用方不需要关心分片边界。 */
    fun append(text: String)
    /** 一行"现在在干什么"。结算时清空。 */
    fun updateProgress(line: String?)
    /** 只结算一次；重复调用忽略。 */
    fun settle(state: JobState, exitCode: Int? = null, diagnostic: String? = null)
}

data class JobSnapshot(
    val id: String,
    val kind: JobKind,
    val survival: JobSurvival,
    val state: JobState,
    val label: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val exitCode: Int? = null,
    val progressLine: String? = null,
    val outputBytes: Long = 0,
    val diagnostic: String? = null,
)

data class JobRead(
    val snapshot: JobSnapshot,
    val text: String,
    val nextOffset: Long,
    val eof: Boolean,
)

interface JobRegistry {
    /** 准入失败抛 [JobAdmissionException]，而不是排队。 */
    fun start(spec: JobSpec): JobHandle
    fun list(ownerSessionId: String): List<JobSnapshot>
    fun read(jobId: String, offset: Long = 0, limitBytes: Int = 8_000): JobRead
    fun kill(jobId: String, reason: String): Boolean
    fun probe(jobId: String): JobSnapshot          // 仅 REMOTE_DETACHED 有意义
    fun onSettled(listener: (JobSnapshot) -> Unit)
    /** 进程启动时调用：本地任务一律 ABORTED，远端任务转 DETACHED_UNKNOWN。 */
    fun reconcileOnStartup(): List<JobSnapshot>
}
```

### 3.3 准入与配额

三个上限，全部在**同步调用栈**里判断：

| 配额 | 默认 | 依据 |
| :--- | :--- | :--- |
| 单会话并发任务 | 4 | 复用 `BackgroundLimits.maxRunningChildren = 4` |
| 全 App 并发任务 | 8 | 手机内存与网络的现实上限 |
| 单会话任务总数（生命周期内） | 64 | 防止"启动-结束-再启动"刷爆记录表 |

`RUNNING` 与 `STOPPING` 都占配额（DSH 的教训）。准入失败是**工具错误**（"已经跑着 4 个任务，先 `job_kill` 或等一个结束"），不是静默排队——模型需要立刻知道它撞墙了。

### 3.4 输出与 spill：复用 0014 的存储

0014 已经定义了 `SpillStore`（`context.noBackupFilesDir/spills/<sessionId>/`）与 `tool_output_read(offset, limit)` 的分页读取。后台任务的输出**不新建一套**：

- `JobHandle.append()` 把字节流写进同一个 spill 目录下的 `job-<id>.log`；
- `job_output` 与 `tool_output_read` 共用同一个读取实现与同一个路径白名单；
- 唯一的差别是 `job_output` 会额外带上 `state / progressLine / exitCode`。

这样做的好处是"上下文控制"与"后台任务"天然对齐：一个跑了 10 分钟、输出 40MB 的构建，进模型时永远只是"最后 N 字节 + 一句 progress"。这正是 0014 里 G1（工具结果溢出）想达到的效果，只是数据源从"一次工具调用的返回值"变成了"一个持续写入的文件"。

### 3.5 存活语义：`PROCESS_LOCAL` 与 `REMOTE_DETACHED`

这是本设计与 DSH 最实质的差异——**DSH 跑在桌面/服务端，进程模型单一；CordiAct 同时管本地进程和远端进程**。

| | `PROCESS_LOCAL` | `REMOTE_DETACHED` |
| :--- | :--- | :--- |
| 例子 | 子 Agent、Termux 命令、本地 Shell | `ssh_exec(background=true)` 的 `nohup` 进程 |
| App 被杀之后 | 任务消失 | 进程仍在远端跑 |
| 启动对账 | 一律 `ABORTED`，附诊断"App 被关闭" | 转 `DETACHED_UNKNOWN`，**不猜** |
| 恢复路径 | 无（父级收到通知，可重新派发） | `job_probe` → `kill -0 <pid>`，活着就回 `RUNNING`（带 `exitCode` 未知标记），死了就 `SUCCEEDED/FAILED` 未知 |

把"未知"显式建成一个状态，比在 UI 上假装知道要诚实得多；而且它给了模型一个正确的动作：**先 probe，再决定要不要重跑**。

### 3.6 数据模型（下一个可用 DB 版本）

> 编号说明：0014 也提议了"下一个版本"（它写的 v7）。两处迁移都是独立的 `CREATE TABLE IF NOT EXISTS`，互不依赖，按落地先后取号即可。下面用 `vNext` 表示。

```sql
CREATE TABLE IF NOT EXISTS jobs (
    id                TEXT PRIMARY KEY NOT NULL,
    kind              TEXT NOT NULL,              -- SHELL | SSH | TERMUX | SUBAGENT
    survival          TEXT NOT NULL,              -- PROCESS_LOCAL | REMOTE_DETACHED
    owner_session_id  TEXT NOT NULL,
    label             TEXT NOT NULL,
    state             TEXT NOT NULL,
    started_at        INTEGER NOT NULL,
    finished_at       INTEGER,
    exit_code         INTEGER,
    progress_line     TEXT,
    output_path       TEXT,                       -- spill 相对路径
    output_bytes      INTEGER NOT NULL DEFAULT 0,
    remote_pid        INTEGER,                    -- 仅 REMOTE_DETACHED
    diagnostic        TEXT,
    last_heartbeat_at INTEGER NOT NULL,
    FOREIGN KEY (owner_session_id) REFERENCES conversation_sessions(session_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS jobs_owner ON jobs(owner_session_id, started_at);
CREATE INDEX IF NOT EXISTS jobs_state ON jobs(state);
```

不变量（可测）：

- `state` 是终态时 `finished_at` 非空，反之亦然；
- `PROCESS_LOCAL` 的行**永远不会**处于 `DETACHED_UNKNOWN`；
- `REMOTE_DETACHED` 的行必须有 `remote_pid`，否则对账无从谈起；
- 会话删除时级联删除（`ON DELETE CASCADE`），并删除对应 spill 文件。

`last_heartbeat_at` 是 4.5/6.1 的 lease 基础：任务在跑就每秒更新一次；超过 `3 × heartbeatInterval`（默认 3×1s）没更新，视为孤儿。

### 3.7 面向模型的工具

| 工具 | 参数 | 返回 |
| :--- | :--- | :--- |
| `job_list` | 无 | 当前会话所有任务的 `id/kind/state/label/progress/elapsed` |
| `job_output` | `job_id`, `offset=0`, `limit_chars=8000`, `wait=false`, `timeout_ms=0` | `state` + `text` + `next_offset` + `eof`；`wait=true` 超时返回 `state=running`，**不是错误** |
| `job_kill` | `job_id`, `reason` | 结算后的 snapshot |
| `job_probe` | `job_id` | 仅 `REMOTE_DETACHED`：探活后的 snapshot |

`ssh_exec` / `termux_run_command` 保留现有的 `background` 参数名，但**统一它的语义**：`background=true` 时不再阻塞、不再返回 PID 或超时，而是登记一个 job 并立即返回 `{job_id, state:"running"}`。名字不变是因为模型与既有 prompt 已经认识它；变的是它终于名副其实（见 2.4 的三条缺陷）。

## 4. 设计：子 Agent 异步回合

### 4.1 三个状态与一个队列

父会话只需要三个状态：

```
        ┌──────────┐   用户发消息 / 唤醒     ┌──────────┐
        │   IDLE   │ ───────────────────────▶│   BUSY   │
        └──────────┘                         └────┬─────┘
             ▲                                    │ 回合结束
             │        ┌──────────┐                │
             └────────│ WAITING  │◀───────────────┘
                      └──────────┘
                 等待子任务（无活跃回合）
```

`WAITING` 与 `IDLE` 的区别是**语义而非实现**：`WAITING` 表示"这个会话有一个未结算的子任务"，因此唤醒它是合理的；`IDLE` 表示"没有理由自己动"。这个区别直接决定 4.4 的唤醒策略。

每个会话一个 **FIFO 收件箱**（DSH 的单队列）：

```kotlin
enum class InboxItemKind {
    /** 子 Agent 自己选择说的话。 */
    CHILD_MESSAGE,
    /** 运行时对子 Agent 结局的陈述。 */
    CHILD_SETTLED,
    /** 后台任务结算（job）。 */
    JOB_SETTLED,
}

data class InboxItem(
    val seq: Long,                    // 单调递增，全局唯一
    val kind: InboxItemKind,
    val sourceId: String,             // childRunId 或 jobId
    val sessionId: String,            // 父会话
    val heading: String,
    val body: String? = null,
    val createdAtMillis: Long,
)
```

### 4.2 因果保序（#2600 的硬不变量）

**同一来源（同一个 child 或同一个 job）产生的条目，必须按产生顺序出现在收件箱里**；`CHILD_SETTLED` 永远排在该 child 的最后一个 `CHILD_MESSAGE` 之后。实现上只有一条规则：**所有条目走同一个自增 `seq`，并且只有一个消费路径按 `seq` 顺序 drain**。

DSH 的教训是"有 seq 但两条队列"照样会乱——只要存在一条能先取走结算的快速通道，`seq` 就形同虚设。所以 CordiAct **不给 `CHILD_SETTLED` 留直通路径**，它和普通消息共用同一个 `drain()`，只是在 `kind` 上区分（结算在 `runToSettle` 末尾入队，现在 `enqueueNotice` 在 `BackgroundSubagentRuntime.kt:383` 做了类似的事，但没有 seq，也没有与消息共用队列）。

配套：注入父级时**必须保留 `kind`**，让模型能分清"子 Agent 说"与"运行时报告"：

```
<child_report kind="settled" child="subagent_01">
"架构调研" 已完成：3 个模块，2 个风险点。
</child_report>

<child_report kind="message" child="subagent_01">
补充：`ToolLoopLimits.maxResultChars` 声明了却没人读，上下文那版要顺手修掉。
</child_report>
```

这与 Codex 的 `<subagent_notification>` 同构，也解决了现在 `noticeBlock` 只有一段散文的问题（`ConversationTurnService.kt:373`）。

### 4.3 忙碌父级：在 step 边界吸收

CordiAct 的 step 边界是 `ToolLoopPlugin` 的**轮次边界**——每轮"模型请求 → 工具执行 → 结果回填"之间。在那里加一个投递点：

```kotlin
// ToolLoopPlugin.run 的每一轮开头
val delivered = inbox.drain(parentSessionId)     // 按 seq 排序，取前 K 条
if (delivered.isNotEmpty()) {
    messages += ModelMessage(MessageRole.SYSTEM, renderChildReports(delivered))
    inbox.acknowledge(delivered.map { it.seq })  // 仅在请求已发出后确认
}
```

三条约束：

- **只在轮次边界投递**，绝不打断正在进行的模型请求（与 DSH 的 "step 边界原子吸收"一致）；
- **每轮最多投递 K 条**（默认 8），避免一次塞进 40 条通知把上下文炸掉；
- **ack 语义不变**：只有包含它的那次请求真的发出去了，才 `acknowledge`。现在 `TurnNoticeSource.acknowledge` 的注释已经写明了这条（"Deliberately not called when the turn fails to start"），保留它。

### 4.4 空闲父级：唤醒是一个有配额的策略

Codex 的 `trigger_turn` 布尔不够用，因为**唤醒会花用户的钱**。CordiAct 需要一个带配额的策略：

```kotlin
data class WakePolicy(
    /** 关闭时，所有条目只排队，等用户下次说话。 */
    val enabled: Boolean = true,
    /** 只有这些 kind 能唤醒。默认只让"子任务结算"唤醒，不让中间消息唤醒。 */
    val wakeOnKinds: Set<InboxItemKind> = setOf(InboxItemKind.CHILD_SETTLED, InboxItemKind.JOB_SETTLED),
    /** 每小时最多自动唤醒几次。超了就只排队。 */
    val maxAutoWakesPerHour: Int = 4,
    /** 单次唤醒允许的最大回合预算，避免被唤醒后跑一个 20 分钟的长任务。 */
    val maxWokenRoundMillis: Long = 120_000,
)
```

关键判断（三条，都来自 DSH #2600 的取舍）：

1. **默认只让"结算"唤醒，不让"中间消息"唤醒。** 中间消息往往成串出现，每一条都唤醒会让一个 10 分钟的子任务拉起十几次父级回合；而结算是"事情有了结论"，值得一次唤醒。中间消息走 4.3 的边界吸收——父级正在忙就能看见，闲着就等下次自然开始。这与 Codex 对完成通知用 `trigger_turn = false` 是同一取舍。
2. **模型不能选择投递模式。** `WakePolicy` 是**宿主侧**（设置页 / 每会话配置）的东西，工具参数里没有"安静发"这个选项。DSH 明确否决了"静默投递"，理由是空闲父级可能永远不读，而且会让等价的模型消息具有不同存活语义。CordiAct 沿用这条否决：模型只能决定**说什么**，不能决定**要不要叫醒**。
3. **结算批处理。** 多个子任务在同一批结算里一起投递，只消耗父级一个 step 的边界吸收，而不是每个子任务一个轮次——这是把结算留在同一个 FIFO 而不是单开快通道的实际收益。

唤醒的执行路径（与现有 `submitWithTools` 共用一条，不新开一条）：

```
inbox 入队（kind ∈ wakeOnKinds 且父级 IDLE/WAITING 且配额未耗尽）
  → wakeLedger.tryAcquire(sessionId)      // 滑动一小时窗口
  → 会话锁：tryLock（拿不到说明用户刚好发了消息，放弃唤醒，交给 4.3）
  → 启动一个"woken turn"：system 块里说明"这不是用户发的消息"
  → 回合结束：结算、更新 wakeLedger、若又有新条目且配额还在，再评估一次
```

"这不是用户发的消息"这句必须写在注入块里（现在 `noticeBlock` 的第一句 "This is a runtime report, not a user message." 已经是对的，保留并强化）。

### 4.5 与会话锁、取消的交互

- **会话锁**：一个会话同时只能有一个活跃回合。唤醒必须 `tryLock`，拿不到就放弃（用户正在输入，优先级更高）。
- **用户取消**：取消父级回合时，**不取消**子任务（子任务的存在意义就是活过父回合）；但取消父会话时（用户关会话），级联 `close` 所有子任务。
- **子任务超时**：`runTimeoutMillis = 600_000` 到点后，子任务 `KILLED` 并入队 `CHILD_SETTLED`，`body` 里说明"超时"——超时必须是一个可解释的结算，不能静默消失。
- **深度与能力**：`maxDepth = 1`、能力只能收窄（0006 的硬不变量），本次不改。

### 4.6 一条不做的边界

**不做"子 Agent 直接给用户弹消息"**。所有子 Agent 的输出只能经父会话的收件箱进入，父级决定怎么转述。理由：多来源直接对用户说话会让 transcript 失去"谁说的"这个基本属性，也会让 0014 的上下文压缩失去唯一的锚点（父会话）。用户可见的只有 P3 的任务面板（那是**观测**，不是**对话**）。

## 5. 设计：任务窗口（Android）

"窗口"在 CordiAct 里有三层含义，必须分别设计：

| 层 | 是什么 | 解决什么 | 阶段 |
| :--- | :--- | :--- | :--- |
| **执行窗口** | 前台服务 | 进程不被杀 | P1 |
| **观察窗口** | 任务面板 / 悬浮窗 | 用户看得见、能干预 | P3 |
| **驻留窗口** | 会话在内存里的 LRU | 内存不被几十个子会话撑爆 | P3 |

### 5.1 执行窗口：`AgentExecutionService`

```
状态机（由 JobRegistry 驱动，不由 UI 驱动）

  runningCount == 0 ──────────────▶ 服务停止、通知取消、WakeLock 释放
        ▲                                        │
        │                                        │ 有任务启动 且 App 不在前台
        └────────────────────────────────────────┘
                    服务启动、通知常驻、WakeLock 获取
```

- `foregroundServiceType="dataSync"`（API 34+ 必需）。选择理由：这是"用户发起的、有限时长的数据同步/传输"，正好对应"用户在等我派出去的任务跑完"。
- 服务**不持有业务逻辑**：它只观察 `JobRegistry` 的计数与结算事件。所有任务都在应用进程内的协程/线程池里跑，服务的作用只有一个——**把进程优先级提上去**。
- 通知：一条汇总通知，`setOngoing(true)`、`setOnlyAlertOnce(true)`、`InboxStyle` 列出最多 5 条任务（`label + state + 已运行时长`），`contentIntent` 打开 App 的任务面板；`action` 提供"全部停止"。

### 5.2 通知与权限

| 权限 | API | 没有它会怎样 | 处理 |
| :--- | :--- | :--- | :--- |
| `FOREGROUND_SERVICE` | 28+ | 无法启动前台服务 | 清单声明 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 34+ | 启动即抛异常 | 清单声明 |
| `POST_NOTIFICATIONS` | 33+ | 服务能起，但通知不可见 | 运行时申请；拒绝时**降级**：服务照起（仍提优先级），UI 里提示"通知被关闭，后台任务进度不可见" |

**不能因为用户拒绝通知权限就不做后台任务**——那是把权限问题变成功能缺失。降级路径必须写清楚。

### 5.3 WakeLock 与时限

- 只在"有活跃任务 且 App 不在前台"时持有 `PARTIAL_WAKE_LOCK`；计数归零立即释放。
- 硬上限 `maxHeldWakeMillis = 30 分钟`。到点后：释放锁、把所有仍在跑的任务标 `ABORTED`（诊断："后台执行已达 30 分钟上限"）、发一条通知让用户回来看。
- 理由：不设上限，一个卡死的网络请求会让手机在口袋里发热 3 小时。30 分钟足够跑完绝大多数构建/测试/抓取。

### 5.4 观察窗口：任务面板 → 悬浮窗

**P3 先做应用内任务面板**（无权限、零风险）：

- 底部抽屉，每行一个任务：`图标 + label + state + 已运行时长 + 最后一行 progress`；
- 点开看实时输出尾部（复用 `job_output` 的读取实现，带"跟随"开关）；
- 行内"停止"按钮 → `job_kill`；
- 空态文案："没有正在运行的任务"。

**悬浮窗（可选，P3 之后）**：

- `SYSTEM_ALERT_WINDOW`，一个可拖动的胶囊，显示"2 个任务运行中"，点击展开成小窗；
- 只在用户显式开启时启用，默认关闭；
- 与现有特权体系一致（CordiAct 已有无障碍服务、虚拟屏），但仍需逐 ROM 的权限引导文案。

**明确不做**：不做分屏、不做画中画。它们解决的是"同时看两个 App"，而这里的问题是"看一个 App 内部的后台任务"，应用内面板是正确形状。

### 5.5 驻留窗口：会话在内存里的 LRU

P3 采纳 Codex 的 residency 思路：

- 一个子会话在 `SUCCEEDED | FAILED | KILLED | ABORTED` **且**无活跃回合 **且**收件箱无待唤醒条目时，可以从内存卸载（它的 DB 记录与 spill 都还在）；
- 驻留上限 `maxResidentSessions = 8`，超出按 LRU 卸载；
- 卸载只是释放内存，不影响 `job_list` 与历史查看。

## 6. 崩溃与僵尸：三层自愈

### 6.1 第一层：lease 心跳（进程活着但协程卡死）

`last_heartbeat_at` 每秒更新。`JobReaper` 每 5 秒扫一次，超过 `3 × interval` 未更新的 `PROCESS_LOCAL` 任务：

1. 调 `cancel("heartbeat lost")`；
2. 标 `ABORTED`，诊断写"任务心跳丢失，可能是协程被阻塞"；
3. 入队 `JOB_SETTLED`，父级可见。

这一层覆盖的是"进程没死、任务卡住"——现有 `recoverAbandonedRuns()` 完全覆盖不到的情况。

### 6.2 第二层：启动对账

进程启动时（`CordiActApplication.onCreate` 里 `recoverAbandonedRuns()` 之后）：

- 所有 `PROCESS_LOCAL` 的 `RUNNING/STOPPING` → `ABORTED`（现有行为，保留）；
- 所有 `REMOTE_DETACHED` 的 `RUNNING` → `DETACHED_UNKNOWN`；
- 每个被改写的任务入队一条 `JOB_SETTLED`，正文明确说"App 被关闭时这个任务还在跑，状态未知，用 `job_probe` 确认"。

### 6.3 第三层：远程分离任务的探活对账

`job_probe` 通过原 SSH 主机执行 `kill -0 <pid> && echo alive || echo gone`：

- `alive` → 保持 `RUNNING`（`exitCode` 未知），更新 `last_heartbeat_at`；
- `gone` → 无法知道退出码，标 `SUCCEEDED` 不合适、标 `FAILED` 也不合适。引入 `diagnostic = "remote process gone, exit code unknown"` 并保持 `DETACHED_UNKNOWN`，由模型决定要不要重跑。

**不允许**"猜一个状态让表格好看"。

## 7. 集成点与改动清单

| 位置 | 改动 | 风险 |
| :--- | :--- | :--- |
| `core/.../jobs/*` | 新增：`JobContracts`、`JobRegistry` 实现、`JobReaper`、`WakeLedger` | 低 |
| `core/.../subagent/BackgroundContracts.kt` | `ChildNotice` 加 `kind`；`TurnNotice` 加 `kind`；新增 `InboxItem` | 中；影响 `noticeBlock` 渲染 |
| `core/.../conversation/ConversationContracts.kt` | `TurnNotice(id, heading, body)` 扩为 `(id, kind, heading, body)` | 中；三处构造点 |
| `core/.../toolloop/ToolLoopContracts.kt` | 每轮开头 `inbox.drain()`；`maxResultChars` 生效（与 0014 同一处） | 中 |
| `model-providers/.../ConversationTurnService.kt` | `noticeBlock` 改结构化片段；回合结束后触发唤醒评估 | 中 |
| `app/.../termux/TermuxBridge.kt` | `background=true` 不再 `latch.await`，登记 job 并返回 `jobId` | **高**；改变现有工具语义，需版本过渡 |
| `core/.../execution/JSchSshService.kt` | `nohup` 的 PID 记进 job；新增 `job_probe` | 中 |
| `app/.../service/AgentExecutionService.kt` | 新增前台服务 + 通知 + WakeLock | 中；需清单与权限 |
| `app/src/main/AndroidManifest.xml` | 三个权限 + 服务声明 + `foregroundServiceType` | 低 |
| `app/.../conversation/AndroidConversationSessionStore.kt` | `jobs` 表 + 索引 + 级联删除 | 中；迁移必须幂等 |
| `app/.../ui` | 任务面板（P3）、悬浮窗（P3+） | 低 |

## 8. 观测与隐私

**指标**（每次任务结算记一条）：

```
jobId, kind, survival, state, wallClockMs, outputBytes,
peakRunningJobs, admissionRejects, wakeCount, wakeRejects
```

四个比率：

- **准入拒绝率**：长期 > 20% 说明配额太低或模型在滥发任务；
- **孤儿率**（`ABORTED` / 总数）：目标 < 5%，高说明后台存活有问题；
- **唤醒有效率**（唤醒后回合产生了非空输出）：目标 > 80%，低说明唤醒策略太激进；
- **单任务输出字节中位数**：用来校准 `outputLimitBytes`。

**隐私**：

- 任务输出落在 `noBackupFilesDir`，不进云备份（与 0014 的 spill 同一策略）；
- 通知内容**只显示 label 与状态**，不显示命令原文与输出——锁屏可见的通知是最容易泄露的地方；
- `jobs` 表随会话级联删除；
- `job_probe` 只接受本会话的 job id，且只在原 SSH 主机上执行固定的 `kill -0` 形式，不接受任意命令。

## 9. 分期与验收

### P0 — `core.jobs` + 三个工具 + 语义统一（约 2 天）

- `JobRegistry`（四后端注册、准入、ring、spill 输出、结算事件）；
- `jobs` 表 + 启动对账（本地 ABORTED / 远端 DETACHED_UNKNOWN）；
- `job_list` / `job_output` / `job_kill` / `job_probe`；
- `termux_run_command`、`ssh_exec` 的 `background` 参数改为真正异步（返回 `job_id`）。

**验收**：一个跑 90 秒的 Termux 命令在 `background=true` 时**立即**返回 `job_id`（改前会阻塞 30 秒后返回 `timedOut`）；`job_output(wait=true, timeout=5s)` 在任务未完成时返回 `state=running` 且**不是** tool error；准入上限生效（第 5 个任务被拒绝且错误信息可读）；杀掉 App 后重启，本地任务全为 `ABORTED`、SSH 后台任务全为 `DETACHED_UNKNOWN` 且 `job_probe` 能正确报告远端进程是否还活着。

### P1 — 执行窗口（前台服务）（约 1.5 天）

- `AgentExecutionService` + 汇总通知 + WakeLock + 30 分钟上限；
- `POST_NOTIFICATIONS` 申请与降级路径；
- lease 心跳 + `JobReaper`。

**验收**：启动一个 5 分钟的后台任务后按 Home，任务在 5 分钟后**正常完成**（改前大概率被杀）；通知在任务期间常驻、结束后消失；拒绝通知权限时服务仍能启动且任务照常完成；30 分钟上限到点后任务被 `ABORTED` 且通知可见；人为制造一个卡死协程（不更新心跳），`JobReaper` 在 15 秒内把它标 `ABORTED`。

### P2 — 异步回合（约 2 天）

- `InboxItem` + 单 FIFO + `seq` 保序；
- `ToolLoopPlugin` 轮次边界投递；
- `WakePolicy` + `WakeLedger` + woken turn；
- 结构化 `child_report` 片段。

**验收**：子 Agent 先发一条 message、再结算，父级收到的顺序**必定**是 message → settled（用一个故意反转投递顺序的假实现证明队列会纠正它）；父级正在跑 8 轮工具循环时，子 Agent 的结算在第 3 轮边界就能看见（改前要等整轮结束）；父级空闲时子任务结算触发一次唤醒，且一小时内第 5 次结算**不再唤醒**而是排队；用户正在输入时唤醒放弃且不产生空回合。

### P3 — 观察窗口与驻留（约 2 天）

- 任务面板（列表 + 跟随输出 + 停止）；
- 驻留 LRU（`maxResidentSessions = 8`）；
- 悬浮窗（可选）。

**验收**：面板里能看到 4 个并发任务的实时尾部输出；点"停止"后 1 秒内状态变 `KILLED`；创建 20 个已完成子会话后，驻留数不超过 8 且被卸载的会话仍可从 DB 完整打开；面板在任务清零后显示空态。

## 10. 明确不做

- **不用 WorkManager 跑实时任务**：10 分钟上限、延迟不可控、无法与前台回合协同。它只适合离线整理（记忆提炼），那条路已经由 0003 的调度占了。
- **不做分屏 / 画中画**：解决的不是同一个问题（见 5.4）。
- **不做子 Agent 直连用户**：所有输出经父会话收件箱（见 4.6）。
- **不做跨设备的任务迁移**：任务绑定本机进程或某个 SSH 主机，迁移语义无法定义。
- **不给后台任务做"自动重试"**：重试是模型的决策（它有上下文），不是运行时的默认行为。运行时只负责如实报告状态。
- **不改 `maxDepth = 1`**：递归委派的风险（成本、死锁、能力收窄失效）远大于收益。

## 11. 未验证 / 待核实

1. **`dataSync` 类型是否被应用商店接受**：`foregroundServiceType="dataSync"` 对"用户发起的长时间数据传输"是合规的，但 CordiAct 的场景是"AI 任务执行"，可能被认定为需要 `specialUse`。需在真机上验证启动是否抛异常，并准备两套清单配置。
2. **Termux `RUN_COMMAND` 的异步语义**：`EXTRA_RUN_COMMAND_BACKGROUND` 在 Termux 侧的准确行为（是"后台运行"还是"不弹窗"）需以 Termux 源码为准。本文按 `TermuxTools.kt:69` 的描述（"without popping up the Termux window"）理解为后者，但 P0 改动前必须核实，否则会做出一个"看起来异步、实际仍阻塞"的假修复。
3. **SSH `nohup` 在断连后的存活率**：取决于远端 sshd 的 `ClientAliveInterval`、`nohup` 是否真的脱离了会话组、以及是否有 `setsid`。`job_probe` 的可靠性依赖这一点，需要在至少两种远端环境（Linux 服务器 / 路由器）实测。
4. **唤醒配额的实际手感**：`maxAutoWakesPerHour = 4` 是拍的。需要用真实多子任务会话跑一轮，看用户是否觉得"它自己动得太频繁"或"该动的时候不动"。
5. **`ToolLoopPlugin` 的轮次边界是否足够**：CordiAct 的"轮"包含"模型请求 + 全部工具执行"，如果一次工具执行就要 5 分钟（P0 之后应该不会，因为长任务都退到后台了），边界粒度仍然偏粗。需要在 P2 实测。
6. **社区做法**：Claude Code / OpenHands / AutoGen / moxw 的机制来自公开资料与工具描述，**未在本地快照中逐行核对**，只作为模式参考。

## 附录 A：上游事实索引

| 事实 | 位置 |
| :--- | :--- |
| DSH `JobHandle` 四动作、`cancel` 同步幂等、owner 是 SessionId | `deepseek-harness/packages/jobs/jobs/src/types.ts` |
| DSH 三个 job 工具、`job_output` 等待与读取合并 | `.../packages/jobs/tool-jobs/src/index.ts` |
| DSH 有界准入、`stopping` 占配额 | `.../.agents/notes/archived/bug-fix/2026-08-11-bounded-background-job-admission.zh.md` |
| DSH 单 FIFO 保序、`agent-message` 与 `subagent-settled` 分型、`sendWaking` | `.../.agents/notes/archived/bug-fix/2026-08-17-subagent-message-settlement-ordering.zh.md` |
| DSH 子 Agent 三种模式、`sendMessage` 要求活着的发送者 | `.../packages/subagent/subagent/src/{control-types,manager}.ts` |
| Codex `AgentStatus` 七态 | `codex/codex-rs/protocol/src/protocol.rs:2023–2039` |
| Codex `trigger_turn` 与两种投递模式 | `codex/codex-rs/core/src/agent/control/delivery.rs:18` |
| Codex 完成通知用 `trigger_turn = false` | `codex/codex-rs/core/src/agent/control.rs:525`、`agent/control/completion.rs:117` |
| Codex 驻留 LRU 与卸载条件 | `codex/codex-rs/core/src/agent/control/residency.rs`（`is_unloadable`） |
| Codex 结构化子 Agent 通知片段 | `codex/codex-rs/core/src/context/subagent_notification.rs` |
| CordiAct 后台子 Agent 已有七个方法 | `CordiAct/core/src/main/kotlin/com/cordiact/core/subagent/BackgroundContracts.kt:139` |
| CordiAct `BackgroundLimits`（并发 4、超时 600s） | `.../BackgroundContracts.kt:83–106` |
| CordiAct 启动对账入口 | `CordiAct/app/src/main/java/com/cordiact/app/CordiActApplication.kt:424` |
| CordiAct 通知只在回合开头读取 | `CordiAct/model-providers/.../ConversationTurnService.kt:66, 258` |
| CordiAct `noticeBlock` 纯散文 | `.../ConversationTurnService.kt:373–384` |
| CordiAct Termux `background` 仍阻塞 | `CordiAct/app/src/main/java/com/cordiact/app/termux/TermuxBridge.kt:182–184` |
| CordiAct SSH `nohup` 丢弃输出、只返回 PID | `CordiAct/core/src/main/kotlin/com/cordiact/core/execution/JSchSshService.kt:129–131` |
| CordiAct 无前台服务 / 无通知权限 | `CordiAct/app/src/main/AndroidManifest.xml` |
| CordiAct SDK 版本 | `CordiAct/app/build.gradle.kts:6,20,21` |

## 附录 B：默认参数表

| 参数 | 默认值 | 出处 / 理由 |
| :--- | :--- | :--- |
| `maxRunningJobsPerSession` | 4 | 复用 `BackgroundLimits.maxRunningChildren` |
| `maxRunningJobsGlobal` | 8 | 手机内存与网络上限 |
| `maxJobsPerSessionLifetime` | 64 | 防刷爆记录表 |
| `jobOutputLimitBytes` | 64 000 | 单次进模型的输出上限；总输出在 spill 里 |
| `heartbeatIntervalMillis` | 1 000 | lease 粒度 |
| `reaperSweepMillis` | 5 000 | 5 倍心跳，够稳也够快 |
| `maxHeldWakeMillis` | 30 分钟 | 防口袋发热；超过即 ABORTED |
| `maxInboxDrainPerRound` | 8 | 一轮最多注入 8 条，防炸上下文 |
| `maxAutoWakesPerHour` | 4 | 唤醒是花钱的动作 |
| `maxWokenRoundMillis` | 120 000 | 被唤醒的回合不应变成新的长任务 |
| `maxResidentSessions` | 8 | 驻留 LRU 上限 |
| `runTimeoutMillis` | 600 000 | 已有值，沿用 |
