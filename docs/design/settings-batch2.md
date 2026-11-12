# CordiAct 设置中心 UI 设计（批二）+ 后端整改清单

- 版本：v1　日期：2026-11-11　基线：main `d3e809f`
- 范围：把所有“需要用户配置/授权”的后端做成一个统一的设置中心；同时列出为支撑 UI 必须做、且**不保留旧兼容**的后端改造。
- 约束沿用批一：浅色优先（已有暗色调色板）、Material 3、自绘 Path 图标（无第三方图标库）、触达 ≥48dp、大字体可增高、所有入口必须对应真实后端，不做假开关。
- 未跟踪原型 `app/.../ui/settings/SettingsScreen.kt` 不复用、不修改；新实现放 `experience/settings/`。

---

## 0. 现状结论（已核实）

| 后端 | 存储 / 入口 | 问题 |
|---|---|---|
| 模型提供商 | `AndroidModelProviderConfigStore` + 凭据命名空间 `model` | 已有添加表单（`ExperienceViewModel.addProvider`）；缺列表/编辑/删除/模型启停/能力档位 |
| 搜索 | `AndroidSearchApiKeyStore`(`search` 命名空间)、`AndroidSearchProviderSettings.baseUrl`、`AndroidSearchPolicyStore`(每工作区，默认 AUTO) | `SearchSettingsDialog.kt` 无调用点 |
| 权限档位 | `SecurityProfileStore.setPreset(workspaceId, PermissionPreset, AccessEnvelope, ReviewerConfig?)`；AUTO_APPROVAL 必须有 reviewer | 无 UI；reviewer 以 6 个散 key 存 endpoint 快照 |
| 特权通道 | `PrivilegeService`(`AutomationRuntime.kt:53`) 的 `rootEnabled/advancedCommandsEnabled` | 无存储，构造时固定为默认 false |
| SSH | `SshHostStore` → `filesDir/ssh_hosts.json` | **密码/私钥/口令明文 JSON**；读写异常被 `catch (_: Exception)` 吞掉（损坏即“空列表”）；有 `pinnedFingerprint` 字段 |
| Termux / Shizuku / 无障碍 / 浏览器 | `AutomationRuntime.registerTools` | 全部无条件注册（`:109-121`），无启停；无障碍无“是否已开启”查询 |
| MCP | `AndroidPluginManagerService.installMcpServer(config)`，仅 LLM 工具调用 | `McpCredential` 无持久化路径 |
| 记忆抽取/上下文摘要模型 | `ExtractionModelSelectionStore` / `SummaryModelSelectionStore` | 有 select/clear，无 UI；不校验模型是否仍存在 |
| 附件限额 | `AttachmentLimits()` 默认构造 | 不可配置 |
| 审计 | `AuditLog.query(AuditFilter)` | 已有查询接口，无查看界面 |
| 设置入口 | 侧栏底部「设置」→ toast「批二开放」 | 待替换 |

## 1. 信息架构

### 1.1 入口与导航
- 入口：侧栏底部「设置」→ 全屏 `SettingsHost`（覆盖 ExperienceScreen，不是 Dialog）。聊天里的就绪错误（如“Termux 未授权”）也可深链到对应页：`settings://connections/termux`。
- 导航：内部轻量 back stack（`SettingsRoute` sealed class），系统返回逐级弹出；表单有未保存改动时返回先弹“放弃修改？”。
- 宽屏（≥600dp）：左列表 + 右详情双栏；手机：单列下钻。

### 1.2 作用域
设置分两类，页面顶部明确标注，避免“改了 A 工作区以为全局生效”：
- **全局**：模型提供商、模型角色、搜索 Key/地址、SSH 主机、MCP 服务器、连接授权、附件限额。
- **按工作区**：权限档位、审批人、搜索模式、能力启停（见 §6.1）。页面顶栏显示「作用于：工作区名 ▾」，可切换，默认当前工作区。

### 1.3 首页结构
```
┌──────────────────────────────────────┐
│ ←  设置                               │
├──────────────────────────────────────┤
│ ⚠ 2 项需要处理                    ›  │  ← 就绪横幅，仅在有 NEEDS_ACTION/ERROR 时出现
│   Termux 未授权 · 记忆抽取模型已失效   │
├──────────────────────────────────────┤
│ 模型                                  │
│  ◇ 模型提供商        3 个 · 41 模型 › │
│  ◇ 模型角色          对话 / 摘要 / 抽取 › │
│ 联网搜索                              │
│  ◎ 搜索服务          自动 · Tavily   › │
│ 安全                                  │
│  ⛨ 权限档位   [当前工作区] 仅工作区   › │
│  ⚡ 特权执行          Shizuku 已连接  › │
│  🔑 凭据与审计                        › │
│ 连接与执行环境                         │
│  ▣ SSH 主机          2 台            › │
│  ▣ Termux            ● 需要授权      › │
│  ▣ 屏幕无障碍         ● 未开启        › │
│  ▣ 离屏浏览器         ● 就绪          › │
│ 扩展                                  │
│  ⊕ MCP 服务器         1 个            › │
│  ⊞ 能力开关           6/7 启用        › │
│ 数据                                  │
│  ▤ 附件限制                           › │
│ 关于                                  │
│  版本 / 构建号 / 开源许可              › │
└──────────────────────────────────────┘
```
每行：左自绘图标、主标题、右侧**真实状态摘要**（来自后端，不写死）、状态点。状态点颜色见 §3.1。

---

## 2. 页面规格

### 2.1 模型提供商
**列表页**
```
模型提供商                         [+ 添加]
─────────────────────────────────────────
 OpenAI 兼容 · CPA-LA        ● 正常
 ai.moxw.dpdns.org:8443      28 个模型 · 启用 12
─────────────────────────────────────────
 Anthropic · 官方            ● Key 失效
 api.anthropic.com           上次检测 401
```
- 按“提供商一级分类”分组（OpenAI 兼容 / Anthropic / Gemini / OpenAI Responses，对应 `ModelProtocol`）。
- 空状态：插画占位 + 「添加第一个模型提供商」主按钮。

**添加流程（底部分步 Sheet，3 步）**
1. 选类型：4 张卡片（协议名 + 一句说明 + 常见示例地址）。
2. 填写：名称（可选，默认取域名）、Base URL、API Key（`SecretField`）。按钮「拉取模型」→ 只调 `ModelDiscoveryService.discover`（不写入；见后端 B7）。
3. 选模型：发现结果列表，全选/搜索/逐个勾选，显示上下文长度、是否支持图像/推理（有元数据才显示）。「完成」才写入。
- 错误就地显示在当前步骤：401→“Key 无效”；404→“该地址不是 X 协议端点”；网络→“无法连接，检查地址或代理”。不关闭 Sheet、不清空输入。

**详情页**
```
CPA-LA                                   ⋮(重命名/删除)
类型      OpenAI 兼容（Chat Completions）
地址      https://ai.moxw.dpdns.org:8443/v1   [编辑]
API Key   已设置 · 更新于 11-10           [更换] [清除]
          [测试连接]   ● 正常 · 312ms
─────────────────────────────────────────
模型（12/28 启用）         [重新拉取] [🔍]
 ☑ gpt-5.1           推理：自动 ▾  速度：自动 ▾
 ☑ claude-sonnet-4.5 推理：高 ▾
 ☐ gemini-2.5-flash
```
- 推理档位 / 响应速度下拉**只列出该模型声明支持的值**（来自后端能力映射）；未声明则不显示控件，而不是灰掉。
- 重新拉取：显示 diff（新增 N / 下架 M），下架模型若被角色引用，标红并链到「模型角色」。
- 删除提供商：确认 Sheet 列出影响（“将同时删除 Key；以下角色会失效：摘要模型”）。

### 2.2 模型角色
```
模型角色
对话默认模型     gpt-5.1 · CPA-LA          ›
上下文摘要       跟随对话模型               ›
记忆抽取         ⚠ 已失效（模型已删除）      ›
自动审批审核人   未设置                     ›
```
- 每项点开为模型选择器（按提供商分组、搜索、只列启用模型），首项「跟随对话模型」（摘要/抽取支持；审核人不支持跟随，必须显式选，且不能等于对话模型——见后端 B3）。
- 失效项显示原因与「重新选择」。

### 2.3 搜索服务
```
搜索服务                作用于：工作区A ▾
模式   (•) 自动：优先模型原生搜索，失败回落第三方
       ( ) 仅第三方  ( ) 关闭
─────────────────────────────────────────
第三方服务（拖动排序，上方优先）
 ≡ Tavily      ● 已配置  本月 312 次   [›]
 ≡ Brave       ○ 未配置               [›]
 ≡ Exa / Bocha / SearXNG ...
网页抓取   本地抓取 ● 就绪（SSRF 防护开启）
```
- 服务详情：Key（`SecretField`）或 SearXNG 实例地址；「测试搜索」用固定查询返回前 3 条标题。
- 用量来自 `AndroidSearchUsageStore`，只读。
- 模式/排序是工作区级；Key/地址是全局，详情页标注“全局”。
- 删除 `SearchSettingsDialog.kt`（死代码）。
### 2.4 权限档位（按工作区）
```
权限档位                       作用于：工作区A ▾
┌ ( ) 只读 ──────────────────────────────┐
│ 只能读取工作区文件，任何写入/执行都被拒绝 │
└────────────────────────────────────────┘
┌ (•) 仅工作区 ───────────────────────────┐
│ 可在工作区内读写；越界与敏感操作逐次审批 │
└────────────────────────────────────────┘
┌ ( ) 完全权限 ⚠ ─────────────────────────┐
│ 当前工作区 + 设备存储；开放任意 Shell    │
│ 任意 Shell 无法证明隔离，仅此档开放      │
└────────────────────────────────────────┘
┌ ( ) 自动审批 ───────────────────────────┐
│ 由独立审核模型代为审批（需设置审核人）   │
│ 审核人：未设置  [选择]                   │
└────────────────────────────────────────┘
凭据读写：始终逐次审批（不受档位影响）
默认不跨工作区。
当前修订 r7 · 11-11 19:02 由你修改
```
- 语义严格对应已确认的权限契约（四档；完全权限 = 当前工作区 + 设备存储；自动审批 = 第三者模型代审；默认不跨工作区）。
- 切到「完全权限」：危险确认 Sheet（列出新增能力 + 勾选“我了解风险” + 主按钮 3 秒后可点）。
- 切到「自动审批」而未设审核人：卡片内联展开审核人选择，未选不能保存（与后端 `require(reviewer != null)` 一致，前端先拦）。
- 保存后显示新 revision，告知“已生效，进行中的待审批请求将失效重审”（对应后端配置变更即失效 grant）。

### 2.5 特权执行
```
特权执行
通道状态
 Shizuku     ● 已连接（shell, uid 2000）
 Root        ○ 未检测到        [检测]
─────────────────────────────────────────
 [ ] 允许 Root 通道            （需完全权限档）
 [ ] 允许高级命令（pm/am/settings 写）
```
- Shizuku 未安装/未运行/未授权三态各给一个主动作：「去安装」「打开 Shizuku」「请求授权」（调 `ShizukuChannel` 的 requestPermission）。
- 两个开关是**全局**，但生效还受工作区档位约束：当前工作区不是完全权限时开关下方提示“在本工作区不生效”。开启走危险确认 Sheet。依赖后端 B1。

### 2.6 凭据与审计
```
凭据与审计
凭据库          AES-GCM · Android Keystore   ● 正常
已存凭据        模型 3 · 搜索 2 · SSH 2 · MCP 1   ›
审计日志                                       ›
```
- **已存凭据**：只列命名空间 / 归属 / 更新时间 / 最后使用时间，永不显示明文；动作仅「清除」。
- **审计日志**：基于 `AuditLog.query(AuditFilter)`。筛选：工作区、时间、类型（审批/凭据/设置变更/执行）、结果（允许/拒绝）。行：时间 · 动作 · 目标 · 决策者（你/审核模型/策略）· 结果。点开看完整事件 JSON（脱敏）。只读，可导出到 `/sdcard/Documents/`（导出本身写审计）。

### 2.7 SSH 主机
**列表**：名称 · user@host:port · 认证方式（密码/私钥）· 指纹状态（已固定/未固定）· 最后连接。
**编辑页**
```
名称      la-vps
主机      192.227.187.222     端口 22
用户      root
认证      (•) 私钥  ( ) 密码
私钥      已设置 [更换] [从文件导入]
口令      [SecretField]
主机指纹  SHA256:ab12…   ● 已固定   [重新探测]
          [测试连接]  ● 成功 · 182ms
```
- 新增主机保存前**必须**先「探测」（`ssh_probe`）拿到指纹并让用户确认后才固定；指纹变化时连接失败并提示，要用户在此页确认更新（不静默接受）。
- 私钥支持 SAF 文件导入；导入后立即入凭据库，不在内存外留副本。依赖后端 B2。

### 2.8 Termux / 屏幕无障碍 / 离屏浏览器（统一“就绪向导”）
三者共用 `ReadinessStepper` 组件：
```
Termux 本地终端
● 需要授权
 ✓ 1 安装 Termux（F-Droid 版）
 ✗ 2 授予 CordiAct「运行 Termux 命令」权限   [授权]
 ○ 3 在 termux.properties 开启 allow-external-apps  [复制命令]
 ○ 4 连通测试                               [测试]
─────────────────────────────────────────
 [启用此能力]（全部步骤通过前不可开）
```
- 每步状态来自后端 `ReadinessProbe`（B4），不是前端猜。返回 App 时（onResume）自动重测。
- 屏幕无障碍：步骤 = 打开系统无障碍设置（Intent 跳转）→ 服务已连接 → 测试读取当前窗口。附隐私说明：读取屏幕内容的范围与留存。
- 离屏浏览器：通常直接就绪；展示 WebView 版本、会话池占用；可选设置 UA、单页超时。

### 2.9 MCP 服务器
```
MCP 服务器                          [+ 添加]
 github-mcp    ● 已连接  14 个工具   [启用 ●]
 https://…/mcp  鉴权：Header Authorization
```
- 添加：名称、URL（仅 https，http 仅限 localhost）、鉴权（无 / 自定义 Header：名称 + `SecretField` 值）→「连接并列出工具」→ 显示工具清单（名称/描述），确认后安装。
- 详情：工具清单（只读）、重新连接、删除（同时删凭据）。依赖后端 B5。

### 2.10 能力开关（按工作区）
```
能力开关                       作用于：工作区A ▾
 联网搜索       [●]
 SSH           [●]   依赖：至少 1 台主机
 Termux        [○]   依赖：Termux 就绪（未就绪）›
 屏幕自动化      [○]   依赖：无障碍
 离屏浏览器      [●]
 特权命令        [●]   依赖：Shizuku/Root
 MCP: github   [●]
```
- 关闭 = 该工作区的模型**看不到**这些工具（后端 B6），不是调用时报错。
- 依赖未满足的项开关禁用，副标题点击跳到对应向导。

### 2.11 附件限制
```
附件限制（默认值 = AttachmentLimits 安全上限）
每条消息附件数   10
图片            单张 10 MB · 20 MP · 长边 8192
PDF             10 MB · 100 页
文本            单个 256 KB · 合计 512 KB
请求体          原始 16 MB / 编码后 24 MB（只读）
───────────────────────────
当前对话模型实际上限：图片 8 张（取二者较小）
[恢复默认]
```
- 用户值只能**下调**，不能超过 `AttachmentLimits` 默认值；请求体上限只读展示。“实际上限” = min(用户值, 当前模型能力)。依赖后端 B8。

---

## 3. 通用组件与视觉

### 3.1 状态语义（全局统一）
| 状态 | 点色（取自 ExperienceTheme） | 文案前缀 | 含义 |
|---|---|---|---|
| READY | secondary `#54655D` 实心 | 就绪/正常 | 可用 |
| NEEDS_ACTION | tertiary `#99682E` 实心 | 需要… | 用户做一步即可 |
| DISABLED | outline `#8A8F9D` 空心 | 已关闭 | 用户主动关 |
| UNAVAILABLE | outline 空心 + 斜线 | 不可用 | 设备不支持/未安装且不可装 |
| ERROR | error `#AD4544` 实心 | 错误 | 曾可用但失败（401、指纹变化、损坏） |
- 颜色从不单独表意：点 + 文案同时出现（色弱/TalkBack）。

### 3.2 组件清单（放 `experience/settings/components/`）
| 组件 | 说明 |
|---|---|
| `SettingsGroup` / `SettingsRow` | 分组标题 + 行（图标/标题/摘要/状态点/›）；整行 ≥56dp 可点 |
| `StatusChip` | §3.1 状态 |
| `SecretField` | 未设置：普通密码输入；已设置：显示“已设置 · 更新于 X”+「更换」「清除」，**从不回显**；粘贴后自动去首尾空白；禁止截屏（FLAG_SECURE 仅在该字段聚焦时） |
| `ScopeBar` | 「作用于：工作区 ▾」，工作区级页面顶栏 |
| `RadioCard` | 权限档位/类型选择，大卡片单选 |
| `ReadinessStepper` | 就绪向导，步骤状态来自后端 probe |
| `DangerConfirmSheet` | 危险操作：影响清单 + 勾选确认 + 主按钮延迟 3s 可用 |
| `TestActionButton` | 「测试连接」：进行中 spinner，结果内联（成功含耗时，失败含原因码映射文案） |
| `ModelPicker` | 分组+搜索+“跟随对话模型”可选首项 |
| `EmptyState` | 自绘线稿 + 一句话 + 主按钮 |
| `UnsavedChangesGuard` | 表单离开拦截 |

### 3.3 新增自绘图标（扩展 `GlyphName`）
`Key, Shield, Server, Plug, Model, Accessibility, Audit, Bolt, Attachment, Drag, Refresh, Info`——沿用 `AppGlyph.kt` 的 Path 风格（24 视口、1.75 描边、圆角端点）。

### 3.4 版式
- 页面背景 `background`，分组卡片 `surfaceContainerLowest`，圆角 16dp，组间距 20dp，行内边距 16/14dp。
- 标题 `titleMedium`，摘要 `bodySmall` + `onSurfaceVariant`；危险文字只在确认 Sheet 用 `error`。
- 双栏阈值 600dp：左栏 360dp 固定，右栏自适应；单栏最大内容宽 640dp 居中。

### 3.5 交互规则
- **即时生效**：开关、单选（模式、档位除外）→ 立即写后端，Snackbar「已更新 · 撤销」（5s，撤销调回原值）。
- **显式保存**：含凭据或多字段的表单（提供商、SSH、MCP、权限档位）→ 底部固定「保存」，校验不过按钮禁用并标出字段。
- **危险操作**：完全权限、Root、高级命令、删除提供商/主机/MCP → `DangerConfirmSheet`。
- 所有写操作在 ViewModel 的 IO 协程执行；进行中禁用重复提交；失败保留输入、内联显示后端原因。
- 返回 App（onResume）时重测就绪状态；后台不轮询。
- 无障碍：每个开关 `contentDescription` 含状态与依赖；状态点有 `stateDescription`；字体放大到 200% 不截断（行高自增）。

---

## 4. 后端整改（为 UI 服务；不保留旧兼容）

> 原则：UI 只读“状态 + 元数据”，不读秘密；每个设置写入都有 revision 和审计；旧格式直接迁移或丢弃，不写双读。

| # | 优先级 | 问题（已核实位置） | 改法 | 破坏性 |
|---|---|---|---|---|
| **B1** | P0 | `PrivilegeService` 的 `rootEnabled/advancedCommandsEnabled` 无存储，构造时固定 false（`AutomationRuntime.kt:53`） | 新 `PrivilegeSettingsStore`（带 revision），`PrivilegeService` 每次调用读当前值；开启 Root 额外要求工作区档位=FULL_ACCESS（与“任意 Shell 仅完全权限开放”一致）；变更写审计并使在途 grant 失效 | 改构造签名 |
| **B2** | P0 | `SshHostStore` 把 password/privateKey/passphrase 明文写 `filesDir/ssh_hosts.json`；读写异常 `catch (_: Exception)` 吞掉，损坏=空列表 | 主机档案（无秘密）与凭据分离：秘密进凭据库命名空间 `ssh`；文件损坏 **fail-closed** 报 ERROR 状态，不当成空；首次启动一次性迁移后**删除**旧 JSON（不保留回退读）；新增主机必须先 probe 固定指纹 | 删除旧文件格式 |
| **B3** | P1 | `ReviewerConfig` 在 `SecurityProfileStore` 里以 6 个散 key 存 endpoint 快照，和提供商配置会漂移 | 改存 `(providerId, modelId)` 引用，运行时解析；引用失效 → 该工作区自动审批状态 ERROR、请求退回逐次审批（fail-closed）；禁止审核人=当前对话模型 | 旧 reviewer 数据丢弃，需重选 |
| **B4** | P0 | 无统一就绪查询：Termux 靠错误串、无障碍无“已开启”查询、Shizuku 状态散落 | 定义 `ReadinessProbe { fun probe(): Readiness }`，`Readiness(state, summary, reasonCode, steps: List<ReadinessStep>, actions)`；Termux/Shizuku/Root/无障碍/浏览器/SSH/MCP/提供商/搜索各实现一个；`ReadinessRegistry.all()` 给首页横幅 | 新接口 |
| **B5** | P1 | MCP 只能由 LLM 工具 `addMcp` 安装；`McpCredential` 无持久化 | `installMcpServer(config, credential?)`：凭据入命名空间 `mcp`；安装前必须 `connect + listTools` 成功；安装/删除写审计；保留 LLM 工具但改为“提议安装→UI 确认”（经 ApprovalGateway） | 改签名 |
| **B6** | P0 | `AutomationRuntime.registerTools` 对 privilege/screen/browser/ssh/termux 无条件注册（`:109-121`） | 内置能力也纳入 `PluginInventory`，每工作区一份启用表；`ToolManager` 按工作区可见性过滤，**禁用即不进模型工具列表**；依赖未就绪的能力强制不可见 | 行为变化 |
| **B7** | P1 | `discoverAndAddProvider` 一次写入全部发现的模型；`refreshModels` 会覆盖列表，用户挑选丢失 | UI 用 `ModelDiscoveryService.discover` 只发现不写；新增 `commitProvider(connection, key, selectedModels)`；配置拆 `discoveredModels` 与 `enabledModels`，`refreshModels` 返回 diff 只更新 discovered；删除 `discoverAndAddProvider` | 删 API、配置格式升级 |
| **B8** | P2 | `AttachmentLimits()` 默认构造，不可配 | `AttachmentPolicyProvider`：effective = min(持久化用户值, 默认安全上限, 当前模型能力)；用户值只能下调 | 新组件 |
| **B9** | P1 | 摘要/抽取模型选择只存裸 ID，提供商删除后悬空 | 读取时校验引用；失效返回 `Invalid(reason)` 而非静默回落；新增“跟随对话模型”显式值 | 存储格式升级 |
| **B10** | P2 | 搜索：策略只有 mode，无第三方顺序/启用；SearXNG 地址散在 `AndroidSearchProviderSettings`；`SearchSettingsDialog.kt` 死代码 | 策略增加 `providerOrder`、`enabled`；地址归入统一 provider 配置；删除死代码 | 格式升级 |
| **B11** | P1 | 凭据/设置读写分散，UI 有读到明文的可能（`AndroidProviderApiKeyStore.read` 返回明文） | 给 UI 的门面只暴露 `CredentialMeta(namespace, owner, isSet, updatedAt, lastUsedAt)`；明文读取只留给执行器 | 门面新增 |
| **B12** | P1 | 设置变更未统一审计 | 所有 `Settings*Store` 写入经 `SettingsWriter`：校验 → 写 → revision+1 → 审计 → 通知订阅者（Flow） | 新层 |

### 4.1 给 UI 的统一门面（建议）
```kotlin
interface SettingsFacade {
    val readiness: StateFlow<List<Readiness>>          // B4
    fun providers(): Flow<List<ProviderSummary>>       // 无秘密
    suspend fun discover(conn: ModelProviderConnection, key: String): DiscoveryResult
    suspend fun commitProvider(conn: ModelProviderConnection, key: String, models: List<String>)
    suspend fun setPermission(workspaceId: String, preset: PermissionPreset, reviewer: ModelRef?): Revision
    suspend fun setPrivilege(root: Boolean, advanced: Boolean): Revision
    fun credentials(): Flow<List<CredentialMeta>>       // B11
    suspend fun clearCredential(ns: String, owner: String)
    // ssh / mcp / search / capabilities / attachments / roles 同构
}
```
ViewModel 只依赖 `SettingsFacade`，便于 JVM 单测（沿用批一“纯逻辑 + 测试”模式）。

---

## 5. 实施分批

| 批 | 内容 | 后端前置 | 验收（可检查） |
|---|---|---|---|
| **2a 骨架+模型** | SettingsHost、导航、通用组件、首页（含就绪横幅）、模型提供商全流程、模型角色、侧栏入口替换 toast | B4（先接提供商/搜索两个 probe）、B7、B9、B11 | 新增→选模型→保存→重启仍在；重新拉取显示 diff 且不丢勾选；删除被引用模型后角色页显示失效；任何界面不出现明文 Key |
| **2b 安全** | 权限档位、特权执行、凭据与审计（含审计查看） | B1、B3、B12 | 切完全权限需确认且 revision+1；自动审批无审核人无法保存；Root 开关在非完全权限工作区标注“不生效”且实际执行被拒；每次变更出现在审计页 |
| **2c 连接** | SSH、Termux/无障碍/浏览器就绪向导、能力开关 | B2、B4 全量、B6 | 旧 `ssh_hosts.json` 迁移后被删除、秘密只在凭据库；指纹变化连接被拒并提示；关闭能力后该工作区模型工具列表中消失（单测断言）；离开 App 授权回来自动刷新 |
| **2d 扩展+数据** | MCP、搜索服务、附件限制 | B5、B8、B10 | MCP 不连通不能安装；搜索排序生效（单测）；附件上限不能调高于默认值 |

每批：JVM 单测（ViewModel/门面）+ Compose 预览 + CI Android build；APK 由 GitHub Actions 出包后真机验收。

## 6. 待你决定

1. **Root 开关是否必须绑定“完全权限”档**（本设计默认绑定）。
2. **自动审批档**是否允许开启 Root/高级命令（本设计：不允许，仅完全权限）。
3. B2 迁移后**直接删除旧 SSH 文件**、B3 **丢弃旧 reviewer 配置**需要重选——按“不强行兼容”默认这么做。
4. 未跟踪原型 `ui/settings/SettingsScreen.kt` 与 `ui/` 目录：实现完成后是否删除。

## 7. 核实说明
- 已核实（读源码）：§0 表格各项、`PermissionPreset` 四档、`setPreset` 的 reviewer 约束、`AutomationRuntime` 无条件注册、SSH 明文与吞异常、`AuditLog.query`、`ModelDiscoveryService` 独立存在、`ModelProviderRegistry` 现有 API、`AttachmentLimits` 默认值、主题色值、`GlyphName` 现有 23 个图标。
- 推断/建议：B 系列具体接口形态、各模型能力元数据能否拿到（上下文长度、图像支持取决于提供商 `/models` 返回）、Shizuku `requestPermission` 的具体调用点、Termux 就绪四步的检测手段。
