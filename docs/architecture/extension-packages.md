# 声明式扩展包

扩展包支持安装、升级、撤回和迁移。可贡献固定文本工具、文本变换工具、固定文本斜杠命令、客户端说明页、会话动作和工作台面板。v4 还可在隔离进程中执行文本工具脚本。

## 清单格式

```json
{
  "schemaVersion": 1,
  "id": "quick-notes",
  "version": "1.0.0",
  "title": "Quick notes",
  "description": "Small declarative extensions",
  "host": { "api": 1, "minVersion": "0.1.0" },
  "capabilities": ["tool.publish", "command.publish"],
  "tools": [
    { "name": "checklist", "description": "Show the checklist", "kind": "static_text", "text": "run tests" }
  ],
  "commands": [
    { "name": "release-checklist", "aliases": ["checklist"], "description": "Show the checklist", "text": "run tests" }
  ]
}
```

约束由 [ExtensionPackageSource](../../core/src/main/kotlin/com/cordiact/core/plugin/ExtensionPackage.kt) 在读取时执行：

- 源码 32 KiB；UTF-8、无 BOM、严格 JSON、拒绝未知字段与尾随内容。
- `id`、工具名、命令名和别名都是 2–24 位小写 slug；版本必须是 `major.minor.patch`。
- 最多 4 个工具、4 个命令；每个固定文本 4 KiB，标题 120 字节，说明 480 字节。
- 工具只允许 `static_text` 和 `text_transform`（trim/uppercase/lowercase）；命令只返回固定文本。
- `capabilities` 必须与贡献精确匹配：有工具才有 `tool.publish`，有命令才有 `command.publish`；未知能力拒绝。
- `host.api` 必须等于宿主的 API 版本；`host.minVersion` 必须不大于宿主版本；不满足时拒绝，不静默降级。
- `sha256` 覆盖原始字节，安装记录和运行 spec 都绑定同一摘要。

### 清单 v2：客户端贡献

v2 在原有字段之外要求 `client`，其中至少有 `settingsPage` 或 `sessionActions`。宿主 API 仍为 1；不认识 v2 的旧宿主会拒绝，不会丢弃客户端声明后按 v1 安装。

```json
{
  "schemaVersion": 2,
  "id": "release-preflight",
  "version": "1.0.0",
  "title": "发布前检查",
  "description": "不需要网络或模型的本地检查清单",
  "host": { "api": 1, "minVersion": "0.1.0" },
  "capabilities": ["command.publish", "ui.settings.publish", "ui.session-action.publish"],
  "tools": [],
  "commands": [
    { "name": "release-preflight", "aliases": [], "description": "显示发布前检查清单", "text": "运行测试\n核对版本\n确认变更说明" }
  ],
  "client": {
    "settingsPage": {
      "title": "发布前检查",
      "description": "使用会话动作把本地清单命令填入草稿。",
      "rows": [{ "label": "网络", "value": "不需要" }]
    },
    "sessionActions": [
      { "id": "insert", "label": "填入发布前检查命令", "command": "release-preflight" }
    ]
  }
}
```

- 设置页为纯文本标题、说明和可选的最多四个 `label` / `value` 行。仅提供说明页的包可将 `tools` 与 `commands` 都设为空数组。
- 会话动作若声明，须有 1–2 项。`id` 是包内唯一 slug，`command` 必须正好是同包 `commands` 中的主名称，不含 `/`、不接受别名。`title` / `label` 限 120 UTF-8 字节，`description` / `value` 限 480 字节，均为非空单行纯文本。
- `ui.settings.publish` 与 `ui.session-action.publish` 各自与对应声明严格双向匹配。它们不授予设置写入、命令执行、权限、联网或发消息能力。会话动作只提供填入裸命令的能力；执行仍由用户独立发送并走宿主授权。示例刻意不用内置包已注册的 `/release-checklist`；重叠作用域的名称冲突不得覆盖。
- Android 设置的「插件与连接」显示当前设置工作区的已发布说明页；「标签总览 → 全部会话 → 当前会话操作」显示已发布会话动作。安装预览不等于运行时发布，入口随停用撤回。可直接导入 [发布前检查](../testing/fixtures/extension-packages/release-preflight.json) 和 [会话交接](../testing/fixtures/extension-packages/session-notes.json)；真机流程以[验收手册](../testing/workspace-extension-packages.md)为准。

### 清单 v3 工作台面板

v3 要求 `client.workbenchPanel` 和 `ui.workbench-panel.publish`，可与 v2 的客户端贡献组合。面板复用说明页的 `title`、`description` 和最多四项 `rows` 文本格式。兼容 v1、v2 的客户端可继续导入对应版本的包。

导入并启用[发布工作台示例](../testing/fixtures/extension-packages/release-panel.json)后，从「标签总览 → 工作台布局」为面板选择标签页、侧栏或隐藏。在宽屏横向工作台可使用侧栏，窄屏使用标签页。停用包会撤回面板，同 ID 的布局设置保留供重新启用时使用。布局 JSON 只携带面板引用与外观设置，安装和启用仍在工作区插件页完成。

### 清单 v4 隔离脚本工具

v4 要求至少一个 `kind: "text_script"` 工具及 `tool.publish`、`tool.script.execute`。`source` 是求值后得到同步函数的 JavaScript 表达式，输入与返回值均为字符串。包可同时声明现有命令和客户端贡献。导入及批准保存只解析包，显式启用后的工具调用才交给 Android 10+ 隔离进程执行。

每次调用使用独立进程，源码最多 16 KiB，输入和输出各最多 4 KiB UTF-8，解释器执行期限为 2 秒。JavaScript 可使用标准语言内建，工具承载只提供本次文本参数。停用会撤回注册并关闭属于该安装代的在途调用。安装和运行时限见 [Sandbox ADR](../adr/0002-sandbox-manager-design.md)。可导入[行数统计示例](../testing/fixtures/extension-packages/line-counter.json)，开启后在会话里请 Agent 调用 `count-lines` 并传入要统计的文本。

[插件 SDK](../../plugin-sdk/README.md)提供 TypeScript 示例、独立项目安装方法和本地打包命令。

### 清单 v5 技能和参考上下文

v5 要求 `agent` 中至少一个贡献。`agent.skills` 支持最多四个带 `name`、`description`、`body` 的条目，并要求 `agent.skill.publish`。技能通过 `<包 ID>.<技能名>` 加入当前工作区的现有索引，用 `skill.open` 按需读取。技能正文每项最多 4 KiB。

`agent.context` 是最多 1 KiB 的 UTF-8 参考文本，要求 `agent.context.publish`。启用后，随后开始的普通前台对话按预算读取参考文本，附上包 ID、版本和不可信数据标记。消息下方的「插件上下文」显示该次请求实际纳入的来源。停用即撤回技能和后续上下文。v5 可组合现有客户端和工具贡献。可导入[变更审阅示例](../testing/fixtures/extension-packages/review-kit.json)验证。

### 清单 v6 工作区搜索服务

v6 要求 `provider.search.publish` 和 `providers.search` 中的一到两个服务。每项声明 `name`、`title` 和 `source`，注册 ID 为 `pkg.<包 ID>.<服务名>`。脚本在 Android 10+ 隔离进程中接收 `{query, maxResults}` 的 JSON 字符串，并返回含 `sources` 数组和可选 `content` 的 JSON 字符串。每个来源包含 HTTP(S) `url`，可带 `title` 和 `snippet`。SDK 的 `SearchProvider` 类型和打包器负责将 TypeScript 对象签名转换为此接口。

启用包后，在当前工作区的搜索策略勾选该服务、调整优先级并保存。诊断测试与 `search.web` 走同一注册表。插件只收到本次查询和结果上限，旧安装代的结果在撤回后失效。可直接导入[参考资料索引示例](../testing/fixtures/extension-packages/reference-search.json)。该示例查询随插件附带的数据，由 [SDK 示例源码](../../plugin-sdk/examples/reference-search.ts)生成，使用隔离运行时的 4 KiB 输入输出限制。

### 清单 v7 工作区模型服务

v7 要求 `provider.model.publish` 和 `providers.model` 中的一到两个声明。每项包含 `name`、`title`、`protocol`、`endpoint` 和一到 32 个不同的 `models`。协议可为 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 或 Gemini Generate Content。端点使用 HTTPS，URL 不携带账号、查询参数或片段。v7 可组合已有搜索、工具、Agent 和客户端贡献。

启用包后，在「设置 → 模型 → 提供商声明」选择带插件来源的声明。用户输入本次 API 密钥，分别批准外连和密钥绑定。宿主探测模型清单并与包声明取交集，再由用户授权具体模型。获批模型进入本工作区的模型选择器，宿主负责协议、凭据和流式传输。停用或升级会撤回该安装代授权并取消在途请求。应用进程重启后需要重新接入。SDK 中的 [OpenAI 模型声明示例](../../plugin-sdk/examples/openai-model.json)可用相同打包命令生成制品。

### 清单 v8 工具前策略

v8 要求 `agent.tool-policy.publish` 和 `agent.beforeTool` 中的一到四条策略。每条声明 `name`、一到 16 个精确工具 ID `tools` 和 `source`。工具管理器完成基础授权检查后，在请求宿主审批前让匹配策略判定。Android 10+ 隔离进程只收到 `{toolId, suppliedParameters, argumentCount}`，其中 `suppliedParameters` 只列出本次提供的已声明参数名。返回 `block` 时拦截调用，返回 `continue` 时继续宿主授权流程。策略异常或返回无效决定时，当前调用被拦截。

等待期间发生策略增删或换代时，宿主拒绝旧调用，重新发起才会按当前策略判定。同一工作区的同一工具最多登记四条策略。拒绝信息会注明包版本和策略名。可编译 [网页抓取限制示例](../../plugin-sdk/examples/web-fetch-policy.ts)，安装并启用后尝试 `search.fetch`，停用包后再按原宿主策略调用。

### 清单 v9 工作流

v9 要求 `workflow.publish` 和 `workflows` 中的一到四个工作流。每个工作流包含一个名称、标题和一到四个隔离脚本步骤。用户在工作区插件页点击「运行」，输入文本并确认后，工作流以 `PLUGIN_WORKFLOW` Job 启动。后续步骤依次接收上一步的 UTF-8 文本输出。输入和输出各限 4 KiB。用户可以在插件页查看当前会话的工作流进度、读取结果或取消运行。插件停用会取消该安装代的工作流。进程重启会将未结束的任务标为已中断。可用 SDK 打包[文本清理示例](../../plugin-sdk/examples/normalize-workflow.json)。

### 清单 v10 子 Agent 预设

声明 `agent.subagents` 时需同时声明 `agent.subagent-preset.publish`。每包可提供一到四项。每项包含 `name`、`title`、`instructions` 和最多 16 个精确工具 ID `tools`。空工具列表运行纯文本子 Agent。启用后，`list_children` 返回当前工作区内的预设 ID 和包版本。用 `delegate_task` 的可选 `preset` 字段选择，例如 `review-subagent.review`。预设沿用父回合模型，只使用父回合和子会话都能调用的同一代工具注册。子会话历史从回合记录显示预设 ID 和包版本。停用或替换时，宿主会撤回工具调用和新模型回合，请求取消在途模型调用，并丢弃旧代预设返回给父回合的结果。

### 清单 v11 会话视图

`client.conversation` 需同时声明 `ui.conversation.publish`。`messages` 可订阅 `user.message` 和 `assistant.message`，每个事件最多声明一个附加消息节点。`toolResults` 按 `tool` 声明本包工具的附加结果卡。每项需提供 `title` 及一到四行 `lines`。只有宿主登记的成功调用结果，且记录中的工具 ID、发布者与制品摘要都匹配当前启用包时，才显示该结果卡。

宿主从实时会话和历史记录投影这些节点，同时保留原消息与工具摘要。消息节点根据当前工作区启用的包展示，工具结果卡绑定精确制品。插件停用后附加节点撤回，换版时旧制品的结果回退到原摘要。此投影使用事件类型与宿主记录的身份字段，可用 SDK 打包[会话检查节点示例](../../plugin-sdk/examples/conversation-views.json)。

### 清单 v12 回合准入

在 `agent.beforeTurn` 中声明一个带 `name` 和 `source` 的策略，并请求 `agent.turn-policy.publish`。每个工作区最多启用四条策略，宿主按包 ID 顺序执行。用户在前台提交模型回合时，宿主在消息入库前用隔离脚本检查 `{hasText, attachmentCount}`。脚本返回 `continue` 或 `block`，看不到正文、附件内容或会话标识。拦截、脚本错误或执行期间策略换代会拒绝本次提交。成功准入后，宿主照常执行模型与工具授权。后台唤醒、子 Agent 和本地命令保留原有路径。

### 清单 v13 工作台交互

`client.workbenchPanel.controls` 支持一到四个可展开区块，每项声明 `id`、`label`、`content` 和可选的 `initiallyExpanded`。包同时请求 `ui.workbench-panel.publish` 和 `ui.workbench-controls.publish`。可选的 `action` 引用同包 `sessionActions` 中的 ID，点击后将该动作的命令填入当前会话的空草稿，并切到会话供用户查看和发送。展开状态在同一个工作区的标签页和停靠面板间共享，重新启用时恢复声明默认值。使用 [SDK 示例](../../plugin-sdk/examples/interactive-workbench.json) 可直接打包。

### 清单 v14 会话组件

`client.components` 可为 `session.toolbar` 和 `composer.accessories` 各贡献一个菜单。使用 `text` 显示说明，使用 `label` 和 `children` 构建可展开组，或用 `action` 引用同包会话动作。每个菜单最多十二个节点、三层。工作区每个插槽最多同时接收两个扩展包组件。宿主按当前会话渲染，切换会话或停用时撤回旧菜单。命令按钮填入空草稿并返回会话，由用户确认是否发送。打包入口见 [SDK 示例](../../plugin-sdk/examples/session-components.json)。

## 安装、升级、回滚、迁移

[ExtensionPackageInstaller](../../core/src/main/kotlin/com/cordiact/core/plugin/ExtensionPackageInstaller.kt) 只记录已验证的清单，不激活贡献：

- 首次安装：能力集必须已被授予，否则返回 `ConsentRequired`，不写存储。
- 升级：版本必须更高；同版本不同字节是 `CONFLICT`；旧版本保留为**一个**回滚槽。
- 升级新增能力：即使旧版本已启用，也必须重新同意完整的新能力集；未同意时旧版本保持运行。
- 回滚：恢复唯一保留的上一版本，并消费该槽；没有上一版本时拒绝。
- 撤包：删除记录；已发布贡献的撤销由插件内核完成，不由安装器假装完成。
- 迁移：`inspectLegacy` 预览 Agent 编写插件（schemaVersion 1/2）的 `0.0.1` 包，再走同一个安装入口。保留原字节、工具和摘要，不生成命令，不自动替换会话试用。

安装确认使用预览得到的不可变源码快照；升级、回滚和删除还须绑定所见安装世代。首次安装要求目标不存在。比较世代、写入和工作区配额（32 个包）在一个存储事务中完成；重装相同字节也不会复用旧确认。若提交结果未知，先刷新清单，不自动重试。

[Android SQLite 适配](../../app/src/main/java/com/cordiact/app/plugin/AndroidExtensionPackageStore.kt) 在不参与系统备份的应用私有目录保存当前和上一个版本的原字节、摘要及格式；重新打开时严格解码，校验工作区查询键、包 ID 和摘要。坏记录不能当作不存在并被覆盖。记录表示安装时已批准声明的贡献，不代表启用意图、运行状态或 Android 权限。

## 贡献装配

[DeclarativePackagePlugin](../../interaction/src/main/kotlin/com/cordiact/interaction/DeclarativePackagePlugin.kt) 把清单投影成普通 `ContextPlugin`：

- 插件 ID 为 `pkg.<包 id>`；工具 ID 为 `extension.pkg.<包 id>.<工具名>`，命令 ID 同理。
- 工具和命令都通过现有 `PluginToolContributions` / `PluginCommandContributions` 发布，继承作用域、代际撤销和审批边界。
- 构建运行实例时，宿主必须显式传入能力同意；清单中的请求不是授权。缺少任何能力则不启动；启动中出错时由内核 scope 撤回尚未发布的注册。
- 声明工具的 `PURE` 分类来自宿主生成的固定文本或有界变换执行器，保存在对应注册世代中。不按工具名前缀放行；同名替代执行器不继承分类，仍经过默认策略及既有执行准入。
- 声明命令的可见性由宿主校验的会话与内核实例决定，不使用按插件 ID 共享的命令开关。同一包在不同工作区运行时，停用其中一个不撤回另一个的命令。
- 在普通对话输入栏输入已启用声明包的裸 `/命令` 或别名，即使未配置模型也可执行。无模型路径不接收命令参数、引用或附件，只读取宿主为该注册世代保存的固定文本，不调用插件执行器或模型。授权后再次校验注册身份；停用期间收到的旧请求不会改走模型或新世代。
- `PluginSpec.requestedCapabilities` 记录声明能力，`config` 记录清单摘要；宿主据此显示清单状态。

真实 App 装配由 [BundledDeclarativePackages](../../app/src/main/java/com/cordiact/app/plugin/BundledDeclarativePackages.kt) 提供：`core-quick-notes` 同时发布 `checklist` 工具和 `/release-checklist` 命令，并通过 [BundledConversationPluginCatalog](../../app/src/main/java/com/cordiact/app/conversation/BundledConversationPluginCatalog.kt) 的受信 spec 白名单注册。两种贡献因此走同一条真实生命周期，而不是只存在于测试夹具。

## Android 工作区入口与当前边界

从「标签总览 → 设置 → 插件与连接 → 工作区插件包」进入[管理页](../../app/src/main/java/com/cordiact/app/experience/WorkspacePluginsScreen.kt)，仅管理设置中选定的工作区，不切换聊天。通过系统文件选择器选取一个 JSON，预览精确版本、工具行为、命令与所需能力后才能确认安装。只读工作区可以预览，不能写入。宿主仅读取选中文件，最多 32 KiB + 1 字节，不留存 URI、不扫描目录。确认在退出页面、退出前台或工作区权限写入后失效，并在 SQLite 提交点复核。

- 页面区分安装记录、工作区启用意图和实际运行状态。安装、更换或回滚本身不会运行包；用户可显式启停。进程重建后，只在首次进入对应工作区且当前权限允许时恢复已选择启用的代；会话试用不恢复。
- 先停用并完成清理，才能更换、回滚或移除；不隐式启用其他版本。启动失败不发布贡献，确认重试只作用于同一已启用世代。清理失败会先关闭准入、再等待重试，不能视为可移除。权限收紧时撤回贡献，可在只读状态下清除启用意图。
- Agent 创作的插件可先在会话插件中保存批准快照，再从工作区插件页选择「从已保存试用版本转包」。只取通过校验的指定快照，不读草稿；预览显示新旧工具 ID，确认前重读校验所选世代。转包为新安装，不改变原快照或正在运行的会话试用，也不带入启用权。
- 普通声明包可导出精确已安装版本的原始 JSON。使用系统保存器选位置并回到前台后二次确认；只读工作区、试用转包及受保护内置包不允许导出。只向本次 URI 写原字节，不保存选择、批准、运行状态、回滚或聊天；不读回、不重试未知结果。
- App 还没有签名下载、可信来源收据或市场；已有的离线核验层和待接入边界见[受控分发设计](signed-package-distribution.md)。`text_transform` 不支持模板、脚本、网络、文件或第二次工具调用。
- [Android SQLite 回归](../../app/src/test/java/com/cordiact/app/plugin/AndroidExtensionPackageStoreTest.kt)及[预览确认回归](../../app/src/test/java/com/cordiact/app/experience/ui/WorkspacePackagePromptTest.kt)待 Android CI 执行。[App 装配测试](../../app/src/test/java/com/cordiact/app/conversation/BundledConversationPluginCatalogTest.kt)用真实 Android 存储、工具管理器和命令分发验证安装、调用、隔离与撤回，不调用外部模型。真机验收流程见[工作区插件验收](../testing/workspace-extension-packages.md)。

## 验收

JVM 已覆盖：

- 合法清单解析、摘要、能力与贡献一致性。
- 未知 schema/能力/宿主版本、尾随内容、重复工具/命令/别名拒绝。
- 安装、同版本冲突、升级、增量能力同意、回滚、撤包。
- 旧 Agent 插件迁移。
- 工具和命令在同一工作区发布、禁用后同时撤回、缺少能力时不留下半套贡献；停用同包的另一个工作区实例不影响当前实例。
- 截获宿主生成的定义再用自定义执行器重注册，不继承 `PURE` 权限；旧绑定也不能调用新世代。覆盖真正的工具管理器执行准入，不仅检查工具目录。

运行：

```sh
sh .cordiact-dev/gradle.sh jvm :core:test --tests 'com.cordiact.core.plugin.ExtensionPackageTest'
sh .cordiact-dev/gradle.sh jvm :interaction:test --tests 'com.cordiact.interaction.DeclarativePackagePluginTest'
```
