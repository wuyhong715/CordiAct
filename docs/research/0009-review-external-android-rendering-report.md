# 0009 外部调研报告复核：Remotion on Android（四方案对比）

- 日期：2026-11-05
- 被复核对象：`remotion_android_rendering_research_report.pdf`（25 页，四方案：云端 Lambda / 端侧 web-renderer / 端侧混合 PixelCopy / 原生 Skia·Media3）
- 复核方式：逐条对回上游一手材料（`remotion-dev/remotion` main 分支文档源码、GitHub API、Chromium issue tracker、本机 `dumpsys webviewupdate`）
- 关联：`0007-embedded-browser-engine.md`、`0008-renderer-remotion-in-webview.md`

## 0. 复核结论

1. **方向判断正确，可用的部分占多数**：四方案划分、DOM 逆向重构算法描述、`z-index`/`inset box-shadow`/`backdrop-filter` 不支持、RN 官方"不做"、Issue #6677 都经核实成立。
2. **但有 4 处会直接把实现带沟里**（API 形状错、回传方式错、示例代码踩安全线、方案三"100% 保真"不成立），已在 §1 逐条列出并给出修正。
3. **1 处事实偏差**：Remotion 最新版是 **v4.0.541**（2026-11-05 发布），报告写 4.0.540；Issue #6677 **已 closed**，报告当作未解决缺陷引用。
4. **5 处关键遗漏**：其中 `html-in-canvas`（Chrome 157 + `#canvas-draw-element`）正是本项目最初的问题所在，报告通篇未提；`<OffthreadVideo>`/`<Html5Video>` 在客户端渲染**不支持**这一条会直接否定"视频里套视频"类模板。
5. **报告里所有性能/内存数字都没有机型、模板、测量方法**，属于估算。不能进选型决策，只能作为待实测项（对应 0008 §5 的 spike 清单）。
6. 已按复核结果修正 `0008` 的两处设计：产物回传改为 `outputWritable` 流式写盘；能力探测改为 `canRenderMediaOnWeb()` + `getEncodableVideoCodecs()`。

## 1. 事实核对

### 1.1 核实成立（可直接用）

| 报告的断言 | 一手依据 |
|---|---|
| 客户端渲染 `@remotion/web-renderer` 自 v4.0.491 稳定 | 官方 `web-renderer/index.mdx`："The API is stable from v4.0.491" |
| 编码走 WebCodecs + Mediabunny，不用 FFmpeg | 同上："Encodes with WebCodecs using Mediabunny instead of FFmpeg" |
| 逐元素重绘：TreeWalker 遍历 → 清祖先 transform → `getBoundingClientRect()` → 累加矩阵 → `<svg>/<canvas>/<img>` 直接 drawImage、文本用 `Intl.Segmenter` 分词后 `fillText` | `client-side-rendering/how-it-works.mdx`，与报告第 10 页逐句对应 |
| `z-index` 不支持，绘制顺序 = DOM 顺序 | `client-side-rendering/limitations.mdx` |
| `box-shadow: inset` / `backdrop-filter` / `perspective` / `transform-style` / `writing-mode` / `object-position` 不支持 | 同上；注意 `box-shadow` 常规阴影、`filter`（有限函数集）**是**支持的，报告矩阵里"无高级滤镜"表述偏保守 |
| Remotion 官方不做 React Native 支持 | `docs/react-native.mdx`："Support for React Native is currently not planned due to performance issues" |
| Issue #6677「AAC 补零导致容器时长超出视频轨」 | GitHub API 返回同号同题 —— 但 **state: closed**，报告仍按未解决缺陷论述 |
| `pageResponsiveness: 'disabled'` 是合法值（4.0.487+） | `client-side-rendering/page-responsiveness.mdx`，取值 disabled/low/medium/high/数字，默认 medium |
| `PixelCopy` API 26、`postVisualStateCallback` API 23、WebView 自 Android 5 起可经商店独立升级 | Android 平台文档 |
| `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`（Android 14+） | Android 平台文档 |
| 方案三吞吐低于实时、方案二受 CSS 子集限制 | 与 0008 结论一致 |

### 1.2 必须修正（照抄会出问题）

| # | 报告写法 | 事实 / 正确写法 |
|---|---|---|
| 1 | `if (!supportCheck.canRender) { supportCheck.reason }` | `canRenderMediaOnWeb()` 返回 `{ canRender, issues: [{message}], resolvedVideoCodec, resolvedAudioCodec }`，**没有 `reason` 字段**。错误信息在 `issues[]` 里 |
| 2 | `onRenderComplete(base64VideoData)`，用 `FileReader.readAsDataURL` 回传整段 MP4 | 4.0.508+ 提供 `outputWritable: WritableStream<StreamTargetChunk>`，可边渲染边写；默认 `outputTarget` 在有 OPFS 时自动选 `"web-fs"`。base64 让体积 +33%、峰值内存翻倍——1080p/10s 的片子会瞬间吃掉 >100MB JS 字符串，正好触发报告自己第 23 页警告的 OOM |
| 3 | 示例里 `allowFileAccess = true` + `addJavascriptInterface(...)` | 两处都违背安全基线（0007 §4.3）。更关键：`file://` 页面**不是 secure context**，`VideoEncoder` 直接 undefined —— 报告的"避坑点二"只讲了 CORS/Tainted Canvas，漏掉了真正让 WebCodecs 彻底不可用的这条根因。正确做法：`WebViewAssetLoader` + `https://appassets.androidplatform.net` + `addWebMessageListener` |
| 4 | 方案三"100% 绝对还原、支持全部 CSS3" | PixelCopy 抓的是**窗口**像素，WebView 在窗口里占多大就抓多大；composition 的原生分辨率（如 1080×1920）会被降采样到窗口尺寸。要拿到 1:1 像素必须让 WebView 以 composition 尺寸离屏布局在专用 Surface 上，且不可能超过屏幕物理分辨率。**"保真"是有条件的，不是方案属性** |

### 1.3 无出处、不可用于决策的数字

- 单帧 60–180 ms、导出 6–10 FPS、900 帧需 90–150 秒
- 峰值内存 150–350 MB / 250–450 MB、APK 增量 5–30 MB
- Remotion Lambda「4K 5 分钟 → 120 并发 15–25 秒」
- 「连续渲染 20 秒后 CPU 降频 30%~50%」

这些可能是真的，但没有机型、模板复杂度、分辨率、测量方法，也没有任何引用。**结论：当作假设，交给 0008 §5 的 spike 去证伪。** 其中"1920×1080×4 ≈ 8.29 MB/帧 ×30 = 248.8 MB/s"是未压缩裸数据吞吐，不等于内存占用，容易被读成"每秒吃 250MB 内存"，是误导性类比。

### 1.4 报告漏掉、但决定成败的

1. **`html-in-canvas` 捕获模式**（`drawElementImage`，Chrome 157.0.8080.0+，需 `#canvas-draw-element` flag）。这是"157 内核"在本项目里的唯一意义，而 WebView 没有 flags 入口 → 方案二在 WebView 上永远是 DOM composer。
2. **`<OffthreadVideo>` / `<Html5Video>` / `<Html5Audio>` 在客户端渲染不支持**。这意味着"口播 + 字幕对齐""画中画"这类最常见模板在方案二里做不了，只能退到 `<Video>`（有限）或换方案。这是模板设计的一级约束，比 CSS 子集更致命。
3. **Remotion 遥测**：客户端渲染每次都上报（含 origin、IP），文档未写可否关闭。
4. **License**：个人 / ≤3 人团队 / 非营利免费，其余需 Company License。报告把方案二、三描述为"绝对零成本"，只在服务端成本意义上成立。
5. **WebCodecs 已知 bug 与内核版本强相关**：官方维护的 issue 列表里 Android 相关至少 3 条 open，其中 "VideoEncoder performance drastically degrades if the page needs to rerender"（Chrome, open）直接影响"渲染时页面还在动"的场景；"Software AVC VideoDecoder drops initial B-frames" 已在 **156.0.8064.0** 修复，而本机 WebView 是 **156.0.8078.47**，已经越过该修复点——这类"版本—bug"对应关系是必须记录进门控表的。

## 2. 对 0008 的修正（已落地）

- §4 架构：回传从"分块 ArrayBuffer"改为 `outputWritable`（`WritableStream`）→ Kotlin `FileOutputStream`，并优先让 `outputTarget` 走 `web-fs`。
- §4 门控：改为 `await canRenderMediaOnWeb({container, videoCodec, width, height})` 取 `issues[]` 做人话提示；`getEncodableVideoCodecs()` 做枚举；`isConfigSupported` 只作兜底。
- §1 事实表补两条：`<OffthreadVideo>`/`<Html5Video>` 不支持；`z-index` 按 DOM 顺序绘制。
- §5 spike 增加一条：用中文模板 + `<Video>` 同时验证"文本分词绘制"和"视频元素是否可渲染"。

## 3. 对报告"三阶段演进路径"的判断

报告的演进路线（云端 MVP → 端侧分流 → 原生化重构）是**短视频产品**的路线，不是 CordiAct 的路线。CordiAct 是 agent 框架，Remotion 在这里是"把 agent 的结构化产出渲染成视频"的执行器，不是设计师资产载体：

- 没有"设计师交付高级模板"这个角色，CSS 子集的痛感远低于剪辑 App；
- 没有 DAU/账单的规模化压力，Lambda 分流没有意义；
- "原生化重构"要求把 Remotion 组件翻译成 Skia/Media3，等于自建渲染 DSL —— 对本项目是纯亏损。

因此本项目的路径是：**方案二（端侧 web-renderer）为主，方案三只在模板被 CSS 子集卡死时作为受控特例**，且必须先解决 1.2 第 4 条的 1:1 分辨率问题。
