# 0013 · 屏幕自动化 P0 真机验证

日期：2026-11-06
设备：vivo，Android 16（API 36），arm64，未 root
环境：Shizuku 以 shell（uid 2000）运行；CordiAct debug APK 来自 CI（`assembleDebug`）

## 结论

主链路全部跑通：**创建公开虚拟屏 → 在虚拟屏上拉起 App → 抓帧 → 注入点击/按键 → 销毁**。
无障碍主屏读取同时验证通过。P0 可以进入下一阶段。

## 逐项结果

| 编号 | 项目 | 结果 | 证据 |
| --- | --- | --- | --- |
| E1 | Shizuku 用户服务创建公开虚拟屏 | ✅ | `createDisplay(720,1280,320)` → `displayId=8/9` |
| E2 | 在虚拟屏启动 App | ✅ | `am start --display 9 -a MAIN -c LAUNCHER -p com.android.settings` → `launchOk=true` |
| E3 | 虚拟屏读 UI 树 | ❌ 未实现 | `dumpDisplay` 返回 `text_connection_unavailable`（见下） |
| E4 | 虚拟屏注入点击 | ✅ | `tap(9,360,240)` 返回 true，前后帧采样 `fffafaf7` → `ff05050a` |
| E5 | 虚拟屏注入按键 | ✅ | `key(9,KEYCODE_BACK)` 返回 true，帧采样回到 `fffafaf7` |
| E6 | 虚拟屏抓帧 | ✅ | `captureDisplay(9)` → 720×1280 Bitmap |
| E7 | 销毁与回收 | ✅ | `destroyDisplay(9)` 后 `dumpsys display` 不再有该 display |
| E8 | Shizuku 状态与命令通道 | ✅ | `status().level=SHELL`；`exec("id")` → `uid=2000(shell)` |
| E9 | root 探测 | ✅ 按预期 | 无 root，`PrivilegeLevel` 停在 SHELL，不误报 |

主屏无障碍（`ScreenDriver.snapshot`）读取 40 个节点，包名/标题/窗口尺寸正确。

## 真机才会暴露、已修复的缺陷

1. **Shizuku 用户服务类型错误**：`PrivilegedUserService` 继承了 `android.app.Service`。Shizuku 直接实例化该类并强转 `IBinder`，抛 `ClassCastException`，所有 `exec` 返回 `privileged channel is not available`。改为继承 `IPrivilegedService.Stub`，删除清单里的 `<service>`。
2. **用户服务没有 Context**：改用 `ActivityThread.systemMain().getSystemContext()` 反射获取；但 AIDL 调用落在 binder 线程，没有 Looper，`Handler` 构造失败。先 `Looper.prepare()` 再取。
3. **创建虚拟屏被拒 `packageName must match the calling uid`**：用户服务以 uid 2000 运行，系统 context 的 `opPackageName` 是 `android`（uid 1000）。改用 `createPackageContext("com.android.shell")` 的 DisplayManager。
4. **虚拟屏拉不起 App**：`context.startActivity` 带的是系统身份，ActivityManager 拒绝。改为在用户服务里执行 `am start --display <id>`（shell 身份）。
5. **用户服务进程死后代理失效**：缓存了 binder 但没做存活检查，进程被回收后所有调用退化成 `requires_privilege`。`binder()` 增加 `isBinderAlive` 检查并重绑。
6. **App 自身启动崩溃**（与插件无关）：`MainActivity.kt:135` 的正则 `@\{([^}]*)}：$` 在 Android ICU 下是语法错误，打开界面即 `PatternSyntaxException`；服务与界面同进程，无障碍服务也被带崩。已改为转义写法并兼容半角冒号。

## 已知缺口

- **虚拟屏 UI 树读取**：`dumpDisplay` 明确返回 `text_connection_unavailable`。要在虚拟屏上做 `find`，需要基于 `UiAutomation.connect(displayId)` 的显示通道（Android 14+，hidden API），当前 P0 未实现。因此后台屏目前只能按坐标操作，不能按节点指纹定位。
- **文本输入**：`input -d <id> text` 通道已存在但未做端到端验证（需要虚拟屏上有可聚焦输入框）。
- **性能**：抓帧耗时、连续抓帧稳定性未量化。
- **Debug 探针**：`AutomationProbeReceiver` 仍在 main source set，合并前应移到 debug variant 或删除。

## 复现方式

```bash
# 构建产物安装后
am broadcast -n com.cordiact.app/.probe.AutomationProbeReceiver \
  -a com.cordiact.app.PROBE --es case flow
cat /sdcard/Android/data/com.cordiact.app/files/probe-flow.json
```

`flow` 用例会完整走一遍创建 → 启动设置 → 抓帧 → 点击 → 抓帧 → 返回 → 抓帧 → 销毁，并返回每步结果。
