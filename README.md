# JZOfficeAndroid

通过 Android `Uri` 预览 DOCX、PPTX 和 XLSX 的有限内容子集。Rust crate `jz-office-core` 解析文档，Android `viewer` 模块负责显示。离线运行，不依赖服务器、WebView、Office 应用或内置字体。

这是基础预览实现，不保证与 Microsoft Office 的版式一致。旧版 `.doc/.ppt/.xls`、加密文档、ZIP64/分卷容器和编辑功能不在支持范围内。

## 模块

| 目录 | 职责 |
| --- | --- |
| `core/` | `jz-office-core`：ZIP/OOXML、关系解析、基础样式、平台无关文档模型 |
| `viewer/` | Android AAR：URI 读取、图片解码、文字排版、Canvas 绘制、滚动与缩放 |
| `viewer/native/` | `jz-office-android`：JNI 桥接，生成 `libjz_office.so` |
| `demo/` | 系统文件选择器和 `ACTION_VIEW` 接入示例 |
| `baseline/` | 同构建配置的空应用，用于估算接入后的 APK 增量 |
| `samples/` | 可复现的 DOCX/PPTX/XLSX 测试样例 |

`core` 不依赖 Android，不持有 `Context`、`Uri`、`Bitmap` 或 JNI 对象。坐标、尺寸、字号统一使用 point。图片以 ZIP 包内路径引用；Android 层从同一份私有缓存读取图片。

一次 JNI 调用解析整份文档，通过带 `schemaVersion` 的 JSON 模型交给 `viewer`。图片数据不放入 JSON；滚动和缩放不会反复调用 JNI。

## 支持范围

| 格式 | 基础能力 | 简化或省略 |
| --- | --- | --- |
| DOCX | 段落、基础样式继承、字号与颜色、粗斜体和下划线、倍数/固定/最小行距、首行/悬挂/左右缩进、图片、矩形表格 | 连续滚动；图片独占内容块；编号简化；忽略精确分页、浮动环绕、页眉页脚和公式 |
| PPTX | 按演示顺序显示幻灯片、固定坐标文字、文本框垂直对齐、倍数/固定行距、首行/悬挂/左右缩进、已保存的自动缩字、矩形图片裁剪与翻转、矩形/椭圆/直线、数值坐标的自定义路径、图形线性渐变、嵌套组合的位置/缩放/旋转、简单表格、缓存数据的基础折线图、基础主题色与占位符继承 | 忽略动画、其他图表类型、SmartArt、其他复杂形状、组合整体翻转和 WordArt；不重新计算自动适配；表格行高近似 |
| XLSX | 多工作表、行列标题、双向滚动、合并单元格、行高列宽、基础字体/填充/边框/对齐/换行、常见数字/百分比/日期格式、公式缓存结果 | 不计算公式；忽略工作表图片、图表、条件格式、数据透视表、冻结窗格和打印分页；列宽与边框近似，富文本合并为单元格文字 |

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
        // info.format, info.pageCount, info.warnings, info.sheetNames
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

XLSX 将每个可见工作表视为一页，`Info.pageCount`、页码监听和 `jumpToPage()` 沿用工作表顺序。`Info.sheetNames` 和 `getSheetNames()` 返回只读表名列表；其他格式返回空列表。切表保留缩放并重置滚动位置，XLSX 缩放范围为 0.5 到 4 倍。Demo 使用底部标签切表。

```kotlin
// Call on the main thread after an XLSX document is loaded; sheet numbers start at 1.
val names = preview.sheetNames
preview.selectSheet(2)
```

隐藏的工作表不显示，隐藏行列保留编号但不占空间。公式只显示文件保存时写入的缓存值，可能不是最新结果；没有缓存时显示公式文本并返回提示。无法识别的数字格式回退到原始值并返回提示，不执行宏或外部链接。

DOCX 行距与缩进沿用默认样式、父样式和直接格式的继承顺序，缩进支持 point 换算的数值；字符单位缩进、网格排版、RTL 和按行单位的段前/段后间距仍为简化预览。PPTX 裁剪支持 `srcRect` 的非负矩形内裁剪；负值外扩、几乎空的裁剪区域或无效值会回退到完整图片并给出提示。图片和基础图形支持水平、垂直翻转，图形内文字保持正向；图片平铺和非矩形蒙版仍未实现。

PPTX 组合保留子元素顺序，支持嵌套位置、非等比缩放和旋转。组合整体翻转暂不应用并返回提示；隐藏组合不显示。组变换应用于绘制和图片可见性判断，离屏图片继续沿用按需加载与释放。异常或过大的变换会被省略并返回提示，组合节点也计入元素预算。

组合子坐标先换算为页面尺寸，再计算文本内边距和布局。字号、行距和描边宽度保持 point 单位，避免文件使用较小的组合坐标单位时，将线宽和文字放大数百倍。

PPTX 文本应用继承后的 `lnSpc`、`marL`、`marR` 和 `indent`。`normAutofit` 使用文件保存的字号比例及百分比行距缩减值，`noAutofit` 和 `spAutoFit` 可清除继承的缩字设置；不根据 Android 字体重新求解自动缩字或扩大文本框。段前/段后百分比间距、自定义制表位和 RTL 仍简化处理。相关语义参见 [DrawingML 组合变换](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.transformgroup)和 [NormalAutoFit](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.normalautofit)。

PPTX 渐变文字采用色带中点的纯色近似，并返回提示；按色标位置插值，保留主题色映射和透明度。颜色支持 `lumMod`、`lumOff` 亮度变换，直接纯色或无填充可覆盖继承的渐变文字样式。文字未实现真实渐变着色或 WordArt 特效，不增加字体或渲染依赖。图形的直接线性渐变填充保留色标、主题色和透明度；路径渐变与主题样式引用的渐变仍不支持，平铺和独立旋转简化处理。

PPTX 圆角矩形 `roundRect` 在圆角调整值明确为 `adj = val 0` 时，按等效普通矩形绘制，保留母版/版式底板的填充、描边和层级顺序。默认圆角、非零圆角和其他预设复杂几何仍不支持。自定义几何支持数值坐标的移动、直线、二次及三次贝塞尔曲线、闭合路径，并保留子路径顺序与逐路径填充/描边开关；弧线、引导公式和特殊填充模式仍省略图形并返回提示，保留文字。

PPTX 图表仅支持单个标准 `lineChart`，读取文件内保存的数值缓存或直接数值，支持分类/日期横轴、线性纵轴、显式范围、断点/补零/跨空值连线、基础主题色、标题和图例。不会读取外部 CSV、打开嵌入工作簿或计算公式。坐标刻度与布局近似，图例统一置于底部，曲线使用直线段，省略标记和复杂样式；组合图、堆积图、对数轴和缺失缓存的图表会被省略并返回提示。复用已有线条和文字元素，不新增图表库、字体或 Android 模型协议。

内部 JSON 模型为 `schemaVersion = 5`，包含元素的组合变换、翻转、自定义 DrawingML 路径和图形线性渐变字段，core 与 viewer 应使用同次构建产物；不匹配时解码器会明确报错。

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

设备测试使用自定义 Instrumentation，以输出 `ALL CHECKS PASSED` 为通过标志。覆盖无真实路径、无文件长度的管道型 `content://`，DOCX/PPTX/XLSX 渲染、双击缩放、错误回调、URI 切换、临时文件清理和不同尺寸的截图。

XLSX 专项使用 `-e suite xlsx`，以 `ALL XLSX CHECKS PASSED` 为通过标志，覆盖管道 URI、表名顺序、切表与页码、双向滚动、缩放、快速换文件、合并区域、隐藏行列和有界文字布局缓存。

```sh
adb shell am instrument -w -e suite xlsx cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```

Demo 标签检查需要先安装 Debug Demo 和测试 APK，并把仓库样例放入 Demo 私有文件目录。该检查通过无障碍点击切换工作表，断言选中状态与页码并保存实际窗口截图；它不替代系统触摸注入检查。

```sh
adb install -r demo/build/outputs/apk/debug/demo-debug.apk
adb shell run-as cn.jingzhuan.lib.office.demo mkdir -p files
adb exec-in run-as cn.jingzhuan.lib.office.demo tee files/sample.xlsx < samples/sample.xlsx > /dev/null
adb shell am instrument -w -e suite demo-xlsx cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```

通过标志为 `ALL DEMO XLSX CHECKS PASSED`。

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

输入文件最多 64 MiB，ZIP 声明的解压总量最多 128 MiB、4096 个条目；单个 XML 最多 4 MiB、60000 个节点、64 层嵌套。PPTX 最多 100 页，累计最多 5000 个元素、组合节点和表格单元格。DTD 被拒绝，关系路径不能逃出包根目录。

每个 PPTX 折线图最多 8 个系列、每系列 2048 个数据槽位、合计 4096 个槽位，超过时省略该图表并提示。图表生成的线段、坐标轴和文字也计入整份 PPTX 的 5000 元素上限。

XLSX 最多 32 个工作表，每表最多 10000 行、256 列；整份文件最多 50000 个存储单元格、50000 个共享字符串、2048 个样式、1000 个合并区域，累计文字预算 8 MiB。单元格以稀疏列表保存，Android 只绘制可见区域，文字布局缓存最多 256 项并受文字量预算约束。仍一次解析完整模型；XML 等通用限制可能更早触发，不代表支持任意 50000 单元格文件。

Android 仅缓存可见区域的图片，离屏时移除缓存引用，返回该区域后重新解码；相同图片路径共用缓存。解码最长边不超过 4096 像素，当前缓存总预算为 800 万像素（ARGB_8888 约 30.5 MiB）。首次加载按图片数量分配预算，随后将小图和采样后剩余的预算优先用于当前页前景图片；可见区域或优先级变化后会重新评估分辨率，升级期间继续显示已有图片。实际清晰度仍受源图分辨率、裁剪、采样和缩放倍率影响。缓存预算不含正在解码的单张图片、压缩数据、系统渲染缓存及等待垃圾回收的旧 Bitmap，不是应用总内存上限。文档结构仍使用有界整份模型，尚未实现按页解析。

文件上限不代表渲染内存上限：单张图片的压缩数据最多 16 MiB，两层各自的内容读取预算仍为 128 MiB；Android 层按每个 ZIP 条目已读取的最大字节数计费，翻页重新读取同一图片不会重复扣额度。较大的文件仍可能触发 XML、图片或文档模型限制。使用 `-e suite limits` 运行容器大小专用设备测试，以 `ALL LIMIT CHECKS PASSED` 为通过标志。

图片缓存回归使用 `-e suite images`，以 `ALL IMAGE CHECKS PASSED` 为通过标志。覆盖大小图片混合时的预算分配、优先级变化与清晰度升级、放大后的单像素细节，以及多页大图的加载、跳页、拖动、返回、重复读取、离屏缓存释放、重新挂载、快速换文件和清理。

PPTX 兼容性回归使用 `-e suite pptx`，通过标志为 `ALL PPTX CHECKS PASSED`。样例 `samples/pptx-compat.pptx` 包含嵌套组合、旋转、翻转图片、缩字段落，以及使用 635 倍坐标换算的细描边和文字；检查组合坐标、Canvas 像素、行距与缩进、可见图片缓存，以及缩放和跳页。该入口不会执行完整测试组。

同一入口包含 `samples/pptx-charts.pptx`：两页分类/日期折线图，使用缓存数据并引用不存在的外部 CSV；通过 JNI/管道 URI 加载，检查曲线像素、标题、边界及两种尺寸的截图。该样例仅打入测试 APK。

`samples/pptx-colors.pptx` 包含红底渐变标题及等效纯色参照页。通过管道 URI/JNI 检查主题色与亮度变换结果，并在两种尺寸下检查金色文字像素和错误黑色像素。该样例仅打入测试 APK。

```sh
adb shell am instrument -w -e suite pptx cn.jingzhuan.lib.office.test/cn.jingzhuan.lib.office.OfficeInstrumentation
```
