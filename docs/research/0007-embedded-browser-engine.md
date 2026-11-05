# 0007 内置浏览器内核：自动化浏览器与实时预览的地基调研

- 日期：2026-11-05
- 决策问题：CordiAct 要不要"内置"一个 Chromium 内核（原设想 Chrome 94+/157），为①自动化浏览器 ②实时预览/渲染器 打地基。
- 证据来源：本机 vivo V2463A 实测（`dumpsys webviewupdate`、`pm list packages`、`cmd webviewupdate`）、Chrome/Chromium 发布通道、Meta 工程博客、Maven 仓库元数据、androidx.webkit 文档。
- 一句话背景：现有模块里**没有任何** WebView / androidx.webkit 依赖（`app/build.gradle.kts` 只有 activity-compose、compose、lifecycle、work）。

## 0. 结论

1. **"内置 Chrome 157"不成立，而且不需要。** 系统 WebView 本身就是 Chromium：本机 `dumpsys webviewupdate` 显示唯一 provider 是 `com.google.android.webview 156.0.8078.47`（targetSdkVersion 36，已装 Chrome 但走的是 Google WebView 包）。Chrome 157 稳定版 2026-11-03 才发布（Android 版 11-04），依据 Chromium 的 Trichrome 共享机制和 Play 分批下发，系统 WebView 追到 157 只是时间问题而不是能力问题（跟进窗口的具体长度属推断，未取证）。也就是说：**能"用"157，不能"带"157**。
2. **Google 没有面向 Android 的可嵌入 Chromium SDK。** 对比 Windows 的 WebView2、桌面的 CEF，Android 侧只有两种形态：系统组件（WebView / Chrome，由 `WebViewUpdateService` 决定 provider）和整包浏览器（自编内核或厂商浏览器）。应用既不能指定 provider，也没有官方 embedder API。
3. **自建 Chromium WebView 是"公司级项目"，不是"调研后就能上"的选项。** 唯一公开案例是 Facebook：官方措辞是"we have been building and testing … for a few years"，并把 Mozilla/Microsoft/Samsung 的"自带引擎"作为先例——那些都是浏览器厂商。它至今没开源，也没有可复用的产物。
4. **"内核自控"唯一可打包的现实选项是 GeckoView**（Gecko 159.0.20261102144711；`geckoview-arm64-v8a` AAR 91.5 MB，全 ABI 244.7 MB）。它不是 Chromium：现代特性代际与 157 相当，但排版/字体/DevTools 工具链不同，且 CordiAct 的自动化脚本要按引擎做兼容。
5. **建议：不内置内核。** 走"系统 WebView + 特性探测（而非版本号门槛）+ 多 Profile + 独立进程 + 双向消息桥"，并把渲染/自动化抽象成引擎无关接口，为将来接 GeckoView 留位置。
6. **"Chrome 94"这个门槛要废弃。** 它发布于 2021-09，比本机内核落后 62 个版本；更关键的是本项目真正会用的 API（document-start 注入、多 Profile、代理覆盖）都比 94 新得多。用版本号当门槛既过时又脆弱，应该用 `WebViewFeature.isFeatureSupported(...)` 逐项探测、逐项降级。

## 1. 事实核对

| 事实 | 证据 | 含义 |
|---|---|---|
| 本机 WebView = Chromium 156 | `dumpsys webviewupdate` → `(com.google.android.webview, 156.0.8078.47)` | 用户设备上"现代内核"早就有了，且随 Play 商店升级 |
| 系统拒绝 32 位/targetSdk 过低的 provider | 同上 → `Minimum targetSdkVersion: 33` | Android 16 起对 WebView provider 有 targetSdk 下限，第三方/老旧内核更难长期存活 |
| provider 由系统选，应用无权 | `cmd webviewupdate` 只暴露 `set-webview-implementation PACKAGE`，且需 shell/root（本机经 Shizuku 执行）；`pm list packages` 里没有 `com.android.webview` | 想换内核 = 让用户去开发者选项改系统组件，App 内部做不到 |
| Chrome 157 稳定版 2026-11-03 | Chrome Release 博客（Stable Channel Update） | 系统 WebView 追上 157 是时间问题，不是能力问题 |
| WebView 无法被应用打包替换 | Chromium/Android 文档 + provider 机制 | 要"自带内核"只能整引擎自带（GeckoView）或自建 |
| Meta 自建 Chromium WebView 耗时数年、未开源 | engineering.fb.com《Launching a new Chromium-based WebView for Android》(2022-09-30) | 参照成本：专职团队、长期维护、上游补丁 |
| GeckoView 159 / arm64 AAR 91.5 MB（全 ABI 244.7 MB） | `maven.mozilla.org` maven-metadata + HTTP `Content-Length` 实测 | 包体代价：arm64 单 ABI 也要 +50~80 MB APK（压缩后） |
| androidx.webkit 当前 1.14.0；Profile API 自 1.9.0-beta01（2023-11-01） | androidx release notes | 多 Profile、文档开始注入、代理覆盖都在 stable 库里，无需自研 |

> 未核实、不作为结论：WebView 各版本的真实市场分布没有公开数据源。本机是 156 不代表用户群是 156——这决定了**必须**做运行时探测和降级，而不是"假定 ≥157"。

## 2. 三条路径的实际代价

| | A. 系统 WebView（推荐） | B. GeckoView | C. 自建 Chromium |
|---|---|---|---|
| 内核版本 | 跟随设备，本机 156，最低可能 53（Android 8 未更新机） | 由 AAR 固定（如 159） | 由你编译决定 |
| 包体代价 | 0 | +50~80 MB（arm64） | +100 MB 起，实际更大 |
| API 面 | `android.webkit` + androidx.webkit（`WebViewCompat`） | `GeckoSession` / `GeckoView`，自有扩展与 WebExtension | Chromium `content` embedder（无官方支持） |
| 自动化 | JS 注入 + 消息桥；CDP 仅调试期 | JS 注入 + `about:debugging`/CDP | 同左，但要自己维护 |
| 维护 | Google 升级，你只做兼容测试 | 你负责升级与体积 | 你负责编译、安全补丁、证书、更新通道 |
| 许可证/上架 | 无额外问题 | MPL 2.0，可商用 | BSD 但产物巨大，Play 审核与用户下载意愿是硬约束 |
| 适用判断 | 覆盖 95%+ 需求 | 需要"内核版本可控"时 | 不做 |

被排除的方案，及原因：

- **CEF**：无 Android 官方产物，社区移植不可用于生产。
- **Crosswalk**：已停止维护（最后版本 Chromium 23），不能作为"94+"。
- **腾讯 X5 / 阿里 U4**：闭源、需商务授权与安全审计；内核代际远落后于 157；包体与初始化开销都在，且引入第三方 SDK 的隐私面。对"要 157"的目标是反向选择。
- **WebView Beta/Dev/Canary 包**：只用于测试通道，不能让 App 自带，也不能保证用户设备装上。
- **`androidx.javascriptengine`（JsSandbox）**：能跑纯 JS，没有 DOM/渲染，只适合脚本沙箱，替代不了浏览器。

## 3. 自动化浏览器的地基（基于系统 WebView）

### 3.1 应用内控制面（P0 就应该只做这一层）

- **注入**：`WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOriginRules)` —— 在文档创建时注入，早于页面脚本，是"给页面装 agent 探针"的唯一可靠时机（`onPageStarted` 后 `evaluateJavascript` 会晚于页面自己的脚本）。运行时用 `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` 探测。
- **回传**：`WebViewCompat.addWebMessageListener(webView, jsObjectName, allowedOriginRules, listener)` + `postWebMessage`。**不要**用 `addJavascriptInterface`：它是全 origin 暴露的 Java 对象引用，是已知的逃逸面，且无法按 origin 白名单。
- **拦截**：`WebViewClient.shouldInterceptRequest` 做资源白名单/离线回放；`onReceivedError` / `onHttpError` 要分清"主文档失败"与"子资源失败"，否则 agent 会把广告位 404 当成页面失败。
- **会话**：`CookieManager`（按 Profile 隔离，见 3.4）；`WebStorage`、`WebViewDatabase` 同理。

### 3.2 调试期外部控制面（CDP）

`WebView.setWebContentsDebuggingEnabled(true)` 后，WebView 在 `localabstract:webview_devtools_remote_<pid>` 列出 CDP 端点；PC 侧 `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`，Puppeteer/Playwright 用 `connect({browserWSEndpoint})` 接上，可拿 DOMSnapshot/Network/Performance。**这是测试与排障通道，不是产品能力**：正式包必须关闭（否则任何能 adb 的人都能驱动你的用户会话），且它只能在有 adb 的机器上用。

### 3.3 应用内自研 CDP 客户端（暂不做）

理论上可以在 App 内用 `LocalSocket` 连到同一个 abstract socket，自己实现 HTTP Upgrade + WebSocket 帧，就能在设备上直接说 CDP。收益是拿到 `DOMSnapshot.captureSnapshot`、`Network.*`、`Performance.getMetrics` 这些 JS 桥拿不到的数据；成本是一个 ~400 行协议客户端 + 协议版本兼容 + 与内核升级同步的维护面。**判断：只有在出现"JS 桥无法满足的具体需求"时才立项**，现在不需要。

### 3.4 隔离与并发（这是最容易埋雷的地方）

- 同一进程只能有一个 data directory。要做"每个 Workspace / 每个任务一个独立浏览器配置"，两条路：`WebView.setDataDirectorySuffix()`（API 28+，需在进程内任何 WebView 使用前调用，**一个进程一个后缀**），或 `ProfileStore.getOrCreateProfile(name)`（androidx.webkit 1.9+，`WebViewFeature.MULTI_PROFILE` 探测，同进程多 Profile）。
- 预览/自动化应放在独立的 `android:process=":web"` 里：WebView 的渲染崩溃、内存峰值、`onPause` 行为都不该拖垮对话主进程。跨进程只传结构化结果（Binder/AIDL 或 Messenger），不要把 `WebView` 当对象传（不可能）。
- 渲染进程崩溃用 `WebViewRenderProcessClient` 捕获，重建会话而不是重建进程。

### 3.5 已知硬限制（设计时必须承认）

- **不可见的 WebView 不合成画面**：`GONE`/`INVISIBLE` 或未附着窗口时 `PixelCopy` 会拿到空白。要出图必须真实布局在窗口里（可以移到屏幕外或 1×1 尺寸、或用 alpha=0 但保持 VISIBLE）。
- **`onPause()` 会暂停渲染与定时器**：后台页面"看着在跑"其实停了。需要后台持续跑页面的场景要么保留可见的预览窗口，要么用 `onPause` + 显式 `resumeTimers`，要么不承诺。
- **自动化动作要靠语义定位**：优先 `querySelector` + 可见性/可点击性判定 + `scrollIntoView`，别用坐标点击；页面里有 iframe 时要先拿到 `contentWindow`，跨域 iframe 需要从顶层用 CDP 或 `postMessage` 协议。
- **等待策略**是三件事的组合：`document.readyState === 'complete'` + 网络空闲（`shouldInterceptRequest` 计数器归零 ≥500ms）+ 目标选择器出现（带超时）。只等 `onPageFinished` 会在 SPA 上误判。

## 4. 实时预览 / 渲染器

### 4.1 三档渲染，按用途选（不要用一个方案通吃）

| 档位 | 用什么 | 适合 | 不适合 |
|---|---|---|---|
| 原生 | Compose + Markdown/AST 渲染 | 聊天流里的普通 Markdown、代码块 | 任意 HTML、复杂 CSS、图表/动画 |
| 内嵌 WebView | `AndroidView { WebView }` + `WebViewAssetLoader` | agent 产出的 HTML/React/图表/幻灯片、交互式预览 | 大量并存的卡片（内存） |
| 离屏 WebView → PDF | `createPrintDocumentAdapter` + `PrintDocumentAdapter.onWrite` 直写 `ParcelFileDescriptor` | 导出报告、打印、归档 | 需要"看得见"的实时预览 |

补充：`.draw(Canvas)` 抓位图对视频/WebGL/合成层不可靠；要位图就用 `PixelCopy`（API 26+，正好是 minSdk）。

### 4.2 资源与离线（决定"预览能不能离线、能不能用 ESM"）

- `WebViewAssetLoader` 把 `assets/` 映射到 `https://appassets.androidplatform.net/assets/...`。这解决了三个坑：`file://` 下 ES module 被 CORS 拦、`fetch` 不可用、`localStorage`/Service Worker 的 secure-context 限制。
- 渲染依赖（KaTeX、Mermaid、Shiki/Prism、字体）全部打进 assets，禁外链，才能保证离线与可复现。
- 需要访问真实站点时**不要**放宽 asset loader 的 origin 白名单，而是另开一个"网络浏览器"实例——离线渲染与联网抓取是两种信任级，混在一起就是把用户 Cookie 交给模型生成的 HTML。

### 4.3 安全基线（agent 渲染模型生成的 HTML = 渲染不可信代码）

- `settings.javaScriptEnabled = true` 只在渲染实例上开；`allowFileAccess = false`、`allowContentAccess = false`、`allowFileAccessFromFileURLs/allowUniversalAccessFromFileURLs = false`。
- 不挂 `addJavascriptInterface`；需要通信走 `addWebMessageListener` + origin 白名单 `["https://appassets.androidplatform.net"]`。
- 给渲染出的 HTML 套 CSP（`default-src 'none'; script-src 'self' 'nonce-…'; style-src 'self' 'unsafe-inline'; img-src 'self' data:`），并用 `iframe sandbox` 再包一层。
- 拦截 `shouldOverrideUrlLoading`：非 `https` 一律拒（`intent://`、`market://`、`tel:` 是最常见的逃逸口），下载交给显式用户确认。
- 正式包 `WebView.setWebContentsDebuggingEnabled(false)`。
- **提示注入**：页面内容对模型来说只是数据。自动化浏览器的工具返回值必须按"外部内容"处理，页面里"忽略之前的指令"之类文本不得改变工具策略（这条应写进浏览器工具的描述与 system 提示）。

### 4.4 性能预算（需要在实现阶段量化）

- WebView 首次初始化（含加载 WebView provider、创建渲染进程）通常 200~500 ms，之后实例创建 ~50~100 ms；建议预热 1 个常驻实例（隐藏但已 `loadUrl` 到空白页）。
- 单实例常驻内存 50~100 MB 量级（渲染进程另计）。聊天流里"每条消息一个 WebView"必然爆内存，必须走"单实例 + 切换内容"或"可视区内的实例池 + 回收"。
- 大页面首次绘制与 `onPageFinished` 之间可能有数百 ms 差异，预览以"首帧可见"为准，不以 `onPageFinished` 为准。

## 5. 门控与降级：用特性探测，不用版本号

```kotlin
// 信息性：上报/展示用
val pkg = WebViewCompat.getCurrentWebViewPackage(context)   // 例：156.0.8078.47
val major = pkg?.versionName?.substringBefore('.')?.toIntOrNull()

// 决策性：按能力开功能
WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
```

降级矩阵：

| 能力缺失 | 行为 |
|---|---|
| `WEB_MESSAGE_LISTENER` 不支持 | 自动化浏览器不可用，只保留"打开链接"（`Intent` 到外部浏览器） |
| `DOCUMENT_START_SCRIPT` 不支持 | 自动化退化为 `onPageFinished` 后注入，接受 SPA 首屏脚本抢跑的竞态 |
| `MULTI_PROFILE` 不支持 | 降级为单 Profile + `setDataDirectorySuffix` 分进程 |
| 内核主版本 < 阈值 | 只在 UI 上提示"浏览器组件过旧，建议更新 Android System WebView"，不阻断主功能 |

顺带补两件常被忽略的事：

- **内核由 Play 静默升级**，意味着"昨天能用的自动化今天可能坏"。CI 必须有一层跑在真实 WebView 上的 instrumentation 用例（导航、注入、取文、截图四条主路径），并在结果里记录内核版本。
- **国产 ROM / 无 GMS 设备**上的 WebView 由 OEM 维护，常年不动。这是"要不要 GeckoView"这个问题的真正驱动力，而不是"想要 157"。

## 6. 落地路线

- **P0（预览，~1 周）**：新增 `browser` 模块（契约放在 `core`，实现按 `search-providers` 的分层习惯），实现最小渲染器：`WebViewAssetLoader` + 单实例复用 + theme/字号跟随 + 截图。先只支持"渲染一段 HTML/Markdown"，不做导航。
- **P1（自动化 v1）**：`browser_open` / `browser_read`（可读文本 + 语义节点树）/ `browser_act`（click/type/scroll/select）/ `browser_screenshot` 四个工具，全部基于 JS 桥；等待策略按 3.5 实现；返回结构化错误而不是抛异常。
- **P2（隔离与门控）**：独立 `:web` 进程 + Profile 隔离 + 特性探测矩阵 + 版本埋点。
- **P3（可选，按需求触发）**：`BrowserEngine` 接口抽象，接 GeckoView 后端；或应用内 CDP 客户端。
- **明确不做**：把 Chromium 编进 APK；用 X5/U4 换内核；让 agent 共享用户主浏览器的 Cookie（要做也必须是显式授权 + 独立 Profile）。

## 7. 追记（2026-11-05）：门槛的真身与阶段调整

- "Chrome 94" 已澄清为 **Remotion 客户端渲染的 WebCodecs 门槛**，不是随手基线；阶段目标改为**渲染器先行**，浏览器自动化与无障碍插件同批。
- 因此"157"的含义也要改写：在 Remotion 语境里 157 对应的是 `html-in-canvas` 原生捕获（`drawElementImage`），依赖 Chromium flag `#canvas-draw-element` 且 Chrome 157.0.8080.0+。**WebView 没有 flags 入口**，所以把 WebView 升到 157 也换不来这条路径——这与"内置内核"的投入产出判断无关，是硬机制。
- 渲染器的专项结论见 `0008-renderer-remotion-in-webview.md`：不内置内核，用系统 WebView + `WebViewAssetLoader`（secure context 是 WebCodecs 的前提）+ `@remotion/web-renderer` + mediabunny。

## 8. 待实测 / 待确认

1. `MULTI_PROFILE` 在本机 156 上是否 `isFeatureSupported` 为真（写一个一次性的 instrumentation 用例即可，5 分钟）。
2. 离屏/屏幕外布局的 WebView 能否稳定出图（决定"后台预览"能不能做），需要在真机上跑 `PixelCopy` 取色验证，不能靠文档推断。
3. GeckoView 159 在 arm64 + 16 KB page size 设备上的可用性，以及 AAR 体积对 CI/安装包的真实影响（如需做 P3 再验）。
4. 原设想里的"Chrome 94"到底是硬门槛还是随手写的基线？如果是后者，按第 5 节改成特性探测即可；如果是前者（比如某个依赖 WebCodecs 的渲染管线），需要把这个依赖单独拿出来评估。
5. 用户对"agent 浏览器使用哪个 cookie 容器"的预期——这决定 3.4 的默认策略，是安全设计问题，不是实现细节。
