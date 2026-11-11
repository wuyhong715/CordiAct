# CordiAct 附件上传能力：调研与设计

- 日期：2026-11-11；状态：**设计提案，尚未实施，等待范围确认**。
- 用户已确认：新增附件上传能力；先做调研与设计。本轮按要求前台完成，不继续消耗后台任务预算。
- 本文中 P0 范围、数值限制、交互和权限规则都是建议，不代表用户已逐项确认。
- 本轮没有上传用户附件，没有对配置中的真实模型进行收费探测，没有修改功能代码、构建 APK 或提交/推送。

## 1. 结论

**附件必须成为消息的一等数据，不是输入框按钮，也不是文本里的一段本地路径。** 推荐“工作区拥有不可变文件对象 → 消息持有有序引用 → 发送前编制传输计划 → 各模型协议编码”的架构。

分清三个不同动作：

1. **导入**：Android 从系统选择器读取字节，保存为当前工作区的持久对象；此时没有发送给模型。
2. **随消息发送**：把该对象编码成图片/PDF/文本内容块，经已选提供商端点发给模型；这是第三方数据披露。
3. **远端文件上传**：调用提供商 Files API，取得只在该提供商账户/项目下有效的远端引用；并非每次发送都需要。

推荐首期闭环：**静态图片 + UTF-8 文本/代码 + 显式支持的原生 PDF**。先用内联传输，不依赖 Files API。不能原生理解 PDF 的模型应明确拒绝，或让用户主动选择后续提供的“仅提取文字”模式，绝不静默改成文本、文件名或省略附件。

首期不包含音视频、压缩包解析、完整 Office 解析、OCR 平台、向量库/RAG、跨设备传输、公开文件托管。通用二进制“只导入不发送”可保留在设计中，不把它伪装成模型已读。

## 2. 研究基线与证据边界

### 2.1 代码现场

本轮开始时本地 HEAD 为 `661f398`，过程中观察到其他操作更新了仓库。最终复核基线为：

```text
HEAD / origin/main = 82f362c7fea21c4f23ad3069510c72fa97904d6f
82f362c feat(model): 按模型映射推理档位与响应速度（后端） (#59)
346cec8 feat(security): 本地凭证库与运行时安全审计（后端）(#58)
```

本轮没有执行切分支、合并、拉取或重置；仓库变化不能归因于本设计。最后一次代码复核使用上述基线。既有未跟踪目录 `app/src/main/java/com/cordiact/app/ui/`、`app/src/main/res/drawable/` 不作本轮编辑对象，也不将它们当作稳定 UI API。

当前会话数据库为 `cordiact-conversations.db`，`DATABASE_VERSION = 9`；迁移提案暂称 9→10，实施前须按实际最新版本重新编号。

### 2.2 已定位的真实接入点

下列位置均在本轮实际读取/定位；行号仅作该基线的定位提示，实施时以符号为准。

| 环节 | 当前文件/符号 | 与附件有关的缺口 |
|---|---|---|
| 模型描述 | `core/.../conversation/ConversationContracts.kt:16`，`ModelDescriptor` | 当前描述不构成逐模型、逐媒体的能力保证 |
| 持久消息 | 同文件约 66 行，`ConversationMessage` | `content: String`，另有 `metadataJson`；无有序媒体引用契约 |
| 模型消息 | 同文件约 236 行，`ModelMessage` | 内容仍为字符串，不可能仅靠按钮得到多模态请求 |
| 输入处理 | `interaction/.../InteractionContracts.kt`，`InteractionService.prepare(text, context)`、`OrdinaryModelSubmitter.submit` | 主入口是文本；附件需要独立参数，不能拼进命令文本 |
| 发送/落库 | `model-providers/.../ConversationTurnService.kt`，`submit`、`submitWithTools` | 多处 `appendMessage(..., text)`，须统一原子提交语义，不能只改一个入口 |
| 上下文投影 | `core/.../context/DefaultContextProjector.kt` | 现有投影围绕文本；应保留有序附件引用并按轮次解析 |
| 上下文预算 | `core/.../context/ContextContracts.kt:115`，`TokenEstimator`；`WeightedTokenEstimator` | 现有文本估算不可替代图片/PDF成本和请求字节预算 |
| 四协议适配 | `model-providers/.../ConfigurableModelBackend.kt:76`，`ModelProtocol` | Chat Completions、Responses、Anthropic、Gemini 均存在，但编码仍偏文本 |
| 编码入口 | 同文件 `toOpenAiMessage`、`toAnthropicMessage` 等 | 不能把通用 content array 直接原样发给四种协议 |
| 会话存储 | `app/.../conversation/AndroidConversationSessionStore.kt` | SQLite 消息文本+metadata；应新增结构化内容与附件表，而非塞 base64 |
| 工作区文件 | `core/.../file/FileManagerContracts.kt`；`app/.../file/AndroidWorkspaceFileBackend.kt` | 应复用作用域/后端入口，但附件不可变对象不应退化为任意可改路径 |
| 安全 | `core/.../security/{SecurityModels,SecurityPolicy,SensitiveOperationGate,SecurityAudit}.kt` | 现有权限与审计不是新增网络发送路径自动受控的证明，须专门接线 |

完整路径前缀：`core/src/main/kotlin/com/cordiact/core/`、`interaction/src/main/kotlin/com/cordiact/interaction/`、`model-providers/src/main/kotlin/com/cordiact/model/providers/`、`app/src/main/java/com/cordiact/app/`。

现有 `model-providers/src/test`、`interaction/src/test`、`core/src/test/.../context` 是回归入口；本文不声称新增测试已经存在或通过。

## 3. 已有方案与依赖取舍

先核对已有能力，再新增对象：

- AndroidX Activity 当前已有 `activity-compose:1.10.0`，可使用 Photo Picker / OpenDocument 契约；不要自建全盘文件浏览器或为选附件申请全部存储权限。
- App 已依赖 `work-runtime-ktx:2.10.0`。可用于受约束导入恢复/远端清理，不因此把所有小文件导入都做成长任务；WorkManager 也不保证长时间实时传输永不中断。
- 模型模块已有 Gson。现有 JSON 构造与字符串/字节转换有多份内存复制风险；不能认为“文件限额小于服务端限制”就不会 OOM。
- Android `ContentResolver`、采样图片解码、`PdfRenderer`、`AtomicFile`/同卷原子改名等优先于新增大依赖；实现时核对各 API 的支持版本与失败方式。
- 如需 EXIF 方向处理，评估 AndroidX ExifInterface；本次不安装。文本解码用标准 Charset，哈希用 MessageDigest，禁止自己写格式解析器。
- Office 文档、OCR、完整 PDF 文本抽取放 P1/P2；若引入第三方库须先检查维护、许可证、原生体积、CVE 与恶意文件行为。Android `PdfRenderer` 不是跨版本通用 PDF 文本提取库。

本地已有 DeepSeek Harness 附件设计：`/workspace/deepseek-harness/docs/subsystems/attachment.zh.md`（本轮读取）。可借鉴“先持久化，再发布不可变引用”“事件不放 base64/临时 URL”的原则，但不照搬其浏览器上传凭证体系，也不认为其仓库代码已在 CordiAct 可用。Codex/dsh-go 的附件相关文件本轮仅检索到路径，未深入核实，不作为方案正确性的证据。

## 4. Android 选入与导入

### 4.1 入口

- 图片：`PickVisualMedia` / `PickMultipleVisualMedia`，限定图片；系统/AndroidX 支持的回退路径由库处理。[A1]
- 文档：`OpenMultipleDocuments` 或显式 `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE`；按允许的 MIME 集过滤。[A2]
- 本轮不需要 `MANAGE_EXTERNAL_STORAGE`，也不为选择少量图片申请全库照片权限。
- `content://` URI 是访问凭证，不是路径。禁止从 URI 字符串猜 `/sdcard/...`，禁止依赖已废弃的 `_data`。

### 4.2 导入算法

1. 固定导入目的工作区与草稿 ID，建立 `IMPORTING` 条目；导入过程中切换工作区不得改变目的地。
2. 从 ContentResolver 查询显示名/MIME/大小；全部视为不可信提示。大小可以未知、名称可以伪造、远端云文档读取可以失败。
3. 开启只读流，以实际读取字节数强制限额，支持取消/超时；写入同一对象库的随机 `.part` 文件，同时计算 SHA-256。
4. 验证实际媒体类型；图片先读边界，防超大像素解码；文本严格解码；PDF做受控验证。扩展名、MIME、魔数冲突时拒绝或要求明确选择，不能“自动按名称信任”。
5. 写完并关闭后，再原子改名为不可变对象；登记附件元数据和草稿引用。失败清理暂存，不能留下一个状态为 READY 的半文件。
6. 图片如需去 EXIF/GPS、纠正方向、缩放或格式转换，生成独立衍生对象并记录变换；原始对象和实际发送对象分别有摘要，不悄悄覆盖原图。
7. UI显示“已导入本地”，不是“上传成功”；网络发送是另一阶段。

### 4.3 URI 授权与虚拟文件

- 优先在选择后立即复制，以持久副本保证重启和历史重放。原始 URI 不写进模型消息、日志或审计。
- 确实需要跨进程恢复复制时，仅在返回授权允许时获取可持久只读授权，捕获拒绝情况；复制完释放不再需要的授权。SAF 持久授权仍可能因源文件移动、删除等失效。[A1][A2]
- Photo Picker 临时授权生命周期有限，长任务可需持久授权；不能把“用户选过一次”当永久可读。[A1]
- `CATEGORY_OPENABLE` 是首期简化策略，虚拟文档不作默认支持。后续若支持，使用 `getStreamTypes` / `openTypedAssetFileDescriptor` 导出到可读 MIME，记录其为转换产物，而非假定 `openInputStream` 对所有文档成立。[A2]
- 超过选择数量仍须应用层复核；不能依赖某个系统选择器一定执行数量上限。

## 5. 模型协议核对结果

### 5.1 能力不是协议名称

三层判定：**端点/代理实际接受的协议字段 ∩ 具体模型的媒体能力 ∩ 产品/安全/预算限制**。模型名称包含 GPT、Claude、Gemini 或接口自称 OpenAI-compatible，均不构成支持图片/PDF/Files 的充分条件。

本次查阅的是公共官方文档，不是已验证当前配置端点可用。反向代理可能只转发生成端点，未实现 Files API；不得从配置的生成 URL 猜测并跨域上传。

### 5.2 协议矩阵

| 协议 | 图片输入 | 文件/PDF输入 | 文本文件策略 | 音视频 | 首期传输 |
|---|---|---|---|---|---|
| OpenAI Chat Completions | `image_url` 内容块，可用 data URL；需视觉模型 | 当前官方文件指南：`file` 块的 `file_data`/`file_id` 支持 PDF；不是任意文件，外部 `file_url` 不支持 | 本地受控解码，作为 text 块 | 另有特定音频模型/接口差异，不纳入通用能力 | 图片/PDF内联；明确配置方可启用 |
| OpenAI Responses | `input_image`，data URL/受支持引用 | `input_file`；支持文件种类比 Chat 更广，具体按模型和官方列表 | 首期仍本地受控解码以便预算可见；后续可显式使用原生文件处理 | 不把媒体生成能力误当所有媒体均可作为输入 | 图片/PDF内联 |
| Anthropic Messages | `image`，`source.type=base64` | PDF `document`；Files API 可引用 file ID，但不是任意 MIME 都能直接理解 | text 或受支持的 document 文本语义；首期统一文本投影 | 通用音视频不作为本方案已确认能力 | 图片/PDF base64，不依赖 Files |
| Gemini GenerateContent | `parts[].inlineData`，需对应模型 | PDF等以 inline data / 受支持 file data 传递 | 首期文本投影 | 具备音频/视频输入的模型存在，但预算、时长、准备状态另行设计 | 图片/PDF inline；Files 后置 |

OpenAI 官方当前资料明确：Chat Completions 的文件输入仍限 PDF，Responses 可处理更广文件；因此“OpenAI 完全不支持文件”和“两个接口完全相同”都不准确。[O1]

Responses 的 PDF 会引入文字和页面图像；非 PDF 文档的嵌入图表不等于都会进入视觉上下文。表格还有专用处理流程；不能承诺 Office 原样视觉理解。[O1]

Anthropic Files 是独立上传/引用协议。本轮直接读取的最新官方指南明确为 **stable，已退出 beta，当前不需要 beta header**；旧 `files-api-2025-04-14` header 仍可工作但保留旧响应形状，不能照搬旧文章的 beta 结论。模型/平台仍有适用边界；能上传文件不等于任意文件可以作为 Messages 的 document 输入。[C1][C2][C3]

Gemini 官方 Files 文档说明上传对象临时保存，当前为48小时；这不是长久会话存储。不能只保留远端 URI。[G1]

### 5.3 最小 wire 形状（示意，不是可直接运行的完整请求）

```json
// OpenAI Chat Completions: messages[].content
[
  {"type":"text","text":"请分析附件"},
  {"type":"image_url","image_url":{"url":"data:image/png;base64,<bytes>"}},
  {"type":"file","file":{"filename":"report.pdf","file_data":"data:application/pdf;base64,<bytes>"}}
]

// OpenAI Responses: input[].content
[
  {"type":"input_text","text":"请分析附件"},
  {"type":"input_image","image_url":"data:image/png;base64,<bytes>"},
  {"type":"input_file","filename":"report.pdf","file_data":"data:application/pdf;base64,<bytes>"}
]

// Anthropic Messages: messages[].content
[
  {"type":"text","text":"请分析附件"},
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"<bytes>"}},
  {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"<bytes>"}}
]

// Gemini REST: contents[].parts
[
  {"text":"请分析附件"},
  {"inlineData":{"mimeType":"image/png","data":"<bytes>"}},
  {"inlineData":{"mimeType":"application/pdf","data":"<bytes>"}}
]
```

字段必须由各协议适配器生成；SDK 属性名和 REST JSON 字段不能机械混用。工具消息、系统消息、reasoning、response speed 的现有编码仍须保留，媒体仅加入被允许的 role。

### 5.4 不硬编码上游限额

官方文档存在文件字节、请求体、图片数量、PDF页数、模型上下文、账户配额等多重限制。它们不是一个数，且会随模型改变；例如单文件上限不等于内联请求上限。P0 使用更保守的本地策略，再取模型/端点限制的最小值。本文不把任一厂商的最大限制直接作为 CordiAct 默认值。

## 6. 核心契约提案

以下是候选 Kotlin 形状，不是已存在 API：

```kotlin
@JvmInline value class AttachmentId(val value: String)

sealed interface MessagePart {
    data class Text(val text: String) : MessagePart
    data class Attachment(
        val id: AttachmentId,
        val mode: Interpretation, // VISION / NATIVE_DOCUMENT / TEXT_EXTRACT / LOCAL_ONLY
        val renditionId: String? = null,
    ) : MessagePart
}

data class AttachmentDescriptor(
    val id: AttachmentId,
    val workspaceId: String,
    val mimeType: String,
    val displayName: String,
    val byteSize: Long,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val pageCount: Int? = null,
)

data class AttachmentInputCapability(
    val interpretations: Set<Interpretation>,
    val mimeTypes: Set<String>,
    val maxFileBytes: Long,
    val maxRequestBytes: Long,
    val maxCount: Int,
    val filesApi: FilesApiCapability?,
)
```

关键约束：

- `AttachmentId` 为不透明随机 ID，**不是路径、哈希、URI或可访问全库的 bearer token**。
- 文件字节归 `AttachmentRepository`；持久消息只保存有序 `MessagePart` 和必要快照。文本、图片顺序要稳定。
- 引用解析必须带 `(workspaceId, sessionId, attachmentId, operation)` 权限上下文；禁止通过枚举 ID 跨工作区读取。
- 使用 SHA-256做完整性与工作区内去重；不跨工作区默认去重，不用“哈希相同”证明访问权。
- 发给模型前生成 `ResolvedModelInput` / `TransmissionPlan`：包括最终 role/parts、受限只读 blob handle、选定衍生对象、预算和目标指纹。不能把 Android Context、可变 ContentResolver URI 直接带入纯 JVM core。
- 文字文件的投影由 `TEXT_EXTRACT` 明确表达；保存提取器版本、编码、字节范围和结果摘要，防历史重放悄悄变内容。
- `ConversationMessage.content` 在迁移期保留为纯文本兼容/搜索预览，结构化 `parts` 成为新消息唯一语义来源；新旧字段同时有值时以版本化 parts 为准，禁止双重追加。
- 不把完整附件放进 `metadataJson`；metadata 仍承担工具证据等既有用途，避免附件改造覆盖安全审计/搜索 metadata。
- 能力配置按 `(providerId, modelId, protocol, endpointRevision)` 存储；未声明为不支持。首期可对官方已知组合给可见预设，但不靠名字推断未知代理能力。

## 7. 存储与数据库迁移

### 7.1 物理存储

逻辑归当前工作区，物理置于 App 私有的工作区对象库，不是共享下载目录，也不是易清理的 cache：

```text
<private-workspace-root>/.cordiact/attachments/v1/
  staging/<random>.part
  objects/<opaque-blob-id>
  derived/<opaque-rendition-id>
```

这是新设计路径，不声称已有。若工作区可落 SAF/远端后端，不在其上假定原子改名可用：P0 优先采用本地 App 私有对象库存储并由数据库绑定逻辑工作区；后续以 Repository 能力扩展后端。附件导出到共享存储须是显式用户动作。

不允许模型/工具把内部对象目录当普通可写文件夹。通过文件工具暴露时给当前工作区受限只读视图；需要修改则创建新附件对象。

### 7.2 建议表

```text
attachments(
  id PK, workspace_id, blob_key, mime_type, display_name,
  byte_size, sha256, state, created_at, verified_at
)
attachment_renditions(
  id PK, attachment_id FK, blob_key, mime_type, byte_size, sha256,
  transform_json, extractor_version
)
message_parts(
  message_id FK, ordinal, kind, text_value NULL,
  attachment_id NULL, rendition_id NULL, interpretation NULL,
  schema_version, PRIMARY KEY(message_id, ordinal)
)
draft_attachment_refs(
  draft_id, workspace_id, session_id, attachment_id, ordinal, created_at
)
attachment_transfers(
  id PK, attachment_id, rendition_id, target_fingerprint,
  purpose, remote_id, remote_expires_at NULL, state, attempt_id, last_error
)
```

`attachment_transfers` 可以 P1 再增加，不为尚未使用的 Files API 提前做完整上传平台。另复用/扩展现有 turn 持久化机制保存 `clientTurnId` 唯一约束和发送尝试状态；实施前查清真实 session/run 表，禁止引入第二套不兼容 turn 系统。

迁移原则：

- 旧消息按一段 Text 懒映射/批量回填均可，但必须幂等；保留原 content 与 metadata，不改时间/角色/证据。
- 9→10为候选编号；不能覆盖其他同时开发迁移。
- 数据库与文件系统没有跨系统事务：先持久化对象，再单个 DB事务创建用户消息、parts、turn及引用；崩溃可能留下孤儿对象，但不能留下声称可用的半对象。
- 引用数量以引用表查询为准，不单靠易漂移计数器。GC先标记再清理，避开活跃 stream、draft、turn lease。
- 备份/导入必须带对象和 manifest；只导出文本数据库不等于完整会话备份。恢复时验证摘要并标出缺失，不能默默剥离附件。

## 8. 草稿、发送、重试与恢复

分别维护两个状态机，不能一个 `uploading=true` 包打天下：

```text
本地对象: SELECTED → IMPORTING → VERIFYING → READY
                                 ↘ FAILED / CANCELLED
草稿引用: ATTACHED → COMMITTED_TO_MESSAGE / REMOVED
发送尝试: PREPARING → AUTHORIZING → SENDING → STREAMING → COMPLETED
                                      ↘ FAILED / CANCELLED / UNKNOWN
远端缓存(P1): NOT_UPLOADED → UPLOADING → PROCESSING → READY → EXPIRED/DELETING
```

发送顺序：

1. 收集文本和有序附件；允许“只有附件”的用户消息，但空白且无附件仍拒绝。
2. 在无副作用阶段完成引用/工作区、内容完整性、模型能力、大小/页数/上下文预算检查。发现不支持即在入网前返回明确错误。
3. 固定不可变 `TransmissionPlan`：模型/端点 revision、内容及衍生对象哈希、发送解释模式、安全策略 revision。
4. 根据现有权限规则授权；任何计划变化必须重新检查，避免审批后换文件或换端点。
5. 原子登记 turn+用户消息+parts，以 `clientTurnId` 防双击和恢复时重复插入。
6. 执行网络发送，记录阶段。首字计时从生成请求发出算，另列本地导入/编码/上传/排队耗时，避免把所有时间误报为模型首字慢。
7. 失败保留同一用户消息与附件；用户“重试”创建新的 attempt，不再次追加同样的用户消息。

不能保证网络端 exactly-once。请求已写出后连接断开，服务端可能已生成/计费，状态记 UNKNOWN；除非端点确实支持可验证幂等，不自动悄悄重发。取消 HTTP 不等于远端立刻停止计费或删除文件。

应用退出/进程被杀后：READY对象继续可读；IMPORTING按暂存记录恢复或提示重选；发送中按可证明事实恢复为中断/UNKNOWN，不伪装为仍在持续上传。关闭输入框/删除草稿引用不应取消已提交消息的对象。

## 9. 预算、媒体安全与性能

### 9.1 P0建议默认值（均可配置，待确认）

| 项目 | 建议值/行为 |
|---|---|
| 每条附件数 | ≤10，且受端点更严格限制 |
| 图片原始单件 | ≤10 MiB；先验证边长/像素，再解码 |
| 图片格式 | 静态 JPEG/PNG/WebP；首期不承诺动画 GIF、HEIC/RAW |
| 输入图片防护 | 原始像素≤20MP、任一边≤8192；过限提示受控转换或拒绝，不直接全尺寸解码 |
| 发送图片 | 可见地标准化方向/去EXIF，长边建议≤4096；保留所用衍生对象 |
| PDF | 单件≤10 MiB，建议≤100页，仍取模型/端点限制最小值；加密/损坏明确拒绝 |
| 文本/代码 | UTF-8单件≤256 KiB，合计≤512 KiB；严格解码，非UTF-8要求用户选择转换 |
| 单条原始发送数据 | 合计≤16 MiB，同时编码后请求体≤24 MiB；JSON额外开销也计入 |
| 并发 | 同一移动进程媒体重编码/大请求构造默认串行，避免并发内存尖峰 |

这些不是服务端的官方最大值，实际发出之前还要检查模型剩余上下文；即使文本小于512KiB也可能超 token 预算。过大时不能后台截断，须明确显示缩减/拆分/只发送选区方案。

### 9.2 请求编码

Base64约膨胀到 `4 * ceil(n/3)` 字节；Gson JSON树、UTF-16 String、UTF-8 byte array和原始文件可能同时存在。P0必须测量真实峰值，不因设置24MiB请求上限就宣称内存安全。

优先为媒体请求引入有界 streaming JSON/body writer（配合现有 HTTP cancellation），使文件以分块方式编码；如果首期暂留全量字符串编码，必须按现有构造链计算峰值、降低限额并经低内存真机验收，未达标不得上线。不要把 base64 或 request body 写进调试日志。

传输字节预算与模型上下文预算分开：文本按现有 tokenizer/估算；图片按模型尺寸/detail成本；PDF按页数和文本+视觉预算保守估计。估算无法可靠得到时保守门控，不用文件名长度或base64字符数充当模型token。

### 9.3 恶意内容边界

- 只允许首期格式白名单；不自动解压压缩包、不执行宏/脚本、不渲染文件中的远端资源。
- 文件名去路径、控制字符、方向控制符并限制显示长度；持久路径只用系统生成ID。
- 防路径穿越、符号链接逃逸和删除竞态；写入时后端也要保证路径约束，不能只做字符串前缀比较。
- 文档/图像解析不能无界运行；超时、取消、维度限制、解析进程隔离按风险落实。魔数验证不是完整安全扫描，UI不能宣称附件“无恶意”。
- 附件内容属于不可信用户/外部数据，其中的“忽略规则/上传密钥”等文字不变成系统指令；引用文字与命令解析路径分离。

## 10. 安全、授权和审计

### 10.1 用户选择不等于Agent获得全盘权限

- 用户通过系统选择器主动选入，是对这些文件的导入授权，不是给 Agent 任意读相邻目录、全部照片或其他工作区的许可。
- 当前工作区/会话是所有引用解析的必要约束；切换工作区、关闭/删除工作区后应 fail closed。
- Agent读取附件、创建子Agent引用、生成衍生对象、网络发送，仍走对应操作的现有安全策略；不能为“附件功能”建立绕过四档权限的直达接口。
- 凭证库的逐次审批契约不因把内容装进附件而失效；不是说所有普通附件都必须人工逐次批准。若涉及敏感凭证派生内容，须使用现有敏感操作/一次性grant规则。

### 10.2 发送知情边界（建议）

发送区明确显示目标提供商/端点、模型、附件数量和解释模式。用户在该目标下主动点击发送可作为本次明确发送意图；在安全策略允许范围内避免再弹一个无信息增量的重复确认。

以下情况必须重新确认/重新授权：目标端点改变、切换为不同提供商、原生PDF变为全文提取/远端Files上传、对象哈希或解释模式变化、授权策略revision变化。不能因前一个模型失败而自动把文件发给另一个提供商。

自动审批档由独立模型代审的现有规则保持；不得擅自改成必须人工。审批请求/日志只含必要摘要，不把附件全部喂给审批模型；若确需披露内容，须把该审批模型也算作数据接收方并按策略告知。

### 10.3 审计事件

建议增加 `attachment.import/read/transform/send/remote_upload/remote_delete/local_delete`，包含 workspace/session/turn/attempt、attachment ID、内容摘要、MIME/字节数、接收端点指纹、模式、策略revision、决策结果与耗时。

默认不记录原始URI、完整本地路径、base64、正文、token、签名URL；显示名可能含个人信息，审计中应脱敏或省略。所有网络路径包括模型请求内联媒体都需受控；不是只有 Files API 才叫发送文件。

## 11. 子Agent、上下文压缩与历史重放

- 子Agent默认不继承父会话全部附件，只能显式附带授权引用；不通过复制裸路径绕过作用域。
- 同工作区也检查会话/委派范围；跨工作区共享首期不做。后续显式复制创建新归属、新审计，不能转交可读全库的ID。
- 上下文投影按确定顺序保留媒体及解释模式。压缩摘要中保留来源ID/解释模式/是否仍可读；摘要不是图片或PDF本身。
- 如果压缩后不重传旧媒体，应明确告知模型“以下是历史摘要，原附件未包含”；不能向用户声称模型仍能看见已省略的原图。
- 当前轮用户选入的附件不得被压缩器静默挤掉。超预算先要求选择缩减方式。
- 切换模型时重新校验历史中将要投影的媒体；不支持时阻止发送或让用户确认文本摘要模式，而非直接丢失。
- 原始对象丢失/损坏、衍生器版本变化、远端引用过期，均有明确错误。恢复历史发送需要原始持久对象或已固定的合法衍生对象，不依赖当初设备上的源 URI。

## 12. UI交互草案（只设计，不改当前未跟踪UI）

输入区添加“图片/文件”入口；导入成功呈现附件卡片：缩略图/文件名、类型/大小、`本地导入中/已就绪/失败`、解释模式、移除按钮。

发送前区分：

- `图片·视觉输入`；
- `PDF·原生文档输入`；
- `文本·完整文字/明确选区`；
- `仅工作区保存·不发送模型`。

不支持状态要在点击发送前可见：`当前模型未声明支持PDF，可换模型或仅本地保存`。不能让用户在导入时以为成功，发送后才静默忽略。

消息回显使用不可变引用和相同衍生对象缩略图，展示发送目标和解释模式；失败可重试同一turn或另存草稿，不复制用户消息。无文字但有附件允许发送。默认不自动读取远端网页图片以免泄露IP/追踪信息。

后续若支持 Android 分享到 CordiAct，分享接收页必须先选择工作区/会话，再导入和明确发送，禁止收到分享 intent 就外发文件。

## 13. 远端Files API（P1）

首期不依赖这一层，但边界必须预留：

- 每家设置显式 Files endpoint、认证来源、目的参数和能力；不能用生成URL字符串拼接猜上传接口，也不把凭据转发给另一域名。
- 缓存键至少含 provider/端点/账户或项目指纹、原始对象或衍生对象哈希、用途和schema；远端ID不是跨账号通用资源。
- 上传中断/超时后可能已有远端对象，先记录UNKNOWN并按提供商可查性对账；不声称普通重试天然幂等。
- 处理 `PROCESSING`、过期、删除和无权限；Gemini等临时文件过期可从本地对象重新上传，但重新外发仍须满足当前授权。
- Anthropic 官方说明Files对象按提供商workspace共享，不是CordiAct终端用户或会话隔离。CordiAct自身的检查不能改变提供商的账户级访问范围。[C1]
- Anthropic当前说明对象可保留到主动删除或配置到期；因此不能硬写“所有厂商48小时删除”。[C1]
- 本地删附件不等于删除远端副本。UI应分开显示并支持远端删除队列；远端删除失败需可见、可重试，不承诺历史备份/在途请求中的所有副本立即消失。

## 14. 保留、删除与容量

建议默认：已发送附件随消息/会话保留；草稿随草稿保留；无引用且无活跃lease的孤儿暂存经启动恢复/周期GC清理。容量达到工作区配额时阻止新导入并提供可检查的清理入口，不自动删仍被历史消息引用的对象。

移除草稿卡片仅解除草稿引用；删除消息解除消息引用；只有全部引用与活跃租约消失后才可物理清理。工作区删除遵守产品已有确认与备份策略。取消导入可以删除本次创建的`.part`，不能删除用户原文件。

配置项建议包括工作区总配额、草稿自动清理时长、远端清理策略；具体默认值等待产品确认，不在本阶段擅自拍板。导出/备份与敏感附件加密可在P1评估；App私有目录不等于用户可见备份，也不等于硬件加密保险库。

## 15. 实施分期

### P0：最小可靠闭环

1. Core：版本化有序MessagePart、附件Repository契约、能力与发送计划；保留旧文本行为。
2. Android：系统选入、受限流式导入、不可变本地副本、草稿恢复、图片规范化、基础PDF/UTF-8验证。
3. 持久化：消息parts和附件引用迁移、原子turn提交、防重复、孤儿回收。
4. 模型：四协议分别支持显式声明能力的静态图片/原生PDF；文本投影；未知能力fail closed；内联字节/上下文门控。
5. 安全：引用读取与网络发送接线、授权revision绑定、审计和敏感日志脱敏。
6. UI：选择/卡片/模式/目标/失败重试与只有附件发送；不把导入状态等同网络上传状态。
7. 验收：旧文本/工具/推理档位回归，协议fixture、DB升级、恢复/取消、内存和真机文件选择。

P0可再拆两次可审查交付：**P0a契约/存储/导入/预算/测试**，**P0b四协议编码/安全接线/UI/端到端**。P0a不得宣称用户已能给模型发附件；P0b通过才算首期闭环。

### P1：效率与常用文档

显式Files API/复用/到期清理；PDF仅文字提取与选页；分享入口；文本编码转换；更完善的容量/备份/可选静态加密。Office原生输入按端点能力单独开放，不能强行统一成所有厂商都支持。

### P2：独立媒体与检索能力

音频/视频时长及采样、转录、OCR、Office本地解析、长文档检索、跨设备/后端共享。必须分别定义输入语义与成本，不作为附件按钮的隐含承诺。

## 16. 可执行验收矩阵

以下是**待实施测试要求**，不是本轮测试结果。

| 维度 | 最低用例与通过条件 |
|---|---|
| 旧消息兼容 | v9旧文本、metadata/工具证据迁移不丢；多次启动不重复回填 |
| 协议编码 | 四协议图片/PDF/text fixture字段正确、顺序稳定；禁止URI/本地路径/base64落库 |
| 真实外发 | 用户授权后用自建无隐私小图/PDF验证实际端点；模型结果只能作辅助，检查真实请求形状/服务端接受；本轮不做 |
| 能力 | 纯文本模型、未知代理、无Files端点、切换模型都在入网前明确失败，不丢附件 |
| 无文字消息 | 单图片/单PDF可提交；完全空消息仍失败；命令解析不读附件文字当命令 |
| 选入与URI | 本地/云端、大小未知、授权拒绝/撤销、源删除、重启、Picker回退、多选超限 |
| 文件安全 | 伪MIME/扩展名、路径名/控制字符、符号链接、损坏/加密PDF、超像素图、超长流、非UTF-8 |
| 崩溃恢复 | 复制一半、rename前后、DB提交前后、发出后断网、流式中杀进程；无半对象、无重复用户消息 |
| 幂等/取消 | 双击发送、超时后重试、取消导入/生成；UNKNOWN不自动重发；原文件不删除 |
| 审批TOCTOU | 批准后切端点/换对象/改模式/策略revision，旧grant不得继续使用 |
| 隔离 | 猜ID跨工作区、未授权子Agent、已删会话/工作区、角色非法媒体part均拒绝 |
| 压缩重放 | 历史附件压缩/切模/缺失有明确语义；当前轮附件不静默丢；旧摘要不冒充原图 |
| 日志审计 | 常规日志不含内容、URI/token/base64；审计有目标/摘要/决策/attempt且无敏感正文 |
| 预算与内存 | 实际编码体超限在入网前拒绝；低内存真机最大允许批量不OOM；并发重编码受限 |
| 保留删除 | 草稿/消息多引用、active lease、GC、远端删除失败；仍被引用对象不误删 |
| 原功能回归 | 工具调用、流式/取消、搜索metadata、reasoning effort/service tier保持；AndroidProvider配置旧JSON可读 |

实施后的验证建议：先运行项目自身 `:core:test`、`:interaction:test`、`:model-providers:test`（模块名以当时settings为准），再用GitHub Actions构建/仪器测试；所有APK打包继续遵守用户的CI-only约定。系统Picker、进程死亡和内存峰值须真机/仪器测试，不能用JVM绿灯代替。

## 17. 待确认的三个关键产品决策

1. **首期范围**：是否采纳“静态图片 + UTF-8文本/代码 + 能力明确的原生PDF”，音视频/Office解析/远端Files缓存后置？
2. **不支持时行为**：是否采纳“明确阻止，用户主动选其他模型或转换方式”，而非自动换模型/静默提取文字？
3. **保留与披露**：是否采纳“随会话持久保留、可显式删除；发送区显示提供商/端点和解释模式；端点或解释变化重新确认”？工作区总配额/草稿TTL可在实施前另定。

不需要用户现在补全所有技术参数才能评审；上述默认提案足以进入实施拆解。未确认前不修改功能代码。

## 18. 一手来源与研究限制

访问日期均为2026-11-11；多数页面未提供稳定发布日期，访问时间不是发布日期。代码证据是本地现场；模型能力矩阵是官方声明，**不等于已测试现有兼容代理**。

- **[A1] Android Photo Picker**：https://developer.android.com/training/data-storage/shared/photo-picker （`/photopicker`入口也可跳转）——选择器、AndroidX回退、授权持久化。
- **[A2] Android SAF documents/files**：https://developer.android.com/training/data-storage/shared/documents-files ——URI、持久授权、源移动/删除、虚拟文档与typed stream。
- **[O1] OpenAI File inputs**：https://developers.openai.com/api/docs/guides/file-inputs ——本轮抓取官方Markdown版本 https://developers.openai.com/api/docs/guides/file-inputs.md，核实Chat/Responses差异及PDF/非PDF处理语义。
- **[O2] OpenAI Images and vision**：https://developers.openai.com/api/docs/guides/images-vision ——图片输入、模型/格式与成本约束。
- **[C1] Anthropic Files**：https://platform.claude.com/docs/en/build-with-claude/files ——本轮抓取其`.md`版本，核实已退出beta、旧header兼容、workspace共享、expiry/删除生命周期、文件类型语义。
- **[C2] Anthropic PDF**：https://platform.claude.com/docs/en/build-with-claude/pdf-support ——本轮抓取其`.md`版本，核实base64/PDF支持和限制维度。
- **[C3] Anthropic Vision**：https://platform.claude.com/docs/en/build-with-claude/vision ——图片输入与视觉模型边界。
- **[G1] Google AI Studio Files**：https://aistudio.google.com/docs/files ——本轮直接读取官方页面，核实临时存储48小时和Files生命周期；https://ai.google.dev/gemini-api/docs/files 的抓取工具本轮返回空列表，未把该空结果当事实依据。
- **[G2] Gemini File input methods**：https://ai.google.dev/gemini-api/docs/generate-content/file-input-methods ——搜索返回官方结果，但本轮正文抓取为空，因此不引用其未取得的详细限额作为结论。
- **[G3] Gemini GenerateContent API reference**：https://ai.google.dev/api/generate-content ——本轮直接读取官方正文，核对Part/Blob的 `inlineData` / `mimeType` / `data` 与PDF MIME；本文Gemini示例使用该REST reference的camelCase。实施时仍需用真实协议fixture和逐模型限制核验，不能把Blob允许的MIME列表当作每个模型均支持。[G3]
- **[P1] DeepSeek Harness本地设计**：`/workspace/deepseek-harness/docs/subsystems/attachment.zh.md`，仅作不可变附件/事件引用原则的先例，不作为CordiAct已实现能力或厂商协议依据。

研究中没有读取/泄露凭证，也没有为验证资料安装工具。网页/文档只作为资料，不能把其中操作示例当成本轮运行指令。

### 本轮完成与尚未完成

**完成**：代码链路核对、Android官方机制核对、主要模型协议差异研究、候选契约/存储/迁移/安全/状态机/分期/验收设计。

**未完成且不伪称完成**：功能实现、真实端点媒体探测、具体配置模型能力确认、实际数据库迁移测试、APK/真机验收。兼容代理能力、精确模型限额、媒体请求内存峰值仍须实施阶段验证。
