# 输入栏批三：实现与验证（2026-11-11）

分支 `feat/composer-batch3`。本次按用户要求直接前台执行，不依赖客户端 Mission。

## 已实施
- `experience/composer/`：动作注册表（真实拍照/照片/文件）、单一模型/强度胶囊、模型搜索/置顶/最近、渐变吸附滑条、速度循环/恢复 auto、带状态的附件托盘、发送/停止过渡动画。
- 档位顺序按后端 enum：none / auto / minimal / low / medium / high / xhigh / Max；仅显示当前模型声明档位。wire 仍为 `max`。明确 AUTO overrides 防止提供商默认 high/fast 重新生效。
- 旧模型底部面板与对话内 ProviderForm 删除；提供商入口改为独立全屏页面（仅保留现有真实列表/添加功能，不冒充已完成设置中心批二）。
- C1：ModelDescriptor 发布已声明模型能力；C2：交互层携带附件、支持附件-only 提交；从消息持久化回调清理草稿，非“网络成功”或猜测时间。
- 草稿按 session 独立持久化；沿用已有 attachment_draft_refs 和 message_parts，不重建表（原设计“无草稿归属”的判断错误，源码已有该能力）。通过现有引用索引保护已发附件，启动清理已删除会话的草稿引用。有效会话草稿不设置24小时自动删，避免丢用户数据。
- 导入 IO、分块取消检查、VERIFYING 事件、失败可重试、取消后清理已落盘结果；图片使用去 GPS 的 rendition 发出并显示缩略图。
- 消息附件支持本地图片缩放、PDF 首页、文本预览。LOCAL_ONLY 明确确认后只持久化，wire 转可见文字标记，不传原文件。普通附件每次保留全量 preflight，换不兼容模型不静默丢历史附件。

## 本地证据
- `./gradlew --offline :core:test :interaction:test :model-providers:test :subagent:test :mcp-client:test` 通过。
- `sh /workspace/spike/compile_clean.sh` Kotlin-only app 编译通过。辅助脚本仅临时移开未跟踪原型 `ui/` 并还原，使用已有 AIDL stub workaround；不是 APK 构建。
- `git diff --check` 通过。
- Android Unit/Compose 测试本地受 x86_64 AAPT2 / app Java AIDL 处理链阻塞，不能声称已运行。CI 是完整 Android build/test 的证据。

## 待 CI 与真机
- 新增 ComposerRules、附件-only 交互、Max wire/AUTO、省略字段测试；新增 Android 草稿/动作注册表测试；现有 Compose 输入测试更新到新组件。
- 未做真机视觉验收：动效、IME 锚点、TalkBack、大字体、相机返回与旋转需 APK 实测。
- 设计非完整兑现项：没有全局8% scrim、模型分组为行内标签而非独立分区、未提供模型不兼容附件计数预览；PDF 只预览首页，未整合全量文档预览窗口；CompactInspector 保持原行为。
- 本 PR 不包含完整设置中心、剪贴板/分享导入、插件菜单；只注册已有可用动作，不放假按钮。

## CI 最终结果（代码 HEAD 02e41b5，PR #63）
- PR Android run `43046685228`：Build and test / Release 校验成功（完整 app 单元/Compose 测试与 APK 构建）。CI repository-check `43046685275` 成功。
- 手动 Android run `43048968556`：build、API29、API35 三个 job 全部 success。
- API35：30 tests，0 failures。
- API29：23 项通过，7 项 RenderSurfaceHostTest 由于 `AssumptionViolatedException: WebView lacks WEB_MESSAGE_LISTENER` 条件跳过。Gradle XML 将 assumption 写在 failure 下，但不能当作产品缺陷或当作已通过。未虚报 30/30。
- APK 来自 Actions（非本地构建）：`/sdcard/Download/Moxw/CordiAct-composer-02e41b5.apk`，16,217,220 bytes；SHA256 `f57889b0e5a1c2384d57100bd931dd35aebed227417406ec0a1f261df67135bd`。
- PR https://github.com/wuyhong715/CordiAct/pull/63 未合并，等待用户确认。真机视觉/交互验收尚未执行。
