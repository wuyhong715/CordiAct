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

真实 Loader 回归也可用[一条命令](test/run-pinned-profile.mjs)复现。它需要 Git、tar、Node 与 npm，以及包含[固定 DSH 提交](https://github.com/deepseek-ai/deepseek-harness/tree/d98f5d68519215da48f3002da2cb9c9d2068d81d)的源仓库。脚本从 Git 对象取源码，不读取 checkout 的未提交改动；在临时目录以 `npm ci --ignore-scripts` 安装 [SHA-512 锁定的依赖](test/pinned-dsh/package-lock.json)，然后用真实 CLI/Loader 启动不带监听器的合成 profile。测试只创建临时 Harness home，不访问用户配置、模型或生产会话。夹具禁用 Node 网络与 IPC 套接字，发现尝试监听、连接或发送数据报就报错。

```sh
node deepseek-harness/host-follow/test/run-pinned-profile.mjs /absolute/path/to/deepseek-harness
```

已缓存所有锁定 npm 包时可加 `--offline`。普通 CI 分开执行核心回归和真实装配，不依赖 GitHub 密钥或已部署 Host。

**工件限制：** 原样 npm 固定版仍不能启动：`@deepseek-ai/cordis-plugin-loader@1.1.0` 缺少 `dsh-app-boot` 静态导入的 `EntryValidationError`、`formatIssues`、`relocate` 和 `validateOptions`。同版 Include 包也与固定源码不同；它缺少 `parseEntryYaml` 和 `stringifyEntryYaml`，但这两个函数已内联到发布的 `dsh-app-boot`，不是这次静态链接失败的直接原因。上述命令使用 `0.2.3-alpha.1` npm 制品，但在测试子进程内以同一提交重建的 Cordis 系列模块替代相应发布包。它验证固定源码的 Host 装配，**不视为原样 npm 版通过，不是部署办法**。

## 用同一源码树的正式构建验证

[手动 GitHub Actions 工作流](../../.github/workflows/dsh-built-profile.yml)在全新 Linux x64 checkout 用 Node `24.19.0` 和 pnpm `11.28.5` 安装上游冻结依赖，执行 `pnpm run build:artifacts --profile official`。随后在无网络命名空间内运行同树的 `apps/cli/lib/bin.js`，并保留正常 pnpm 工作区依赖解析。这里不替换 Cordis 或 Loader 模块；Node 套接字守卫只用于让意外的网络或 IPC 使用报错。

若本地已有用上述步骤构建的独立 DSH checkout，也可直接运行下列验证。脚本拒绝不匹配的提交、脏工作树和缺失的 CLI，打印用到的版本、平台以及锁文件和 CLI 入口摘要；它不安装、不启动 Web、不证明旧的本地工件一定来自当前构建。CI 的干净检出和同作业构建才是完整来源证据。

```sh
node deepseek-harness/host-follow/test/run-built-profile.mjs /absolute/path/to/built-deepseek-harness
```

本地 Linux arm64 以 Node `24.19.0` / pnpm `11.28.5` 完成冻结安装（本地禁用包安装脚本）、官方 8 阶段工件构建及禁网合成 profile。上游锁 SHA-256 为 `f7c01c56b65e31e1924a82bf8061ee9b63122e6e769b4aaed4781b4d1d3c088a`，实测 CLI 入口 SHA-256 为 `b293de4609dfd0625b4e5c0a88363fb49efd7d162b41a9ac2e36a1f8e196d133`。此结果不是原样 npm 版通过，也不替代待运行的 Linux x64 工作流。没有经过跨进程的认证、真正的传输背压、限字节解析、TLS 和断线时限验证，也没有 Android 连接界面。继续接入前按[架构验收要求](../../docs/architecture/dsh-readonly-session-follow.md)执行。
