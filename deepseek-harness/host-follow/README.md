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
  deepseek-harness/host-follow/test/ipc-follow.test.mjs \
  deepseek-harness/host-follow/test/pairing-offer.test.mjs
```

真实 Loader 回归也可用[一条命令](test/run-pinned-profile.mjs)复现。它需要 Git、tar、Node 与 npm，以及包含[固定 DSH 提交](https://github.com/deepseek-ai/deepseek-harness/tree/d98f5d68519215da48f3002da2cb9c9d2068d81d)的源仓库。脚本从 Git 对象取源码，不读取 checkout 的未提交改动；在临时目录以 `npm ci --ignore-scripts` 安装 [SHA-512 锁定的依赖](test/pinned-dsh/package-lock.json)，然后用真实 CLI/Loader 启动不带监听器的合成 profile。测试只创建临时 Harness home，不访问用户配置、模型或生产会话。夹具禁用 Node 网络与 IPC 套接字，发现尝试监听、连接或发送数据报就报错。

```sh
node deepseek-harness/host-follow/test/run-pinned-profile.mjs /absolute/path/to/deepseek-harness
```

已缓存所有锁定 npm 包时可加 `--offline`。普通 CI 分开执行核心回归和真实装配，不依赖 GitHub 密钥或已部署 Host。

**工件限制：** 原样 npm 固定版仍不能启动：`@deepseek-ai/cordis-plugin-loader@1.1.0` 缺少 `dsh-app-boot` 静态导入的 `EntryValidationError`、`formatIssues`、`relocate` 和 `validateOptions`。同版 Include 包也与固定源码不同；它缺少 `parseEntryYaml` 和 `stringifyEntryYaml`，但这两个函数已内联到发布的 `dsh-app-boot`，不是这次静态链接失败的直接原因。上述命令使用 `0.2.3-alpha.1` npm 制品，但在测试子进程内以同一提交重建的 Cordis 系列模块替代相应发布包。它验证固定源码的 Host 装配，**不视为原样 npm 版通过，不是部署办法**。

## Kotlin 端侧互操作验证

[接收器](../src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeReceiver.kt)一次只跟随已核验载体绑定的一代，最多缓冲 127 字节的行正文、单次接收 4 KiB。它直接校验 Host 序号和精确帧格式，拒绝重复、跳号、未知键、断帧和游标倒退；EOF 立即清除数据，不猜测是撤权还是普通断开。可信本地撤权可显式结束为 `ACCESS_LOST`。关闭、字节和渲染都必须持有原打开身份，不能把旧回调重贴给新选择。接收器不打开进程或连接，也不处理凭据。[端侧生命周期](../src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeLifecycle.kt)显式接管一次独占载体，使用调用方提供且不超过 30 秒的剩余授权预算；首帧最多等 3 秒。片段、正常更新和空闲都不续租。到期先清除状态，再请求关闭原载体。健康的静默 Host 也会按期关闭；这不是探活或网络分区检测。[受信交付](../src/main/kotlin/com/cordiact/harness/deepseek/ApprovedFollowPipe.kt)把已核验的独占管道、远端工作区、会话与专用凭据绑定在一次性对象中；从收到交付时就计算绝对截止，交接和排队不续租。[读循环](../src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeClient.kt)在独立执行器只发送一次固定请求，随后仅读取该管道保留准确世代。发送前复核当前状态；取消后已交给系统的字节不可追回。交付对象不是 Host 认证机制；不读 URL，不持久化凭据，不自动重试。真实管道测试用请求端 EOF 让 Host 关闭响应端并解除阻塞读取；未来载体必须提供同样效果的非阻塞取消，不在断开时自动开新流。

默认 JVM 测试覆盖解析和生命周期，不要求 Node；真实互操作测试显式运行：

```sh
./gradlew -p verification/jvm :deepseek-harness:followPipeInteropTest \
  -PfollowNode="$(command -v node)" --no-daemon
```

## 回环 TLS 原型

[客户端连接器](../src/main/kotlin/com/cordiact/harness/deepseek/PinnedFollowTlsConnector.kt)只连受信控制面交付的确定 IP 和端口，要求 TLS 1.3、精确叶证书 SHA-256 指纹、证书有效期及 `cordiact-follow/1` ALPN。指纹是该配置的 Host 身份，不使用系统 CA 或 DNS 名称替代，不发现、不从未核验的对端录入信任。绑定在一份连接器实例的凭据仅使用一次；握手最多 3 秒，或原授权剩余时间（取较短者）。握手完成才交付可发送凭据的载体，原授权时间不重算。失败关闭，无 owner 或明文回退。

[Host 载体](tls-follow.mjs)须显式提供 PKCS#12 或叶证书与匹配私钥，以及投影读取服务，才能启动，硬编码绑定 `127.0.0.1` 的临时端口。它不拿发证权限、不注册 Gateway 或控制路由；只接受 TLS 1.3 与精确 ALPN，预握手及已握手连接合计最多 16 条，3 秒握手截止，之后沿用管道请求、写入和授权边界。停止监听会关闭尚在握手的 TCP 连接和现有投影。此模块未装入默认 DSH profile。

[JVM 测试](../src/test/kotlin/com/cordiact/harness/deepseek/PinnedFollowTlsConnectorTest.kt)验证指纹、协议、握手超时；[Node ↔ Kotlin 回环测试](../src/test/kotlin/com/cordiact/harness/deepseek/FollowTlsInteropTest.kt)使用同一专用传输和真实授权服务，验证首帧、更新、错域拒绝、Host 撤权、握手超时和关闭监听。签名配对用例涵盖正常撤权和模拟静默断线：后者在不终止 TLS 的单连接回环中继吞掉 Host 后续报文及断开信号，验证客户端在本地期限到达时主动关闭并清除状态。夹具凭据仅通过隔离的进程控制通道交付；这不是跨设备网络分区测试。未开放外网，也未接入正式 profile 或 Android。

[慢读 TLS 回归](test/tls-backpressure.test.mjs)使用 OpenSSL 生成临时证书，连接真实 Node TLS 回环载体后暂停客户端读取。测试用有限个合成小帧让实际写回调停滞，验证约 3 秒写截止后关闭，且不再向数据源取下一帧。这个夹具故意去掉生产服务 100 ms 合并采样的限速；不把测试帧数当成内存上限，也不声称已经写入 TCP 的字节可撤回。复现：`node --test deepseek-harness/host-follow/test/tls-backpressure.test.mjs`。

### 预置信任的配对原型

[Node 签发器](pairing-offer.mjs)只在受信控制面使用。从双方已批准的精确路由出发，为客户端的 32 字节随机挑战签发一份新的 15 秒只读授权。签名覆盖 ASCII 单行 `cordiact-follow-pair/1 <连接> <本地工作区> <远端工作区> <远端会话> <IPv4> <端口> <叶证书 SHA-256> <挑战> <专用凭据>\n`，使用独立预置的 P-256 签名键和 SHA-256（DER 签名）。[Kotlin 验证器](../src/main/kotlin/com/cordiact/harness/deepseek/FollowPairing.kt)只认可预置公钥、精确路由与当前挑战，成功后只允许连接一次。客户端期限从创建挑战起计 15 秒，不因签发、批准或握手续期。[路由控制器](../src/main/kotlin/com/cordiact/harness/deepseek/FollowRouteController.kt)管理当前配对、握手和已打开的投影；重新配置（即使可见 ID 不变）、新挑战或清除时，先使旧引用失效，再取消旧握手、清退旧流。不把晚到的握手归给替代路由。该本地清退不撤销 Host 发证，Host 仍独立执行到期和撤权。

回复使用另行预置、每路由独立随机生成的 32 字节交付密钥做 AES-256-GCM。外层为 `12 字节随机 IV || 密文 || 16 字节认证标签`，附加认证数据为 ASCII `cordiact-follow-seal/1 <挑战>\n`；明文由一字节签名长度、DER 签名和签名覆盖的原始行组成。路由入口只接受密封回复，不回退明文；认证解密后仍检查内层签名、目标和原截止。应用持有的解密缓冲和路由密钥副本用后清零，不承诺抹除密码学提供者或运行时的副本。

Host 的 `createFollowPairingController` 固定一份已批准的路由和交付密钥，只返回密封回复。受信的操作者适配器先用 `capture` 获取包含固定路由和挑战的不可变审核对象；这一步不签发、不撤权。只有明确同意后才调用 `redeem` 提交原对象，最多等待 3 秒。复制、跨实例、换代、关闭或取消后的对象均无效。提交有效审核才撤回旧凭据和已打开的流；失败不会恢复旧授权。测试适配器模拟同意，不认证真人。同一实例最多记录 256 个挑战（含失败签发），不淘汰旧记录、不重试；满额时须通过受信控制面重新配置。关闭时撤回当前授权并清零本实例的交付密钥副本。重放去重仅覆盖此内存实例，不跨进程，也不认证挑战提交者。

可选的[本地终端适配器](operator-tty.mjs)只在拥有专用 stdin/stderr 的受信进程中调用；非 TTY、已占用输入和取消都默认拒绝。它显示实际待批准的 Host 指纹、工作区和会话，并要求在原期限内输入本次随机确认码；它返回密封回复，不将凭据打印到终端。拥有终端或 PTY 就能回答，**这不是操作者身份认证**。适配器未注册命令、未加入默认 profile，也没有远程转发入口。

[显式回环 Host 装配](loopback-host.mjs)组合专用监听器、一个固定路由和终端审批。调用方提供匹配且在有效期内的叶证书与私钥；装配从实际送入 TLS 的同一证书计算指纹，用实际绑定的端口形成签名路由，不接受单独指定的指纹或端口。它返回端点和本地审批、撤权、关闭函数；停止时取消审核、撤回本路由授权并关闭监听器，不销毁共享 DSH 服务。Host 调用方负责把关闭绑定到自己的生命周期。未提供配置存储、远程挑战提交入口或非回环监听。

密封不解决信任启动，也不把 bearer 变成持有证明。生产仍需保密地预置与轮换交付密钥、批准操作者和签发范围，不能把测试控制信道照搬进服务端。TLS 监听器不接受配对命令，也不持有签名键、交付密钥或发证权限。

## 用同一源码树的正式构建验证

[手动 GitHub Actions 工作流](../../.github/workflows/dsh-built-profile.yml)在全新 Linux x64 checkout 用 Node `24.19.0` 和 pnpm `11.28.5` 安装上游冻结依赖，执行 `pnpm run build:artifacts --profile official`。随后使用同树的 `apps/cli/lib/bin.js` 分别验证无监听器 profile 和回环 TLS profile，保留正常 pnpm 工作区依赖解析，不替换 Cordis 或 Loader 模块。两次运行使用独立网络命名空间；仅 TLS 分支启用 `lo`。TLS 分支的 Node 守卫只允许一个回环 TLS 监听器，拒绝 CLI 主动连接和数据报。守卫不是沙箱，网络隔离由命名空间提供。

若本地已有用上述步骤构建的独立 DSH checkout，也可直接运行下列验证。脚本拒绝不匹配的提交、脏工作树和缺失的 CLI，打印用到的版本、平台以及锁文件和 CLI 入口摘要；它不安装、不启动 Web、不证明旧的本地工件一定来自当前构建。CI 的干净检出和同作业构建才是完整来源证据。

```sh
node deepseek-harness/host-follow/test/run-built-profile.mjs /absolute/path/to/built-deepseek-harness
node deepseek-harness/host-follow/test/run-built-profile.mjs /absolute/path/to/built-deepseek-harness --loopback-tls
```

TLS 模式额外需要 OpenSSL 生成临时合成证书，以及 util-linux `script` 提供伪终端。真实 Loader 提供授权服务；固定 CLI 用本地终端适配器读取测试拒绝与随机确认码。测试 IPC 不确认同意，只交付挑战及密封回复；独立 Node 客户端用预置密钥认证解密，复核签名与完整路由后才打开 TLS 投影。回归核对终端上显示的真实端口、指纹与目标，拒绝时不触发签发，且不显示专用凭据。验证首帧和更新、错工作区/会话、重复挑战与凭据、非专用凭据与 HTTP 前缀拒绝，以及卸载后现有流关闭、仍未使用的凭据不能打开、新挑战不能签发。没有生产端点或凭据。

本地 Linux arm64 以 Node `24.19.0` / pnpm `11.28.5` 完成冻结安装（本地禁用包安装脚本）、官方 8 阶段工件构建及禁网合成 profile。上游锁 SHA-256 为 `f7c01c56b65e31e1924a82bf8061ee9b63122e6e769b4aaed4781b4d1d3c088a`，实测 CLI 入口 SHA-256 为 `b293de4609dfd0625b4e5c0a88363fb49efd7d162b41a9ac2e36a1f8e196d133`。此结果不是原样 npm 版通过，也不替代待运行的 Linux x64 工作流。继承管道已对合成进程实测认证、限字节解析、撤权、过期及输入关闭；受控 writable 验证单帧背压和写超时；回环慢读则验证真实 TLS 写停滞后关闭，未测量各层缓冲占用。同一构建也在本机 `127.0.0.1` 跑通真实 Loader 的合成 TLS profile，使用专用 Node 测试客户端；本地容器不能在新网络命名空间启用 `lo`，因此仅回环命名空间模式尚待远端 CI。独立载体另有 Kotlin 互操作回归。这些证据不涵盖生产配对、静默网络分区或 Android。继续接入前按[架构验收要求](../../docs/architecture/dsh-readonly-session-follow.md)执行。
