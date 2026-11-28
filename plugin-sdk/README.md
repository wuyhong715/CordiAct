# CordiAct 插件 SDK

本 SDK 提供文本工具类型和 v4 扩展包打包命令。插件开发者可在自己的项目编写 TypeScript，在 Android 10 或更高版本的 CordiAct 安装生成的 JSON。

1. 在此目录执行 `npm ci`，用 `npm test` 运行打包回归和示例类型检查。
2. 将 `examples/line-counter.ts` 和配套清单作为起点。每个入口应默认导出一个接收字符串并同步返回字符串的函数。入口内部可以定义辅助函数并使用类型导入。
3. 执行 `node bin/cordiact-pack.mjs examples/line-counter.json line-counter.extension.json`。
4. 在 CordiAct 的「设置 → 插件与连接 → 工作区插件包」导入制品，检查版本和能力，保存后显式启用。在会话中让 Agent 调用工具 `count-lines`，传入要统计的文本。

打包过程只转译源码。Android 宿主负责验证包、记录授权，并将每次文本调用交给独立隔离进程。编译后脚本最多 16 KiB，完整包最多 32 KiB。参见[扩展包格式](../docs/architecture/extension-packages.md)。

要在独立项目使用，可从这个目录运行 `npm pack` 生成 SDK 包，在独立项目中将该文件作为开发依赖安装，然后运行 `npx cordiact-pack <manifest.json> <extension.json>`。

`examples/reference-search.ts` 演示工作区搜索服务。用同一命令打包 `examples/reference-search.json`，安装并启用后到当前工作区的搜索策略勾选该服务并保存。SDK 将结构化查询和结果转换为隔离进程使用的 JSON 文本。

`examples/openai-model.json` 演示工作区模型服务声明。打包、导入和启用后，在工作区的模型接入页批准端点、绑定本次密钥并授权所选模型。SDK 提供 `ModelProviderDeclaration` 类型，宿主按所声明的协议管理流式请求。

`examples/web-fetch-policy.json` 演示工具前策略。`BeforeToolPolicy` 接收工具 ID、已声明参数的存在情况和参数数量，返回 `block` 或 `continue`。宿主独立完成工具授权和审批。

[`examples/attachment-turn-policy.json`](examples/attachment-turn-policy.json) 演示回合准入。`BeforeTurnPolicy` 接收 `hasText` 和 `attachmentCount`，返回 `block` 或 `continue`。启用示例后，仅发送附件会被拦截，原草稿仍可编辑。

`examples/normalize-workflow.json` 演示四步以内的文本工作流。工作流在工作区插件页由用户显式运行，使用现有 Job 列表、进度、取消和输出限制。

[`examples/review-subagent.json`](examples/review-subagent.json) 演示纯文本子 Agent 预设。用户启用后，父 Agent 可通过 `list_children` 查看预设，并在 `delegate_task` 中传入 `preset: "review-subagent.review"`。

[`examples/conversation-views.json`](examples/conversation-views.json) 演示助手消息节点和本包工具的成功结果卡，安装并启用后可在会话中看到附加展示。

## 签发与发布

`cordiact-catalog` 使用离线 P-256 PEM 密钥。根管理员和各发布者使用独立密钥。发布工具只写本地目录，输出包含公共策略、签名清单和插件制品。私钥应留在其持有方的私有目录。

### 根管理员

准备 `root.json`，为每个发布者填写其公钥文件。示例域名需换成各自的 HTTPS 静态目录。`expires` 填未来 30 天内的 Unix 秒数，发布新版根时递增 `revision`。

```json
{
  "revision": 1,
  "expires": 1796000000,
  "trustSource": "alpha",
  "sources": [
    { "id": "alpha", "base": "https://alpha.example.test/plugins/", "packagePrefix": "alpha-" },
    { "id": "beta", "base": "https://beta.example.test/plugins/", "packagePrefix": "beta-" }
  ],
  "grants": [
    { "source": "alpha", "publisher": "alpha", "keyId": "alpha-key", "publicKey": "alpha.pub.pem" },
    { "source": "beta", "publisher": "beta", "keyId": "beta-key", "publicKey": "beta.pub.pem" }
  ]
}
```

执行 `npx cordiact-catalog root root.json root.pem root-output`。在 `trustSource` 的站点发布 `root-output/catalog/trust.cdx`。将同一次输出的 `cordiact-signed-catalog-v1.txt` 和 `trust.cdx` 交给发布者。APK 维护方使用此公共策略配置 Beta。撤销授权时发布代数更高且已移除该 grant 的完整根清单。

### 独立发布者

先用 SDK 打包插件。制品 ID 必须在获批的包名前缀下。将收到的公共策略及 `trust.cdx` 路径写入 `release.json`。工具会核对根签名、授权、制品摘要和包名。索引的 `expires` 填未来 7 天内且不晚于根到期的 Unix 秒数。

```json
{
  "policy": "root-output/cordiact-signed-catalog-v1.txt",
  "trust": "root-output/catalog/trust.cdx",
  "source": "alpha",
  "publisher": "alpha",
  "keyId": "alpha-key",
  "revision": 1,
  "expires": 1795920000,
  "artifacts": ["alpha-tools.extension.json"]
}
```

执行 `npx cordiact-catalog release release.json alpha.pem release-output`。将新 `packages/` 条目上传到已配置的发布者站点，再发布 `catalog/indexes/alpha-key.cdx`。升级时递增索引代数，给修改过的包分配新版本，并在索引中保留仍可回滚的版本。根更新后用新根重新签发索引。

### APK 维护方

用 `npx cordiact-catalog check-policy root-output/cordiact-signed-catalog-v1.txt` 检查公共策略。签名 Beta 可以通过 GitHub Actions 仓库变量 `CORDIACT_BETA_CATALOG_POLICY_BASE64` 接收该文件原字节的 Base64。工作流校验策略并核对 APK 内的资产与配置一致。客户端在「设置 → 插件与连接 → 工作区插件包」按现有流程刷新签名根和索引、预览精确版本并确认安装。

用户也可以在同一页面的「自管签名目录」导入公共策略，核对根指纹后确认。这样独立作者可分享由客户端导入的策略和制品站点，使用同一个 APK 安装和更新。
