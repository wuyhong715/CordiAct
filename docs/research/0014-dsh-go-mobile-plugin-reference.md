# DSH Go 移动端插件参考：把 Host、传输和原生渲染分开

**核对范围：** [DSH Go v1.3.0](https://github.com/ccch1mneyyy/dsh-Go/releases/tag/v1.3.0)，提交 [`da79802`](https://github.com/ccch1mneyyy/dsh-Go/tree/da798027013851328f83f59af4adb52c3afaab23)。以下是公开文档和发布记录静态核对，未安装、运行或接入 DSH Go，未用它替代 CordiAct 的固定版本验证。

| DSH Go 的做法 | CordiAct 的决定 |
|---|---|
| Android 原生界面与本机 DSH 引擎分进程；Flutter 客户端按同一接口复用本地和远端载体。Android 本机运行的是经 PRoot 的 Ubuntu/Node DSH，不是 Go 重写。安装产物另有平台专用锁、补丁和分层引擎升级。[架构](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/architecture.md#L7-L56) · [运行时构建](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/architecture.md#L380-L394) | 保持 Compose + Kotlin 宿主，不为接入远端插件在 APK 内再交付一个 Node/Linux 运行时。把所选主机能力、连接世代、业务投影分离；同一原生组件可消费本地数据或以后经过授权的远端投影，不共用可写凭据或取消对象。 |
| 工具结果卡随对话记录流转；额外的原生槽和回调要专用桥，按主机提供不同覆盖。普通 DSH 网页插件也不是全量免改兼容。[选型](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/plugins/choose-path.md#L5-L26) · [主机覆盖与降级](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/plugins/hosts.md#L41-L56) | 保持现有历史通用展示，单一扩展缺失不影响对话。下一批选一个只读、已有产品需求的声明表面；UI 按工作区、连接和协议版本判断该表面，不按插件名、主机种类或「已连接」猜能力。未知或撤回使用明确的降级状态，不加载网页脚本兜底。 |
| 桥接提供版本与功能协商、贡献目录、原生渲染数据和有边界的执行；兼有调用、上传等可写能力，不是只读角色。原生插槽的配额和响应预算由桥执行，用户授权不隐含在 View 中。[桥接边界](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/architecture.md#L356-L378) · [稳定性与配额](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/plugins/stability.md#L7-L62) | 借鉴「Host 发布最小描述、原生宿主解释」的模式，不复用 DSH Go 的宽权限接口。P2 继续只有显式授权目标的三字段状态与撤权；不加发现、通用 RPC、调用、上传或互动。首次完整基线、离线/撤权清空和旧世代隔离仍由 CordiAct 自己验证。 |

**兼容性不是同一个版本号。** DSH Go v1.3.0 [构建脚本默认锁定](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/runtime/build-runtime.sh#L41-L49) DSH `0.2.2-rc.1`；其功能对照还保留 `0.2.1-rc.2` 下的旧实测记录。CordiAct P2 审计的是 `0.2.3-alpha.1`。并且 DSH Go 交付的是另有锁定和补丁的运行时，不能以它能启动来否认 [CordiAct 记录的 npm Loader 缺导出](../architecture/dsh-readonly-session-follow.md#host-插件验证)。可参考它把运行时制品与客户端发布分开验收，不直接借用它的工件证明本项目门槛通过。[DSH Go 官方功能对照中的实测记录](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/docs/official-parity.md#L127-L138)

**许可。** CordiAct 是 [Apache-2.0](../../LICENSE)；[DSH Go 仓库许可说明](https://github.com/ccch1mneyyy/dsh-Go/blob/da798027013851328f83f59af4adb52c3afaab23/README.md#L345-L354)区分 AGPL-3.0 主体、单独许可的 SDK/示例和第三方内容。本轮只借鉴设计，不复制代码、资产、运行时补丁或品牌。若以后采用明确 MIT 授权的独立材料，须按具体文件核验许可并保留声明。
