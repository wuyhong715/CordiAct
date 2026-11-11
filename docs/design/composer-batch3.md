# CordiAct 对话输入栏重做设计（批三）+ 后端整改

- 版本：v2（按 ChatGPT 的 `+` 菜单与 Codex 的推理强度滑条重设计）　日期：2026-11-11　基线：main `d3e809f`
- 范围：输入栏（Composer）重做——附件入口与附件状态、思考深度、模型切换；统一动效与图标比例；去掉“大抽屉”式弹出。
- 关联：设置中心设计 `CordiAct-设置中心UI设计与后端整改-20261111.md`（模型增删/提供商管理移到设置，输入栏只做“快速切换”）。
- 约束沿用：浅色优先、Material 3、自绘 Path 图标、触达 ≥48dp、所有控件对应真实后端，不做假开关，不强行兼容旧实现。

---

## 0. 现状问题（已核实，`experience/ExperienceScreen.kt`）

| # | 问题 | 位置 / 证据 |
|---|---|---|
| U1 | 图标尺寸混乱：下拉箭头 13dp、加号 16dp、`AppGlyph` 默认 22dp、发送按钮 48dp 实心圆，同一行视觉重量失衡 | `Composer` / `CompactModelPicker`；`AppGlyph.kt:59 size(22.dp)` |
| U2 | 模型选择是底部大面板：`heightIn(max = maxHeight * .45f)`，`if (modelMenu)` 直接出现/消失，**无进出场动画** | `CompactModelPicker` |
| U3 | 添加提供商表单 `ProviderForm` 同样以底部大面板叠在对话上 | `ExperienceScreen.kt` `if (providerForm)` |
| U4 | 模型名是整行 `TextButton(weight 1f)`，长名直接挤满底栏，无能力提示 | `Composer` |
| U5 | 发送↔停止切换、按钮可用态、输入框增高全部是瞬变 | `Composer` |
| U6 | 无附件入口；无思考深度 / 响应速度入口 | — |
| U7 | 模型选择存全局 `SharedPreferences("experience")`，换会话不跟随 | `ExperienceViewModel.selectModel:201-204` |

## 1. 设计原则

1. **锚定弹层取代抽屉**：输入栏发起的选择（附件来源、模型、思考深度）一律用**锚定在触发控件上方的小浮层**（Popover），高度 ≤ min(360dp, 可用空间)，不遮住整段对话，不出现底部 45% 大面板。
2. **一个尺寸体系**：图标 20dp、容器 36dp、触达 48dp；所有弹层/状态切换都有动画，并尊重系统“移除动画”设置。
3. **状态可见**：附件导入、校验、失败、与当前模型不兼容，都在附件卡片上直接显示，不靠 toast。
4. **能力驱动**：思考深度、速度、可附加的类型，全部由“当前模型声明的能力”决定；模型没声明的选项**不显示**，而不是灰掉。
5. **会话状态归会话**：模型、思考深度、速度、未发送附件都是“这个会话的草稿”，切会话跟随，进程被杀可恢复。

## 2. 布局

### 2.0 参考基准（你指定的两张截图）
| 参考 | 采纳 | 不采纳 / 原因 |
|---|---|---|
| ChatGPT：`+` 弹出纵向大菜单（拍照/照片/文件/插件/深入思考，勾选态） | `+` 作为**唯一附件与扩展入口**，纵向菜单、大图标行、可扩展；「深入思考」类开关也从这里走 | 「插件」「拍照」：后端暂无对应入口，菜单项由注册表生成，后端有了自动出现 |
| ChatGPT：极简收起态（`+`、占位文本、发送），无模型行常驻 | 收起态只保留 `+` + 输入 + 发送；**模型与强度收进同一个“控制胶囊”**，聚焦/非空时才显示 | 麦克风：无语音后端，不放假按钮 |
| Codex：推理强度胶囊 + 浮层（档位名、模型链接、**渐变滑条**、⚡速度档、恢复默认） | 强度用**渐变滑条**（吸附到模型支持的档位），⚡双击循环速度档，恢复默认按钮；胶囊文案 `模型 · 强度` | 纯装饰星空粒子：只在拖动时轻微呈现，且系统关动画时不播 |

### 2.1 结构（收起态 / 展开态）
```
收起（无焦点、无附件、未输入）          展开或有附件
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ [img][报告.pdf ⓧ]            │   │ (+)                          │
│ (+)  输入消息…            (➤) │   │ 输入消息…                     │
└──────────────────────────────┘   │ (◇ gpt-5.1 · high ⌄)      (➤) │
                                   └──────────────────────────────┘
```
- 卡片：圆角 24dp（参考图大圆角），`surface` 底，1dp `outlineVariant`，聚焦描边过渡 `primary` 40%（150ms）；外边距 12dp，底部跟随 IME。
- **控制胶囊（Codex 式）**：高 36dp、圆角 18dp、`surfaceContainerHigh` 底，内容 `提供商首字母标 · 模型名 · 强度`，尾部 12dp 箭头；⚡生效时前置闪电图标。宽度过窄时只显示 `模型名 · 强度`，再窄只显示模型名。
- 强度为「自动/默认」时胶囊里强度段**不显示**（比“思考·自动”更轻）；⚡加速生效时胶囊前置 `⚡` 并 tertiary 着色提示“用量更多”。
- 工具栏高度 44dp；`+` 与发送同 ChatGPT：发送 36dp 圆，禁用态灰、可发送态主色、运行中变圆角方停止键。

### 2.2 尺寸规范（4dp 网格）
| 元素 | 视觉 | 触达 | 图标 |
|---|---|---|---|
| `+` 按钮 | 36dp 圆、`surfaceContainerHigh` 底（菜单开时变 `primaryContainer`） | 48dp | 20dp |
| 控制胶囊 | 高 36dp，左 10/右 8dp 内边距 | 高 48dp | 16dp + 12dp 箭头 |
| 发送/停止 | 36dp 圆 / 圆角方 12dp | 48dp | 20dp |
| `+` 菜单行 | 高 56dp：40dp 圆形图标底 + 16sp 文字（参考图样式） | 同 | 20dp |
| 附件卡片 | 图片 64×64 圆角 12；文件条 56 高 × ≤200 宽 | 卡片 + 角标 32dp | 20dp |
| 浮层行 | 48–56dp | 同 | 20dp |
- `AppGlyph` 默认 22dp → **20dp**，新增 `GlyphSize.Small(16)/Regular(20)/Large(24)`，删除各处 13/16dp 临时值。
- 新图标：`Attach, Camera, Image, File, Pdf, Text, Bolt, Sparkle, Pin, Retry, Eye, Doc, Reset`；风格同 `AppGlyph.kt`（24 视口、1.75 描边、圆角端点）。

---

## 3. 附件

### 3.1 入口：`+` 菜单（唯一入口，ChatGPT 式）
点 `+` → 锚定浮层（宽 260dp，纵向大行，大图标底圆 + 16sp 文字，圆角 24dp）：
```
 ┌────────────────────┐
 │ ◉ 拍照              │   ← 仅当后端提供相机源时出现（C10）
 │ ◉ 照片              │   → PickMultipleVisualMedia
 │ ◉ 文件              │   → OpenMultipleDocuments
 │ ◉ 插件与能力        │   → 深链设置 › 扩展（本批只做深链）
 └────────────────────┘
```
- **菜单由 `ComposerActionRegistry` 生成**：每个动作声明 `id, glyph, label, enabled(provider capability), visible`。后续加拍照/语音/剪贴板只注册动作，不改菜单结构。
- 「深入思考」不进这个菜单：它是强度胶囊的一部分（Codex 式），避免与 ChatGPT 一样藏在附件菜单里。
- 当前模型完全不支持附件：菜单照常可开，顶部一行提示“当前模型不能读取附件，只会保存到工作区”。
- 打开菜单时 `+` 底色过渡为 `primaryContainer`（180ms），菜单从按钮上方 scale+fade 进场（§7.1）。

### 3.1b 相机（预留，后端 C10）
`TakePicture` + FileProvider 拍照 → 直接进导入管线（同一 `importSource`），菜单项由注册表在 C10 落地后自动显示。

### 3.2 附件托盘
- 位置：输入区上方，有附件才出现，高度 0 → 72dp 弹簧展开；横向滚动，超过可视数量末尾显示 `+N`。
- 卡片样式：
```
 图片                    文件
 ┌──────┐               ┌──────────────────────┐
 │ 缩略图 │ ⓧ            │ ▤ 季度报告.pdf     ⓧ │
 └──────┘               │   PDF · 2.4 MB · 12 页 │
                        └──────────────────────┘
```
- 缩略图来自导入后生成的 `sendRendition`（已去 EXIF/GPS、限长边），不直接解码原图。

### 3.3 附件状态（对应 `AttachmentState`）
| 状态 | 卡片表现 | 发送按钮 |
|---|---|---|
| IMPORTING | 缩略图/图标 50% 透明 + 中心 20dp **不定进度环** | 可点；点后进入“等待附件”态（按钮内转圈，文案“准备附件…”），全部 READY 自动发出；再点取消等待 |
| VERIFYING | 同上，环变细 | 同上 |
| READY | 正常 | 正常 |
| FAILED | `errorContainer` 底 + 红描边 + `Warning` 角标；点卡片弹出原因浮层：原因文案 +「重试」「移除」 | 禁止发送，按钮旁提示“有 1 个附件失败” |
| CANCELLED | 卡片缩小淡出后移除 | — |

**与当前模型的兼容性**（由 `AttachmentInputCapability` 计算，不是状态而是“标签”）：
| 结果 | 卡片角标 | 行为 |
|---|---|---|
| 可直接读取（VISION / NATIVE_DOCUMENT） | 无 | 原样发送 |
| 只能抽取文本（TEXT_EXTRACT） | 左下小标“文本” | 发送抽取文本；长按卡片说明 |
| 不能读取（LOCAL_ONLY） | 灰化 + 左下小标“仅本地” | 不随消息发送；输入栏上方一行提示：“1 个附件当前模型无法读取 [换模型] [仍然发送文字]” |
| 超限（FILE_TOO_LARGE / IMAGE_TOO_LARGE / TOO_MANY_ATTACHMENTS） | 红描边 | 阻止发送，原因内联 |
- 切换模型时角标实时重算（带 150ms 淡变），用户立刻看到“换到这个模型后哪些附件读不了”。

### 3.4 错误码 → 文案（节选，全部内联显示，不用 toast）
| 错误码 | 文案 |
|---|---|
| UNSUPPORTED_MIME | 不支持这种文件类型 |
| FILE_TOO_LARGE | 文件超过 10 MB |
| IMAGE_TOO_LARGE | 图片超过 20 MP，已无法缩放 |
| TOO_MANY_ATTACHMENTS | 每条消息最多 10 个附件 |
| REQUEST_TOO_LARGE / BUDGET_EXCEEDED | 附件合计过大，请移除一些 |
| INTEGRITY_MISMATCH / STORAGE_FAILURE | 保存失败，可重试 |
| UNSUPPORTED_CAPABILITY / LOCAL_ONLY_PART | 当前模型无法读取此附件 |
- 数值取自 `AttachmentLimits`，不写死在文案里。

### 3.5 已发送消息中的附件
- 用户气泡上方显示同款卡片（只读，无删除角标），点开：图片全屏预览（可缩放）、PDF/文本在“预览”窗口打开。
- LOCAL_ONLY 的附件在气泡里显示“仅保存到工作区，未发送给模型”。

---

## 4. 模型切换（胶囊内）
- 胶囊左侧 16dp 首字母圆标（色由 providerId 哈希取 container 色组）+ 模型名 + 强度段 + 12dp 箭头（§2.1）。
- 无可用模型：胶囊变“添加模型”，点按深链 `settings://models/providers`。
- 浮层（取代 `CompactModelPicker`，Codex 式菜单）：锚定胶囊上方，宽 min(屏宽−24dp, 360dp)，高 ≤ min(360dp, 可用空间)：
```
 选择模型
 置顶
  ◇ gpt-5.1           👁 ✦ 400K  ✓
 最近
  ◇ deepseek-flash        ✦ 128K
 CPA-LA
  ◇ gemini-2.5-pro     👁 📄 ✦ 1M
  …
 ─────────────────────────────
 ○ 默认（跟随设置）                ← 首组之前
 管理模型 →
```
- 「默认」为可选首项 = 跟随设置中的对话默认模型（对应你截图的“默认 · 推荐模型集”；我们不做“推荐模型集”，默认值来自设置页 2.2 模型角色）。
- 能力标签 16dp：👁 图像、📄 原生文档、✦ 可调强度，后跟上下文窗口（有数据才显示）。
- 长按置顶；有附件时不兼容的行尾小字“1 个附件不可读”，不禁止选择。
- 切换后胶囊文案 `AnimatedContent` 上滑替换；新模型不支持当前强度 → 自动回“默认”，⚡若不支持自动回自动，胶囊轻闪 tertiary 一次。
- 运行中可切换，胶囊显示小点“下一条生效”。
- 「刷新模型」移入设置（不在对话浮层），避免再叠大表单。

## 5. 推理强度（Codex 式滑条浮层）
点胶囊 → 浮层（宽 320dp）：
```
 ┌────────────────────────────────┐
 │ [⚡]      xhigh          ↺      │
 │          GPT-6 Astra ›         │   ← 点模型名 = 打开模型浮层
 │ ═══════════●─────────────       │   ← 渐变滑条
 │ none low medium high xhigh max  │   ← 刻度（仅模型声明的档位）
 └────────────────────────────────┘
```
- **滑条吸附到当前模型声明的档位**（`ReasoningEffort` 声明值；首档前含“默认/关闭”，映射 AUTO 与 NONE）——只显示模型支持的档位，滑块步进吸附，拖动时实时更新大标题文案与触感反馈（`HapticFeedbackType.SegmentTick`）。
- **档位文案直接用后端 `ReasoningEffort` 的 OpenAI 标准值**：`none / auto / minimal / low / medium / high / xhigh / Max`（小写原样显示，不翻译、不造 Ultra 之类的档名）。顶部大字 = 当前值；`auto` 时胶囊不显示强度段。右侧 ↺ = 恢复 `auto`；模型不可调时浮层退化为“此模型不支持推理强度”一行。
- **⚡速度档**：点 ⚡ 在 常速 → FAST（快速）→ ULTRAFAST（极速）间循环（只循环模型声明的值）；tooltip/胶囊提示“快速 · 用量更多”。
- 模型名行：点击切换到 §4 模型浮层（浮层家族互斥）。
- 选择即生效（会话级，C4），胶囊强度段同步更新；发送时档位失配（能力刷新后）自动回落并在胶囊轻闪提示（C9）。

---

## 6. 发送 / 停止 与输入区

| 状态 | 按钮 | 动画 |
|---|---|---|
| 空输入、无附件 | `surfaceContainerHigh` 底、图标 `onSurfaceVariant` 38% | 与可用态互相 150ms 颜色过渡 |
| 可发送 | `primary` 底、白色发送图标 | 按下 scale 0.92 → 1（spring） |
| 等待附件 | `primary` 底 + 环形进度包围 | 环淡入 120ms |
| 运行中 | 圆形 → 圆角方（12dp）、图标切停止 | 形状与图标 `AnimatedContent` 180ms crossfade+scale |
| 停止中 | 停止图标 + 不定进度环，禁用 | — |
- 输入区高度：1–6 行，`animateContentSize(spring(dampingRatio=0.85, stiffness=StiffnessMediumLow))`；超过 6 行内部滚动。
- 占位文案保持“发送消息 · / 指令 · @ 上下文 · # 私密”。`/ @ #` 的补全浮层后续沿用同一 Popover 组件（本批不改逻辑）。
- 草稿（文本 + 附件 + 模型 + 思考档位）按会话保存，切会话/进程被杀可恢复（依赖 C4）。

---

## 7. 弹层与动效规范

### 7.1 统一 `AnchoredPopover`
- 定位：优先在锚点**上方**，左对齐锚点，越界则右对齐；可用高度 = 锚点顶到状态栏底 − 8dp；IME 弹起时一起上移（锚点随输入栏）。
- 进场：从锚点方向 `scaleIn(0.92, transformOrigin=锚点)` + `fadeIn`，**180ms，EmphasizedDecelerate**。
- 出场：`scaleOut(0.96)` + `fadeOut`，**120ms，EmphasizedAccelerate**。
- 遮罩：`scrim` 8% 透明度，点空白或返回键关闭；同一时刻只开一个浮层，打开新的先关旧的。
- 表面：`surfaceContainerLowest`，圆角 16dp，阴影 6dp，1dp `outlineVariant`。

### 7.2 动效 Token（新建 `experience/Motion.kt`）
| Token | 值 | 用途 |
|---|---|---|
| `Motion.Fast` | 120ms | 出场、颜色微变 |
| `Motion.Standard` | 180ms | 进场、图标切换 |
| `Motion.Emphasized` | 300ms | 托盘展开（无弹簧时降级） |
| `Motion.Spring` | damping 0.85, stiffness MediumLow | 高度、托盘、卡片位移 |
- 附件卡片：进入 `fadeIn + scaleIn(0.8)`；移除 `fadeOut + shrinkHorizontally`；其余卡片 `animateItem()` 平移补位。
- **系统动画缩放为 0**（开发者选项/无障碍“移除动画”）时：全部 Token 视为 0，直接切换，不播放。

### 7.3 其他“大抽屉”的处理
- `CompactModelPicker`、`ProviderForm`：删除（被 §4 浮层与设置页替代）。
- `CompactInspector`（工具步骤详情）：本批不改行为，只把出现/消失补上 §7.1 同款动画；是否改成“轨迹窗口内展开”留到对话窗口批次。

---

## 8. 后端整改（为输入栏服务；不保留旧兼容）

| # | 优先级 | 问题（已核实） | 改法 | 破坏性 |
|---|---|---|---|---|
| **C1** | P0 | `ModelDescriptor` 只有 `id/displayName/supportsStreaming/contextWindowTokens`；思考档位/速度能力在 `ModelProviderConfig.parameterCapabilities`，附件能力在 `AttachmentInputCapability`，**UI 层拿不到** | `ModelDescriptor` 增加 `capabilities: ModelCapabilities(reasoningEfforts, responseSpeeds, attachment: AttachmentInputCapability)`，由提供商注册表填充；未声明 = 空集合 | 改数据类 |
| **C2** | P0 | `InteractionService.submitOrdinary(prepared, modelId, parameters, onEvent)` **没有附件参数**；而会话存储 `appendUser` 已支持 `parts: List<MessagePart>`——链路断在交互层 | `submitOrdinary` / `dispatch` 增加 `attachments: List<AttachmentRef>`；交互层负责：校验同工作区、READY、按模型能力选择 interpretation、组装 `MessagePart`，失败抛 `AttachmentException(code)` 供 UI 映射 | 改签名 |
| **C3** | P0 | `ExperienceViewModel.send` 调 `submitOrdinary` 时**未传 `parameters`**（`GenerationParameters` 已有 `reasoningEffort/responseSpeed` 字段），后端 #59 的能力在 UI 上用不到 | ViewModel 从会话草稿读取并传入；“自动”传 null | 无 |
| **C4** | P0 | 模型选择存全局 `SharedPreferences("experience")`（`selectModel:201-204`）；无会话级草稿 | 新 `SessionComposerState(sessionId, modelId, reasoningEffort?, responseSpeed?, draftText, draftAttachmentIds)` 持久化在会话库；新会话继承“上次使用”的模型/档位；删除 `experience` prefs 中的 provider/model 键（不迁移） | 删旧存储 |
| **C5** | P0 | 附件目录无“草稿/已绑定消息”区分；导入后未发送就离开会产生孤儿附件（`AndroidAttachmentCatalog` 无 owner/message 字段） | 描述符增加 `owner: Draft(sessionId) | Message(messageId)`；发送成功原子转为 Message；草稿移除即删除 blob；启动时清理 >24h 的孤儿草稿 | DB 迁移（可直接重建表） |
| **C6** | P1 | `importUri` 同步阻塞、无取消、无进度 | 改 `suspend`，在 IO 调度器执行、响应协程取消（取消 → CANCELLED 并清理半成品）；以 `Flow<AttachmentState>` 暴露状态；进度保持“不定”，不伪造百分比 | 改签名 |
| **C7** | P1 | 发送前兼容性判断目前只能在请求失败后得知 | 新纯函数 `AttachmentCompatibility.evaluate(descriptor, capability, limits): Compatible | TextOnly | LocalOnly | OverLimit(code)`，UI 实时用、C2 发送时复用同一实现（单一真相） | 新增 |
| **C8** | P2 | 模型“置顶/最近”无存储 | 小表 `model_usage(modelId, pinned, lastUsedAt)`；发送成功时更新 lastUsedAt；提供商删除时级联清理 | 新增 |
| **C9** | P2 | 切换模型后若档位不被支持，现状会在发送时由后端报“未映射选项”错误 | 选择模型时由 ViewModel 依据 C1 自动回落 AUTO；后端报错仍保留作为最后防线 | 无 |

### 8.1 ViewModel 形态（建议）
```kotlin
data class ComposerUiState(
    val text: String,
    val attachments: List<AttachmentChipState>,   // id, kind, name, size, state, compatibility, thumbnail
    val model: ModelChipState?,                   // null = 无可用模型
    val reasoning: ReasoningChipState?,           // null = 模型无可调项 → 隐藏
    val send: SendButtonState,                    // Disabled / Ready / WaitingAttachments / Running / Stopping
    val banner: ComposerBanner?,                  // 附件不兼容 / 失败 / 档位回落提示
    val popover: ComposerPopover?,                // Attach / Model / Reasoning / AttachmentError(id)
)
```
- 纯逻辑（兼容性、发送可用性、档位回落、草稿还原）放 `experience/composer/ComposerReducer.kt`，JVM 单测覆盖，与批一侧栏的“纯逻辑 + 测试”一致。

---

## 9. 无障碍
- 每个 Chip：`contentDescription = "模型：gpt-5.1，双击切换"`、`"思考深度：高，响应速度：快速，双击修改"`。
- 附件卡片：`"图片 IMG_2041，就绪"`、`"文件 报告.pdf，导入失败：文件超过 10 MB，双击查看"`；删除角标单独可聚焦“移除 报告.pdf”。
- 浮层打开时焦点移入首项，关闭后回到触发控件；TalkBack 下浮层以 `paneTitle` 宣告。
- 状态不只靠颜色：失败有图标与文字，兼容性角标有文字。
- 字体 200%：Chip 文字省略而非换行，工具栏高度可增到 56dp；附件托盘卡片等比放大。

---

## 10. 实施分批

| 批 | 内容 | 后端前置 | 验收（可检查） |
|---|---|---|---|
| **3a 骨架与动效** | `Motion.kt`、`AnchoredPopover`、`GlyphSize` 统一与新图标、新 Composer 布局、发送/停止动画、删除 `CompactModelPicker`/`ProviderForm` | — | 工具栏所有图标为 16/20dp 两档（代码中无其它 dp 尺寸的 Glyph）；浮层进出有动画且系统关闭动画时为瞬变；对话页不再出现任何底部大面板 |
| **3b 模型与思考** | 控制胶囊 + 模型浮层（置顶/最近/能力标签）、推理强度滑条浮层、会话级草稿 | C1、C3、C4、C8、C9 | 切会话模型/档位跟随；进程杀死后恢复；不可调强度的模型不显示强度段；请求体中 reasoning 字段与所选一致（单测断言 wire 映射）；“自动”时请求不含该字段 |
| **3c 附件** | `+` 浮层、托盘、卡片全状态、兼容性角标、错误浮层、气泡内附件展示 | C2、C5、C6、C7 | 选 3 张图 + 1 个 PDF → 卡片依次 IMPORTING→READY；超限文件显示具体原因且无法发送；切到无视觉模型时图片变“仅本地”并出现横幅；发送后附件绑定消息，删除草稿附件后 blob 被删除；取消导入不留文件 |

每批：JVM 单测（Reducer/兼容性/草稿）+ Compose 预览截图 + CI Android build；APK 由 GitHub Actions 打包后真机验收（动画观感需真机确认）。

## 11. 待你决定
1. 相机（C10，`TakePicture`+FileProvider→注册表自动出现菜单项）排在 3c 一起做，还是之后单独一批？
2. 新会话默认模型：**继承上一次使用**（本设计）还是固定为设置里的“对话默认模型”。
3. 强度档位作用范围：**会话级**（本设计）还是每条消息临时选择。
4. 不兼容附件的默认行为：**提示并让用户选择**（本设计）还是自动降级为“仅本地”直接发送文字。
5. `CompactInspector` 是否随本批一起改为非抽屉形式。

## 12. 核实说明
- 已核实（读源码）：§0 全部问题；`AttachmentState` 五态、`Interpretation` 四种、19 个 `AttachmentErrorCode`、`AttachmentInputCapability` 字段、`AttachmentPickerRequests` 只有照片/文档两种选择器；`ModelDescriptor` 字段；`submitOrdinary` 签名（有 `parameters`、无附件）；`GenerationParameters` 已含 `reasoningEffort/responseSpeed`；`ExperienceViewModel.send` 未传 parameters；会话 `appendUser` 支持 `parts`；附件目录无草稿/消息归属字段。
- 推断/建议：C 系列接口形态、动效具体数值（需真机调）、各提供商能否给出上下文窗口与能力元数据。

## 用户最终确认（2026-11-11）
前台实施。滑条 auto 位于 none 与 minimal 之间；仅最高档显示 Max（大写 M），wire 仍为 max。其余按 v2 实施。
