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
- 迁移：`importLegacyAuthored` 把已有的 Agent 编写插件（schemaVersion 1/2）显式提升为 `0.0.1` 包，保留工具和摘要，不凭空生成命令；这是用户触发的迁移，不自动替换会话试用。

存储是 `ExtensionPackageStore` 端口；`InMemoryExtensionPackageStore` 供 JVM 测试。Android 持久化适配器仍需绑定工作区键并实现原子写入。

## 贡献装配

[DeclarativePackagePlugin](../../interaction/src/main/kotlin/com/cordiact/interaction/DeclarativePackagePlugin.kt) 把清单投影成普通 `ContextPlugin`：

- 插件 ID 为 `pkg.<包 id>`；工具 ID 为 `extension.pkg.<包 id>.<工具名>`，命令 ID 同理。
- 工具和命令都通过现有 `PluginToolContributions` / `PluginCommandContributions` 发布，继承作用域、代际撤销和审批边界。
- 启动时再次核对能力同意；缺少任何能力则整个启动失败，已注册的一半由内核 scope 回滚。
- `PluginSpec.requestedCapabilities` 记录声明能力，`config` 记录清单摘要；宿主据此显示清单状态。

真实 App 装配由 [BundledDeclarativePackages](../../app/src/main/java/com/cordiact/app/plugin/BundledDeclarativePackages.kt) 提供：`core-quick-notes` 同时发布 `checklist` 工具和 `/release-checklist` 命令，并通过 [BundledConversationPluginCatalog](../../app/src/main/java/com/cordiact/app/conversation/BundledConversationPluginCatalog.kt) 的受信 spec 白名单注册。两种贡献因此走同一条真实生命周期，而不是只存在于测试夹具。

## 当前边界

- 只有内置样例完成了 App 生命周期装配；用户包的安装/升级/回滚服务与界面尚未接到设置页或 `plugin_manager`。
- 没有签名、远程索引、版本解析或市场；包格式只服务宿主验证过的本地内容。
- `text_transform` 仍是闭集，不支持模板、脚本、网络、文件或第二次工具调用。
- Android 的 `ExtensionPackageStore` 适配器、进程重启恢复和跨工作区实例尚未实现。

## 验收

JVM 已覆盖：

- 合法清单解析、摘要、能力与贡献一致性。
- 未知 schema/能力/宿主版本、尾随内容、重复工具/命令/别名拒绝。
- 安装、同版本冲突、升级、增量能力同意、回滚、撤包。
- 旧 Agent 插件迁移。
- 工具和命令在同一工作区发布、禁用后同时撤回、缺少能力时不留下半套贡献。

运行：

```sh
sh .cordiact-dev/gradle.sh jvm :core:test --tests 'com.cordiact.core.plugin.ExtensionPackageTest'
sh .cordiact-dev/gradle.sh jvm :interaction:test --tests 'com.cordiact.interaction.DeclarativePackagePluginTest'
```
