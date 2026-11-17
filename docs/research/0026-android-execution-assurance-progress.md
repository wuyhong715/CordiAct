# Android 任务运行保障：首批实施（2026-11-17）

基线 main fc605a0，前台实施，未提交/推送/打包。工作目录 /workspace/CordiAct。

## 已实现
- core/jobs/ExecutionPresence.kt：独立于持久化生命周期的进程内执行阶段，按来源合并；来源清空不会清除其他来源，前台资格显式表达。
- ExecutionPresenceHost：统一 Job/自动化投影；标准低重要性通知、点击回主界面、锁屏通用文案。前台服务请求失败时降级普通通知（请求被接受不等于服务已成功启动）。
- AutomationTaskRuntime：准备、模型、工具执行阶段发布，finally 清理；异常 sessionId 进入失败处理。
- AgentExecutionManager 不再独立停止整个服务，交由统一投影仲裁。
- AgentExecutionService：通知返回入口和锁屏脱敏；API35 timeout 停止服务，不重放外部动作；停止使用 startService 发送 STOP；移除旧固定30分钟 wake lock，不以 CPU 锁代替执行窗口。

## 边界和待办
- 现有持久化 TaskRunState 和 recoverInterrupted 复用，未新增安全自动续跑、租约、幂等回执。
- 自动化任务仍依赖现有 WorkManager 窗口，不宣称 dataSync FGS 资格；既有 Job 路径仍沿用前台服务，下一阶段须按工作类型审计资格。
- 等待审批阶段枚举已定义，尚未连接审批生命周期；不能把它当已支持。
- timeout 只停止服务，不意味着底层任务已安全取消或保存检查点。未做超时后的重启抑制及运行条件状态反馈。
- 当前 compileSdk35，未接 Android Live Updates、OEM 岛/云；未引入厂商或推送 SDK。
- 暂无通知内敏感操作，只有打开主界面，不绕过审批。
- 未做 APK/CI/真机验收；后台、锁屏、通知拒绝、FGS超时需要后续真机验证。

## 验证
- `cd /workspace/.verify-integration && sh gradlew :core:test --tests '*ExecutionPresenceTest' --tests '*AutomationServiceTest' --console=plain`：3+13测试通过。
- `cd /workspace/CordiAct && sh /workspace/spike/local_compile.sh`：App Kotlin编译通过，不构建APK。
- `git diff --check` 通过。

## 第二批：审批边界与超时抑制
- ObservingApprovalGateway 委托真实网关，不改变审批结果；同步工具派发期间的 requestApproval 发布 WAITING_USER，结束/异常回到 TOOL，再回 MODEL。ThreadLocal 只归属同步线程，不猜测跨线程归属；自动审批瞬时决策也经过该边界，不等于一定在等人工。
- FGS timeout 持久化阻止再次拉起；普通状态更新不解除，实际 MainActivity.onResume 才解除。通知显示额度耗尽提示；即使通知发布失败也 finally 停止服务。
- 这不是底层 Job 的取消/检查点保障，也未实现跨进程任务租约；超时标记不承诺系统预算已经恢复。
- 针对性测试共21项通过（审批观察4、审批队列回归1、执行状态3、自动化13）。App Kotlin编译通过；无APK/真机验收。

## 第三批：持久化工具执行边界
- AutomationRun 添加可选 executionCheckpoint，旧存储无字段仍可读取；存储执行阶段、时间及随机工具边界 token，不存工具参数/密钥/模型输出。
- claim 写 PREPARING；MODEL/TOOL/WAITING_USER 阶段真实写盘，重复阶段不重复写。
- 工具调用前 beginTool 持久化 pending token，写盘失败不执行工具；同步派发正常返回后 completeTool 清除 token（只证明派发返回，非外部成功收据）。抛错保留 token，禁止直接跳回 MODEL 或将任务标为成功。
- recoverInterrupted 对未结工具明确记 external effects unknown/do not replay，保留 token，不重新排队。进程中异常结束的任务同样保留未知外部效果诊断。
- 取消与派发返回竞争时允许记录已有工具返回，但不复活 CANCELLED 状态。未实现 exactly-once、跨进程租约、工具专属幂等键或自动安全续跑。
- 新增2项服务测试验证重开存储/中断恢复、token校验、未结边界成功门禁及取消竞争；相关测试共23项曾通过，最后修改后重新验证自动化15项及App Kotlin编译。
