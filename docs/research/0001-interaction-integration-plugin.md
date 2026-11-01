# 交互集成插件：前期调研

**状态：** 前期调研建议；实现顺序于 2026-11-01 修订，见“实现顺序更新”
**日期：** 2026-11-01
**范围：** 评估 DeepSeek Harness 的交互扩展方式，并为 CordiAct 的系统插件边界提出建议。本文初稿不启动 UI 或 provider 实现；后续独立模型后端任务已先行。

## 摘要

建议把“交互集成插件”定义为**客户端能力的受管集成入口**：为宿主提供统一的交互能力目录、范围检查和分发契约，使客户端可组合普通模型输入、插件命令和语义提及。它不是一个同时实现聊天 UI、模型供应商、斜杠命令、文件解析和凭据存储的巨型插件。

DeepSeek Harness 的可借鉴点不是某一种输入框，而是**能力定义与交互适配器分离**：命令由功能插件注册，客户端 UI 负责发现和呈现；语义文本规则和引用由各自的功能贡献，composer 保留原始文本与结构化引用；模型选择由独立功能包维护并连接共享模型目录。

CordiAct 应先闭合模型后端与会话回写链路，再实现无 UI 的后端交互分发契约，最后连接 Android composer。首期交互能力优先支持普通模型请求、明确注册的 `/command` 和受 Workspace/Session 限定的 `@file`、`@session` 引用。模型 provider、API 凭据和 Android UI 是相邻能力，不能都塞进交互插件。

## 实现顺序更新（2026-11-01）

用户已将具体模型后端优先级前移。当前实现顺序调整为：

1. 先闭合 `core.model-adapter` 到会话历史持久化的端到端基础模型链路，并提供 DeepSeek（默认 `deepseek-flash`）、Anthropic Messages、Gemini streaming 与 OpenAI Responses 协议适配及可配置 provider 管理。
2. 再实现 Interaction Integration 后端分发契约：能力目录、Workspace/Session 范围校验、命令与语义引用处理；本阶段仍不做 UI。
3. 后续接入 Android composer/model selection UI，并验证取消、错误与已持久化历史的一致性。

模型 provider plugin 归属模型后端，不并入交互插件。它经 `ModelAdapterService` 注册，以独立安全存储解析 provider key；provider 元数据不是 SQLite 数据，也不包含 API key。此顺序明确取代前期调研中“先交互分发再接模型后端”的建议。

## 调研基线

- DeepSeek Harness 上游仓库快照：`deepseek-ai/deepseek-harness`，commit `ce7073d51254c26cd63b22a60835b8f948deb447`，提交日期 `2026-10-30`。下文依据该快照中的源代码及包文档，未把社区讨论当作规范。
- CordiAct 检查时处于 `feat/tool-file-system-plugins`，HEAD `62b3630`，工作树干净。
- CordiAct 已有 `ModelAdapterService`、`SessionManagerService`、Workspace、Tool Manager 和 File Manager 系统能力；`ModelBackend.generate` 支持事件回调及取消。插件宿主通过受保护的内置插件目录装配后端服务。
- 当前仓库仍处在后端/架构阶段：没有已实现的统一 composer 客户端闭环，也没有已定义的命令或提及注册协议。

## Harness 的机制

| 能力 | Harness 的实现边界 | 对 CordiAct 的启示 |
|---|---|---|
| `/command` | `packages/interaction/commands` 提供插件拥有的命令注册与执行服务；`packages/client/ui-commands` 提供客户端发现/呈现适配。命令直接执行于目标 agent，不必伪装成模型消息。 | 命令定义和执行留在贡献功能的插件；客户端通过目录显示、提交到统一分发器。命令未知时不能把看起来像命令的输入悄悄发送给模型。 |
| `@` 语义引用 | `ui-conversation` 定义用户文本规则/节点与 Facts；具体引用类型由功能扩展。session 规则既能识别结构化的 `@[label](dsh-session:...)`，也能读取可用标签匹配原始 `@label`。 | 引用应成为带类型与稳定 ID 的结构化 draft 内容，显示文本只是标签；提交时按当前 Workspace/Session 再做权限校验和解析。避免只靠正则把路径字符串拼入 prompt。 |
| 模型选择 | `ui-model-selection` 是独立客户端包，消费模型目录、持久化选择并贡献 `/model` 与 composer 模型位；具体模型供应商另有 LLM/凭据包。 | “选模型”“输入 prompt”“配置 provider/API key”是不同用户动作和后端责任。交互入口可汇总，但不接管 provider 协议或明文凭据。 |
| composer | `ui-conversation` 管理提交态、语义文本、引用/附件与会话上下文；不同交互能力通过客户端契约组合，而不是把所有规则写在一个输入组件里。 | 先稳定可持久化的 `InteractionDraft` 和分发语义，再做 Compose 控件；保留普通文本、引用和附件的区分及顺序。 |

Harness 包含终端及 Web 客户端，其具体 UI、Cordis 插件类型和 TypeScript 扩展 API 不适合直接搬入 Android。应借鉴职责拆分和可组合协议，而非移植实现。

## CordiAct 建议边界

### 插件职责

新增受保护的系统插件 `core.interaction-integration`，作为客户端能力集成入口，提供纯 Kotlin/Android UI 无关的服务契约：

- `InteractionCatalog`：只读枚举当前可用的命令、提及解析器和模型入口元数据；声明稳定 ID、版本和 owner plugin ID。
- `InteractionDispatcher`：接收携带 `workspaceId`、`sessionId` 和结构化 draft 的请求，判定普通消息/命令等意图并分发；目标 ID 必须经 Workspace/Session 权威服务验证。
- `InteractionDraft` / `InteractionSegment`：有序保存纯文本与语义引用（以及未来的附件引用），而非仅保存已拼平字符串。
- 统一的可观察结果：接受/拒绝、流式模型事件、命令结果、错误码和取消句柄；不把 UI 类型、Compose 类或 provider 私有数据放进 `core` 契约。

这个系统插件是聚合/分发层，不拥有全部业务能力：

- `ModelAdapterService`/provider 插件拥有模型目录与生成；模型 API key 由独立凭据能力管理，不写入 session/message、普通偏好或命令日志。
- 业务插件拥有自己的 `/command` 定义和处理器；Tool Manager 仍负责工具注册/启停/调用策略，不能把工具调用绕过 Tool Manager 伪装成人类命令。
- File Manager、Session Manager 等能力贡献有类型的 `@` 解析/建议项；`@file` 只接受当前 Workspace 中的路径/文件 ID，`@session` 只接受允许访问的 session ID。
- Android 客户端负责输入法、候选列表、引用 chip、模型选择和可访问性；UI 不直接持有 provider 凭据，也不绕过 dispatcher 调后端服务。

建议依赖方向：

```text
Android conversation client
  -> InteractionDispatcher / InteractionCatalog
       -> Workspace Manager + Session Manager
       -> Model Adapter (普通模型请求)
       -> Tool Manager (仅经策略授权的工具路径)
       -> File Manager (受 Workspace 限定的引用解析)
       <- plugin-owned command / mention contributions
```

实现时应避免插件循环依赖：能力插件注册定义/处理器，宿主组合目录和 dispatcher；不能让命令插件依赖交互插件实现细节，也不能让 `core` 依赖 Android UI。按 CordiAct 既有保护规则，加入内置可信目录和明确依赖声明；依赖禁用/撤销时，目录项应立即失效，进行中的请求应取消或以可识别错误终止。

### 请求语义与边界

1. **普通输入**：空白 draft 不提交。提交时固定捕获 Workspace、Session、model 和有序段快照；异步执行期间不从 UI 的“当前会话”重新取值。
2. **命令**：只在约定的起始语法位置解析 `/name`。命令必须在当前范围已注册且可用；未知、歧义、无权限或参数无效时明确返回错误，不回退为普通模型 prompt。原始命令文本不得意外泄漏到模型请求。
3. **提及**：编辑期显示可发现的候选；提交期根据稳定 ID 与当前范围重新解析。过期、跨 Workspace 或越权引用要在模型调用前拒绝。普通 `@` 文字仍可作为文本，引用编码/转义规则须明确，避免把邮箱或代码误解析。
4. **模型与凭据**：模型 API 输入（用户 prompt）走普通提交路径；provider/API key 的录入与存放另行设计。密钥不可进入 SQLite 对话历史、日志、异常文本或可被模型读取的参数。供应商凭据与模型选择应明确是用户级还是 Workspace 级，不能隐式继承。
5. **并发与取消**：请求固定 session/workspace 上下文；插件被禁用、session 被切换/归档或用户取消时，停止模型/工具工作并保留可审计终态。命令执行和模型生成走不同类型结果，不用“假 assistant 消息”表达设置动作。

## 推荐落地顺序

实现顺序已由用户确认调整：先完成模型后端，再做本交互集成插件。

1. **模型后端插件与 LLM 闭环（当前优先项）**：基于既有 `ModelAdapterService` 实现具体 DeepSeek backend；用 `SessionManagerService` 提交用户消息、读取历史、流式接收模型事件并持久化完整回复。先验证 HTTP/SSE、错误、取消和密钥边界，不建 UI。实现进度见 `model-deepseek/`。
2. **交互集成后端契约**：定义 draft、能力目录、意图判定、请求/结果与错误语义；使普通模型输入走已经可用的模型适配器。
3. **命令与引用目录**：实现插件自有命令注册、范围/权限校验和直接执行；添加 `@file`、`@session` 的类型化解析、候选查询与提交时复核。
4. **插件生命周期与安全测试**：验证未知命令不触发模型；跨 Workspace/过期引用在模型调用前失败；同名注册冲突拒绝；禁用 provider 或命令 owner 后能力消失并取消运行；draft 顺序/语义引用持久化一致；密钥不落日志或历史。
5. **客户端集成**：以上端到端测试通过后，再在统一 composer 接入模型入口、`/` 命令建议、`@` 候选和类型化 chip；补齐无 session、加载、无模型、网络失败、运行中/取消、键盘与 TalkBack 状态。

首期范围建议限定为普通模型输入、插件命令目录和 `@file`/`@session`。不要首期加入 agent/@assistant 概念、任意插件代码动态注入 UI、跨 Workspace 文件引用或复杂宏/命令链；目前 CordiAct 的产品定义没有 assistant 实体，这些都需要单独产品决策。

## 待决策项

- “模型 API 输入”是指用户输入自然语言 prompt，还是也包含 provider/API key 设置界面？本调研将两者拆开，建议 API key 属于后续独立凭据/Provider 设置能力。
- 命令参数采用自由文本、类型化参数，还是命令自定义表单？建议第一版自由文本 + plugin-owned parser，后续按需求增加 schema/form。
- draft 与结构化提及是否纳入现有 session SQLite schema，历史消息采用不可变引用快照还是以 ID 重解析？应在实现 ADR 明确兼容与删除语义。
- provider 凭据的归属（用户级/Workspace 级）、Android Keystore 封装与备份策略，需要独立安全设计；本调研未验证具体实现方案。
- 模型适配器现有回调事件能否承载 UI 生命周期与持久化需求，需在普通输入端到端实现时验证；本轮不引入新的网络库或更改传输抽象。

## 来源

### CordiAct

- `core/src/main/kotlin/com/cordiact/core/conversation/ConversationContracts.kt`：ModelBackend、ModelAdapterService、session/message 与事件/取消合同。
- `app/src/main/java/com/cordiact/app/conversation/BundledConversationPluginCatalog.kt`：受保护的内置插件目录及现有服务组合。
- `docs/adr/0003-workspace-first-design.md`：Workspace 为首要实体、conversation 范围决策。
- `docs/adr/0004-tool-and-file-manager.md`：Tool/File Manager 边界、Workspace 校验、后续模型运行时消费路径。
- `docs/plugin-lifecycle.md`：插件状态和生命周期管理。

### DeepSeek Harness 上游（快照 `ce7073d51254c26cd63b22a60835b8f948deb447`）

- [Human Commands subsystem](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/docs/subsystems/commands.md) 与 `packages/interaction/commands/README.md`：插件命令目录和直接执行边界。
- `packages/client/ui-commands/README.md`：客户端命令呈现与注册适配。
- `packages/client/ui-conversation/README.md`、`src/client/contract/user-text.ts`、`src/client/conversation/user-text-registry.ts`、`src/client/user-text/session-reference-rule.ts`：composer、结构化文本规则及 session 引用。
- `packages/client/ui-model-selection/README.md`、`src/client/index.ts`：独立模型选择功能和 `/model`/composer 贡献。
