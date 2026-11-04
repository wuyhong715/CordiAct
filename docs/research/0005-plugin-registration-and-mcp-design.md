# 0005 用户/Agent 可编辑的插件注册与 MCP 连接：调查与设计

- 日期：2026-11-04
- 参照快照：`deepseek-harness`（工作区本地副本）
- 上游相关包：`packages/boot/plugin-manager`、`packages/mcp/mcp-client`、`packages/boot/config-editor`、`packages/util/official-bundles`

## 0. 结论

DeepSeek-Harness 没有“插件注册 API”。它的插件就是 profile 的一行 YAML；启用/禁用就是改写这一行；MCP 服务器也只是某一行插件条目的 `config`。`plugin_manager` 工具是一个“改 YAML + 跑 pnpm”的前端，真正的一致性靠三件事守住：**组合层的单一入口（composeEntries）、注释保留的原子改写、以及把 `application` 状态翻译成 `nextStep` 句子交给模型**。

CordiAct 走的是另一条路：插件以内存对象注册（`ManagedPlugin`）、状态机持久化在 SQLite、`BUNDLED_SYSTEM` 白名单校验来源。这条路缺少的是**声明层**——插件没有可持久化的配置，也没有“从记录重建实例”的工厂，所以今天不可能存在用户安装的插件。

因此本次设计不是照搬，而是**补三个缺口并新增一个 MCP 客户端模块**：

1. `PluginSpec` 增加 `config`，`PluginLifecycleManager` 增加工厂重建路径；
2. `ToolDefinitionRegistry` 增加 `unregister`（MCP 整代换工具的硬前置）；
3. 新增 JVM 模块 `mcp-client`：Streamable HTTP 传输 + 两阶段工具同步 + `ManagedPlugin` 适配。

面向模型的一侧新增一个 `plugin_manager` 工具（动作枚举 + `nextStep`），与将来的 Compose 设置界面共用同一个服务——这一点照 DSH 的“一个服务、两个前端”。

## 1. 已核对的上游机制

### 1.1 声明层：插件 = profile 的一行

```yaml
- insert:
    - id: memory-engram
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: engram
        transport: stdio
        command: engram
        args: [mcp]
```

- `id` 是组合条目 id，`name` 是 npm 包名，`config` 由该包自己的 Schemastery `Config` 校验（`packages/mcp/mcp-client/src/index.ts:119` 是 `z.union([stdio, streamable-http])`）。
- bundle（可安装的“插件包”）就是一个 npm 包，`package.json` 的 `dsh.bundle.patch` 指向自带 `cordis.patch.yml`。样例见 `apps/web/tests/fixtures/plugins/fixture-bundle/`。
- 分层：bundle patch → profile patch → home patch → 启动 overlay，全部经 `composeEntries()` 合并。**没有第二套注册入口**。

### 1.2 启用/禁用：注释保留的原子改写

`packages/boot/plugin-manager/src/patch.ts:25` `writePluginEnabled(profile, id, name, enabled)`：

1. `yaml` Document API 解析，保留注释、锚点与 `!!js` 标签。
2. 定位最后一条 `id` 匹配且不含 `insert` 的覆盖行。
3. 计算“隐含值”：把 bundle patches 与“去掉本行之后的 profile patches”重新 compose，看这条本来是什么状态。
   - 隐含值已等于目标 → **删除该行**并把注释迁到下一项/文档头，避免每切一次就堆一层。
   - 否则就地改 `disabled`。
4. `writeFileAtomic(mode: 0600)`。

随后 `reload([patchId])`。有 HMR 即时生效；没有则返回 `application: 'restart-required'` 并附原因（`no-hmr` / `package-moved` / `host-module` / `unmanaged-plugin` / `module-comparison`）。

### 1.3 `plugin_manager`：一个工具，八个动作

`packages/boot/plugin-manager/src/tools.ts:87`：

```
list_plugins | list_bundles | set_plugin | set_bundle
install_bundle | remove_bundle | list_version_exemptions | set_version_exemption
```

要点，按可迁移性排序：

1. **`ChangeResult` 携带 `application` 状态，非 `applied` 时补一个 `nextStep` 自然语言句子**（`tools.ts` 的 `withNextStep`），显式告诉模型该对用户说什么。模型因此不会把 `restart-required` 说成“已生效”。
2. 每个动作先 `approveEscalation({requestedMode: 'danger-full-access'})`，理由是“改 profile 影响所有会话，装进来的 Host 代码跑在 sandbox 外”。
3. `install_bundle` / `remove_bundle` 底层是 pnpm：registry 回退链、`pnpm view` 查版本、`approvedBuilds` 显式批准 install script、失败回滚旧版。
4. `PROTECTED_PLUGINS`（`packages/util/official-bundles/src/plugin-manager.ts:8`）是硬编码集合（plugin-manager 自己、loader、api-gateway、tools、hmr、client-modules 等），命中返回 `readOnlyReason: 'management-required'`，UI 与工具都关不掉。

### 1.4 MCP：普通插件，无独立入口

`packages/mcp/mcp-client/src/index.ts`：

- **一个插件实例 = 一个 MCP 服务器**；`serverName` 匹配 `[A-Za-z0-9_-]{1,32}`，同一注册作用域内唯一（`activeServerNames` WeakMap），冲突只让本实例加载失败。
- **工具桥接 = 整代替换**（`tools.ts:113` `syncTools`）：先 `tools/list` 建出完整新一代定义且**不碰注册表**；再 dispose 旧代、注册新代；冲突则整代回滚为 0 个工具，绝不留半套。
- 公开名 `mcp__<serverName>__<rawName>`，非法字符替换，超长用 sha256 前 8 位做后缀（`tools.ts:81`）。
- `isError: true` 抛异常，让运行时报成失败而非成功。
- 图片结果走 attachment store，且先证明当前模型声明了 image 输入模态。
- `mcp-resources` 是独立 seam 包，由连接所有者提供，消费方按 agent 作用域读取。

**没有任何“添加 MCP 服务器”的 UI**：`grep -rln mcp packages/client` 只命中一个测试文件。官方路径写死在 `docs/user/develop/practice/dynamic-cordis.md`——让 agent 写一个只含配置的 bundle，用 `plugin_manager install_bundle` 装进去。`ui-plugin-manager` 是插件市场界面，底层是同一个 `PluginManager` 服务（`listPlugins` / `readBundleDetail` / `inspect` / `listVersions` / `updateBundle` / `waitForInstall` / `cancelInstall`）。

## 2. CordiAct 现状（已核对源码）

| 维度 | 现状 | 位置 |
|---|---|---|
| 插件定义 | `PluginSpec(id, version, target, requestedCapabilities, dependencies)`，**无 config** | `core/.../plugin/PluginLifecycle.kt:6` |
| 用户安装 | 无。`manager.register()` 全仓库零调用点 | `PluginLifecycle.kt:86` |
| 来源校验 | `BUNDLED_SYSTEM` 仅受 `BundledPluginVerifier` 白名单 | `BundledConversationPluginCatalog.kt:114` |
| 启用/禁用 | 完整：依赖检查、fail-closed、`FAILED` 记录、`UNINSTALLING` 墓碑 | `PluginLifecycle.kt:138,172,205` |
| 重启恢复 | **恢复不了用户插件**。`bindings[id]` 只在内存；`recoverAfterProcessRestart()` 把残留 `REGISTERING` 的用户插件直接 `uninstall` | `PluginLifecycle.kt:247` |
| 工具撤销 | **无**。`ToolDefinitionRegistry` 只有 register/get/all/registerVisibility/visibility | `ToolManagerContracts.kt:184` |
| 工具 schema | `ToolInputSchema` 只有顶层 `ToolParameter` 列表，装不下嵌套 JSON Schema | `ToolManagerContracts.kt:90` |
| 模型侧工具名 | 直接用 `descriptor.id`（`ModelToolSpec.name = descriptor.id`） | `AndroidToolDispatcher.kt` |
| MCP | **完全没有**。只有枚举常量 `MentionType.MCP`，无人产出 | `interaction/InteractionContracts.kt:30` |
| 网络 | 裸 `HttpURLConnection`（`search-providers/HttpJsonClient.kt`）；core 无 JSON 库 | — |
| 设置存储 | `workspace_plugin_settings(workspace_id, plugin_id, key, value)`，**workspace 作用域** | `AndroidConversationSessionStore.kt:467` |

## 3. 设计

### 3.1 声明层：`PluginSpec.config` + 工厂

```kotlin
data class PluginSpec(
    val id: String,
    val version: String,
    val target: PluginTarget,
    val requestedCapabilities: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    /** Opaque plugin-owned configuration (JSON). Empty for bundled plugins with no settings. */
    val config: String = "",
)

/** Rebuilds a user-installed plugin from its durable spec; null when this build cannot. */
fun interface PluginFactory {
    fun create(spec: PluginSpec): ManagedPlugin?
}
```

`PluginLifecycleManager` 增加：

```kotlin
fun installUserPlugin(spec: PluginSpec, factory: PluginFactory): PluginRecord
fun rebuildFromRecords(factory: PluginFactory): RebuildReport
```

规则：

- `PluginSpec` 进入 `equals` 比较，`config` 变化即视为规格变化 → 与 `register` 现有的“同 id 同元数据幂等、冲突拒绝”语义一致。**改配置 = 卸载后重装**，不做原地热改。
- `rebuildFromRecords` 只处理 `USER_INSTALLED`；`BUNDLED_SYSTEM` 仍由 APK 自带目录注册（那是可信来源，不该从数据库复活）。
- 恢复顺序：`ENABLED` → 重建并 enable；`DISABLED` → 重建不 enable；`REGISTERING` → 沿用现有语义（清理墓碑）；`UNINSTALLING` → 沿用现有 `recoverPendingUninstalls`。
- 工厂返回 `null`（本构建不支持该 target/版本）→ 记录保持 `FAILED`、`lastError` 写明原因，**不静默删除用户数据**。

### 3.2 工具撤销：`ToolDefinitionRegistry.unregister`

```kotlin
interface ToolDefinitionRegistry {
    fun register(definition: ToolDefinition)
    /** Remove one tool and its visibility gate. Returns whether it was registered. */
    fun unregister(toolId: String): Boolean
    …
}
```

这是 MCP 整代换工具、插件禁用时撤下工具、以及 `FAILED` 插件不留半套工具的硬前置。实现要点：持锁删除 definition 与 gate；`ToolManagerPlugin` 现有 `generation` 计数已能在 dispatch 中途丢弃被撤销的工具的返回值，无需额外机制。

### 3.3 schema 直通：`ToolDescriptor.rawInputSchemaJson`

MCP 的 `inputSchema` 是任意 JSON Schema，而 `ToolInputSchema` 只有顶层参数。两种选择——把 JSON Schema 降级映射进 `ToolInputSchema`（丢失嵌套、`oneOf`、`$ref`），或加一个逃生舱。选后者：

```kotlin
data class ToolDescriptor(
    …,
    /** Full JSON Schema object used verbatim instead of [inputSchema]; MCP passthrough. */
    val rawInputSchemaJson: String? = null,
)
```

- 非 null 时 `AndroidToolDispatcher.specs()` 直接用它，`InMemoryToolDefinitionRegistry` 校验它必须是 JSON 对象根（长度上限沿用现有预算）。
- `inputSchema` 仍保留（非 null 路径下忽略），避免破坏既有构造点。
- 工具名必须仍是 `[a-z][a-z0-9._-]{1,127}`：MCP 的 `mcp__server__tool` 不符合，改用 `mcp.<server>.<tool>`，非法字符替 `_`，超长加 sha256 后缀。

### 3.4 MCP 连接层（新 JVM 模块 `mcp-client`）

只做 Streamable HTTP。理由：Android 没有子进程，stdio 传输在本平台无意义；而 `tools/list` + `tools/call` 就是 HTTP POST + 可选 SSE，`HttpURLConnection` 够用，与 `search-providers` 现有的网络栈一致，不引入 coroutines/ktor。

配置形状对齐 DSH（子集）：

```kotlin
data class McpServerConfig(
    val serverName: String,          // [A-Za-z0-9_-]{1,32}
    val url: String,                 // http(s)
    val headers: Map<String, String> = emptyMap(),
    val toolCallTimeoutMs: Long = 60_000,
    val connectTimeoutMs: Long = 15_000,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
)

data class ReconnectPolicy(
    val enabled: Boolean = true,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val maxAttempts: Int = 5,
)
```

契约（放 `core`，纯数据、无网络）：

```kotlin
const val MCP_PLUGIN_ID = "core.mcp"
const val MCP_PLUGIN_VERSION = "0.1.0"

interface McpConnection {
    fun initialize(): McpServerInfo
    fun listTools(): List<McpToolDescriptor>
    fun callTool(name: String, argumentsJson: String, timeoutMs: Long): McpToolCallResult
    fun close()
}

interface McpClientFactory {
    fun connect(config: McpServerConfig, credential: McpCredential?): McpConnection
}
```

`credential` 单独注入（不落 JSON 配置），复用现有 credential 存储约定。

### 3.5 工具桥接：整代换 + 失败不半套

`McpToolBridge.sync(connection, registry, ownerPluginId)`：

1. **Fetch**：`tools/list` 全部成功才继续；任一失败直接返回，注册表不动。
2. **Build**：为每个工具构造 `ToolDescriptor(id = mcpToolId(server, raw), rawInputSchemaJson = …)` 与执行器（`ToolArguments` → JSON → `tools/call` → `ToolResult`）。
3. **Swap**：先 `unregister` 上一代全部 id，再注册新一代；注册抛错则把已注册的逐个 `unregister` 回滚成 0 个，返回失败。

与 DSH 的差别只在命名与存储，语义一致：**模型要么看到完整一代，要么看到零个**。

`tools/call` 返回 `isError: true` → 不抛异常，而是返回带 `ToolResult(values = mapOf("error" to StringValue(text)))` 的失败结果；宿主已有 `ToolResult` 语义，不需要引入异常路径（与 DSH 抛异常等价，但更贴合 CordiAct 现有 `invoke` 契约）。

### 3.6 `plugin_manager` 工具（P1）

```kotlin
action: list_plugins | list_servers | add_server | update_server
      | remove_server | set_enabled | restart
```

返回结构照搬 `ChangeResult` 的思路：

```kotlin
data class PluginChange(
    val target: String,
    val application: Application,     // APPLIED | RESTART_REQUIRED | FAILED | OVERRIDDEN
    val reason: String? = null,
    val nextStep: String? = null,     // 非 APPLIED 时必填，模型据此对用户说话
)
```

来源标记：`BUNDLED_SYSTEM` 一律 `readOnlyReason = "management-required"`，与 DSH 的 `PROTECTED_PLUGINS` 等价。

### 3.7 安装记录（P1，仅 Android）

新表，全局作用域（与 workspace 无关）：

```sql
CREATE TABLE installed_plugins (
  plugin_id    TEXT PRIMARY KEY,
  version      TEXT NOT NULL,
  target       TEXT NOT NULL,
  config_json  TEXT NOT NULL,
  enabled      INTEGER NOT NULL,
  installed_at INTEGER NOT NULL
);
```

凭据不入此表（沿用 keystore / 现有 credential store）。

## 4. 分阶段

### P0 底座（本轮，JVM 可验证）

1. `ToolDefinitionRegistry.unregister` + `InMemoryToolDefinitionRegistry` 实现与测试。
2. `ToolDescriptor.rawInputSchemaJson` + 注册校验 + `AndroidToolDispatcher.specs()` 分支。
3. `PluginSpec.config`、`PluginFactory`、`installUserPlugin`、`rebuildFromRecords` 与测试。
4. `core/mcp/McpContracts.kt`：配置、连接接口、工具名映射（纯函数，重点测边界）、JSON-RPC 消息形状。
5. 新模块 `mcp-client`：`McpHttpTransport`（Streamable HTTP + JSON-RPC）、`McpToolBridge`（两阶段 swap）、`McpPlugin`（`ManagedPlugin`）。
6. `settings.gradle.kts` 增加 `:mcp-client`。

### P1 Android 接线

7. `installed_plugins` 表 + `AndroidInstalledPluginStore`。
8. `CordiActApplication` 启动时 `rebuildFromRecords`。
9. `McpSettingsDialog`（加/删服务器、连接测试）+ `plugin_manager` 工具。
10. MCP 凭据存 `AndroidMcpCredentialStore`（keystore）。

### P2 后续

11. SSE 增量事件、`resources/read`、`prompts/list`。
12. 工具名冲突策略（不同 server 的同名工具已由命名空间隔离，暂不需要）。
13. 插件市场（bundle 分发）——本轮不做。

## 5. 测试计划

JVM（`:core:test`、`:mcp-client:test`）：

- `unregister` 后 `get/all/visibility` 都为空，重复 `unregister` 返回 false。
- `rawInputSchemaJson` 非 null 时注册通过；非对象根被拒。
- `PluginSpec.config` 参与相等性；`installUserPlugin` 同 id 不同 config 被拒。
- `rebuildFromRecords`：ENABLED 重建并启用、DISABLED 重建不启用、工厂返回 null → FAILED 且记录保留。
- `mcpToolId` 映射：大小写、非法字符、超长哈希、冲突稳定性。
- `McpToolBridge`：fetch 失败不动注册表；swap 中途冲突回滚为 0；连续两次 sync 后旧工具全部消失。
- JSON-RPC 编解码：结果、错误、`isError: true`、超时。
- `McpHttpTransport`：用本地 `HttpServer`（JDK 自带）起假 MCP 服务器，覆盖 initialize / tools/list / tools/call / 401。

Android（CI）：设置界面的增删改查、重启后插件复活、禁用后工具消失。

## 6. 风险与开放项

1. **改配置 = 重装**：不做热改，意味着用户改一个 URL 会触发一次完整 enable 周期。接受，换来状态机不变复杂。
2. **Streamable HTTP 的会话语义**：服务端可能返回 `Mcp-Session-Id` 并要求后续请求带上；P0 只做“带上就用”，不实现 DELETE 会话终止。
3. **无 stdio**：Android 上无法起子进程。若将来要跑本地 MCP（如 Termux 里的 server），需走 HTTP 到 localhost，而不是 stdio。
4. **工具命名不可逆**：`mcp.<server>.<tool>` 归一化后无法从 id 反推原始名，桥接必须持有映射表（内存即可，随 plugin 生命周期）。
5. **官方凭据泄露面**：`headers` 里可能含 token，禁止写入 `config_json`；由 credential store 在连接时注入。
6. **DSH 的 `plugin-market` 不在本轮**：CordiAct 没有 npm 生态，bundle 分发是另一个问题。

## 7. 来源

- `packages/boot/plugin-manager/src/patch.ts:25` `writePluginEnabled`
- `packages/boot/plugin-manager/src/tools.ts:87` `plugin_manager`，`withNextStep`
- `packages/util/official-bundles/src/plugin-manager.ts:8` `PROTECTED_PLUGINS`
- `packages/mcp/mcp-client/src/index.ts:119` `Config`，`packages/mcp/mcp-client/src/tools.ts:81,113`
- `packages/boot/config-editor/src/index.ts:26` `ConfigEditor`
- `docs/user/develop/practice/dynamic-cordis.md`（官方“让 agent 配 MCP”的路径）
- CordiAct：`core/.../plugin/PluginLifecycle.kt`、`core/.../tool/ToolManagerContracts.kt:184,92`、`app/.../tool/AndroidToolDispatcher.kt`、`app/.../conversation/AndroidConversationSessionStore.kt:467`

## 8. 实现进度（2026-11-04）

### P0 已完成

- `ToolDefinitionRegistry.unregister(toolId)`：`InMemoryToolDefinitionRegistry` 实现，随 definition 一并清理 visibility gate；重复调用返回 false 而非抛错。
- `ToolDescriptor.rawInputSchemaJson`：注册时校验“以 `{` 开头、以 `}` 结尾”且长度 ≤ 32 KB。core 不引入 JSON 解析器，结构合法性由外来适配器负责。
- `PluginSpec.config`、`PluginFactory`、`installUserPlugin`、`rebuildUserPlugins` + `PluginRebuildReport`。
- `core/mcp/`：`McpContracts.kt`（配置、连接、工具 id 归一化）、`McpServerConfigCodec.kt`（手写编解码）。
- 新模块 `mcp-client`：`McpHttpConnection`（Streamable HTTP + JSON-RPC + SSE）、`McpToolBridge`（两阶段 swap）、`McpPlugin`。
- `AndroidToolDispatcher.specs()` 优先使用 `rawInputSchemaJson`。

### P1 已完成

- `AndroidPluginLifecycleStore` schema v3：`config` 列 + 迁移。**没有这一列，重启后 MCP 插件的配置会变成空字符串，永远无法重建。**
- `PluginManagerService` 契约（core）+ `PluginManagerPresenter`（`nextStep` 语义在这层统一）。
- `AndroidPluginManagerService`：`inventory` / `setEnabled` / `install` / `remove`，bundled 一律 `OVERRIDDEN`。
- `plugin_manager` 工具（`app/plugin/PluginManagerTools.kt`），动作 `list | enable | disable | add_mcp | remove`。
- `CordiActApplication` 启动顺序：bundled 注册启用 → 注入管理服务 → `restoreUserPlugins()`。

### 过程中修正的设计问题

1. **`recoverAfterProcessRestart` 会把用户插件降级为 DISABLED**，导致每次重启都要手动重开。现在它只修复 `BUNDLED_SYSTEM`，用户插件交给 `rebuildUserPlugins`。两个既有测试随之更新。
2. **`McpToolBridge` 原有一个独立的 `bind()`**，sync 与 bind 顺序写反会派发到死连接。现在 `sync()` 同时接管连接。
3. **`mcpToolId` 里的“数字开头”保护是死代码**：`core.mcp.` 前缀保证首字符是字母。已删除，改为测试断言。

### 未验证

`app` 模块本地无法编译（`/workspace/android-sdk` 只有 cmdline-tools，无 platform），`CordiActApplication`、`AndroidPluginManagerService`、`PluginManagerTools`、`AndroidToolDispatcher` 的改动只有人工核对。CI 是唯一验证源。

### 仍未做

- 设置界面（`McpSettingsDialog`）——服务层已就绪，界面可随时接。
- MCP 凭据存储（`AndroidMcpCredentialStore`）：`plugin_manager` 工具已把 header 通过回调交给宿主，但宿主尚未落地存储。
- SSE 增量事件、`resources/read`、`prompts/list`。
