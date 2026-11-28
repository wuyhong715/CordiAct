# 架构治理验证清单

## 已完成的静态门禁

- [x] 根模块包含 `app` 与 7 个 JVM 模块。
- [x] JVM 模块源码没有 Android、Compose、WebView 或 Shizuku import。
- [x] 能力状态文件存在，且每项都有 `status` 和 `executionTarget`。
- [x] Sandbox、Remote Harness 和验证入口的边界已写入治理基线。

运行命令：

```sh
python3 tools/check-architecture-governance.py
```

## 尚需外部证据的门禁

| 门禁 | 证据入口 | 当前状态 |
|---|---|---|
| JVM 全量测试 | `./gradlew -p verification/jvm test` | 待手机执行 |
| Android 编译和 APK | `.github/workflows/android.yml` | 待 GitHub Actions |
| API 29/35 隔离服务 | Android emulator job | 待 GitHub Actions |
| 真机执行与权限 | CI APK + 真机报告 | 待设备执行 |
| Harness 互操作 | CI Node/Kotlin jobs | 待 CI 复核 |
| Release 无 debug probe | Release manifest/APK/DEX 检查 | 待 CI 复核 |

静态门禁通过时，不能把上述待执行项目标记为通过。没有证据的项目必须保持 `NOT_RUN`。

