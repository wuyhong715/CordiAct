# DSH 只读跟随 Host 插件（离线验证）

本插件通过 Cordis 的服务与可撤回注册接入固定版 `0.2.3-alpha.1`。不加入 CordiAct APK、已有 DSH profile 或运行中的 Web GUI。不注册 HTTP、WebSocket、Gateway、控制接口或通用命令。它是供可信 Host 装配验证的模块，不是可部署的 Android 远端入口。

## 操作与数据范围

- 可信进程内控制面用 `cordiactFollowAuthority.issue(remoteWorkspaceId, remoteSessionId, ttlMs)` 显式授予精确目标。必须是已经加载、属于该 DSH 工作区、未归档的同一会话实例；不查历史、不激活、不借用 Web/LiveShare 身份。授权随机生成，Host 只在内存保留查表摘要；每实例最多 16 个未失效授权，最长 5 分钟。这不是面向不可信同进程插件的沙箱，不能把授权服务交给载体或端侧。
- 读端只有 `cordiactFollowReader.open({ version: 1, workspaceId, sessionId, token })`。未知版本、错域、未知凭据或已经使用的凭据统一拒绝；拒绝不会查询会话。一次打开用 `next()` 获取完整替换，首个结果即基线；可同游标变化或跳增，不重放。业务数据严格只有 `cursor/active/running`；`cursor` 是当前进程会话日志位置，不证明落盘、不充当传输编号。`active` 表示附着同一会话的 Agent，无 Agent 但会话仍在内存时有效。冷会话无数据。
- 读端只保留上一投影和至多一个待取投影，不累计历史；同一流只允许一个未决 `next()`。显式撤权、到期、会话离开内存、工作区撤销/解绑或归档均会清除待取内容，唤醒未决读，并触发 `signal`。关闭或卸载后也不复用旧凭据。已经完成交付的值不可追回；未来载体必须在最终发送前复核 `signal`，不能把本轮的进程内取消当成跨网断线保证。
- 会话样本同步读取当前注册目录；变化按需与每 100 ms 采样，可能合并中间状态，不保证观测所有切换。授权期限按单调时钟复核，定时器不强保 Node 进程存活。插件不读取会话事件正文、队列、待输入或会话盘，也不调用写操作；验证夹具创建合成工作区时会写自己的临时目录。

## 验证

需要 Node 24.9+。从 CordiAct 根目录运行无依赖回归：

```sh
node --test deepseek-harness/host-follow/test/follow-service.test.mjs
```

用已能启动的固定版 DSH 工件运行真实 Loader 测试。它用临时 `DSH_HOME` 和空 bundle 清单装配精确白名单，使用真实 Session、Agent 和 Workspace 注册目录及合成数据（没有模型或会话持久化后端），不打开监听器。

```sh
CORDIACT_PINNED_DSH_BIN=/absolute/path/to/dsh/lib/bin.js \
  node --test deepseek-harness/host-follow/test/*.test.mjs
```

**固定版工件限制：** 本轮的原样 npm 安装启动失败，声明的 `@deepseek-ai/cordis-plugin-loader@1.1.0` 缺少 `dsh-app-boot` 所需的 `EntryValidationError` 导出。真实 Loader 回归因此使用隔离的混合验证工件：DSH npm 固定包仍为 `0.2.3-alpha.1`，Cordis 系列运行模块则由同一 [DSH 提交](https://github.com/deepseek-ai/deepseek-harness/tree/d98f5d68519215da48f3002da2cb9c9d2068d81d) 的 `vendor/` 源码构建。只用于测试，**不视为原样 npm 版通过，也不应部署这个 preload**。

如需复现混合验证，可从 CordiAct 根目录向临时 npm 前缀安装制品（禁用安装脚本），并准备已校验的 DSH checkout 和 `esbuild` 0.27.7：

```sh
export DSH_SOURCE=/absolute/path/to/deepseek-harness
export ESBUILD=/absolute/path/to/esbuild
stage=$(mktemp -d)
npm install --prefix "$stage" --ignore-scripts --no-audit --no-fund --save-exact @deepseek-ai/dsh@0.2.3-alpha.1
mkdir "$stage/cordiact-vendor"
for package in cordis cosmokit schemastery loader include group logger-console timer hmr; do
  "$ESBUILD" "$DSH_SOURCE/vendor/$package/src/index.ts" --bundle --platform=node \
    --target=node24 --format=esm --packages=external --outfile="$stage/cordiact-vendor/$package.mjs"
done
cp deepseek-harness/host-follow/test/register-vendor.mjs "$stage/cordiact-vendor/"
CORDIACT_PINNED_DSH_BIN="$stage/node_modules/@deepseek-ai/dsh/lib/bin.js" \
CORDIACT_DSH_VENDOR_PRELOAD="$stage/cordiact-vendor/register-vendor.mjs" \
  node --test deepseek-harness/host-follow/test/*.test.mjs
```

没有经过跨进程的认证、真正的传输背压、限字节解析、TLS 和断线时限验证，也没有 Android 连接界面。继续接入前按[架构验收要求](../../docs/architecture/dsh-readonly-session-follow.md)执行。
