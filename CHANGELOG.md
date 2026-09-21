# 更新日志

## 0.2.0

发布页面：[v0.2.0 Release](https://github.com/donglua/JZOfficeAndroid/releases/tag/v0.2.0)。

### 在线预览与缓存

- 新增可选 `viewer-online` 模块，支持文件直链、签名 URL、自定义请求头、下载进度、取消和可替换下载器；完整下载后交给 `viewer` 预览。
- `RemoteOfficeLoader` 管理下载与预览生命周期，取消或关闭时抑制旧回调并清空预览；下载、解析或显示中的文件由使用者持有，最后一个使用者释放后删除。
- `OfficeCache` 统一管理 `cacheDir/office-preview/`，清理时保留正在使用的文件，并返回删除、跳过和失败统计。
- Demo 增加在线打开、取消、重试、关闭文档和清理缓存入口。

### 发布配置

- 提供 `io.github.donglua:viewer:0.2.0` 和 `io.github.donglua:viewer-online:0.2.0` 两个坐标。Maven 接入在线预览只需后者，它通过 `api` 依赖同版本 `viewer`；直接引用 AAR 时必须同时引入两个文件。
- 两个模块共用发布配置。根任务 `:prepareRelease` 在 `build/release-repository/` 生成两套 AAR、sources/Javadoc JAR、POM 和 Gradle Module Metadata，并检查坐标、依赖和 AAR 内容。
- 根任务 `:publishToSonatype` 上传两个模块后只向 Central Portal 交接一次；旧入口 `:viewer:publishToSonatype` 同样发布两个模块。
- `PUBLISHED_ARTIFACT_ID` 仍指定 `viewer` 的 artifactId，在线模块使用其值加 `-online`。根项目默认版本、Demo `versionName` 和 Rust workspace 版本统一为 `0.2.0`，Gradle daemon 固定使用 JDK 21。
- `Publish libraries to Sonatype Central` 工作流在上传前运行 Java 网络与缓存检查、Rust 检查和发布准备，并向 Release 上传两个带版本号的 AAR。

### 已知限制

- 仍为 DOCX、PPTX、XLSX 的有限内容预览，不保证 Office 版式一致；不支持旧版 `.doc/.ppt/.xls`、加密文档、ZIP64/分卷容器或编辑。具体格式与资源限制见 [README](README.md)。
- 在线预览不支持边下载边看、断点续传或持久离线缓存。缓存使用状态仅在同一应用进程内共享，不支持多个进程共用缓存目录。
- 取消立即使旧任务回调失效，但默认下载器的阻塞网络读取可能等到超时才退出，同一 loader 的后续下载需等待线程退出。默认连接超时 15 秒、读取超时 30 秒，可通过请求配置缩短。
- URI 交接会额外复制一份预览文件，单个文档可能占用约两倍文件大小的磁盘空间。DOCX/PPTX 的预览副本保留至关闭，XLSX 在解析完成后释放。

### 验证状态

- Debug/Release 构建、lint、47 项网络检查、缓存检查及 Rust 检查已通过。
- 双模块本地发布产物检查已通过。独立消费项目使用 Gradle 元数据和纯 POM 均能从 `viewer-online:0.2.0` 解析出同版本的 `viewer`。
- 正式签名和上传由发布 CI 执行，结果见 [Actions](https://github.com/donglua/JZOfficeAndroid/actions/workflows/publish-sonatype.yml)。
- Android Instrumentation APK 已编译，尚无可用设备或 AVD 执行，设备验收未完成。

## 0.1.0（已发布）

`viewer` 的历史版本已发布至 [Maven Central](https://central.sonatype.com/artifact/io.github.donglua/viewer/0.1.0)，AAR 附件见 [v0.1.0 Release](https://github.com/donglua/JZOfficeAndroid/releases/tag/v0.1.0)。
