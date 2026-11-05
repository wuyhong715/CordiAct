# 0010 渲染器定位修订：内容宿主 + Remotion 三级预算

- 日期：2026-11-05
- 触发：产品形态澄清——端侧完整渲染压力太大；端侧只做单帧静帧与 ≤5s 样片供 agent 迭代；完整渲染提供但不提倡；渲染器日常主要服务 HTML 等普通内容；必须深度嵌入 CordiAct，不跳外部。
- 影响文档：`0008`（Remotion 技术地基）与 `0009`（外部报告复核）结论不变，但**架构中心从"Remotion 宿主"改为"内容宿主"**。

## 0. 结论

1. **架构中心是 `RenderSurface`（内容宿主），不是 Remotion 宿主。** 一个常驻的 WebView 承载"一段内容"，Remotion 只是它能渲染的第四种内容类型。
2. **端侧 Remotion 分三级，预算是硬约束**：L0 静帧（agent 的眼睛，p95 < 800 ms）→ L1 ≤5s 样片（< 10 s）→ L2 完整渲染（不推荐，默认引导云端）。
3. **L0 不消耗 WebCodecs**：`renderStillOnWeb()` / `renderFramesOnWeb()` 只把 DOM 重绘到 canvas 再编 PNG/JPEG。**因此"内核版本 / H.264 / secure context"这条风险链从主路径上消失了**——它只影响 L1/L2。
4. **默认全内嵌**：`AndroidView` + 常驻单实例 + 磁盘缩略图。禁止 `Intent` 跳出看结果；外链一律拦。
5. 完整渲染不是"不能做"，是**不进热路径**：L2 只出现在二级菜单，带耗时/发热预估，超阈值转 VPS（`洛杉矶`/`首尔` 已具备 Node 环境，可跑 Remotion CLI）。

## 1. 内容路由

| 内容类型 | 载体 | 理由 |
|---|---|---|
| Markdown / 纯文本 / 代码块 | Compose 原生 | 可选文本、可搜索、跟随主题、零内核依赖 |
| HTML / SVG / Canvas / 图表 / 表格 | WebView `preview.html`（**不含 Remotion 运行时**） | Web 生态的唯一入口；保持 bundle 小 |
| 交互式表单 / 页面片段预览 | 同上，加 sandbox iframe | 与宿主样式隔离 |
| Remotion composition（静帧/样片/完整） | WebView `remotion.html`（**懒加载**，独立 bundle） | React + Remotion + mediabunny 约数 MB，不为纯 HTML 付这个成本 |

两条 document 路由共用同一个 WebView 实例，切内容 = `loadUrl` 另一个入口，不是新建 WebView。

## 2. 三级预算

| 级别 | API | 上限 | 目标时延 | 产物 | 失败降级 |
|---|---|---|---|---|---|
| **L0 静帧** | `renderStillOnWeb({frame, scale})`；批量用 `renderFramesOnWeb({frames:[...]})`（一次挂载，4.0.540+） | ≤ 6 帧 / 次，`scale` 0.5（540p 量级），`delayRenderTimeoutInMilliseconds` 5 s | p95 < 800 ms | PNG（≤ 200 KB/张） | 超时 → 只回文本描述 + 提示"渲染超时" |
| **L1 样片** | `renderMediaOnWeb({scale: 0.5, fps: 12, durationInFrames ≤ 75, outputWritable})` | 5 s / 12 fps / 540p | < 10 s | MP4 ≤ 2 MB | WebCodecs 不可用 → 退回 L0 静帧序列（GIF/APNG 或分段静帧） |
| **L2 完整** | `renderMediaOnWeb({scale: 1, fps: 30})` | 不限，但**默认不做** | —— | MP4 | 预估 > 60 s 或 > 600 帧 → 建议云端 |

L2 的触发条件收窄到：用户显式点"导出高清"、一次性明示"预计 90 秒 / 会发热"、且 App 在前台（`FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` + `PARTIAL_WAKE_LOCK`）。超出阈值直接给"发送到云端渲染"的选项，而不是让用户在手机上等。

## 3. Agent 闭环：把渲染器当眼睛

这是"端侧渲染"真正的价值所在，也是最该先做的东西：

```
agent 改 spec
   → renderFramesOnWeb({frames: [0, 25%, 50%, 75%, N-1], scale: .5})   // 一次挂载
   → 拼成 1 张 contact sheet（2×3）
   → 作为图片回灌给模型："这是我按你的改动渲染出来的关键帧"
   → 模型据此继续改
```

规则：

- **末帧必抽**。转场、出入场动画的 bug 几乎都落在最后一帧（元素没归位、字幕溢出）；只抽 0/50% 会漏。
- 有转场时在转场中点额外加 1 帧，总数不超过 6 帧（一次挂载，成本线性但很低）。
- **拼成一张**再回灌：一张 1080×1620 的 2×3 拼图，信息量等于 6 张但只花一张图的 token；模型也能直接比较帧间变化。
- 分辨率 540p 起步；模型不需要 1080p，token 和视觉编码开销是纯浪费。
- 传回 Kotlin 的顺序：`blob() → ArrayBuffer → postMessage`（图片小，不必走 `outputWritable`），Kotlin 侧拼图或直接交模型。

## 4. 嵌入架构

```
Compose 聊天流
├── PreviewCard（缩略图 = 磁盘缓存的 PNG，不持 WebView）
└── 点击 → FullSheet
        └── AndroidView(RenderSurface)   ← 全局唯一实例，内容切换
                └── WebView(:render 进程)
```

- **不给每条消息建 WebView**。消息多时直接 OOM。卡片只显示首帧缩略图；点开才把常驻实例的内容切成该条目的 spec。
- `RenderSurface` 全局单例（按会话隔离），内容切换用 `loadUrl` 或 `postMessage({type:'setContent', ...})`；后者更快（不重建 document），但要求内容类型相同，跨类型（html ↔ remotion）才 `loadUrl`。
- 生命周期：滚出可视区只 `onPause()`（停定时器/播放），**不销毁**；切会话才换成占位页。页面 `onPause` 会暂停渲染与 `requestAnimationFrame`（0007 §3.5），所以"后台继续渲染"必须由前台服务 + 显式 resume 承担，不能靠挂着的 WebView。
- **不跳外部**：`shouldOverrideUrlLoading` 对一切 `http/https/intent/market/tel` 返回 true 并丢弃；只有在用户点明确的"在浏览器打开"菜单项时才 `Intent`。这一条同时满足安全基线和"深度嵌入"的产品要求。
- 缩略图缓存：`PNG` 存 app 私有目录，键 = `specHash + frame + scale`，命中直接出图，不启动 WebView。

## 5. 与 0008 的取舍

保留：`WebViewAssetLoader`（secure context 是 L1/L2 的前提，也是 CORS 的正解）、`addWebMessageListener`、`:render` 独立进程、`outputWritable` 流式写盘、能力探测（`canRenderMediaOnWeb` / `getEncodableVideoCodecs`）。

降级：`renderMediaOnWeb` 从"P0 主线"降为 L1/L2；P0 主线变成 **HTML 预览 + L0 抽帧**，两者都不需要 WebCodecs。

删除：把"端侧全片渲染"当默认体验的任何假设；base64 回传（0009 §1.2）。

## 6. 验收标准（可勾选）

- [ ] 同一会话里连续预览 20 条不同 HTML，App 内存稳定（RenderSurface 单实例，不新建 WebView）。
- [ ] `renderFramesOnWeb` 出 5 帧 540p 的 p95 < 800 ms（本机 156 内核实测）。
- [ ] 5 s / 12 fps / 540p 样片端到端 < 10 s，产物可被系统相册播放。
- [ ] WebCodecs 不可用的模拟场景下，L0 仍可用、L1 自动降级为静帧序列，UI 文案准确。
- [ ] 聊天流里点击预览 → 全屏查看 → 返回，不出现白屏、不出现外部浏览器。
- [ ] 外链点击被拦截（`shouldOverrideUrlLoading` 全覆盖用例）。

## 7. UI 设计预览 / 快速迭代（与静态 HTML 预览的四点不同）

这是渲染器的**第一职责**，不是普通 HTML 展示的变体：

| 维度 | 静态内容预览 | UI 设计迭代 |
|---|---|---|
| 交互 | 只读，点击可全禁 | 必须真可点（hover/focus/表单/滚动），否则结论不可信 |
| 视口 | 单一宽度 | 同一份 UI 在 360/390/768 下结论不同，必须能切换与并排 |
| 回读 | 取文本 | 要元素级几何与样式（为什么溢出、为什么错位） |
| 时延 | 百毫秒级可接受 | 改一行 CSS 到看见结果，目标 < 150 ms，且保留滚动位置 |

### 7.1 Agent 的眼睛：结构化快照，不是截图

**截图在这条回路里不可用**：WebView 出图必须有可见合成面（0007 §3.5），而 agent 迭代时内容通常不在前台；且像素只回答"错了"，不回答"为什么错"。

正确做法是让页面返回 JSON 快照：

```json
{"viewport":{"w":390,"h":844},
 "nodes":[{"sel":"#hero > h1","tag":"h1","text":"你好",
           "rect":{"x":16,"y":88,"w":358,"h":42},
           "style":{"fontSize":"32px","lineHeight":"1.2","color":"#111","overflow":"hidden"},
           "flags":["overflow-x"]}]}
```

按选择器列表取（默认 `body > *` 一层 + 用户点选的节点），成本远低于图片，且能直接定位问题。配合三条确定性检查，比让模型看图更准：

1. **横向溢出**：`scrollWidth > clientWidth + 1`。
2. **意外重叠**：同级节点 rect 相交且都不在 `position:absolute` 的层里。
3. **对比度**：前景/背景色算 WCAG 比值，< 4.5 报。

这三条是 UI 迭代的高频错误，且判定是纯计算，不需要模型参与。

### 7.2 多视口：同页 3 个 iframe，不是 3 个 WebView

一个页面里放 3 个 `iframe`（360 / 390 / 768），内容同源加载，宽度由 iframe 控制。切换视口 = 改 iframe 宽度（< 50 ms），并排比较 = 三个同时显示。用多个 WebView 做这件事，内存立刻翻 3 倍，且页面状态不同步。

### 7.3 元素拾取

长按进入"选择模式"，点击元素回传 `{selector, tag, text, rect, computed:{...}}`。**默认不拦截点击**——预览要真实，不能用"点击即拾取"的方式做事，否则用户测不了交互。实现用 `WebViewCompat.addDocumentStartJavaScript` 注入拾取器，而不是 `evaluateJavascript` 临时塞脚本（后者会被页面自身的 CSP/脚本时序干扰）。

### 7.4 迭代注入的三种方式

| 改动范围 | 方式 | 成本 |
|---|---|---|
| 只改样式 / 文案 | `postMessage({type:'patch', css?, html?})` → 页面内替换 `<style>` 或 root 的 `innerHTML` | 最快，保留滚动与状态 |
| 换整份文档（同类型） | `document.open()/write()/close()` 或 `loadUrl` 同类型入口 | 中等，状态丢失 |
| 换内容类型（html ↔ remotion） | `loadUrl` 另一入口 | 最慢（要重新加载 bundle） |

目标是 p95 < 150 ms 能看到变化，且不重建 document。

### 7.5 资源与依赖

- **字体**：用系统字体（`system-ui`）。CJK 字体包 10 MB+，为预览打包不划算；只有导出 L2 时才考虑嵌入字体。
- **图片**：`data:` 或 `appassets`；默认禁网，需要 CDN 的（图标库、字体）走 `shouldInterceptRequest` 白名单并缓存。
- **Tailwind 是唯一需要拍板的依赖**：官方只有浏览器端 JIT 的 CDN 脚本路径，且标注为 dev-only（v4 对应包名与可离线打包性未核实）。三条路：(a) 把该脚本打进 assets 离线用，接受体积与"非官方用途"；(b) CI 预编译一份固定 utility 子集，代价是 agent 只能用白名单类名；(c) agent 只输出普通 CSS。**这是产品决策，不是技术细节**——模型的 UI 语感目前最强的是 Tailwind，选 (c) 会明显降低首次出图质量。

### 7.6 截图与分享

只在**可见的全屏 sheet** 上做 `PixelCopy` 裁切，不在后台伪造；聊天卡片的缩略图在内容首次真实可见时生成并缓存（键 `specHash + viewport + theme`），命中直接出图，不启动 WebView。

### 7.7 验收标准（UI 迭代）

- [ ] 改一处 CSS → 画面更新 p95 < 150 ms，且滚动位置不变。
- [ ] 三视口切换 < 50 ms，并排比较时三栏内容一致（同一 iframe 源）。
- [ ] 元素拾取返回的 selector 能在快照里唯一定位到该节点。
- [ ] 快照的三条检查能抓到刻意构造的横向溢出、重叠、低对比度各一例。
- [ ] 预览默认禁网时，带 CDN 图标库的模板有明确降级提示，不是白屏。

## 8. 进阶动画预览

### 8.1 能跑什么：WebView 是真浏览器

不需要为动画做任何特殊集成，下列都能直接跑（Chromium 156）：

| 技术 | 最低内核 | 在 WebView 里的状态 |
|---|---|---|
| CSS `transition` / `@keyframes` | 古老 | 可用，走合成器 |
| Web Animations API（`element.animate()`） | 84+ | 可用，**可 seek** |
| 滚动驱动动画（`animation-timeline: scroll()/view()`） | 115+ | 可用，随 `scrollTop` seek |
| Lottie（lottie-web svg/canvas） | — | 可用，`goToAndStop(frame, true)` |
| Rive | — | 可用，`advance(t)` 可驱动 |
| GSAP / anime.js | — | 可用，`timeline.seek(t)` |
| Framer Motion | — | 可用，但**没有全局 seek**，靠 rAF |
| Three.js / WebGL | — | 可用，受填充率限制 |
| View Transitions | 111+ | 可用，但**不可 scrub**（一次性过渡） |

**"能跑"不是这次要解决的问题。** 对 agent 迭代真正有用的是"能按住"——能把动画停在 `t` 秒看那一帧。

### 8.2 把时间按住：虚拟时钟 + 批量 seek

用 `addDocumentStartJavaScript` 在页面脚本之前注入，两层机制缺一不可：

```js
// 1) 虚拟时钟：接管 rAF / 计时器语义的"现在"
let vt = 0, rafQueue = [];
const realRAF = requestAnimationFrame;
window.requestAnimationFrame = cb => (rafQueue.push(cb), rafQueue.length);
window.cancelAnimationFrame = id => { rafQueue[id - 1] = null; };
performance.now = () => vt;              // 页面读到的"现在"由我们决定
Date.now = () => 1700000000000 + vt;

// 2) 单步：推进 vt，跑一批 rAF，再把所有 CSS/WAAPI 动画对齐到 vt
window.__step = async ms => {
  vt += ms;
  const batch = rafQueue; rafQueue = [];
  batch.forEach(cb => cb && cb(vt));
  for (const a of document.getAnimations({subtree: true})) {
    if (a.playState !== 'paused') a.pause();
    try { a.currentTime = vt; } catch {}
  }
  await Promise.all([document.fonts.ready, new Promise(r => realRAF(r))]);
  return vt;
};
```

要点：

- **必须两层**。CSS/WAAPI 动画由合成器时钟驱动，不受 rAF 影响，所以要显式 `pause()` + `currentTime = vt`；而 JS 驱动的动画（Three.js、Framer、自写 rAF 循环）只认 rAF/`performance.now`，所以要接管时钟。只做一层，总有一半动不了。
- `document.getAnimations({subtree: true})` 每次 `__step` 都要重新取——hover 触发的动画是后创建的。
- `document.fonts.ready` 必须等，否则首帧文字宽度是错的（0008 §6 同一条风险）。
- 注入时机只能是 `addDocumentStartJavaScript`；`onPageFinished` 之后再注入，页面自己的 rAF 循环已经跑起来了，接管不到。

### 8.3 像素回读的分水岭（这是能力边界，不是实现细节）

想把某帧给模型看，先看内容属于哪一类：

| 内容 | 能否离屏拿到像素 | 手段 |
|---|---|---|
| `<canvas>` / WebGL / Lottie-canvas | **能** | `canvas.toBlob()` |
| Remotion composition | **能** | `renderFramesOnWeb()`（DOM composer） |
| 纯 SVG 动画 | **能**（需内联化） | 序列化 SVG → `Image` → canvas |
| 纯 DOM/CSS 动画 | **不能** | WebView 无视口截图 API，且不可见不合成 |

最后一行的替代方案，按信息量排序：

1. **结构化快照 at t**（推荐）：`__step(ms)` 之后取 `rect` + `computed style`，回答"为什么在 300ms 时元素跑出屏幕"。成本极低，不需要可见。
2. **可见时抓帧**：全屏 sheet 打开状态下逐 `t` 走 `__step` + `PixelCopy`，抽 4–6 帧。质量真，但要求前台可见。
3. **`SVG foreignObject` 栅格化**（尽力而为）：能抓 DOM 的静态外观，但外链字体/图片、`filter`、`backdrop-filter`、跨域资源会破功；不要当主路径。

所以产品上要接受一件事：**动画的"肉眼验收"由人在可见 sheet 里完成；agent 的迭代依据是结构化快照 + canvas/SVG 类内容的真像素。** 强行给纯 DOM 动画做后台截图，只会得到空白或过时的那一帧。

### 8.4 性能红线（进阶动画最容易翻车的地方）

- 只动 `transform` / `opacity`；动 `width/height/top/left` 会每帧 layout+paint，WebView 上直接掉到 20–30 fps。
- 合成层数量控制在个位数；`will-change` 只加在真正要动的元素上，加多了内存翻倍。
- `filter: blur()`、`backdrop-filter` 在 WebView 里是像素级杀手，预览阶段允许，但要在导出前提示"这段效果在低端机可能掉帧"。
- 长动画（> 1 min）会持续占 CPU，配合前台服务时要主动降频或提示。
- 可测指标：`PerformanceObserver({entryTypes:['longtask']})` 的 long task 数 + 自采样 rAF 间隔算 p95 帧时长。目标：预览 60 fps 场景下 p95 帧间隔 < 25 ms。

### 8.5 API 面：与 Remotion 共用一条时间轴

```kotlin
surface.play()                       // 实时播放（人看）
surface.pause()
surface.seek(ms: Double)             // 定格到 t（agent 与人共用）
surface.recordFrames(times: List<Double>, scale: Double)  // 抽帧 → PNG[]
surface.snapshot(t: Double)          // 结构化快照（rect + computed）
```

`seek/recordFrames` 对 Remotion 内容映射到 `frame = round(t/1000*fps)` → `renderFramesOnWeb`；对 HTML 内容映射到 `__step(delta)`。**同一个"时间轴"抽象，两种实现**——这样 agent 只需要会一件事：给定时间点，拿到画面或描述。

### 8.6 验收标准（进阶动画）

- [ ] 同一份 CSS keyframes 模板，`seek(0) / seek(300) / seek(600)` 三帧截图互不相同，且重复 seek 同一时间点像素一致（确定性）。
- [ ] Lottie 与 GSAP 模板同样可 `seek`，且与实时播放到的同一时刻一致（误差 < 1 帧）。
- [ ] 纯 DOM 动画在不可见状态下明确返回"无法取像素"，而不是返回空白图。
- [ ] 60 fps 动画预览的 p95 帧间隔 < 25 ms（本机实测，标注机型）。
- [ ] 滚动驱动动画可以通过设置 `scrollTop` 定格。

## 9. 实现状态（2026-11-05）

### 已落地

| 位置 | 内容 | 验证 |
|---|---|---|
| `core/src/main/kotlin/com/cordiact/core/render/UiSnapshot.kt` | 快照模型 + 6 类确定性检查（溢出 X/Y、同级重叠、对比度、触控热区、可点元素出屏）+ 颜色解析与 WCAG 对比度 | `./gradlew :core:test --tests "com.cordiact.core.render.*"` → **16 个用例通过** |
| `app/src/main/assets/render/preview.html` / `preview.js` | 舞台与缩放、同源 iframe 挂载、虚拟时钟 shim（`seek`/`step`/`play`/`pause`）、快照生成、错误上报 | `node tools/preview-test.js` → **11 条消息，断言全过** |
| `app/src/main/java/com/cordiact/app/render/` | `RenderSurfaceHost`（资产域、消息桥、禁跳转、禁网、生命周期）、`SnapshotParser`（不可信负载分片校验）、`RenderSurface`（Compose 宿主）、`PreviewWindow`（独立预览窗口） | 未编译（本地无 Android SDK） |
| `MainActivity` | 工具栏新增"预览"入口，打开 `PreviewWindow` | 未编译 |

示例模板故意留了三个缺陷（40dp 按钮、`#a8a8a8` 小字、720px 的溢出行），用于确认"检查"按钮真的能报出来。写这个示例时发现并修掉了一个真 bug：节点背景为 `rgba(0,0,0,0)` 时对比度检查会直接跳过——透明必须回退到页面背景，否则最该被检查的无样式文本永远不被检查。已补 `transparentNodeBackgroundFallsThroughToThePage` 用例。

### CI 结果（PR #30，commit 5f4701d）

`build` + `Android isolated-service proof (API 35)` + `Android isolated-service proof (API 29)` 全绿；API 35 模拟器上 `RenderSurfaceHostTest` 四个用例通过（0.1–0.8 s），四个"只能真机回答"的问题全部有了答案：

1. `document.write` 里的内联 shim **确实**先于页面脚本执行（`pageSawClock = true`）；
2. iframe 文档里 `getAnimations({subtree:true})` **能**取到 CSS/WAAPI 动画，`seek` 后 currentTime 对齐误差 ≤ 1 ms，换起点重 seek 结果一致；
3. `blockNetworkLoads = true` **不影响** `appassets`（`ready` 只在资产域可加载时才可能到达）；
4. 桥签名与 `WebViewClient` 回调的可空性都编译通过。

**模拟器抓到两个真 bug（本地推理抓不到）：**

- **资产前缀**：`AssetsPathHandler` 会剥掉注册的前缀，注册 `/render/` 导致请求 `/render/preview.html` 实际去找 `assets/preview.html` → 404、页面永不 announce。改为注册 `/assets/`，同时补 `onReceivedError`，让"加载失败"变成带错误码的上报而不是空白超时。
- **`View.post` 在未附着时排队**：命令从非主线程发出时走 `view.post`，而 WebView 未 attach 时该 runnable 会一直排队——页面能 announce、所有命令静默丢失。改为绑定主 looper 的 `Handler`。这在生产里同样成立：预览面在真正显示前被创建（预加载）就会命中。

顺带记录一处与本 PR 无关的 flake：`subagent` 的 `OrchestrationTest > a MAP stage starts one child per item in order()` 在 OrchestrationTest.kt:131 失败过一次、重跑通过——MAP 子任务的启动顺序存在竞态，值得单独查。

### 已知缺口（按优先级）

1. 没有像素回读：`capture()` 未实现，纯 DOM 内容按 8.3 的分水岭本就不该做后台截图。
2. 聊天流还没接：卡片缩略图缓存、单实例切换、全屏 sheet 都没做，现在只有独立验收台。
3. ~~三视口是切换不是并排~~ —— 已实现并排（见 §10 ②）。
4. Remotion 路由未接（L0 抽帧 / L1 样片），`assets/render/` 里还没有 Remotion bundle。


## 10. 窗口模型（2026-11-05 修订）

**不默认渲染。** 预览不是聊天流的一部分，而是像轨迹窗口那样独立存在：agent 写出来的 HTML/UI 只在被"投放"时进入队列，**渲染由用户按下才发生**，关闭窗口或点"停止渲染"立刻释放 WebView。

理由不是美观，是资源：一个 WebView 实例 50–100 MB、一个常驻渲染进程、以及 30fps 起的持续 CPU。把它挂在消息流上意味着用户只是在翻聊天记录就已经在为没人看的东西付费。

落地形态：

| 概念 | 行为 |
|---|---|
| `PreviewInbox` | 会话级队列，只存文本与视口，**不含任何渲染状态**；最多 12 项（旧版本自动淘汰），未查看计数驱动工具栏角标 |
| `PreviewWindow` | 独立全屏窗口。列表页只显示标题/来源（agent/本地）/视口/是否未查看 |
| 渲染 | 在列表项上按"渲染"才创建 `RenderSurfaceHost`；面板提供 360/390/768、播放/暂停、定格滑杆、检查（快照）、停止渲染 |
| 释放 | `rememberRenderSurfaceHost` 位于"正在渲染"的那个分支里，退出分支 = 离开组合 = `destroy()`。同一时刻只有一个 surface |
| 投放 | 现在由"投放示例"按钮模拟；agent 侧工具（`preview_push`：标题 + html + css + 视口，返回"已投放 N 项未查看"）下一步接 |

这条规则同时解决了三件事：聊天流不再因为预览而爆内存；用户对"什么时候花 CPU"有明确控制权；agent 无法在用户不看的时候偷偷渲染。

### `preview.push`（已接，常开）

```json
{"title": "登录页 v2", "html": "<main>…</main>", "css": "main{padding:16px}",
 "width_dp": 390, "height_dp": 844}
```

返回 `{ok, title, viewport, unseen, truncated, note}`，其中 `unseen` 是本会话里还没被用户看过的条目数——agent 据此知道"用户还没看"，从而不重复投放同一份东西。

三个刻意的决定：

1. **只入队，永不渲染。** 工具语义里写明"renders nothing"；渲染永远是用户动作。
2. **常开，不做插件开关。** 预览是会话面板能力，不是可选集成；ownership 挂在 `core.workspace-manager`（恒启用）而不是新建一个用户能关掉的插件。代价被"工具只做一次入队"限定住了。
3. **超限截断而不是报错。** `html` 200k / `css` 100k 字符封顶，超出部分截断并在返回里标 `truncated: true`——一份被截断的预览仍然比一次失败的投放有用。

### ① 抽帧：多帧结构化（已实现）

窗口里新增"抽帧"：`captureSeries([0, 400, 800, 1200])` 依次 seek + 快照，得到四份结构性快照，然后相邻两帧做差分，输出人能读、模型也能读的位移行：

```
t=0ms   · 12 个节点 · 1 项问题
t=400ms · 12 个节点 · 1 项问题
t=0→400ms · #dot 移动 (0, 40)
t=400→800ms · #dot 移动 (0, 40)
```

为什么先做结构化而不是像素帧（0010 §8.3 的分水岭）：纯 DOM/CSS 内容在不可见状态下**没有**任何办法拿到像素，PixelCopy 要求窗口可见，而 Remotion 的离屏 composer 需要把 `remotion` + `@remotion/web-renderer` 打进 assets（当前没有 npm 构建管线）。结构化抽帧不需要任何这些前提，且对"元素在动、动到哪里、有没有撞上别的元素"这类问题，数字比图片回答得更准。

仪测 `frameSeriesMakesAnAnimationLegibleWithoutPixels` 用一段 `@keyframes` 位移动画验证：三次 stop 都拿到快照，且同一节点在三帧上的 y 各不相同。

剩余（未做）：像素帧（需要可见窗口 + PixelCopy，或 Remotion bundle 管线），以及把抽帧结果变成 agent 可调用的工具——目前是窗口里的人工动作，因为渲染面只存在于 UI 进程内。

### ② 三视口并排（已实现）

窗口多一个"并排"开关：舞台从 1 个 iframe 变成 3 个（360 / 390 / 768），同一份文档同时挂到三个 frame，各自以声明宽度布局，整排再按 `min(1, 可用宽/总宽, 可用高/最高)` 缩放——**只缩显示，不改布局宽度**，否则预览会对断点撒谎。

三个实现要点：

1. **每个 frame 有自己的时钟 shim。** `seek/step/play/pause` 广播到全部 frame；否则并排比较会出现"一个停住、两个还在动"的假象。
2. **快照/抽帧只针对 primary frame**（默认 390）。"哪个元素动了"必须只有一个答案；想按 360 分析就把 primary 切过去。
3. **`probe` 报的是声明宽度**（`parseFloat(style.width)`）而不是 `getBoundingClientRect().width`——舞台整体缩放后 rect 量到的是缩放比，不是断点。

切换并排会重新挂载文档（每个 frame 以自己宽度从干净状态布局）；`setViewport` 在并排模式下会把布局收回单屏。

仪测 `compareLayoutMountsEveryBreakpoint` 断言 `frameCount == 3`、`frameWidths == [360, 390, 768]`、且每个 frame 都拿到了 shim。
