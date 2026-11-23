# 工作区声明包：M1 验收

用同一次 Android CI 生成的 APK 和版本提交记录。以下是待执行的真机手册，不是通过报告。不要在非测试工作区或生产对话中操作。Android 测试分别覆盖 [Compose 导航入口](../../app/src/test/java/com/cordiact/app/experience/ui/ConversationSidebarTest.kt)、[预览确认](../../app/src/test/java/com/cordiact/app/experience/ui/WorkspacePackagePromptTest.kt)、[SQLite](../../app/src/test/java/com/cordiact/app/plugin/AndroidExtensionPackageStoreTest.kt)和 [真实 App 装配](../../app/src/test/java/com/cordiact/app/conversation/BundledConversationPluginCatalogTest.kt)。最后一项不调用外部模型；真机还要检查真正的前台对话和系统文件选择。

## 样例

将以下纯文本保存为 `checkpoint.json`，放到系统文件选择器可选的本地目录。不要把私人数据写入样例。

```json
{
  "schemaVersion": 1,
  "id": "checkpoint",
  "version": "1.0.0",
  "title": "工作区验收",
  "description": "返回固定的测试文本",
  "host": {"api": 1, "minVersion": "0.1.0"},
  "capabilities": ["tool.publish", "command.publish"],
  "tools": [{"name": "message", "description": "读取工具测试文本", "kind": "static_text", "text": "PACKAGE_TOOL_OK_V1"}],
  "commands": [{"name": "package-checkpoint", "aliases": ["pkgcheck"], "description": "读取命令测试文本", "text": "PACKAGE_COMMAND_OK_V1"}]
}
```

## 通过条件

| 步骤 | 用户操作及预期 |
|---|---|
| 预览与取消 | 进入工作区 A，在竖屏工作台顶部右侧或横屏标签条末端打开「… → 工作区插件 → 导入 JSON」。切换工作区期间不可打开旧工作区入口。系统选择取消不产生记录。选中样例可见版本、摘要、工具、命令和能力；关闭预览不安装。用无效/超限 JSON 验证不留记录。 |
| 分离安装与启用 | 确认安装后显示已安装、未启用；会话中不出现 `/package-checkpoint`、`/pkgcheck` 或工具。安装阶段不配置模型也可以完成。 |
| 实际调用 | 确认启用后显示运行中。选择测试模型，在普通前台会话调用 `extension.pkg.checkpoint.message`，核对工具轨迹实际返回 `PACKAGE_TOOL_OK_V1`（不要只依据模型文本）；另用未配置模型的环境输入裸 `/pkgcheck`，核对 `PACKAGE_COMMAND_OK_V1`；普通消息、非本地命令、带参数的命令和含附件草稿不能通过无模型入口。 |
| 作用域 | A 的另一个活跃用户会话可用；切换至工作区 B，不出现相同工具和命令。可以在 B 独立装一个相同 ID 的版本并启用；停用 A 不改变 B。 |
| 恢复与撤回 | App 进程重建后，只有首次进入显式启用过的工作区才恢复其包；其他工作区不会因启动 App 而恢复。停用 A 后，补全及下一轮工具列表不再提供贡献；重启后仍不恢复。已有对话不删除。 |
| 版本更换 | 修改样例版本为 `1.1.0`，将命令/工具输出改到 V2。运行时不能直接更换；停用并清理后，取消确认保留 V1。确认更换后先看到 V2 未启用；可回滚到 V1，回滚不启用且消耗回滚槽。 |
| 启动失败与重试 | 在 A 安装并启用另一个带相同命令别名的测试包后，尝试启用样例。样例显示启动失败，工具与命令都不可见；解除冲突不会自动启动，只有「重试启动 → 确认」才应用当前已批准版本。 |
| 权限 | 仅在测试工作区把配置改为只读。已运行包撤回，保留启用意图；只读不能安装和启用，仍可停用并清除意图。返回可写模式不把此前未启用包自动带起。 |
| Agent 转包 | 在工作区 A 的会话试用一个 Agent 编写插件并保存批准版本。插件页的「从已保存试用版本转包」展示原始摘要与新旧工具 ID；取消不写，确认生成 `0.0.1` 未启用记录。原试用与保存快照仍独立；B 不可看到 A 的快照。 |

结果记录：提交号、CI run/工件、设备/API/构建类型、每项预期与实际、是否看到越界目录、失败发生阶段（存储/权限/运行/派发）、回退或阻断决定。不要记录模型密钥或私有工具输出。任何作用域或撤回失败均阻断 M1 放行。
