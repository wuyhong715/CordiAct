# 0008 渲染器先行：Remotion 在 Android WebView 上跑通的可行性

- 日期：2026-11-05
- 前置：`0007-embedded-browser-engine.md`（内核可行性）。004 的"Chrome 94+"已澄清为 Remotion 的浏览器渲染门槛，不是随手基线。
- 本阶段目标：先做渲染器（Remotion 视频），浏览器自动化留到下一批无障碍插件一起做。
- 证据来源：Remotion 官方文档源码（`remotion-dev/remotion` main：`web-renderer/index.mdx`、`client-side-rendering/{index,how-it-works,limitations,html-in-canvas}.mdx`、`license/faq.mdx`、`telemetry.mdx`）、MDN `VideoEncoder`、Chromium `android_webview/docs/developer-ui.md`、w3c/webcodecs issue #397、本机 `dumpsys webviewupdate`。

## 0. 结论

1. **渲染器不需要内置内核。** Remotion 浏览器渲染的唯一硬门槛是 WebCodecs（Chrome 94+），本机系统 WebView 是 **156.0.8078.47**，超出 62 个版本。包体代价 0。
2. **"157 内核"在 Remotion 语境下买到的是另一件东西**：`html-in-canvas` 原生捕获（`drawElementImage`），它把"逐元素重绘"换成"整页截图"，从而摆脱 CSS 子集限制。但它依赖 Chromium flag `chrome://flags/#canvas-draw-element` **显式开启**，WebView 没有 flags 入口（`WebView DevTools` 只能翻精选列表，且不能随 App 分发）。**所以即便 WebView 升到 157，也大概率仍旧走 DOM composer。** 想靠"157 内核"拿到像素级一致，这条路在 WebView 上不通。
3. **决定成败的是三件与内核版本无关的事**：
   - **secure context**：`VideoEncoder` 只在 https 源可用。`file:///android_asset/`、`data:` 一律拿不到 WebCodecs → 渲染页必须由 `WebViewAssetLoader` 以 `https://appassets.androidplatform.net` 提供。
   - **H.264 硬件编码**：Android 上 WebCodecs **没有 H.264 软件编码回退**（w3c/webcodecs #397），codec 串写错（如 1080p 用 `avc1.42001e` = Baseline@3.0）会直接 `NotSupportedError`。必须 `VideoEncoder.isConfigSupported()` 探测 + webm/VP8 兜底。
   - **DOM composer 的 CSS 子集**：transform/opacity/圆角/边框/渐变/文字属性支持，`perspective`、`transform-style`、`writing-mode`、`object-position`、非线性的 `background-image` 不支持。模板设计必须照着这张清单写。
4. **建议 P0 走"`@remotion/web-renderer` 跑在 WebView + appassets 包"**，两天内做一个 spike 把上面三条验证掉；原生捕获（PixelCopy + MediaCodec）作为 B 方案保留，不预先实现。服务器渲染（VPS 上的 Remotion CLI）作为"高保真导出"的可选路径，不进 P0。
   **> 2026-11-05 修订（见 `0010`）**：产品形态确定为"渲染器以 HTML 为主、Remotion 只做静帧与 ≤5s 样片"，本文件的"全片端侧渲染"降为 L2 非推荐路径；P0 主线改为 **HTML 预览 + L0 抽帧**，两者都不依赖 WebCodecs。

## 1. Remotion 一侧的硬事实

| 事实 | 含义 |
|---|---|
| 客户端渲染要求 WebCodecs；最低 Chrome 94 / Firefox 130 / Safari 26 | 94 是 WebCodecs 门槛，不是 CSS 门槛 |
| `@remotion/web-renderer` 自 v4.0.491 稳定；API `renderMediaOnWeb()` / `renderStillOnWeb()` | 可以直接在页面里调，不需要 Node |
| 编码用 **Mediabunny** 而不是 FFmpeg；默认 mp4 + h264，webm 容器默认 vp8 | 不需要 ffmpeg.wasm、不需要 SharedArrayBuffer/COOP-COEP |
| **不截视口**：遍历 DOM → `getBoundingClientRect()` → 把元素画到 canvas；文本按 `Intl.Segmenter` 切词后逐 token 绘制 | 全透明的布局计算；对应地，HTML 里"浏览器会替你做的事"（阴影、滤镜、混合模式、多列、`writing-mode`）大部分不成立 |
| `html-in-canvas` 模式：使用 Chromium `drawElementImage`，需 `#canvas-draw-element` flag，Chrome 157.0.8080.0+；失败自动回退 DOM composer | WebView 里不可用（无 flag），除非该 API 后来默认开启 |
| 渲染在宿主页面同一环境：CSS 变量/Tailwind 直接生效，但也可能和宿主样式冲突 | 渲染页建议独立 route，不与其它 UI 共享样式域 |
| 每次渲染都上报遥测（含 `window.location.origin`、IP、成功/失败），即使没设 licenseKey | 离线场景会静默失败；是否可关闭文档未写明，需向 Remotion 确认 |
| License：个人 / ≤3 人团队 / 非营利免费；其余需 Company License | CordiAct 若作为公司产品分发需要买；现在开发期不受影响 |

## 2. WebView 一侧的约束（本机实测 + 机制）

| 约束 | 依据 | 对策 |
|---|---|---|
| 内核 156，随 Play 商店升级 | `dumpsys webviewupdate` | 只做特性探测，不做版本断言 |
| 无 `chrome://flags`；`WebView DevTools` 的 flag UI 只在开发期生效，且只覆盖精选 feature | Chromium `android_webview/docs/developer-ui.md` | 不要设计依赖未默认开启的 Chromium 特性 |
| `WebCodecs` 需要 secure context | MDN `VideoEncoder`："available only in secure contexts" | 渲染页必须走 `https://appassets.androidplatform.net/...`（`WebViewAssetLoader`），禁止 `file://` |
| H.264 无软件编码器 | w3c/webcodecs #397 | `isConfigSupported` 逐档探测：720p 用 `avc1.42001f`，1080p 用 `avc1.640028`；失败降 webm/VP8 |
| 渲染产物要跨桥回传 | 4.0.508+ 提供 `outputWritable: WritableStream`；`WebMessageCompat` 也支持 `ArrayBuffer` | **首选 `outputWritable` 流式写盘**（配 `outputTarget: "web-fs"`），次选分块 ArrayBuffer；不要整体 base64（+33% 体积、峰值内存翻倍） |
| `<OffthreadVideo>`/`<Html5Video>`/`<Html5Audio>` 在客户端渲染不支持 | Remotion `limitations.mdx` | 口播/画中画类模板做不了；模板设计必须以此为一等约束 |
| `z-index` 不支持，元素按 DOM 顺序绘制 | 同上 | 模板禁用靠 z-index 叠层的写法，改用 DOM 顺序 |
| 不可见 WebView 不合成 | 0007 §3.5 | 只影响"原生捕获"B 方案；A 方案（canvas 编码）不需要可见 |

## 3. 三条路径的取舍

| | **A. web-renderer in WebView**（推荐 P0） | B. 原生捕获 + MediaCodec | C. VPS 上跑 Remotion CLI |
|---|---|---|---|
| 渲染保真度 | DOM composer（CSS 子集） | 真实浏览器渲染，像素级 | 真实 Chromium 渲染，像素级 |
| 编码 | WebCodecs（硬件，若支持） | MediaCodec Surface 直喂（硬件） | ffmpeg（软件/硬件） |
| 依赖 | 0 外部服务 | 大量原生代码（PixelCopy→ImageReader→MediaCodec→MediaMuxer） | 需要联网、上传素材、排队 |
| 需要可见窗口 | 否 | 是（PixelCopy 要求窗口可见） | 否 |
| 离线 | 是 | 是 | 否 |
| 落地成本 | 1 个 JS bundle + 1 个消息桥 | 2–3 周原生视频管线 | 一个服务 + 鉴权 + 对象存储 |
| 适用 | 手机内出片、模板受控 | 必须全 CSS 保真的场景 | 高保真导出/长视频 |

**建议**：P0 = A。若 spike 发现模板必须用 CSS 子集之外的属性（阴影、滤镜、`backdrop-filter`、竖排），再评估 B；C 作为"导出高清版"的后续选项，不进 P0。

## 4. P0 架构

```
app/src/main/assets/renderer/          # CI 用 node 预构建（vite/esbuild）后产出
  index.html                           # React + remotion + @remotion/web-renderer
  renderer.js  fonts/  templates/
        ↑ WebViewAssetLoader → https://appassets.androidplatform.net/renderer/index.html
RendererHost (:render 进程)
  ├─ WebView（无 JsInterface，仅 WebMessageListener）
  ├─ 收 progress / log / result 三类消息
  └─ outputWritable 流式接收 → FileOutputStream → /sdcard/Movies/Moxw/<job>.mp4
```

要点：

- **进程**：`android:process=":render"`。WebView 的渲染进程崩溃、内存峰值不与对话主进程共享。
- **桥**：`WebViewCompat.addWebMessageListener(webView, "CordiAct", setOf("https://appassets.androidplatform.net"), listener)`；JS 侧 `CordiAct.postMessage(JSON.stringify({type:'progress', ...}))`。**不要** `addJavascriptInterface`。
- **素材**：模板、字体、图片全部打进 assets；远端素材走 `shouldInterceptRequest` 白名单，不允许模板直连外网（避免渲染期网络抖动 + 数据外泄）。
- **API 形状**（给 future 的 body agent 看的最小面）：
  ```
  renderVideo({templateId, props, width, height, fps, durationInFrames}) -> jobId
  onProgress(jobId) -> {phase, encodedFrames, progress, etaMs}
  cancel(jobId)
  ```
- **门控**：进渲染页先 `await canRenderMediaOnWeb({container, videoCodec, width, height})`，把返回的 `issues[]` 逐条翻成人话；再用 `getEncodableVideoCodecs()` 列出本机可用编码；`isConfigSupported` 只作兜底。结果回传 Kotlin 并缓存，不支持时 UI 直接给出"本机不支持视频导出，可改用静态图/服务端导出"。
- **构建**：保持"APK 只走 CI"的既有约定；`assets/renderer` 由 CI 里 `npm ci && npm run build` 生成，产物不入库（或作为 release artifact 归档）。

## 5. Spike 清单（1–2 天，必须逐条有数）

1. `https://appassets.androidplatform.net/` 页面里 `typeof VideoEncoder === 'function'`；同内容用 `file:///android_asset/` 打开时为 `undefined` —— 验证 secure-context 结论。
2. `VideoEncoder.isConfigSupported()` 覆盖：720p30 `avc1.42001f`、1080p30 `avc1.640028`、1080p30 `vp8`，记录本机结果。
3. 3 s / 720p30（90 帧）`renderMediaOnWeb()` 端到端耗时、峰值内存（`adb shell dumpsys meminfo`），以及 DOM composer 的单帧成本。
4. 拿一个真实模板（文字动画 + 字幕 + 渐变背景 + 一张图）过一遍[限制清单](https://www.remotion.dev/docs/client-side-rendering/limitations)，列出"必须改设计"的属性。
5. 产物回传：`outputWritable` 流式写盘 20 MB 的实测耗时与内存抖动；对照一次 base64 回传，量化差距。
6. 10 s / 1080p30 完整出片，检查首尾帧、音画长度、`MediaMetadataRetriever` 能否读出。

## 6. 风险与对策

| 风险 | 表现 | 对策 |
|---|---|---|
| 设备无 H.264 编码 | `configure()` 抛 `NotSupportedError` | 探测后降 webm/VP8；或提示改用静态图 |
| 字体未就绪就绘制 | 首帧字体回退、文字宽度错位 | 渲染前 `await document.fonts.ready`；字体全部 self-host 并预加载 |
| 中文/CJK 排版 | `Intl.Segmenter` 按词切分，长句中英文混排可能断行异常 | spike 第 4 项专门用中文模板验证，必要时改成按行/按字符切分 |
| 内核静默升级 | 昨天能渲染今天崩 | CI 跑真机 instrumentation 用例；渲染页里把 `navigator.userAgent` 的内核版本带回日志 |
| 遥测外发 | 离线环境失败、隐私说明缺失 | 先向 Remotion 确认可否关闭；不可关就在设置页明示 |
| 包体 | React + Remotion + 模板产物 | 预估 1.5–3 MB（gzip 前），可接受；素材另算 |

## 7. 待确认（需要人拍板）

1. CordiAct 的分发形态（个人项目 / 公司产品）→ 决定 Remotion 是否需要 Company License。
2. 视频导出是否必须离线。若允许联网，"VPS 渲染"能以更低成本拿到像素级保真，且不受 DOM composer 限制。
3. 渲染时长上限（多少秒、多少分辨率算"可接受"）——这决定 A 方案够不够用，也是 B 方案是否值得投入的唯一判据。
