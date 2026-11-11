# CordiAct 附件能力：P0 实施进度（第一批）

- 日期：2026-11-11；基线 `82f362c`（`origin/main`）。
- 依据：`docs/research/0016-attachments-upload-design.md`（设计提案）。
- 本批只落地设计文档 §6/§9 的纯 JVM 契约与协议编码；**尚未**接入 Android 持久化、系统选择器、请求构造与 UI，因此用户还不能真正发送附件。

## 1. 已实现

### 1.1 `core` 新包 `com.cordiact.core.attachment`

| 文件 | 内容 |
---|---|
| `AttachmentContracts.kt` | `AttachmentId`（不透明、非路径/哈希）、`AttachmentState`、`Interpretation`、`AttachmentMediaKind`、`AttachmentDescriptor`、`AttachmentRendition`、`AttachmentLimits`（§9.1 建议默认值）、`FilesApiCapability`、`AttachmentInputCapability`（未声明即不支持）、`AttachmentException`/`AttachmentErrorCode` |
| `AttachmentFormats.kt` | MIME 归一化与分类、P0 白名单、魔数嗅探（PNG/JPEG/WebP/PDF）、严格 UTF-8 解码、二进制判定、加密 PDF 提示、仅读文件头的图片尺寸解析（PNG/JPEG/WebP）、显示名清洗（去路径/控制符/双向覆盖） |
| `MessageParts.kt` | 有序 `MessagePart.Text` / `MessagePart.Attachment`、结构校验、文本投影、旧文本懒映射 |
| `MessagePartsJson.kt` | 版本化（v1）编解码；未知版本/未知 kind 直接失败，不静默丢附件 |
| `AttachmentBlobStore.kt` | 字节存储端口 + `LocalAttachmentBlobStore`：`staging/<random>.part` → fsync → 原子 rename 到 `objects/<opaque-key>`，按工作区摘要分目录，键名校验，暂存清扫 |
| `AttachmentCatalog.kt` | `AttachmentReader`（按 workspace 解析、`open`、`verify`）与 `AttachmentCatalog` 元数据端口 |
| `AttachmentImporter.kt` | 流式导入：边读边算 SHA-256、边读边卡字节上限；文本整块严格 UTF-8 校验；图片先验头再验像素/边长；PDF 拒绝加密；失败即 `abort()`，不产生 READY 半对象；返回 `ImportedAttachment(descriptor, blobKey)` |
| `AttachmentPlanner.kt` | `TransmissionRequest` → `TransmissionPlan`：能力 fail-closed、跨工作区、非 READY、LOCAL_ONLY、MIME/解释模式匹配、数量/单件/合计/base64 编码预算校验；`fingerprint` 绑定端点 revision、策略 revision 与文件摘要 |

`ConversationContracts.kt`：`ConversationMessage.parts`、`ModelMessage.parts`（默认空，兼容旧文本）；`ConversationSessionStore.appendWithParts` 与 `SessionManagerService.appendMessageWithParts`（默认抛错，禁止静默丢 parts）；`DefaultSessionManagerService` 实现：parts 非空时只允许 USER 角色并做结构校验。

`core/build.gradle.kts`：新增 `com.google.code.gson:gson:2.11.0`（`MessagePartsJson` 使用；与其它模块版本一致）。

### 1.2 `model-providers` 新包 `com.cordiact.model.providers.attachment`

`AttachmentWireEncoder`：把 `PlannedAttachment` 编码成四种协议各自的 content 块（§5.3）：

- Chat Completions：`image_url` / `file.file_data` / `text`
- Responses：`input_image` / `input_file` / `input_text`
- Anthropic：`image|document.source.base64` / `text`
- Gemini：`inlineData{mimeType,data}` / `text`
- `TEXT_EXTRACT` 一律解码成文本块，绝不以 base64 冒充文本；`LOCAL_ONLY` 直接拒绝。

### 1.3 请求接线（第二批）

- **能力持久化**：`ModelProviderConfig.attachmentCapabilities: Map<modelId, AttachmentInputCapability>`，随 provider 配置写入 `AndroidModelProviderConfigStore` 的 `attachmentCapabilities` JSON（interpretations / mimeTypes / maxFileBytes / maxRequestBytes / maxCount / filesApi）。未声明的模型一律 `UNSUPPORTED`。
- **入网前判定**：`ConfigurableModelBackend.preflightAttachments` 在任何连接打开前逐条消息构建 `TransmissionPlan`；失败以 `ModelEvent.Failed("attachment_rejected", …)` 返回，不落网、不降级。
- **按协议编码**：OpenAI Chat（`content` 数组：`text` / `image_url` / `file`）、Responses（`input_text` / `input_image` / `input_file`）、Anthropic（`text` / `image` / `document`）、Gemini（`text` / `inlineData`），由 `AttachmentWireEncoder.encodeOrdered` 保持用户选择顺序。
- **fail-closed 防线**：`requestBody` 若遇到带附件 part 但无已批准 plan 的消息直接抛错；`content` 与 parts 文本投影不一致也拒绝，避免"只发投影、丢正文"。
- **会话链路**：`ModelGenerationRequest.workspaceId`；`ConversationTurnService.submit/submitWithTools` 新增可选 `parts`，支持"只有附件"的消息；`DefaultContextProjector` 按消息保留 `parts`（压缩摘要覆盖的历史除外）。
- **fingerprint 绑定**：plan 的 `endpointRevision` 由 endpoint 的 SHA-256 前 16 位生成，`policyRevision = p0-1`，端点或策略变化会改变 fingerprint。

### 1.4 应用层持久化（第三批）

- **schema**：新增 `app/.../attachment/AttachmentDatabaseSchema.kt`，建 `conversation_attachments`、`conversation_attachment_renditions`、`conversation_message_parts` 三张表及索引；数据库版本 9→10，`onUpgrade` 用 `CREATE TABLE IF NOT EXISTS` 幂等补表。parts 对 attachments 的外键为 `RESTRICT`（消息不会静默失去附件），对 messages 为 `CASCADE`；`onConfigure` 保持 `foreign_keys=ON`。
- **单事务写入**：`AndroidConversationSessionStore.appendWithParts` 在一个事务里更新会话时间、校验附件与 rendition 的所属工作区、写 message 行、逐条写 parts；任一校验失败整体回滚，返回带 `parts` 的 `ConversationMessage`。
- **读取**：`messages()` 用一条 join 查询把 parts 按 `(message_id, ordinal)` 分组后回填；未知 kind、未知解释模式、未来 schema 版本都会明确失败，不静默丢弃。
- **目录实现**：`AndroidAttachmentCatalog` 实现 `AttachmentCatalog`（`find/list/rendition/open/verify/upsert/upsertRendition/delete`），所有查询按 `workspace_id` 约束；`open`/`verify` 通过 `AttachmentBlobStore` 读取并复核 SHA-256；删除附件时若仍被消息引用会抛错而不是级联清空。
- **接线**：`BundledConversationPlugins` 用 `privateRoot/attachments` 建 `LocalAttachmentBlobStore`，并把 `AndroidAttachmentCatalog` 作为 `attachmentReader` 注入 `ModelProviderRegistryPlugin`。至此带附件的消息不再被"无存储"拒绝，而是走真实目录。
- 旧文本消息不回填 parts：`parts` 为空即"纯文本"，读取与投影行为等价；需要结构化时再显式重建。

### 1.5 Android 导入（第四批）

- **导入服务**：`AndroidAttachmentImportService.importUri()` 只接受 `content://`，用 `ContentResolver` 查询显示名与 MIME（都视为不可信提示），流式写入 `AttachmentImporter`，成功后 `AndroidAttachmentCatalog.upsert`；任何一步失败都不留 READY 半对象。
- **一致性**：字节与目录行的一致性抽到 core 的 `AttachmentImportCoordinator`——先写 blob 再写目录，目录写失败时删除 blob，避免"看不见的字节"。
- **选择器**：`AttachmentPickerRequests` 只提供两条路径——系统 Photo Picker（`PickMultipleVisualMedia`，限 10）与 `OpenMultipleDocuments`（MIME 白名单 `image/*`、`application/pdf`、`text/*`）；不申请存储/全库照片权限，不猜路径。
- **草稿引用**：新增 `conversation_draft_attachments` 表与目录方法 `addDraftAttachment / draftAttachments / removeDraftAttachment / clearDraft`；草稿引用与消息 parts 分离，移除卡片不影响已发送消息。
- **接线**：`BundledConversationPlugins` 现在同时暴露 `attachmentCatalog` 与 `attachmentImportService`，blob 根目录为 `privateRoot/attachments`。
- 未做：图片 EXIF 方向/缩放规范化（需要 `Bitmap`/`ExifInterface` 与真机验收）、草稿勾选 UI、分享入口。

### 1.6 安全、审计与保留（第五批）

- **审计模型**：core 新增 `AttachmentAuditEvent` / `AttachmentAuditSink`（事件类型里没有正文、字节、URI、路径、显示名，写入方无法泄露它没有的东西）；动作覆盖 IMPORT / READ / TRANSFORM / SEND / LOCAL_DELETE。
- **接入现有审计链**：`SecurityAuditAttachmentSink` 把事件写进既有 HMAC 审计日志，category=SECURITY，toolId=`attachment`，附件信息只进 `targetDescriptorSanitized`（id/mime/bytes/sha256 前 16 位/mode）与 `receiverDescriptorSanitized`（端点指纹），审计事件 canonical payload 未改动，旧事件仍可验证。
- **fail-closed**：发送前允许事件写不进审计 → 拒绝发送；导入后的审计写失败 → 删除目录行与 blob 并拒绝导入；读取前审计写失败 → 不交出字节。拒绝事件本身写失败不掩盖原始拒绝。
- **读取/删除审计**：`AndroidAttachmentCatalog.open` 记录 READ（含缺失时的 DENIED），`delete` 记录 LOCAL_DELETE（删除已完成，审计失败不谎报失败）。
- **保留策略**：core `AttachmentGarbageCollector` 只删除"无消息引用、无草稿引用、非 IMPORTING、且超过 24h"的对象；每条目独立失败不中断整轮；应用层 `AndroidAttachmentCatalog.isReferenced` 查 `message_parts` 与 `draft_attachments`；`BundledConversationPlugins.runAttachmentGc()` 跨工作区执行，`CordiActApplication` 在启动时用守护线程跑一次。
- **UI 缺口**：未接任何界面；导入服务、目录、GC、审计都以公开 API 暴露，等待统一 UI 设计。

### 1.7 图片规范化（第六批）

- **纯策略**：`ImageNormalizationPolicy`（core，无 Android 类型）把 EXIF 方向 1..8 映射为旋转/翻转/交换轴，按 `MAX_SEND_EDGE = 2048` 计算采样因子与目标尺寸；2048 而非 4096 是为了把峰值位图内存压到约 16 MB（4096 需要约 64 MB），并在注释中说明理由。
- **派生对象**：`AttachmentRenditionWriter`（core）把规范化结果写成独立 blob + rendition 行，记录 `transform_json`、`extractor_version`、SHA-256；原图不被覆盖。目录接口新增 `deleteRendition`，审计失败时只回滚该 rendition。
- **Android 实现**：`AndroidImageNormalizer` 用 `inJustDecodeBounds` + `inSampleSize` 解码、`Matrix` 应用方向/翻转、缩放到目标尺寸、JPEG(85)/PNG 重编码（PNG 保留透明），并显式 recycle 中间位图。
- **导入接线**：`AndroidAttachmentImportService` 导入图片后读取 EXIF；方向需要纠正、超长边或含 GPS 时生成 rendition，返回 `ImportedAttachmentResult(descriptor, sendRendition)`。规范化失败会删除刚导入的对象并拒绝本次导入，避免"能发但会泄露元数据"的半成品。
- **元数据**：GPS 坐标存在即强制重编码（去 EXIF），即使方向正常。

## 2. 验证结果

```
./gradlew :core:test :interaction:compileKotlin :model-providers:compileKotlin --offline   → BUILD SUCCESSFUL
./gradlew :model-providers:test --tests "com.cordiact.model.providers.attachment.*"         → BUILD SUCCESSFUL
```

新增测试 76 个（core 59 + model-providers 17），覆盖：MIME/魔数/尺寸解析、显示名清洗、能力 fail-closed、导入限额与失败清理、blob 原子发布与工作区隔离、计划器全部拒绝分支与 fingerprint、parts JSON 版本化、四协议 wire 形状、请求体四协议接线、无能力/无存储/无 workspace 的入网前拒绝、未预检消息拒绝、顺序保持。

未做：真机、仪器测试、真实端点探测、APK 构建（按 CI-only 约定）。`app` 模块的 Gradle 构建在本机因缺少可执行的 `aidl`（SDK 为 x86-64，宿主为 aarch64）无法运行；作为替代，`AndroidConversationSessionStore`、`AttachmentDatabaseSchema`、`AndroidAttachmentCatalog` 已用 Kotlin 编译器对 `android-36` + `core.jar` 单独做类型检查并通过（`-jvm-target 17`），但数据库行为、迁移与 FK 约束仍需仪器测试覆盖。`BundledConversationPluginCatalog` 的接线改动未经编译器验证。

注：`ConfigurableModelBackend` 的附件路径目前只由单元测试覆盖；`ModelProviderRegistryPlugin` 的 `attachmentReader` 仍为 null，等待持久化仓储实现后接线——在那之前，任何带附件的消息都会在入网前被拒绝。

## 3. 未实现（P0 剩余）

1. **UI（统一设计，暂缓）**：附件卡片、解释模式、目标端点展示、不支持前置提示、只有附件的消息、失败重试同一 turn；把选择器与导入服务接到输入区。
2. **图片规范化**：EXIF 方向/去元数据/缩放为衍生对象（需真机验收）。
3. **仪器测试**：v9→v10 迁移、事务回滚、FK RESTRICT、跨工作区读取、导入中断恢复。
4. **P1 预留**：Files API 缓存与到期清理、PDF 文字抽取、分享入口。

## 4. 下一批状态

非 UI 的 P0 代码链路已完整：选入 → 流式导入 → blob/目录 → 草稿或消息 parts → 发送前 plan 与能力校验 → 四协议编码 → 审计与保留。剩余工作集中在仪器测试、图片规范化和统一 UI。
