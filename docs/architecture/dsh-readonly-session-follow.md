# P2 · DSH 只读会话状态跟随

**状态：本地归约器和进程内 Host 插件已实现，独立 TLS 载体只验证了回环；默认 DSH 不符合接入门槛，未开放外网或装配 APK。** 本文核对的是 [DSH `dsh-v0.2.3-alpha.1`](https://github.com/deepseek-ai/deepseek-harness/tree/d98f5d68519215da48f3002da2cb9c9d2068d81d)，不是其他版本或正在运行的 Web GUI。

## 兼容性结论

| 固定版表面 | 决定与依据 |
|---|---|
| 普通 Web token／`/connect` | **不能接入。** 默认认证将浏览器及外部 API 客户端都建为 `owner`；`interactions: none` 只禁转发交互，不降权。Gateway 的 unary 和 stream 都在分派前按主体决策，`owner` 跳过调用授权检查。[认证实现](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/host/authentication-token/src/index.ts#L31-L77) · [Gateway 分派](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/gateway/src/index.ts#L770-L815) |
| `session/state` 与 `session/observeState` | 仅作为后续受限 Host 的候选。前者是单次 RPC，后者是流，返回目标会话的完整状态替换而非事件增量。第一次可直接返回当前状态，不另发 `ready`；同一会话日志游标可有多个运行状态，连续变化可合并。原响应还含待处理队列及输入状态，不对 CordiAct 放行原始响应。[端点声明](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/index.ts#L543-L563) · [状态采样](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/state/controller.ts#L124-L161) · [合并语义](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/state/listener.ts#L63-L100) |
| 清单、历史和 `session/follow` | 本切片不接入。`session/list` 是无分页清单；`session/events` 按消息计数分页，不是事件或字节硬上限；`session/observeEvent` 是先 `ready` 后事件、无基线。`session/follow` 可通过 `activate: true` 激活冷会话，不能只按名字判定只读。冷观察还依赖存储后端，遗留 SQLite 导入可写盘；不能宣传所有部署零宿主磁盘写。[会话端点](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/index.ts#L498-L586) · [分页实现](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/history.ts#L132-L166) · [显式激活分支](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/web/controller.ts#L59-L76) · [冷读后端](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/session-query/session-query/src/cold-read.ts#L20-L44) |
| LiveShare 与其他接口 | 不复用。LiveShare 的 `viewer-session-read` 角色包含文件上传、目标修改及提交回答，并非纯读角色。默认也不授予附件、全局控制、事件转发、任意 RPC 或 HTTP 路由。自定义 RPC 不自动继承 Gateway 的每方法策略；必须在真实 Host 装配中审计备用入口。[LiveShare 角色](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/experimental/liveshare/src/host/viewer-access-policy.ts#L123-L171) · [Connection 路由](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/client/connection/src/rpc-host.ts#L143-L209) |

这里的 RPC 与流指 Web Typert Gateway：unary 通过 `/api` 发起，流经独立认证的 `/api/remote.mux`。不把控制 socket 的 JSON-RPC 方法名混用。`session/follow` 的请求定义没有恢复游标，重开会得到新的快照，不能靠文档中的「resume」字样承诺重放。[Gateway 客户端传输](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/gateway/README.md#L53-L68) · [跟随请求定义](https://github.com/deepseek-ai/deepseek-harness/blob/d98f5d68519215da48f3002da2cb9c9d2068d81d/packages/api/session-controller/src/types.ts#L477-L491)

## 本地模型

[状态归约器](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/ReadOnlySessionFollow.kt)只保留当前所选目标的一份内存状态：当前会话日志位置 `cursor`、是否附着 Agent、是否运行。`cursor` 不是落盘确认、恢复令牌或传输序号。没有 URL、凭据、网络、磁盘、命令、历史、工具输出或模型输入。现有写向抽象不是它的传输层，两者不衔接。

- 目标组合独立绑定连接、CordiAct 本地工作区和远端会话。目标字段是本地路由身份，非服务端授权凭据；[受信交付](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/ApprovedFollowPipe.kt)绑定已核验的独占管道、远端工作区、会话和一次性凭据；不从响应猜测归属，也不把旧响应重新盖章。[固定证书 TLS 连接器](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/PinnedFollowTlsConnector.kt)在交付前验证已审批的证书与协议。证书指纹、端点和目标须经受信渠道预先绑定；不做自动发现、系统 CA 回退或信任首次连接。[签名配对原型](../../deepseek-harness/host-follow/README.md#预置信任的配对原型)在隔离控制信道中验证独立预置的签名键、精确路由、随机挑战与专用凭据的绑定；从挑战起计 15 秒，批准、连接、握手和排队不续期。回复另用每路由独立预置的密钥做 AES-256-GCM 认证加密，并绑定同一挑战；新路由入口不接受明文回复。密封不替代密钥预置、轮换或操作者批准。
- [路由控制器](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowRouteController.kt)按已配置路由、审批和载体分别核验原对象。重新配置或显式开启新挑战时立即清除旧状态，关闭旧握手与原投影；每次选择或重连都创建新世代，即使所有 ID 相同。晚到的响应或配置回调不能发布、读取或关闭新状态。必须先收到当前世代的完整基线。进程内归约器会忽略已处理编号；[管道适配器](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeReceiver.kt)则严格使用 Host 帧自带的交付序号，首个为 `0`，重复、跳跃、游标倒退、畸形内容均结束当前世代并清除内容，绝不在客户端重新编号。编号不是 Host 会话日志游标；后者允许相同或跳增。
- 断线或收到撤权时立即清除当前投影，不把旧内容当成在线状态；离开页面可清除整个选择。[端侧生命周期](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeLifecycle.kt)一次接管一个已核验、独占的载体；终止时先清状态，再请求取消原载体。首帧最长等 3 秒，端侧投影最多保留 30 秒，或以调用方提供的更早授权期限为准。即使正常流保持空闲或继续有更新也不续租；读入和状态读取同步复核单调截止，不依赖后台定时回调先执行。不自动重连、重放、读历史或恢复授权。本地截止不证明网络分区，也不代替 Host 撤权。[单次读循环](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeClient.kt)接管其绑定的流，用独立执行器读取有界字节，不打开进程或认证；读取 EOF、错误、执行器拒绝或选中变化时，清退同一载体。截止时取消须能解除实际阻塞读取，不能只标记逻辑关闭。

## 验证

[本地回归](../../deepseek-harness/src/test/kotlin/com/cordiact/harness/deepseek/ReadOnlySessionFollowTest.kt)覆盖同游标状态替换、同目标重连、A→B→A、跨实例和错目标响应、重复／缺失／无效帧以及撤权后晚到数据。七模块 JVM 共 1,227 项通过，含路由换代和密封配对回归：晚到握手不能发布，阻塞握手主动关闭，旧审批失效，轮换公钥后拒绝旧签名，且明文、错误交付密钥、错误挑战、篡改或重放不能获批。TLS 测试用即时生成的合成证书，覆盖精确指纹和协议、凭据只用一次、失败不发凭据、握手截止及端侧撤权关连接。另有 3 项显式互操作测试经真实子进程管道验证首帧、更新、EOF 清除、旧流隔离，以及 Host 保持静默时本地截止能主动关闭管道并解除阻塞读取。另有 4 项 [Node ↔ Kotlin 回环 TLS 互操作](../../deepseek-harness/src/test/kotlin/com/cordiact/harness/deepseek/FollowTlsInteropTest.kt)：除更新、错域、撤权、预握手和监听关闭外，新增仅转发加密字节的回环故障夹具。夹具隔离 Host 侧流关闭与客户端 EOF，实测客户端仍按配对的本地 15 秒截止关闭连接并清除投影，无须界面轮询。所有互操作使用合成数据，不依赖外网，未生成 APK。

## Host 插件验证

[进程内插件](../../deepseek-harness/host-follow/README.md)在独立验证 profile 使用真实 Loader 和 DSH 活态注册目录。可信控制面与读取服务分开；后者仅接受独立的一次性、单目标、短时授权。Host 不装 Gateway、Web、LLM 或通用命令，不查冷存储，也不注册监听器。撤权、过期、解绑/归档、会话换代及插件卸载可关闭现有进程内读流。测试的数据和目录均为合成，不访问生产会话。

Node 34 项核心、IPC、配对签发、密封、终端审批、回环 Host 装配和 TLS 慢读测试通过。可选的 [Cordis 装配插件](../../deepseek-harness/host-follow/loopback-plugin.mjs)只在显式进程内启动后监听；验证了启动与卸载交叉时清退，以及旧服务引用拒绝使用替代依赖。[回环装配](../../deepseek-harness/host-follow/loopback-host.mjs)由实际 TLS 证书和监听端口形成签名路由，拒绝不匹配私钥，关闭时清退审核、授权和连接。终端适配器覆盖非交互拒绝、精确确认码、错误回复、超时和取消；PTY 仅证明交互逻辑，不证明生产操作者身份。固定路由覆盖单次审核对象的作用域、过期、取消与替换，以及挑战去重、失败签发不重试、替换和在途撤权；真实回环慢读端让写回调停滞，Host 在最后一次拉取后约 3 秒关闭，未再取下一帧。该测试使用高频合成源，不是生产吞吐或内存基准。另有 1 项真实 Loader 测试从空目录用锁定 npm 包和固定 Git 对象重建后通过，复现命令已纳入仓库 CI。原样 npm 固定包因 Loader 缺导出无法启动，混合验证工件不是可部署制品。[同树正式构建验证](../../deepseek-harness/host-follow/README.md#用同一源码树的正式构建验证)单独执行，不在任何模式间自动回退。Linux arm64 的干净固定 checkout 经冻结安装后，官方工件构建 8 阶段通过，并在禁网命名空间直接运行已构建 CLI，通过同一个合成 profile。无监听器模式通过继承管道覆盖首帧、更新和撤权断流；独立的 `--loopback-tls` 模式将同一 Loader 的授权服务接入 Host 配对控制器，固定 CLI 从真实 PTY 读取测试拒绝与随机确认码。已核对终端显示的路由与指纹，拒绝不触发签发；确认不经测试 IPC，终端也不显示 bearer。Node 测试客户端用独立预置的密钥打开密封回复并核验签名与完整路由，再经专用 TLS 读取合成会话。验证错域、重复挑战与凭据、非专用凭据和 HTTP 前缀拒绝，并确认读取插件卸载时，依赖它的装配服务取消注册、现有流关闭、回环端口拒绝新连接，旧启动和审批引用不能再用。TLS 载体未加入默认 profile；本地验证使用 `127.0.0.1`，单独启用 `lo` 的隔离工作流尚未远端运行。

## 接入前必须补的证据

1. 提供固定版本 Host 装配和独立认证主体（**非 owner**），服务端默认拒绝。只允许对显式授权目标读取脱敏的状态投影；不接受默认 Web/控制凭据，不做低权限失败后回退。投影须在 Host 输出有界字段，不能先把完整队列传进手机再隐藏。
2. 用无生产数据的 Host 集成/协议测试，证明合法目标能读，另一会话和另一工作区被拒绝；对 prompt、cancel、fork、activate、上传、附件、通用控制、互动回复及替代 RPC/HTTP 入口进行拒绝测试。若要求宿主不写盘，固定可审计存储配置并覆盖冷观察。
3. 继承管道与固定 Loader 的合成 TLS profile 已验证关闭及卸载清退；尚缺生产受信配对、非回环传输策略、外部载体背压、跨网静默断线及 Android 端侧清除的联合证据。回环故障注入只验证本地截止，不验证设备休眠、网络分区探测或重新连接。断线、关闭或重连时，载体、目标和凭据的世代不能替换旧回调身份。未验证前不新增 Android 连接入口。
