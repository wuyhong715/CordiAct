# CordiAct 插件优先改造

目标不是让 APK 下载任意代码，而是让用户可以安装、组合、调整、停用自己的能力，并能看清作用域、数据去向和授权。保留 Android 对设备与本地数据的最终授权。插件化先于开发工作台；后续代码工作台本身应使用成熟的贡献接口，而不是直接改宿主核心。

## 第一段：可信代码的工具发布

[PluginToolContributions](../../core/src/main/kotlin/com/cordiact/core/plugin/PluginContributions.kt) 从宿主签发的 `PluginContext.id` 取得全局、工作区或会话作用域。普通前台回合可见全局、所属工作区和该会话三层；没有已确认属于该工作区且未归档的会话时只投影旧全局工具。不做名称遮蔽：同一视图会重叠的注册名冲突，两个不相交的作用域可以同名。工作区读授权（`@`）不扩展工具目录。非全局工具名须为 `extension.<pluginId>.*`，避免碰到宿主的保留工具名。

[PluginKernel](../../core/src/main/kotlin/com/cordiact/core/plugin/PluginKernel.kt) 在成功启动后开放该实例的发布，停机及依赖失效先关闭整条受影响链的发布，再运行清理。注册、注销逐代拥有；旧释放句柄只能清理自己的注册。全局设置与插件清单维持旧投影，不返回会话私有工具的名称或数量。首段不把工作区工具写进旧的按工具名保存的设置，也不向后台任务或子 Agent 继承前台会话目录。

[Tool Manager](../../core/src/main/kotlin/com/cordiact/core/tool/ToolManagerContracts.kt) 保留工作区、插件生命周期、能力、参数、权限和审批检查。发给模型的名字与 schema 不含本机句柄；[会话服务](../../model-providers/src/main/kotlin/com/cordiact/model/providers/ConversationTurnService.kt) 只在宿主调用上下文保存当轮注册身份，并把它从 provider 请求中移除。作用域工具不接受缺少绑定的直接调用。注册在等待审批、排队或执行后发生变化，旧调用拒绝或丢弃结果；已入场的副作用不是事务，不能把停用或丢弃结果称为撤销外部效果。

当前贡献接口供可信应用代码调用，不是第三方原生 SDK 或隔离沙箱。宿主的效果分类默认拒绝未知工具。Agent 源码只由宿主编译为固定文本和有界文本变换工具，不能直接调用贡献接口或注册命令，见 [Agent 编写插件](../testing/authored-plugins.md)。

## 第二段：可信代码的斜杠命令发布

[命令贡献接口](../../interaction/src/main/kotlin/com/cordiact/interaction/PluginCommandContributions.kt) 从同一个内核实例取得命令作用域；[命令目录](../../interaction/src/main/kotlin/com/cordiact/interaction/InteractionCommandRegistry.kt) 对全局、工作区、会话投影，不允许遮蔽。命令 ID、主名或任一别名在可重叠范围内冲突，启动失败时不会留下部分候选；不相交的会话可安全复用同名。非全局 ID 必须为 `extension.<pluginId>.*`。没有经宿主验证的活跃会话时只返回全局目录；`@` 引用不扩大命令目录，也不存在把 Agent 工具自动变成斜杠命令的机制。

补全、解析、授权后分发都以相同的 `InteractionContext` 解析。注册单独受宿主 owner 开关约束，只有内核完成启动后才可见；内核停用时先撤回整代，再做清理。相同参数的旧命令也不能经准备结果调用新代。已进入可信 executor 的操作不自动撤销；若之后使用宿主模型入口，入口会再次核对会话、权限与注册身份。内置 `/目标`、`/compact`（`/压缩`）由 Android 宿主通过相同贡献接口发布，保持全局名称不变。

## 第三段：用户直接管理会话试用

[会话插件面板](../../app/src/main/java/com/cordiact/app/experience/SessionPluginsDialog.kt) 只显示本会话的启用记录、SHA-256 和工具 ID。用户可以不选模型直接撤回；界面绑定所见的运行世代，即使相同字节重新启用，旧确认也不会撤回新实例。已撤回的注册不能被旧回合调用；操作不删除工作区草稿或历史对话。该入口只供可信宿主调用，不作为 Agent 工具公开。

[会话管理器](../../core/src/main/kotlin/com/cordiact/core/conversation/ConversationContracts.kt) 先持久化会话归档，再由[试用运行时](../../core/src/main/kotlin/com/cordiact/core/plugin/AuthoredPluginRuntime.kt) 注销会话插件并作废该会话待审批的变更。归档与恢复串行化；恢复前重试未完成的清理，不复活插件。审批卡若已出现，宿主尝试关闭对应请求；卡关闭与最终禁止提交是两回事，后者由运行时世代校验保证。以上不自动撤销已完成的对话、后台任务或已产生的模型输出。

Agent 申请变更时，CordiAct 将审批绑定到[宿主签发的前台回合](../../core/src/main/kotlin/com/cordiact/core/plugin/AuthoredPluginTurn.kt)。切换会话（即使随后切回）、工作台暂停或停止回合都会关闭旧授权；普通切换不撤回先前已经提交的插件。插件运行时不在审批或源码读取期间持有回合锁，最后提交与授权关闭排序，并重新核对源码与当前权限配置。子 Agent、后台任务和斜杠命令不继承此授权。共享[审批网关](../../core/src/main/kotlin/com/cordiact/core/action/ActionContracts.kt)用单调时钟判断截止时间，只关闭对应请求；接受决定不代表插件已生效。

DeepSeek Harness 按 profile 持久化 bundle 并区分安装与运行状态。CordiAct 采用同样的分离原则：会话面板的「工作区插件草稿」只分页发现当前工作区的 `plugins/` 顶层目录，并在用户打开条目时严格校验并计算 SHA-256。预览展示同 ID 在本会话的启用摘要，但不授予权限、不发布工具、不传给模型。用户可以从预览明确确认启用，由独立于 Agent 回合的一次性确认绑定当前前台会话、源码摘要和权限快照；提交前重读验证，并与模型审批共用配额、世代注册及撤回。只读工作区可查看或撤回而不能启用，不允许自动替换正在运行的同名版本。仍缺工作区级运行生命周期接入与通用扩展设置。

## 第四段：持久保存批准版本，先不授予运行权

从草稿的「保存批准版本」可以在不发布工具的情况下写入工作区的一个精确版本。[批准版本服务](../../core/src/main/kotlin/com/cordiact/core/plugin/AuthoredPluginInstallations.kt) 重读严格源码、复核 SHA-256 与实时权限，将原始字节及派生元数据通过一次性前台确认交给[专用 SQLite 存储](../../app/src/main/java/com/cordiact/app/plugin/AndroidAuthoredPluginInstallStore.kt)原子提交。数据库独立于工作区文件、App 通用插件生命周期和模型工具，不从普通 JSON 推导授权。每工作区最多 32 条记录；同 ID 同摘要幂等但要重新校验，异版拒绝并保留旧版。记录损坏、不支持、查询失败与空列表分别呈现，不因记录存在而宣称运行。提交结果未知时先查询，不自动重放。

重启后可检查已保存版本；应用启动不会发布工具或恢复会话试用。用户可以从校验通过的清单条目打开预览并单独确认运行。该路径按工作区、插件 ID、安装世代和摘要重新核对私有快照与派生清单，不使用可变草稿作为执行来源；复用会话配额、权限与撤回，既不覆盖同名运行版本，也不修改保存记录。

用户可对校验通过的快照明确确认删除，或预览同名草稿并确认旧、新摘要后替换。保存数据库为每一代快照分配唯一 ID；旧请求即使碰到相同字节重新保存的记录也不能变更新代。SQLite 在一笔写事务中比对具体记录并完成删除或替换；旧数据库迁移保留原字节。两个操作都不更新运行实例、不删除草稿，也不保留历史快照。损坏或不支持的条目不开放变更；提交结果未知先回清单核对，不重放。

## 下一步交付顺序与退出条件

| 阶段 | 交付 | 最低验收 |
|---|---|---|
| P0 作用域与撤销 | 已落地可信工具、命令和别名的作用域发布与同上下文解析；App 接入内置命令 | 两工作区、两会话的目录/执行不串；启动失败无可见项；同 schema 的新代拒绝旧回合；授权等待期间撤回不给执行器准入。 |
| P1 Agent 创作闭环 | 已落地会话试用：Agent 写源码、用户批准具体版本、下一轮可见；支持固定文本和有界文本变换；本地会话清单、撤回、会话归档清理及前台回合审批时效已接入 | 拒绝或审批期间源码改变不发布；工具无文件、网络、命令或编排能力；重启不恢复激活；Android CI 与真机尚需覆盖。 |
| P2 扩展包与实例治理 | 已落地[声明式扩展包](extension-packages.md)的严格格式、宿主/能力校验、安装/升级/回滚/迁移和 JVM 装配；App 内置 `core-quick-notes` 同时装配工具与命令 | 至少两种贡献通过真实 App 装配；未知版本不静默降级；升级新增能力须重新同意；失败不遗留注册、清理可重试。用户安装服务、Android 持久化与界面尚未接入。 |
| P3 远端和生态 | 受控 HTTP MCP 按同一世代管理；固定版 Harness 只读连接；日后评估宿主插件或签名随包 JS | 远端原生授权独立；断线先对账；可移植性、权限、资源上限和撤销在真机有证据之前不支持任意脚本。 |

Agent 创作与 MCP 安装是两条路径。已有 HTTP MCP 客户端，缺的是受控安装与连接世代治理，不是 HTTP 传输。先补试用治理，再考虑工作区持久化、历史版本与回滚。任意 TypeScript/Cordis 源码只能在独立、明确授权的 Harness 宿主上安装和执行，不在 APK 里自动运行，也不继承 Android 权限。

本地使用[纯 JVM 开发入口](../development.md)验证生命周期、调用和模型边界；Compose、Android 存储与 APK 由 GitHub Actions 验证和打包，真机验收独立取证。
