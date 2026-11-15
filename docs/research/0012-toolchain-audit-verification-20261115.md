# 工具链审计核实与修复（2026-11-15）

基线：feat/settings-action-overview / 812ac9e；前台完成，未创建后台任务；改动未提交。

## 核实与处理
- 文件工具未声明 defaultEnabled，连接开关仅写可见性、没有写工具 preference：成立。文件工具与 skill.open 默认启用；连接开关按插件所属能力写 tools.setEnabled，再保存能力门控。执行工具保持默认关闭，已有能力开关需要重新关闭/开启，不做静默迁移。
- bundled capability 清单遗漏 SSH / Termux / advanced shell / Root：成立，补齐宿主插件能力声明，不替代用户连接开关、执行审批及底层授权。
- job_* / tool_output_read / screen / skill.open 分类缺失：成立，增加显式分类；job_kill 为 COMMAND，screen 为 DEVICE_READ。未知工具继续拒绝；plugin_manager 不自动开放。
- 审批只能操作第一张：成立，增加下一项切换；队列按 ID 排序、过滤已决定项，clear 与 respond 同锁且不覆盖已有决定。
- Root 负结果缓存：成立，显式检测使用 refreshStatus，普通 UI 刷新继续使用无副作用缓存。
- 空模型回复：改为 FAILED，不再作为成功空回复；已有上层失败事件处理仍在。
- 同步完成后 cancelTurn=false：不视作 bug，已完成的调用不应注册成可取消任务。
- XML source 属性错误转义：成立，改为 XML 实体转义。

## 尚未解决 / 不应宣称通过
- plugin capability consent 持久层目前缺少生产接线，不伪造用户 consent，不将 bundled capability 声明称为用户授权。
- EnvelopeToolAccessPolicy 对 ROOT 存在独立硬拒绝 ROOT_REQUIRES_SEPARATE_AUTHORIZATION；本次不绕过。这意味着 Root 端到端执行仍未打通。
- 新增工具分发/审批的真机闭环尚未测试，旧能力设置不静默迁移。

## 验证
- 修复前及修复后 :core:test :model-providers:test --offline 成功。
- sh /workspace/spike/local_compile.sh --offline --console=plain 成功：仅 Kotlin 检查，临时 AIDL stub，由脚本恢复，不等同 Android 打包。
- App 单元测试尝试在 :app:processDebugResources 被本机 AAPT2 daemon startup failed 阻塞。没有安装或打包 APK。
- 用 /tmp/cordiact-tool-audit-check.init.gradle 将纯 Kotlin classifier 源码和等价 kotlin.test 测试临时挂到 core test source，针对 classifier、approval queue、tool loop 的检查成功。该临时脚本未修改仓库 Gradle 配置，不等同 app:testDebugUnitTest。
- git diff --check 通过。完整 APK/Android 单元测试仍需 GitHub Actions；尚未推送、触发或安装。


## 后续：插件同意生产接线（2026-11-15，前台）

- 工具能力门控新增 owner-aware grantsTool，工具发现与执行都实时复核；兼容已有 gate 实现。
- App gate 核对插件 ENABLED、spec 声明、当前工作区 consent 与 activeConsents。委派/编排/后台仅对明确的内置宿主插件保留运行时能力策略，连接类不再依赖 bundled 白名单直接放行。
- 连接设置显式开启：先关闭可见性，再保存工具偏好、工作区 consent、激活授权，最后打开可见性。任一步失败保持关闭。关闭撤销本工作区 consent；其他工作区不受影响。
- AndroidPluginLifecycleStore DB v3→v4 新增工作区 consent 表。不将历史开关或历史全局 consent 自动迁成工作区同意。重启恢复沿用已有 activateConsentedAccess；卸载 revokeAll 同时清理 scoped consent；插件版本变更清空同意，删掉声明的能力同步撤销。
- 旧开关无 consent 在界面显示为关闭，需用户明确开启。屏幕能力同时映射 core.display；界面文案说明开启的同意含义与审批/系统权限仍适用。
- Settings 之外的模型 plugin_manager 不写 consent，不能自行授予能力。
- 验证：:core:test 592 项、0 failure；包括新增跨工作区、伪归属、发现后撤销再执行的 gate 测试。App Kotlin-only 编译成功；git diff --check 成功。
- 授权持久化测试并入 AndroidPluginLifecycleIntegrationTest（Robolectric，CI 的 test 任务会执行）：覆盖 scoped consent、跨工作区撤销隔离、数据库重开、版本更新清空、revokeAll。本机 App 单测被 AAPT2 阻塞，未本地运行；v3→v4 真机迁移仍未验证。
- Root 独立 ROOT_REQUIRES_SEPARATE_AUTHORIZATION 仍保留，不在本次插件接线中绕过。
