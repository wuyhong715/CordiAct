# P2 · DSH 只读会话状态跟随

**状态：本地归约器和进程内 Host 插件已实现；默认 DSH 不符合接入门槛，未接网、未装配 APK。** 本文核对的是 [DSH `dsh-v0.2.3-alpha.1`](https://github.com/deepseek-ai/deepseek-harness/tree/d98f5d68519215da48f3002da2cb9c9d2068d81d)，不是其他版本或正在运行的 Web GUI。

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

- 目标组合独立绑定连接、CordiAct 本地工作区和远端会话。目标字段是本地路由身份，非服务端授权凭据；未来适配器必须从已经核验的请求捕获目标与世代，不能从响应猜测归属，也不能把旧响应重新盖章。
- 每次选择或重连都创建新世代，即使所有 ID 相同；旧回调不能读、写、关闭新状态。必须先收到当前世代的完整基线。进程内归约器会忽略已处理编号；[管道适配器](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeReceiver.kt)则严格使用 Host 帧自带的交付序号，首个为 `0`，重复、跳跃、游标倒退、畸形内容均结束当前世代并清除内容，绝不在客户端重新编号。编号不是 Host 会话日志游标；后者允许相同或跳增。
- 断线或收到撤权时立即清除当前投影，不把旧内容当成在线状态；离开页面可清除整个选择。[端侧生命周期](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeLifecycle.kt)一次接管一个已核验、独占的载体；终止时先清状态，再请求取消原载体。首帧最长等 3 秒，端侧投影最多保留 30 秒，或以调用方提供的更早授权期限为准。即使正常流保持空闲或继续有更新也不续租；读入和状态读取同步复核单调截止，不依赖后台定时回调先执行。不自动重连、重放、读历史或恢复授权。本地截止不证明网络分区，也不代替 Host 撤权。[单次读循环](../../deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/FollowPipeClient.kt)接管其绑定的流，用独立执行器读取有界字节，不打开进程或认证；读取 EOF、错误、执行器拒绝或选中变化时，清退同一载体。截止时取消须能解除实际阻塞读取，不能只标记逻辑关闭。

## 验证

[本地回归](../../deepseek-harness/src/test/kotlin/com/cordiact/harness/deepseek/ReadOnlySessionFollowTest.kt)覆盖同游标状态替换、同目标重连、A→B→A、跨实例和错目标响应、重复／缺失／无效帧以及撤权后晚到数据。七模块 JVM 共 1,206 项通过，其中 8 项为归约器、6 项为管道接收器、7 项为载体生命周期、4 项为读循环回归。另有 3 项显式互操作测试经真实子进程管道验证首帧、更新、EOF 清除、旧流隔离，以及 Host 保持静默时本地截止能主动关闭管道并解除阻塞读取。互操作使用合成数据、无网络，未生成 APK。

## Host 插件验证

[进程内插件](../../deepseek-harness/host-follow/README.md)在独立验证 profile 使用真实 Loader 和 DSH 活态注册目录。可信控制面与读取服务分开；后者仅接受独立的一次性、单目标、短时授权。Host 不装 Gateway、Web、LLM 或通用命令，不查冷存储，也不注册监听器。撤权、过期、解绑/归档、会话换代及插件卸载可关闭现有进程内读流。测试的数据和目录均为合成，不访问生产会话。

Node 16 项核心与 IPC 测试通过；另有 1 项真实 Loader 测试从空目录用锁定 npm 包和固定 Git 对象重建后通过，复现命令已纳入仓库 CI。原样 npm 固定包因 Loader 缺导出无法启动，混合验证工件不是可部署制品。[同树正式构建验证](../../deepseek-harness/host-follow/README.md#用同一源码树的正式构建验证)单独执行，不在任何模式间自动回退。Linux arm64 的干净固定 checkout 经冻结安装后，官方工件构建 8 阶段通过，并在禁网命名空间直接运行已构建 CLI，通过同一个合成 profile。随后以两个继承管道把实际 Loader 中的活态投影交给独立测试进程，覆盖首帧、更新和撤权断流；只验证本地 IPC，不开放网络或 Android。

## 接入前必须补的证据

1. 提供固定版本 Host 装配和独立认证主体（**非 owner**），服务端默认拒绝。只允许对显式授权目标读取脱敏的状态投影；不接受默认 Web/控制凭据，不做低权限失败后回退。投影须在 Host 输出有界字段，不能先把完整队列传进手机再隐藏。
2. 用无生产数据的 Host 集成/协议测试，证明合法目标能读，另一会话和另一工作区被拒绝；对 prompt、cancel、fork、activate、上传、附件、通用控制、互动回复及替代 RPC/HTTP 入口进行拒绝测试。若要求宿主不写盘，固定可审计存储配置并覆盖冷观察。
3. 继承管道已验证撤权关闭和本地资源上限；外部载体仍须单独验证安全认证、背压、静默断线和端侧清除。断线、关闭或重连时，载体、目标和凭据的世代不能替换旧回调身份。未验证前不新增 Android 连接入口。
