# DSH 只读跟随 Host 插件（离线验证）

本插件通过 Cordis 的服务与可撤回注册接入固定版 `0.2.3-alpha.1`。不加入 CordiAct APK、已有 DSH profile 或运行中的 Web GUI。不注册 HTTP、WebSocket、Gateway、控制接口或通用命令。它是供可信 Host 装配验证的模块，不是可部署的 Android 远端入口。

## 操作与数据范围

- 可信进程内控制面用 `cordiactFollowAuthority.issue(remoteWorkspaceId, remoteSessionId, ttlMs)` 显式授予精确目标。必须是已经加载、属于该 DSH 工作区、未归档的同一会话实例；不查历史、不激活、不借用 Web/LiveShare 身份。授权随机生成，Host 只在内存保留查表摘要；每实例最多 16 个未失效授权，最长 5 分钟。这不是面向不可信同进程插件的沙箱，不能把授权服务交给载体或端侧。
- 读端只有 `cordiactFollowReader.open({ version: 1, workspaceId, sessionId, token })`。未知版本、错域、未知凭据或已经使用的凭据统一拒绝；拒绝不会查询会话。一次打开用 `next()` 获取完整替换，首个结果即基线；可同游标变化或跳增，不重放。业务数据严格只有 `cursor/active/running`；`cursor` 是当前进程会话日志位置，不证明落盘、不充当传输编号。`active` 表示附着同一会话的 Agent，无 Agent 但会话仍在内存时有效。冷会话无数据。
- 读端只保留上一投影和至多一个待取投影，不累计历史；同一流只允许一个未决 `next()`。显式撤权、到期、会话离开内存、工作区撤销/解绑或归档均会清除待取内容，唤醒未决读，并触发 `signal`。关闭或卸载后也不复用旧凭据。已经完成交付的值不可追回；未来载体必须在最终发送前复核 `signal`，不能把本轮的进程内取消当成跨网断线保证。
- 会话样本同步读取当前注册目录；变化按需与每 100 ms 采样，可能合并中间状态，不保证观测所有切换。授权期限按单调时钟复核，定时器不强保 Node 进程存活。插件不读取会话事件正文、队列、待输入或会话盘，也不调用写操作；验证夹具创建合成工作区时会写自己的临时目录。

## 继承管道载体

[专用载体](ipc-follow.mjs)只拿读取服务和可信监督者已建立、由它独占的一对字节流；不打开监听器，不接受地址、路径、方法或控制命令。它不是常规 profile 的启动入口。测试通过额外继承的管道传输数据，日志与合成测试控制信道完全分开。

- 请求为单行 ASCII：`cordiact-follow/1 <remoteWorkspaceId> <remoteSessionId> <token>` 加 LF。工作区和会话 ID 都限 128 字符，输入缓冲最多 384 字节，首行必须在 3 秒内结束。工作区、会话和协议版本精确匹配；一次通道最多验证一次，拒绝只关闭，不输出错误或发现清单。发完请求须保持输入端开放且静默；EOF 或后续字节终止整条流。
- 响应是固定顺序 `delivery/cursor/active/running` 的紧凑 ASCII JSON 行，以 LF 结束；`delivery` 从 0 连续编号，不是 DSH 日志游标。整数使用十进制，无前导零、指数、负零或分数；除 `cursor = -1` 外，数值都在 JavaScript 非负安全整数范围内。单次只把一帧交给 writable，并等待该写完成再查询下一帧；等待超过 3 秒则关闭。整个打开窗口不超过 5 分钟，另受更早的授权期限约束。
- 授权失效或主动关闭后不再提交新帧，不等待写端冲洗、不排队发终止帧；对端以 EOF/错误/主动关闭结束当前选择。提交给内核或对端的字节不可追回。输入端仍开着时，仅关闭输出读端未必马上被检测到；监督者应把两端成组关闭。凭据为一次性 bearer，不绑定 PID；发行、传递和回收未消费凭据由可信控制面负责，绝不把授权服务传给载体。

## 验证

需要 Node 24.9+。从 CordiAct 根目录运行无依赖回归：

```sh
node --test deepseek-harness/host-follow/test/follow-service.test.mjs \
  deepseek-harness/host-follow/test/ipc-follow.test.mjs
```

真实 Loader 回归也可用[一条命令](test/run-pinned-profile.mjs)复现。它需要 Git、tar、Node 与 npm，以及包含[固定 DSH 提交](https://github.com/deepseek-ai/deepseek-harness/tree/d98f5d68519215da48f3002da2cb9c9d2068d81d)的源仓库。脚本从 Git 对象取源码，不读取 checkout 的未提交改动；在临时目录以 `npm ci --ignore-scripts` 安装 [SHA-512 锁定的依赖](test/pinned-dsh/package-lock.json)，然后用真实 CLI/Loader 启动不带监听器的合成 profile。测试只创建临时 Harness home，不访问用户配置、模型或生产会话。夹具禁用 Node 网络与 IPC 套接字，发现尝试监听、连接或发送数据报就报错。

```sh
node deepseek-harness/host-follow/test/run-pinned-profile.mjs /absolute/path/to/deepseek-harness
```

已缓存所有锁定 npm 包时可加 `--offline`。普通 CI 分开执行核心回归和真实装配，不依赖 GitHub 密钥或已部署 Host。

**工件限制：** 原样 npm 固定版仍不能启动：`@deepseek-ai/cordis-plugin-loader@1.1.0` 缺少 `dsh-app-boot` 静态导入的 `EntryValidationError`、`formatIssues`、`relocate` 和 `validateOptions`。同版 Include 包也与固定源码不同；它缺少 `parseEntryYaml` 和 `stringifyEntryYaml`，但这两个函数已内联到发布的 `dsh-app-boot`，不是这次静态链接失败的直接原因。上述命令使用 `0.2.3-alpha.1` npm 制品，但在测试子进程内以同一提交重建的 Cordis 系列模块替代相应发布包。它验证固定源码的 Host 装配，**不视为原样 npm 版通过，不是部署办法**。

## Kotlin 端侧互操作验证

[接收器](../src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeReceiver.kt)一次只跟随已核验载体绑定的一代，最多缓冲 127 字节的行正文、单次接收 4 KiB。它直接校验 Host 序号和精确帧格式，拒绝重复、跳号、未知键、断帧和游标倒退；EOF 立即清除数据，不猜测是撤权还是普通断开。可信本地撤权可显式结束为 `ACCESS_LOST`。关闭、字节和渲染都必须持有原打开身份，不能把旧回调重贴给新选择。接收器不打开进程或连接，也不处理凭据。[端侧生命周期](../src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeLifecycle.kt)显式接管一次独占载体，使用调用方提供且不超过 30 秒的剩余授权预算；首帧最多等 3 秒。片段、正常更新和空闲都不续租。到期先清除状态，再请求关闭原载体。健康的静默 Host 也会按期关闭；这不是探活或网络分区检测。

默认 JVM 测试覆盖解析和生命周期，不要求 Node；真实互操作测试显式运行：

```sh
./gradlew -p verification/jvm :deepseek-harness:followPipeInteropTest \
  -PfollowNode="$(command -v node)" --no-daemon
```

## 用同一源码树的正式构建验证

[手动 GitHub Actions 工作流](../../.github/workflows/dsh-built-profile.yml)在全新 Linux x64 checkout 用 Node `24.19.0` 和 pnpm `11.28.5` 安装上游冻结依赖，执行 `pnpm run build:artifacts --profile official`。随后在无网络命名空间内运行同树的 `apps/cli/lib/bin.js`，并保留正常 pnpm 工作区依赖解析。这里不替换 Cordis 或 Loader 模块；Node 套接字守卫只用于让意外的网络或 IPC 使用报错。

若本地已有用上述步骤构建的独立 DSH checkout，也可直接运行下列验证。脚本拒绝不匹配的提交、脏工作树和缺失的 CLI，打印用到的版本、平台以及锁文件和 CLI 入口摘要；它不安装、不启动 Web、不证明旧的本地工件一定来自当前构建。CI 的干净检出和同作业构建才是完整来源证据。

```sh
node deepseek-harness/host-follow/test/run-built-profile.mjs /absolute/path/to/built-deepseek-harness
```

本地 Linux arm64 以 Node `24.19.0` / pnpm `11.28.5` 完成冻结安装（本地禁用包安装脚本）、官方 8 阶段工件构建及禁网合成 profile。上游锁 SHA-256 为 `f7c01c56b65e31e1924a82bf8061ee9b63122e6e769b4aaed4781b4d1d3c088a`，实测 CLI 入口 SHA-256 为 `b293de4609dfd0625b4e5c0a88363fb49efd7d162b41a9ac2e36a1f8e196d133`。此结果不是原样 npm 版通过，也不替代待运行的 Linux x64 工作流。继承管道已对合成进程实测认证、限字节解析、撤权、过期及输入关闭；受控 writable 验证单帧背压和写超时，未宣称填满内核缓冲。尚未验证远端网络载体、TLS、静默网络分区或 Android。继续接入前按[架构验收要求](../../docs/architecture/dsh-readonly-session-follow.md)执行。
