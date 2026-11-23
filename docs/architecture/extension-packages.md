# 声明式扩展包（P2）

P2 的目标不是把 npm 或 Cordis 搬进 APK，而是让**不执行第三方代码**的扩展也能被安装、升级、撤回和迁移。一个包只声明宿主已经实现的有界贡献：固定文本工具、文本变换工具和固定文本斜杠命令。包本身没有解释器、文件、网络或命令执行能力。

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

从当前工作区「更多操作 → 插件」进入[独立页面](../../app/src/main/java/com/cordiact/app/experience/WorkspacePluginsScreen.kt)。通过系统文件选择器选取一个 JSON，预览精确版本、工具行为、命令与所需能力后才能确认安装。只读工作区可以预览，不能写入。宿主仅读取选中文件，最多 32 KiB + 1 字节，不留存 URI、不扫描目录。确认在退出页面、退出前台或工作区权限写入后失效，并在 SQLite 提交点复核。

- 页面区分安装记录、工作区启用意图和实际运行状态。安装、更换或回滚本身不会运行包；用户可显式启停。进程重建后，只在首次进入对应工作区且当前权限允许时恢复已选择启用的代；会话试用不恢复。
- 先停用并完成清理，才能更换、回滚或移除；不隐式启用其他版本。启动失败不发布贡献，确认重试只作用于同一已启用世代。清理失败会先关闭准入、再等待重试，不能视为可移除。权限收紧时撤回贡献，可在只读状态下清除启用意图。
- Agent 创作的插件可先在会话插件中保存批准快照，再从工作区插件页选择「从已保存试用版本转包」。只取通过校验的指定快照，不读草稿；预览显示新旧工具 ID，确认前重读校验所选世代。转包为新安装，不改变原快照或正在运行的会话试用，也不带入启用权。
- 没有签名、远程索引、版本解析或市场；`text_transform` 不支持模板、脚本、网络、文件或第二次工具调用。
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
