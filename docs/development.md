# CordiAct 开发环境与验证入口

先按改动面选择入口：业务逻辑、模型协议、MCP、编排用纯 JVM；Compose、SQLite Android 适配、AIDL、JNI 和 APK 用 Android CI 验证，设备行为再单独取证。纯 JVM 通过不代表 Android 层通过。**Debug、Release 和 Beta APK 只由 GitHub Actions 打包，不在本地打包。**

## 纯 JVM：Linux ARM64 与常规开发机

需要 JDK 21 启动 Gradle，JDK 17 用于 JVM 模块的 toolchain。推荐用 [Gradle Wrapper](../gradle/wrapper/gradle-wrapper.properties)，版本与分发包 SHA-256 已固定。以下操作从 CordiAct 仓库根执行，JDK 的环境变量只在当前 shell 生效：

```sh
export JAVA_HOME=/path/to/jdk-21
export JAVA17_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew -p verification/jvm \
  -Porg.gradle.java.installations.fromEnv=JAVA_HOME,JAVA17_HOME javaToolchains
./gradlew -p verification/jvm \
  -Porg.gradle.java.installations.fromEnv=JAVA_HOME,JAVA17_HOME test
```

[独立 JVM 设置](../verification/jvm/settings.gradle.kts) 直接引用生产模块和它们自己的测试，不复制源文件，不加载 Android Gradle Plugin。首次在空缓存下需要联网获取 Gradle/Kotlin 插件与 Maven 依赖；缓存齐备后可加 `--offline`。只跑一类时把 `test` 换为 `:core:test`、`:mcp-client:test` 等；还可用 `--tests 'com.cordiact.core.tool.*'` 聚焦 core 用例。这里不需要 Android SDK 或 QuickJS 子模块。

**本工作区的现成环境。** 在 CordiAct 的父工作区运行以下命令。脚本仅为本地工作区提供 PATH、JDK、Gradle 缓存和 SDK 路径；不会改全局环境，也不需要配置 GitHub、模型或其他凭据。

```sh
sh .cordiact-dev/gradle.sh jvm -q javaToolchains
sh .cordiact-dev/gradle.sh jvm test --console=plain
sh .cordiact-dev/gradle.sh jvm test --offline --console=plain
```

本地 ARM64 基线 `71e26bc09aed`：Temurin 17.0.21.1／21.0.13.1、Gradle 9.8.2；六个模块实际执行 979 项测试，0 失败、0 跳过。`deepseek-harness` 适配模块编译成功，没有测试源。离线复跑成功，使用了已缓存的任务结果。要强制重新执行选定测试，可给相应的 `test` 任务加 `--rerun`。XML 和 HTML 报告在各模块的 `build/test-results/test/` 和 `build/reports/tests/test/` 下。

## Android：由 GitHub Actions 验证和打包

以 [App 构建配置](../app/build.gradle.kts#L11-L42) 和 [Android 主工作流](../.github/workflows/android.yml#L35-L76) 为准：Actions 初始化固定的 QuickJS-NG 子模块，配置 Java 17/21、Android SDK Platform 36、Build Tools 36.0.0、Platform Tools、NDK 27.2.12479018、CMake 3.22.1，执行单测并打包。工作流上传测试报告和 Debug APK，构建 Release 检查调试探针，并在 API 29/35 模拟器上运行设备测试。需要真机验收时，取 CI 产生的 APK 与配套测试产物。签名 Beta 走[独立 Actions 工作流](../.github/workflows/beta.yml)，见 [Beta 放行清单](beta-readiness.md)。

**当前工作区的边界。** Android 36 平台与固定 QuickJS 子模块已就绪，可用于源码阅读与 IDE 索引。官方 Linux Build Tools 36.0.0 归档中的 AAPT2 和 AIDL 是 x86_64，不在当前 ARM64 容器安装替代工具或使用 AIDL 桩。本次未打包、触发工作流、连接真机或修改上游参考仓库。GitHub 的 `CI` 文件检查、Android 主工作流、真机验收是不同证据。

## 日常流程

1. 先定范围：会话/工具改动本地跑对应 JVM 模块；Android 适配由主 CI 跑 App 单测和打包；真机验收使用 Actions 产物并单独取证。
2. 不新增与主入口平行的 stub 构建作为通过证据。独立 JVM 项目的 Kotlin 插件版本需与[根项目](../build.gradle.kts)保持一致；新增生产 JVM 模块需更新[独立 JVM 设置](../verification/jvm/settings.gradle.kts)。
3. 项目指令、补丁和远端适配等未来改动按[长期路线图](roadmap.md)与[开发能力验收场景](testing/development-capability-benchmark.md)补确定性测试，再做外部端点或真机验证。不要提交本机 SDK、缓存、密钥或 APK。
