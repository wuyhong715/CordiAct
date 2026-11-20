# 模型后端兼容性与请求合规：核实与修复

- 原报告基线 `ea8b98b`；本地变更基于 `c81c02e`（`feat/refine-conversation-surface`）。交付分支基于远端 `main` 重新整理。
- 方法：追踪生产调用链、对照公开协议文档，在 JVM 上使用本地 HTTP/SSE 夹具测试；没有向真实厂商发送请求，也未验证 Android 界面或构建 APK。下文的协议规范与实际厂商的错误行为不是同一层证据。

| 编号 | 核实结果与处置 |
|---|---|
| M1 | 已确认旧代码原样发送内部工具 ID，与 OpenAI 和 Anthropic 的名称约束冲突。声明、历史及 Gemini 回执使用同一确定性别名；响应只反查本轮声明，未知、缺名或歧义不分派。合法短名称尽量保持不变，未改变内部 ID 或审批逻辑。未声称真实端点实测每轮 400。 |
| M2 | 旧代码确实把宿主 JSON Schema 放入 Gemini 的 `parameters`。按现行 Gemini API 改用文档支持的 `parametersJsonSchema`，保留 `additionalProperties`，不剥掉约束；不保证旧版兼容网关接受该字段，也不保证所有 MCP schema 在厂商支持的子集内。 |
| M3 | 已确认生产连接路径没有给两张能力表填值。新增/刷新官方 OpenAI 端点时，为精确的 `gpt-5-mini` 标识及其快照声明推理档位，保留既有显式项；其他模型/网关不推断。附件仍仅本地；逐模型显式配置和提示仍未落地。 |
| M4 | 已确认非 2xx 丢弃响应体。现在有界读取结构化错误，保留 HTTP 状态、白名单厂商错误或标准化溢出码、已知字段路径、可重试标志和 `Retry-After`。原始错误消息只在本地做分类，不回显可能含凭据、正文或工具数据的厂商原文；不新增自动重试。 |
| M5 | 已确认并行工具结果原来被拆成多个 `user` turn。Gemini 现在合并相邻同角色内容，保留 `parts` 顺序；有 ID 时匹配 `functionCall` / `functionResponse`，并在同一工具循环回传调用 part 上的思考签名。用同一工具两次调用的独立 ID 验证。 |
| M6 | 确认摘要、子任务报告原来可被作为 system 内容发送；换行拼接本身不能证明权重变化。摘要及报告降为结构化会话数据，新增报告追加到已有历史之后，不把动态内容塞进稳定指令前缀。未增加 Anthropic 显式缓存标记。 |
| M7 | 官方 OpenAI Chat 流需显式请求 usage；只对 `api.openai.com` 的 Chat 启用 `stream_options.include_usage`，不向兼容网关普发字段，也不自动重发。主报告 #10 的其他协议 usage 解析不由此条覆盖。 |
| M8 | Responses 发送 `store:false`，表示不要求保存响应对象作为应用状态，不代表零数据保留；未加入设置开关。 |
| M9 | Responses 接受 input 中的 `system`；没有证据证明顶层 `instructions` 天然具有更高权重。现在把连续的前置宿主 system 提升至协议专用字段，不将运行期回执移到顶层。 |
| M10 | 兼容网关返回普通 JSON 是条件性场景，并非官方流式合规缺陷。修复现有 Gemini 非 SSE 分支的工具调用丢失；未给其他协议无条件开启 JSON 降级。 |
| M11 | Gemini 空成功改为显式失败，非 SSE 工具调用复用解析及别名校验；Anthropic、Gemini 与 Chat 的结束原因为完成事件提供元数据，工具路径保留结束原因。未统一所有 Responses `incomplete`/过滤状态，也未为 UI 增加截断提示。 |
| M12 | 已确认工具循环丢失原始失败；现在透传原始 `Failed` 事件，而不是用通用 `tool_loop_stopped` 覆盖。工具路径不自动重跑长回合，避免重放已执行的工具；无工具路径保留已有的溢出恢复。 |
| M13 | 标准 `/v1/models` 推导为 `/v1/chat/completions` 不属于缺陷。确认的误剪发生在完整生成端点的 base 本身以 `/models` 结尾时；现只剥一层已知终端后缀。任意自定义发现/生成双 URL 不在此次范围。 |
| M14 | 原报告已撤销；图片工具走独立配置路径，未修改。 |
| M15 | 常规会话在没有显式或模型默认输出上限时，把已按上下文窗口计算的预算用于 Anthropic 必填的 `max_tokens`；没有会话预算的独立调用仍有兜底。与主报告 #1 交叉引用。 |
| M16 | 给摘要与审查输入标记数据边界，摘要转义后送入模型，不再以宿主指令身份回灌。数据标记不是安全权限边界，没有更改审批策略。 |

## 验证

在 `/workspace/.verify-integration` 执行 `sh gradlew --offline :model-providers:test :core:test --tests 'com.cordiact.core.context.*' --tests 'com.cordiact.core.toolloop.*' --console=plain`。`model-providers` 122 项与 `core` 定向 44 项均通过；`git diff --check` 通过。新增覆盖包括 Gemini JSON 工具回写及双 ID、HTTP 错误体信息最小化、官方 Chat usage、Responses `store`/`instructions`、能力选择与端点归一化。

## 规范参考

- [Google Gemini GenerateContent API](https://ai.google.dev/api/generate-content)、[Gemini function calling](https://ai.google.dev/gemini-api/docs/function-calling)、[thought signatures](https://ai.google.dev/gemini-api/docs/thought-signatures)
- [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat/create)、[Responses](https://platform.openai.com/docs/api-reference/responses/create)、[data controls](https://platform.openai.com/docs/guides/your-data)、[GPT-5 mini](https://platform.openai.com/docs/models/gpt-5-mini)

## 变更文件

- `/workspace/CordiAct/core/src/main/kotlin/com/cordiact/core/context/DefaultContextProjector.kt`
- `/workspace/CordiAct/core/src/main/kotlin/com/cordiact/core/context/OverflowClassifier.kt`
- `/workspace/CordiAct/core/src/main/kotlin/com/cordiact/core/conversation/ConversationContracts.kt`
- `/workspace/CordiAct/core/src/main/kotlin/com/cordiact/core/toolloop/ToolLoopContracts.kt`
- `/workspace/CordiAct/core/src/test/kotlin/com/cordiact/core/context/ContextP1Test.kt`
- `/workspace/CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ConfigurableModelBackend.kt`
- `/workspace/CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ConversationTurnService.kt`
- `/workspace/CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ModelDiscovery.kt`
- `/workspace/CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ModelProviderRegistryPlugin.kt`
- `/workspace/CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ModelReviewerClient.kt`
- `/workspace/CordiAct/model-providers/src/main/kotlin/com/cordiact/model/providers/ModelSummarizer.kt`
- `/workspace/CordiAct/model-providers/src/test/kotlin/com/cordiact/model/providers/ConfigurableModelBackendTest.kt`
- `/workspace/CordiAct/model-providers/src/test/kotlin/com/cordiact/model/providers/ModelDiscoveryTest.kt`
- `/workspace/CordiAct/model-providers/src/test/kotlin/com/cordiact/model/providers/TurnNoticeTest.kt`
