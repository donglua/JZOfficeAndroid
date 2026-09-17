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
| DOCX | 段落、基础样式继承、字号与颜色、粗斜体和下划线、倍数/固定/最小行距、首行/悬挂/左右缩进、图片、矩形表格 | 连续滚动；图片独占内容块；编号简化；忽略精确分页、浮动环绕、页眉页脚和公式 |
| PPTX | 按演示顺序显示幻灯片、固定坐标文字、文本框顶端/居中/底端对齐、矩形图片裁剪、矩形、椭圆、直线、简单表格、基础主题色与占位符继承 | 忽略动画、组合图形、图表、SmartArt、复杂形状、自动缩字；表格行高近似 |

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

`open`、`clear`、`setZoom` 和 `resetZoom` 在主线程调用，回调也在主线程。文件读取、Rust 解析和图片解码在工作线程执行。重复 `open` 会取消旧任务并抑制旧回调；View 脱离窗口时取消文档加载、暂停图片请求并清空图片缓存，已有文档保留暂存文件以支持重新挂载后继续显示。取消正在阻塞的文件提供方读取属于尽力而为。

支持拖动滚动、双指缩放、双击缩放和 `resetZoom()`。`clear()` 释放文档及图片引用，当前图片读取结束后关闭 ZIP 并删除暂存文件。Activity 的 `onDestroy()` 或 Fragment 的 `onDestroyView()` 应调用 `clear()`，Demo 已接入该清理。

`onLoaded` 表示文档模型可用，图片随后按可见区域加载；等待解码时显示占位块。图片读取或解码失败通过 `onError` 通知，其他内容仍可浏览，因此 `onError` 也可能在 `onLoaded` 之后触发。`Info.warnings` 是模型加载时的提示快照。

页码从 1 开始；未加载、加载中、失败或 `clear()` 后，当前页和总页数均为 0。DOCX 为一个连续页面，不表示 Word 的实际页数。PPTX 当前页按视口内可见高度最多的幻灯片计算；可见高度相同时保留当前页。跳转保留缩放，重置横向偏移，并把目标页尽量移到视口顶端。

```kotlin
preview.setOnPageChangeListener { pageNumber, pageCount ->
    pageLabel.text = "$pageNumber / $pageCount"
}
// Can be called after onLoaded; if the view has no size yet, the jump is applied on first layout:
preview.jumpToPage(2)
val currentPage = preview.currentPage
val pageCount = preview.pageCount
```

`setOnPageChangeListener` 和 `jumpToPage` 在主线程调用，页码读取也应在主线程。监听器注册时立即收到当前状态，之后仅在当前页或总页数变化时回调；传入 `null` 可移除监听器。越界跳转抛出 `IllegalArgumentException`；文档已加载但 View 尚无尺寸时，合法跳转会记录目标页并在首次获得非零尺寸时应用。Demo 的上一页/下一页按钮使用同一接口。

DOCX 行距与缩进沿用默认样式、父样式和直接格式的继承顺序，缩进支持 point 换算的数值；字符单位缩进、网格排版、RTL 和按行单位的段前/段后间距仍为简化预览。PPTX 裁剪支持 `srcRect` 的非负矩形内裁剪；负值外扩、几乎空的裁剪区域或无效值会回退到完整图片并给出提示，图片平铺、翻转和非矩形蒙版仍未实现。

内部 JSON 模型为 `schemaVersion = 2`，core 与 viewer 应使用同次构建产物；不匹配时解码器会明确报错。

## URI 约定

- 主要入口为 `content://`，也接受应用有权限读取的 `file://`；不把 URI 转换成真实路径。
- 通过 `ContentResolver` 读取流，复制到 `cacheDir` 的临时 ZIP 文件，完成解析和图片解码后删除。
- 文件类型由包内关系与文档结构识别，不依赖扩展名、MIME 或文件提供方的长度信息。
- `ACTION_OPEN_DOCUMENT` 返回的授权由宿主应用管理。只有提供方授予可持久化权限时才调用 `takePersistableUriPermission`；分享 URI 通常只有临时读取权限。
- 不声明广泛存储权限，也不请求联网权限。外部图片和远程关系不会下载。

完整选择器与授权处理见 `demo` 的 `MainActivity`。[Android 文件访问文档](https://developer.android.com/training/data-storage/shared/documents-files)说明了 URI 读取和持久化授权。

## 构建

需要 JDK 17 或更新版本、Android SDK 35、NDK `28.2.13676358`、通过 rustup 安装的 Rust 1.87 或更新版本。`rustup` 和 `cargo` 需要在构建进程的 `PATH` 中可用；Android Studio 也需要能找到这两个命令。使用 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `local.properties` 配置 SDK。

```sh
./gradlew :viewer:assembleRelease :demo:assembleDebug
```

Gradle 的 `buildNative` 任务会通过原生构建脚本检查当前 Rust 工具链的 target，仅对缺失项执行 `rustup target add`，再编译并打包 `.so`。无需手动安装 Android target；首次缺少 target 时需要联网。Rust/rustup 本身仍需预先安装。

默认同时打包 `arm64-v8a` 和 `armeabi-v7a`，兼容 ARM64 与 ARM32。需要 x86_64 时指定 ABI，对应 target 会自动准备：

```sh
./gradlew :viewer:assembleRelease -PofficeAbis=arm64-v8a,armeabi-v7a,x86_64
```

只需要某一种架构时，可使用 `-PofficeAbis=arm64-v8a` 或 `-PofficeAbis=armeabi-v7a`。宿主应用也必须包含对应架构的所有其他原生依赖。

`./gradlew --offline :viewer:assembleRelease` 不安装缺失 target，Cargo 也使用离线模式。离线构建前需准备好 Gradle/Cargo 依赖缓存和所选 ABI 的 Rust target；缺少 target 时构建会给出明确提示并停止。

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

仅运行新增排版、图片裁剪和页码接口检查时，增加 `-e suite layout`，以 `ALL LAYOUT CHECKS PASSED` 为通过标志。该组检查包含真实 `StaticLayout` 坐标、Canvas 像素、窗口截图、拖动页码、布局前跳转和错误状态；完整测试仍保留独立的系统触摸注入用例。

```sh
adb shell am instrument -w -e suite layout cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```

在支持 32 位应用的设备上，可以指定 ARM32 安装并启动测试，避免默认选择 ARM64：

```sh
adb install -r --abi armeabi-v7a viewer/build/outputs/apk/androidTest/debug/viewer-debug-androidTest.apk
adb shell am instrument --abi armeabi-v7a -w cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```

## 资源限制

输入文件最多 64 MiB，ZIP 声明的解压总量最多 128 MiB、4096 个条目；单个 XML 最多 4 MiB、60000 个节点、64 层嵌套。PPTX 最多 100 页；文档模型限制块、元素和表格单元格数量。DTD 被拒绝，关系路径不能逃出包根目录。

Android 仅缓存可见区域的图片，离屏时移除缓存引用，返回该区域后重新解码；相同图片路径共用缓存。解码最长边不超过 1600 像素，当前缓存总预算为 800 万像素（ARGB_8888 约 30.5 MiB）。可见图片较多时按图片数量分配预算并进一步采样。该数值不含正在解码的单张图片、压缩数据、系统渲染缓存及等待垃圾回收的旧 Bitmap，不是应用总内存上限。文档结构仍使用有界整份模型，尚未实现按页解析。

文件上限不代表渲染内存上限：单张图片的压缩数据最多 16 MiB，两层各自的内容读取预算仍为 128 MiB；Android 层按每个 ZIP 条目已读取的最大字节数计费，翻页重新读取同一图片不会重复扣额度。较大的文件仍可能触发 XML、图片或文档模型限制。使用 `-e suite limits` 运行容器大小专用设备测试，以 `ALL LIMIT CHECKS PASSED` 为通过标志。

图片缓存回归使用 `-e suite images`，以 `ALL IMAGE CHECKS PASSED` 为通过标志。覆盖多页大图的加载、跳页、拖动、返回、重复读取、离屏缓存释放、重新挂载、快速换文件和清理。
