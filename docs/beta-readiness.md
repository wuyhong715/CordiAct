# CordiAct 公测：签名与放行清单

## 分发

- 公测工作流 `Signed beta APK` 仅可在 `main` 手工运行，产物是 Actions artifact，不会自动公开发布。Android `compileSdk` / `targetSdk` 为 36。此工作流交付 APK，不是 Play 上架全流程。

## DIY 客户端发布门槛

- 运行 `plugin-sdk` 的 Node 测试、TypeScript 类型检查和 CDX1 签发互操作测试。
- 在手机上串行运行 core、interaction、search-providers、model-providers、subagent 和 UI 的定向测试。当前已通过 128 项 JVM 测试和 8 项 SDK 测试，最后一批自管目录、会话视图、工作流和子 Agent 预设变更仍需手机恢复后重跑。
- 通过 Android CI 的 Debug、Release 和 instrumentation 构建。API 29/35 隔离服务测试需要覆盖脚本工具、策略撤回和工作流终止。真实设备安装 CI 产物后，复核配置导入、签名目录刷新、工作区隔离、插件停用、模型路由撤销、旧会话回放和进程重启。
- DIY 阶段完成前，不接入 Remote Codex、Claude Code 或 DeepSeek Harness。Remote 的固定版本、配对和恢复验收属于下一阶段。
- 在 GitHub 配置 Secrets：`CORDIACT_BETA_KEYSTORE_BASE64`、`CORDIACT_BETA_STORE_PASSWORD`、`CORDIACT_BETA_KEY_ALIAS`、`CORDIACT_BETA_KEY_PASSWORD`；配置变量 `CORDIACT_BETA_CERT_SHA256` 为从持有的签名证书独立取得的 SHA-256。不得使用仓库 debug key，妥善离线备份公测签名密钥。不要把密钥或密码放进仓库、日志、工单或对话。
- 输入如 `0.1.0-beta.1`；由工作流运行号生成递增 versionCode（重跑相同 run 不生成新版本；发布时仍需确认版本号大于已发版）。工作流在上传前核对签名、包名、版本、target SDK 以及 release manifest，无签名输入则不上传。
- `com.cordiact.app.beta` 和现有 debug `com.cordiact.app` 并存。不会覆盖 debug 数据，也不会自动复制数据库或凭证。保留旧包；需重配模型、连接、工作区与权限。尚无全量会话迁移；不要为安装 beta 卸载旧包。
- 先下载核对 SHA-256，做 Beta A 新装 → A 创建会话和数据 → Beta B 原地升级 → 查数据、设置和授权。故障用相同证书和更高 versionCode 投放修正版；禁止建议卸载或直接降级替代回退验证。

## 放行前实测（未完成）

1. 干净安装：工作区 → 模型提供商 → 首条对话 → 人工审批只读工具。检查错误密钥、拒绝权限、断网和中文输入法；代表机型检查附件及模型能力提示。
2. 两个工作区分别授权 → 撤销 → 重启 → 升级：确认授权不溢出工作区，撤销仍有效，旧开关不会成为同意。只发虚构凭证做脱敏检查。不把 Root、自动审批、通用第三方代码宿主、跨进程自动续跑作为首发承诺。
3. vivo 与另一个 Android 机型：锁屏、省电、断网、禁用通知、杀进程。确认系统状态不会被误标为模型可达；中断的未知出站操作不自动重放，立即运行需人在界面确认。此门禁针对手动开启新运行，不等同更改任务的正常周期；不知道外部结果时应暂停周期任务并核对目标端。
4. 设置 → 关于：复制诊断概要仅含版本、包名、系统 API、设备型号。用户另填失败阶段和复现步骤；不收集对话、文件、日志或凭证。

依旧未验：签名 CI 实跑、A→B 真机升级及回退、旧库授权迁移、机型后台限制。未验证前不作为公开公测通过。
