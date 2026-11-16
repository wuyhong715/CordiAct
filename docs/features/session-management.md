# 会话整理与首句标题（2026-11-16）

## 功能与边界
- 用户主会话：重命名、归档/恢复、置顶/取消。子Agent会话不提供整理操作。
- 归档只改变元数据；后台消息仍可写入，任务不会停止，不自动恢复。打开归档会话可看历史，输入区显示“恢复并继续”；服务端没有禁止后台append。
- 归档当前会话后建立新对话空态；页面提供撤销，恢复并选回原会话。
- 抽屉归档入口切换为归档视图；普通搜索默认不含归档，用户可切换包含归档。置顶优先、最近活动次序其次。重命名/归档/置顶不改updatedAtMillis。
- 标题取首个USER消息的第一句（中英文句末标点/首行），压缩空白，最多120 UTF-16单元并避免截断代理对；无文本为“附件对话”。手动标题标记后不覆盖。历史已有USER消息不回填。
- Interaction保存首个用户消息后，用envelope.originalText校正标题，避免展开后的引用/私密填充成为标题；该回调在刷新侧栏前执行。
- DB版本10→11：session_archived_at/session_pinned_at/title_manually_set；升级保留数据，旧数据manual=false，但已有USER消息阻止再次命名。
- 以上字段不写入模型上下文。永久删除不在本批范围。

## 文件
- core/src/main/kotlin/com/cordiact/core/conversation/ConversationContracts.kt
- app/src/main/java/com/cordiact/app/conversation/AndroidConversationSessionStore.kt
- app/src/main/java/com/cordiact/app/CordiActApplication.kt
- app/src/main/java/com/cordiact/app/experience/{ExperienceViewModel,ExperienceScreen,ConversationSidebar}.kt
- app/src/main/java/com/cordiact/app/experience/model/SidebarModels.kt
- app/src/test/java/com/cordiact/app/conversation/SessionLifecycleTest.kt
- app/src/test/java/com/cordiact/app/experience/{SessionTitleTest,SidebarModelsTest}.kt

## 验证
- `sh /workspace/spike/local_compile.sh`：应用Kotlin编译通过；不等于完整APK构建。
- `git diff --check`：通过。
- 临时JVM harness `/workspace/.verify-session` 复用现有Gradle/Robolectric依赖，绑定仓库生产和测试源：
  `/workspace/CordiAct/gradlew -p /workspace/.verify-session --offline --no-daemon :test --tests 'com.cordiact.app.experience.*' --console=plain`
  SidebarModelsTest 17项、SessionTitleTest 2项通过。
- SessionLifecycleTest已编译，执行被本机Robolectric SDK35 `sqlite.nativeOpen` UnsatisfiedLinkError阻塞；SDK28依赖未缓存，备用legacy模式受SDK35 shadow不兼容阻塞，最终恢复标准SDK35配置供CI运行。未宣称持久层Android测试通过。
- Python sqlite3执行生产v10→v11 ALTER SQL，验证已有title/updated时间保留、字段默认值与写入。仅SQL迁移烟测，不替代Android/事务并发验证。
- 未打APK、未真机验收、未提交推送。APK按约定由GitHub Actions打包。
