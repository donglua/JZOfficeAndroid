# JZOfficeAndroid

通过 Android `Uri` 预览 DOCX 和 PPTX 的有限内容子集。Rust crate `jz-office-core` 解析文档，Android `viewer` 模块负责显示。离线运行，不依赖服务器、WebView、Office 应用或内置字体。

这是基础预览实现，不保证与 Microsoft Office 的版式一致。旧版 `.doc/.ppt`、加密文档、ZIP64/分卷容器和编辑功能不在支持范围内。

## 模块

| 目录 | 职责 |
| --- | --- |
| `core/` | `jz-office-core`：ZIP/OOXML、关系解析、基础样式、平台无关文档模型 |
| `viewer/` | Android AAR：URI 读取、图片解码、文字排版、Canvas 绘制、滚动与缩放 |
| `viewer/native/` | `jz-office-android`：JNI 桥接，生成 `libjz_office.so` |
| `demo/` | 系统文件选择器和 `ACTION_VIEW` 接入示例 |
| `baseline/` | 同构建配置的空应用，用于估算接入后的 APK 增量 |
| `samples/` | 可复现的 DOCX/PPTX 测试样例 |

`core` 不依赖 Android，不持有 `Context`、`Uri`、`Bitmap` 或 JNI 对象。坐标、尺寸、字号统一使用 point。图片以 ZIP 包内路径引用；Android 层从同一份私有缓存读取图片。

一次 JNI 调用解析整份文档，通过带 `schemaVersion` 的 JSON 模型交给 `viewer`。图片数据不放入 JSON；滚动和缩放不会反复调用 JNI。

## 支持范围

| 格式 | 基础能力 | 简化或省略 |
| --- | --- | --- |
| DOCX | 段落、基础样式继承、字号与颜色、粗斜体和下划线、图片、矩形表格 | 连续滚动；图片独占内容块；编号简化；忽略精确分页、浮动环绕、页眉页脚和公式 |
| PPTX | 按演示顺序显示幻灯片、固定坐标文字、图片、矩形、椭圆、直线、简单表格、基础主题色与占位符继承 | 忽略动画、组合图形、图表、SmartArt、复杂形状、图片裁剪、自动缩字；表格行高近似 |

字体使用系统默认字体，可能改变换行。识别到的未支持内容通过 `Info.warnings` 返回；它不是完整兼容性扫描。DOCX 的 `pageCount` 为 1，表示连续内容，不表示原文件页数。

## Android 接入

最低 Android 6.0（API 23）。引用源码模块：

```kotlin
dependencies {
    implementation(project(":viewer"))
}
```

也可以把构建得到的 AAR 放入现有项目的 `libs` 目录，再用 `implementation(files("libs/viewer-release.aar"))` 引用。AAR 包含 JNI 库和 R8 保留规则，不需要额外的 JVM 运行时依赖。

```kotlin
import cn.jingzhuan.lib.office.OfficePreviewView

val preview = OfficePreviewView(context)
container.addView(preview)

preview.open(uri, object : OfficePreviewView.Listener {
    override fun onLoaded(info: OfficePreviewView.Info) {
        // info.format, info.pageCount, info.warnings
    }

    override fun onError(error: Exception) {
        showError(error.message)
    }
})
```

`open`、`clear`、`setZoom` 和 `resetZoom` 在主线程调用，回调也在主线程。文件读取和 Rust 解析在工作线程执行。重复 `open` 会取消旧任务并抑制旧回调；View 脱离窗口时取消加载并关闭执行器。取消正在阻塞的文件提供方读取属于尽力而为，已有文档可以在重新挂载 View 后继续显示。

支持拖动滚动、双指缩放、双击缩放和 `resetZoom()`。`clear()` 释放当前文档引用。

## URI 约定

- 主要入口为 `content://`，也接受应用有权限读取的 `file://`；不把 URI 转换成真实路径。
- 通过 `ContentResolver` 读取流，复制到 `cacheDir` 的临时 ZIP 文件，完成解析和图片解码后删除。
- 文件类型由包内关系与文档结构识别，不依赖扩展名、MIME 或文件提供方的长度信息。
- `ACTION_OPEN_DOCUMENT` 返回的授权由宿主应用管理。只有提供方授予可持久化权限时才调用 `takePersistableUriPermission`；分享 URI 通常只有临时读取权限。
- 不声明广泛存储权限，也不请求联网权限。外部图片和远程关系不会下载。

完整选择器与授权处理见 `demo` 的 `MainActivity`。[Android 文件访问文档](https://developer.android.com/training/data-storage/shared/documents-files)说明了 URI 读取和持久化授权。

## 构建

需要 JDK 17 或更新版本、Android SDK 35、NDK `28.2.13676358`、Rust 1.87 或更新版本。使用 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `local.properties` 配置 SDK。

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi
./gradlew :viewer:assembleRelease :demo:assembleDebug
```

默认同时打包 `arm64-v8a` 和 `armeabi-v7a`，兼容 ARM64 与 ARM32。需要 x86_64 时安装对应 Rust target，并指定 ABI：

```sh
rustup target add x86_64-linux-android
./gradlew :viewer:assembleRelease -PofficeAbis=arm64-v8a,armeabi-v7a,x86_64
```

只需要某一种架构时，可使用 `-PofficeAbis=arm64-v8a` 或 `-PofficeAbis=armeabi-v7a`。宿主应用也必须包含对应架构的所有其他原生依赖。

原生构建脚本支持 macOS 和 Linux；Windows 构建未实现。NDK 链接时设置 16 KiB ELF 段对齐。多 ABI AAR 可以由宿主应用的 `abiFilters` 或 AAB 分发选择；单 APK 包含多个 ABI 时会增大体积。[Android ABI 文档](https://developer.android.com/ndk/guides/abis)解释了这些差异。

产物位置：

```text
viewer/build/outputs/aar/viewer-release.aar
demo/build/outputs/apk/debug/demo-debug.apk
```

Rust 核心可独立使用：

```rust
let document = jz_office_core::parse_path(std::path::Path::new("sample.docx"))?;
```

## 验证

```sh
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all -- --check
cargo run -p jz-office-core --example fixtures -- samples
./gradlew :viewer:assembleDebugAndroidTest
adb install -r viewer/build/outputs/apk/androidTest/debug/viewer-debug-androidTest.apk
adb shell am instrument -w cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```

设备测试使用自定义 Instrumentation，以输出 `ALL CHECKS PASSED` 为通过标志。覆盖无真实路径、无文件长度的管道型 `content://`，DOCX/PPTX 渲染、双击缩放、错误回调、URI 切换、临时文件清理和不同尺寸的截图。实际执行结果与包体积见 `VERIFICATION.md`。

在支持 32 位应用的设备上，可以指定 ARM32 安装并启动测试，避免默认选择 ARM64：

```sh
adb install -r --abi armeabi-v7a viewer/build/outputs/apk/androidTest/debug/viewer-debug-androidTest.apk
adb shell am instrument --abi armeabi-v7a -w cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```

## 资源限制

输入文件最多 32 MiB，ZIP 声明的解压总量最多 64 MiB、4096 个条目；单个 XML 最多 4 MiB、60000 个节点、64 层嵌套。PPTX 最多 100 页；文档模型限制块、元素和表格单元格数量。DTD 被拒绝，关系路径不能逃出包根目录。

Android 图片解码最长边采样至不超过约 1600 像素，总预算为 800 万像素。暂存文件大小、文档运行时内存与安装包大小分别计算。大文档使用有界整份模型，尚未实现按页解析和按需图片解码。
