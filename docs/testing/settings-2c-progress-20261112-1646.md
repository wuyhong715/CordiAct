# 设置中心 2c 进度（2026-11-12 16:46 起，前台实现）

仓库 `/workspace/CordiAct`，分支 `feat/composer-batch3`。未提交/推送；保留原有改动和未跟踪 `ui/`、`drawable/`。不是全量设置中心验收。

## 前轮延续
- 按工作区能力开关：ToolManager 在列表与调用时重检 host policy，禁用不进入模型工具列表；已加 workspace 隔离/旧请求拒绝测试。
- 被动就绪探测与连接页：Termux 需明确连通测试；屏幕/浏览器/SSH/Shizuku 检查。Root 独立 probe、MCP 全量注册及插件 inventory 统一仍未完成。
- B8：持久化用户附件限制（仅下调、请求体只读），接入导入 coordinator 与发送 AttachmentPlanner；未声明模型能力仍拒绝，已有动态用户限额发送测试。页面为基础表单，非全部视觉规范。

## 本轮实现
### B2 SSH 档案/凭据分离
- `app/src/main/java/com/cordiact/app/ssh/SshHostStore.kt`：`ssh_hosts_v2.json` 只含主机/认证方式/指纹/不透明 credentialOwner/revision；密码/私钥/口令 JSON 仅写入加密凭据库 `ssh` namespace。
- 懒加载：构造不迁移；首次读取 parse 所有旧项 → 写 vault 并回读校验 → 原子持久化无秘密档案 → 删除 `ssh_hosts.json`。无双读回退。旧文件损坏/凭据写失败均抛错，不当空列表。
- 崩溃恢复：若新旧文件同时存在，先检查新档案所引用凭据存在，再删旧文件。普通 metadata list 不解密凭据。
- 旧未 pin / 非 SHA256 主机需重新探测。新增和 repin 限 SHA256。更新先写新 credential owner 再换档案，避免档案仍旧而凭据已换。提交/清理失败可能遗留 vault 孤立项，不会写明文回退；尚无孤立凭据清理器。
- `AndroidSystemCredentials`/core SystemCredentialStore/凭据门面支持 ssh。模型/搜索仍沿用既有迁移逻辑，ssh 旧主机格式迁移仅由 SshHostStore 执行。
- `SshTools` 将存储异常转结构化失败，保留取消传播；无 pin 的 `ssh_save_host` 明确拒绝。LLM 工具仍由现行审批网关负责权限，设置 UI 的探测收据不代表工具侧可绕过审批。

### SSH 设置页
- 新 `SshSettings` 门面 + `AndroidSshSettings` + `SshSettingsPage`，首页有真实入口。
- 列表无秘密；新增密码/私钥粘贴；10 秒探测，5 分钟内匹配 host/port/SHA256 收据，用户勾选独立核对后保存。
- 已有主机可测试连接、探测/危险确认更新指纹、危险确认删除；变更走现有 SettingsOperationAudit，store revision 增长。
- secrets 仅 Compose remember，非 rememberSaveable；用已有 SecretField 限制聚焦截屏。没有 SAF 私钥导入、完整编辑/重命名、最后连接时间。
- 把 ON_RESUME 自动刷新限制在连接页，避免全局刷新覆盖尚未保存的附件/提供商表单。

### SSH 主机校验安全修复
- 核查 JSch 既有 openSession 先认证后比指纹不安全。
- 新 core `PinnedHostKeyRepository`，openSession 用 StrictHostKeyChecking=yes + 指定 repository，在 key exchange 阶段校验后才认证。不再连接后才校验。
- probe 明确生成 OpenSSH SHA256（无 padding），不依赖 JSch 默认指纹散列。探测仅用于人工核对，不自动信任。
- 非 SHA256 旧 pin 拒绝执行，需明确重探测。该变化也收紧即时 SSH 调用：未提供 pin 不再执行。

## 验证命令与边界
```sh
cd /workspace/CordiAct
./gradlew -p /workspace/.verify-settings :core:test :model-providers:test test --no-daemon
./gradlew -p /workspace/.verify-ssh test --no-daemon
sh /workspace/spike/local_compile.sh
git diff --check
```
- `.verify-settings` 指向真实 core / model-providers；已有设置 VM 纯 JVM harness。
- `.verify-ssh` 用真实 SshHostStore、SshTools、SshPlugin、SshJobProbe 与 org.json 20240303；AndroidSystemCredentials 仅是注入失败 stub。测试注入内存 vault，因此证明迁移/存储/工具行为，不证明 Android Keystore 真机行为。
- 全量 Kotlin-only 编译仍由未跟踪 `ui/composer/DynamicActivityPill.kt:59` 动画类型错误与 `ui/window/preview/ScreenshotView.kt` Coil 未解析阻塞；无本轮生产文件编译诊断。不因此声称 Android build 成功。
- 无实际设备凭据迁移、远程主机访问、APK 构建或真机验收；APK 仍应 GitHub Actions。

## 待续（不冒充完成）
- B5 MCP 凭据持久化、连接成功才安装、UI 审批安装；B10 搜索顺序/启用、服务设置页。
- 2c 就绪向导授权动作完整性、Root probe、workspace 选择器、内置能力统一 PluginInventory、MCP 能力门控。
- SSH SAF 导入、完整编辑、精确错误原因/最近连接时间。
- 全量编译旧 UI 原型阻碍、CI Android build 与真机视觉/200% 字体/导航验收。

## 最终测试统计（本轮实读 XML）
- core: 588 tests, 0 failures, 0 errors
- model-providers: 108 tests, 0 failures, 0 errors
- settings JVM: 15 tests, 0 failures, 0 errors
- SSH JVM: 10 tests, 0 failures, 0 errors
SSH 最终 run 成功；git diff --check 通过。全量 Kotlin 检查仍只报上述旧原型三处错误。
