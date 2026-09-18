# 验证记录

日期：2026-09-18。版本：0.1.0。

项目 `JZOfficeAndroid`，Rust crate `jz-office-core`，Android namespace `cn.jingzhuan.lib.office`。以下先记录本轮 PPTX 兼容性改进，后续章节为历史验证结果，不将历史结果视为当前产物证明。

## PPTX 白色底板

18 页文件的第 13 页使用 `slideLayout2.xml` 的白色内容底板，几何为 `roundRect`，圆角调整公式为 `adj = val 0`。原先它被当作复杂几何省略，导致黑、蓝、红色正文直接叠在红色背景图上。现将明确零圆角的该预设识别为等效 `RECT`，沿用主题色、描边、变换和绘制顺序；未改变正文颜色，没有新增依赖或模型字段。

| 环节 | 本轮证据 |
| --- | --- |
| 根因与回归 | 零圆角底板与普通矩形的完整页面模型对照用例修复前失败、修复后通过；默认/非零/无效调整和其他几何仍保持原有省略行为 |
| Rust | 193 项工作区测试通过，含 2 项新增形状测试；fmt、Clippy `-D warnings` 通过 |
| 原文件解析 | 第 13 页恢复白底 `(-0.1, 54.2, 960.0945, 481.95)` pt、无描边；430 条段落和文字输出与修复前一致；同版式的第 4、5、6、7、9、10、11、12、13、14、15、18 页同步恢复底板 |
| 构建与包内容 | Release AAR、Debug/Release Demo APK、测试 APK 构建通过，四种产物的双 ARM 原生库哈希一致；lint 为 0 错误、1 个原有触摸可访问性警告；Release APK 通过 16 KiB zipalign 检查 |
| 设备回归 | 华为 BKY-W00 的 ARM64 和强制 ARM32 均输出 `ALL PPTX CHECKS PASSED`；涵盖既有组合、排版、图表和渐变文字用例 |
| 实际页面 | 分别安装 ARM32/ARM64 Demo，从已授权 content URI 打开原文件第 13 页，白底、正文和二维码可见；检查第 12、14 页，恢复后未遮挡其前景内容；测试后恢复 ARM64 Demo 并停在第 13 页 |
| 像素检查 | 系统截图按内嵌 ICC 转成 sRGB，同一正文区域的近白像素由 58,664 增至 ARM32 的 1,338,121 和 ARM64 的 1,338,124；红色标题栏采样保留 |

原生库体积：ARM64 由 673,664 B 增至 674,464 B，增加 800 B；ARM32 由 489,080 B 增至 489,576 B，增加 496 B。当前双 ARM AAR 为 762,698 B，Release Demo APK 为 1,215,375 B。构建时工作区另有图片解码最长边改为 4096 的并行改动，已保留且被打包；因此 APK/AAR 总体变化不作为本次形状修复的独立增量。

AAR SHA-256：`7306297c32999e349deda396a49faf744c0aba1451dc31d8fbd1e2742a504738`。Release APK SHA-256：`8701fd84c6bd0189b6242a104ffbe11abbdb01b7a049c464125f27b71d374899`。已安装 Debug Demo 与本机 APK 哈希一致：`8f5ada619fb1fec26dab973596cddbeba6b577784ff0913656a6387be6a85dc7`；测试 APK 安装哈希一致：`b677f1b899950fcb8f63871f5bbc978fe4a165c058de354ec514686d035ec590`。

边界：本次修复只覆盖明确零圆角的矩形，未实现真实圆角、通用几何公式或字体/符号字体精确匹配；没有逐页与 Office 做完整对照。并行的图片清晰度改动不属于本节的独立回归范围，原业务文件与临时截图未进入仓库。

## PPTX 渐变文字颜色

用户提供的 18 页文件封面标题使用 `gradFill`，原先解析器忽略它并保留默认黑色。其中一个色标还使用主题色 `accent4` 的 `lumMod`、`lumOff`，原先也未应用。现支持亮度变换，并将文字渐变按色标位置取中点纯色近似；该标题得到 `FFF3DD`。直接纯色、无填充与继承优先级保持不变。没有新增依赖、字体、Android 生产代码或模型字段。

| 环节 | 本轮证据 |
| --- | --- |
| 根因与回归 | 原文件核心输出及旧 Demo 截图均为黑色；修复前三项合成用例失败，修复后通过，等效纯色参照得到相同结果 |
| Rust | 191 项工作区测试通过，含 5 项颜色回归及样例路径调用；fmt、Clippy `-D warnings` 通过 |
| 颜色边界 | 色标位置/乱序/无效位置、透明色插值、主题色、连续亮度变换、变换顺序、灰度、范围钳制与直接格式覆盖 |
| Android 构建 | Release AAR、Debug/Release Demo APK、测试 APK 构建通过；lint 0 错误、1 个原有触摸可访问性警告 |
| 包内容 | AAR、测试 APK、Debug/Release Demo APK 的 ARM64/ARM32 原生库哈希一致；颜色样例仅在测试 APK；Release APK 通过 16 KiB zipalign 检查 |
| 设备路径 | 华为 BKY-W00 的 ARM64、强制 ARM32 均输出 `ALL PPTX CHECKS PASSED`，包含此前组合、排版和图表用例 |
| 颜色与画面 | 两页渐变/纯色参照经管道 URI/JNI 得到同一颜色；1080x1600、1800x1000 Canvas 均有金色文字且无黑色标题像素；已检查四张画面及窗口截图 |
| 用户文件 | 系统文件选择器选中原文件后，安装新版 Debug Demo，通过已授权 content URI 重开封面；两行标题由黑色恢复为浅金色，原红色底层文字和页脚保留 |

系统截图带有 Display P3 ICC 配置，直接按 sRGB 字节比较会误报；按内嵌配置转换到 sRGB 后，同一标题区域由 227,628 个黑色像素变为 0，新增 223,908 个精确 `FFF3DD` 像素。Canvas 样例本身为 sRGB，原有颜色断言无需调整。

| 双 ARM 产物 | 改动前 | 改动后 | 增量 |
| --- | ---: | ---: | ---: |
| Release Demo APK | 1,196,623 B | 1,214,879 B | 18,256 B，约 17.8 KiB |
| Release AAR | 759,257 B | 761,898 B | 2,641 B，约 2.6 KiB |
| ARM64 `.so` | 671,600 B | 673,664 B | 2,064 B |
| ARM32 `.so` | 487,208 B | 489,080 B | 1,872 B |

APK 增量包含原生库的 16 KiB ZIP 对齐开销。AAR SHA-256：`ac2d7b610f9c335bb830bc15f4e401b94c976a35117cf9224372b94e7798cf3f`；Release APK SHA-256：`e1a0f1303d5bb1c6b984b53bd649bc4fc1d2def083684eb0f7a0293336011a6c`。

已安装 Demo 与本机 Debug APK 哈希一致：`584fa53af8243a6fee53aaade1ac5e683e7ae929813593955dfb1d206a75de50`；测试 APK 安装哈希一致：`aa8ca7d592f9f0a114b2aca2cfd175016e10db37f1779f02d276a5e5c1326e95`。测试后恢复 ARM64 安装，并将 Demo 停在原文档封面。

边界：仅验证该文件封面颜色，没有逐页与 Office 对比；渐变仍是带提示的纯色近似，图形渐变、字体精确匹配和 WordArt 特效未实现。ARM32 在支持 32 位进程的 ARM64 设备上验证，未覆盖纯 32 位旧设备。原业务文件未进入仓库或发布包。

## PPTX 缓存折线图

用户提供文件的第 16 页空白区域是内嵌 `chart3.xml`，包含 587 个缓存数据点，原先所有图表均被跳过。现用 Rust 将标准折线图转换为已有线条、文字和矩形元素，恢复标题、分类/日期轴、曲线与图例。未新增依赖、字体或 Android 生产代码，模型保持 schema 4；不访问外部 CSV，不计算公式。

| 环节 | 本轮证据 |
| --- | --- |
| 根因 | 第 16 页 `graphicFrame` 指向折线图，缺失不是图片解码或内存预算导致 |
| Rust | 工作区 185 项测试通过，含 11 项图表测试；fmt、Clippy `-D warnings` 通过 |
| 回归边界 | 缓存索引、日期格式、断点/补零/跨空值、显式范围裁剪、缺失/异常缓存、堆积/柱状/对数轴回退及图表预算 |
| 实际核心调用 | 原始 31 页文件解析通过；三个标准折线图生成绘制元素，柱状图仍返回省略提示 |
| Android 构建 | Release AAR、Debug/Release Demo APK、测试 APK 构建通过；lint 0 错误、1 个原有触摸可访问性警告 |
| 包内容 | AAR、测试 APK、Debug/Release Demo APK 的 ARM64/ARM32 原生库哈希一致；图表样例仅在测试 APK；Release APK 通过 16 KiB zipalign 检查 |
| 设备路径 | 华为 BKY-W00 上 ARM64 与强制 ARM32 的 `-e suite pptx` 均输出 `ALL PPTX CHECKS PASSED`；覆盖管道 URI/JNI、实际 Canvas 曲线像素/标题/边界与窗口截图 |
| 图形检查 | 分类/日期样例在 1080x1600、1800x1000 均检查截图；曲线、标签、图例可见且没有相互遮挡 |
| 用户文件 | 安装新版 Demo，通过原 content URI 打开第 16 页，确认曲线恢复在表格左侧原空白处，未遮挡周围内容 |

ARM32 首次运行因设备熄屏停在已有窗口绘制等待步骤，尚未进入图表用例；确认 `mWakefulness=Asleep` 后唤醒重跑通过，未修改代码或放宽断言。

同一双 ARM release 配置的体积变化如下。APK 中原生库不压缩，ZIP 对齐会影响最终增量，不应直接把两个 `.so` 的差值相加当作 APK 差值。

| 产物 | 改动前 | 改动后 | 增量 |
| --- | ---: | ---: | ---: |
| Release Demo APK | 1,162,807 B | 1,196,623 B | 33,816 B，约 33.0 KiB |
| Release AAR | 732,338 B | 759,257 B | 26,919 B，约 26.3 KiB |
| ARM64 `.so` | 645,408 B | 671,600 B | 26,192 B，约 25.6 KiB |
| ARM32 `.so` | 469,776 B | 487,208 B | 17,432 B，约 17.0 KiB |

AAR SHA-256：`6099244461aae55778dac19ff8f9c5e772aa2c1fcbc904a0d11cbeffbf04051b`。Release APK SHA-256：`7e36f033eebbeddc8426c39cdfec9a6d07f90f9707d95b10b67d69bc1f3c3279`。已安装 Demo 与本机 Debug APK 哈希一致：`e3c562ab124c749d2270384ac71b9cab6862693f27e8a7d3ec1237e189a686be`。

仍为有限图表预览：布局、刻度密度和图例位置近似，省略标记和复杂样式；未与 Office 逐页比对，不支持其他图表类型、组合图、堆积图或对数轴。业务文件仅用于本地验证，不进入仓库或发布包。

## PPTX 组合与文本排版

组合节点原先被整体跳过，图片翻转只产生警告；PPTX 行距、左右和悬挂缩进、文件保存的自动缩字值也未完整应用。本轮补充嵌套组合的位置、非等比缩放和旋转，图片/基础图形局部翻转，百分比/固定行距、右缩进/首行缩进，以及 `normAutofit` 的字号比例和百分比行距缩减。没有新增依赖。

绘制与可见图片判断使用相同的最终变换，保留原有按需解码和离屏释放。独立源码复查发现小于 1pt 的元素会被原有尺寸下限扩大，现仅 DOCX 流式布局保留该下限，PPTX 使用原始尺寸；同时修正透明描边扩大图片可见范围的问题，相关设备断言已补充。修正后的源码复查没有剩余阻塞项。内部模型升级为 schema 4，core 与 viewer 必须同步更新。

真实文件回放发现，组合内 Logo 的 1pt 描边被 635 倍坐标换算放大，遮住正文；同组标题也因在换算前扣除 point 内边距而消失。现先把每个子元素的尺寸换算到页面单位，再计算文字布局；残余矩阵保留旋转和剪切，字体、内边距、线宽不随坐标单位放大。此修复未增加依赖或按文件名分支。

| 环节 | 本轮证据 |
| --- | --- |
| Rust | 174 项工作区测试通过；fmt 和 Clippy `-D warnings` 通过 |
| 新增回归 | 13 项组合/翻转测试、12 项文本测试；包含 635 倍坐标单位、描边/字体/内边距、叶节点旋转、不同层级变换顺序、45 度旋转、隐藏组顺序、缺失/过大坐标、5000 对象预算、样式覆盖与异常行距 |
| 实际核心调用 | `parse_path` 打开两页兼容样例和 31 页业务文件；业务文件 Logo 还原为 110x29pt、1pt 描边，原先省略的组合标题恢复 |
| Android 构建 | Release AAR、Debug/Release Demo APK 与测试 APK 构建通过；lint 为 0 错误、1 个原有触摸可访问性警告 |
| 包内容 | AAR、测试 APK 和 Debug/Release Demo APK 均包含相同哈希的 ARM32/ARM64 原生库；样例与测试 APK 内资产哈希一致；Release APK 的 16 KiB zipalign 检查通过 |
| Android 设备用例 | 华为 BKY-W00 上 ARM64 与强制 ARM32 的 `-e suite pptx` 均输出 `ALL PPTX CHECKS PASSED`，包含真实 JNI/URI、像素/基线断言、变换后图片可见性、缩放/跳页和截图 |
| 既有布局回归 | `-e suite layout` 输出 `ALL LAYOUT CHECKS PASSED`；截图等待窗口淡入完成，按当前页实际背景色断言，修正旧用例遗漏第二页背景色的误报；保留失败截图便于定位 |
| 真实文件回放 | 通过原有 content URI 打开用户提供的 55,019,791 B 文件；检查第 28-30 页，正文遮挡消失、Logo 恢复正常尺寸、组合标题可见；用户确认效果 |

本轮双 ARM AAR 为 732,338 B；Release Demo APK 为 1,162,807 B。AAR SHA-256：`e20c9e2dd1b286aaaf1ae4e153dad51210631cca839ffbc00dfac271cf1f9ea5`。

已安装 Demo APK 与本机 Debug APK 的 SHA-256 均为 `537e04d3cb01cb095dfe682c210ce7f611a24773b4307df7d278ec5aca8bb156`。原始业务文件仅用于本地验证，仓库只保留不含业务内容的合成回归样例。

仍未支持组合整体翻转、复杂几何、SmartArt、图表、WordArt、独立竖排文字、字体精确匹配和表格精确行高。自动缩字仅采用文件保存值，不重新求解 Office 排版。没有与 Office 逐页对比全部 31 页，不能据此声明完整兼容；任意剪切组合的描边仍属近似。

## XLSX 有限预览

新增 Rust 工作簿解析、稀疏单元格模型、样式和常用数字格式；Android 按可见区域绘制网格，提供固定行列标题、双向滚动、缩放与工作表切换。Demo 接入 XLSX MIME 和底部工作表标签。公式只显示缓存值，无缓存时显示公式文本并提示；未加入公式计算、图片、图表和编辑功能。没有新增依赖。

| 环节 | 本轮结果 |
| --- | --- |
| Rust | 工作区 149 项测试通过，包括 85 项格式化用例和 23 项 XLSX 容器用例；fmt、Clippy `-D warnings` 通过 |
| 核心实际调用 | 通过 `parse_path` 打开 `samples/sample.xlsx`，确认两张表及数字、百分比、日期、公式缓存结果；非 ZIP 输入明确失败 |
| Android 构建 | viewer Release AAR、测试 APK、demo Debug/Release APK 构建通过；`lintDebug` 0 错误、1 个已有触摸可访问性警告 |
| 包内容 | AAR、Debug/Release Demo APK 与测试 APK 均含 ARM32/ARM64，分别对应相同原生库哈希；Release APK 的 16 KiB zipalign 检查通过 |
| XLSX 设备路径 | 小米 API 36，分别指定 ARM64 与 ARM32 安装、启动，均输出 `ALL XLSX CHECKS PASSED` |
| XLSX 断言 | 管道型 `content://`、表名顺序、切表与页码、同表跳转复位、双向拖动实际偏移、缩放、快速换文件、合并锚点离屏、隐藏行列、换行/对齐/裁剪与缓存上限 |
| 图片及旧格式 | 双 ABI 的排版用例均通过；图片缓存用例也分别通过，但 ARM32 有一次源样例丢失的失败，见下方 |
| Demo | 华为 API 31：Debug APK `ACTION_VIEW` 打开样例，无障碍点击 Data/Overview 后选中态与 2/2、1/2 页码一致，输出 `ALL DEMO XLSX CHECKS PASSED` |
| 画面 | 1080 x 1600、1800 x 1000、缩放/滚动/第二张表的 Canvas 截图，以及手机和平板 Demo 窗口截图；已检查网格、表头、合并区域和标签 |

复查修正了三处问题：单元格 `00` 前缀颜色需要按不透明色显示；跳转当前工作表也应停止惯性滚动并回到左上角；拖动断言需要固定视口并检查实际偏移，避免把缩放产生的偏移当成拖动结果。新增颜色与同表跳转回归均通过。内部模型升级到 schema 3，core 与 viewer 必须同步更新。

小米双 ABI XLSX 检查所用测试 APK，本地和安装文件的 SHA-256 相同：

```text
58866e2db04d2c72d085571b9684db1a86b511fd9d778793a15a34b9b3c24300
```

之后仅增加 Demo 无障碍检查入口，生产代码未再修改。华为安装的补充测试 APK SHA-256 为 `f54306a9f98c16f158fd1641cbb66daa6329b9f5992007ed937ac981e555c2d1`；Demo Debug APK 为 `a702a223a0e229f2ae748a9b69aed4174433baf9887518e92a79bdcaeed1c3cf`，均与本地一致。交付 AAR 的 SHA-256 为 `834655f94e7bfb473bbb1cd618211208b6d290d98dba2e0134388788dbb64b87`。

| 双 ARM 产物 | 大小 | 相对本轮修改前 |
| --- | ---: | ---: |
| viewer Release AAR | 726,066 B，约 709.05 KiB | +70,872 B，约 69.21 KiB |
| demo Release APK | 1,159,143 B，约 1.11 MiB | +107,312 B，约 104.80 KiB |

本轮没有重新测量单 ABI 或空应用增量。Java、JNI、资源及压缩方式均影响接入大小，不能用 AAR 压缩体积代替宿主 APK 增量。

保留的异常和验证边界：

- ARM32 图片缓存检查一次在快速替换文档时发生 `FileNotFoundException`：测试自己创建的 `cache/image-memory-*.pptx` 已不存在；不修改图片生产代码的重跑通过，删除原因未定位，不记为稳定性问题已修复。
- 小米上的 adb 坐标点击没有切换 Demo 标签，未确认输入未送达的原因；华为无障碍点击检查通过，不能据此宣称小米系统触摸注入已通过。
- 设备连接期间发生过中断和切换，只有带明确通过标志的执行计入结果。未重跑完整测试入口、系统文件选择器、双指缩放或长时间压力测试。
- 仅覆盖构造样例；复杂工作簿、区域化数字格式和真实业务文件的兼容率未验证。ARM32 在支持 32 位应用的 ARM64 设备上测试，API 23 和纯 32 位旧设备仍未运行验证。
- Release Demo 已构建与检查包内容，但设备运行使用 Debug Demo。生产 XLSX 上限与不支持项见 README。

## 图片按需解码与释放

文档模型仅保留图片路径。Android 按可见区域异步解码图片，释放离屏缓存引用；当前缓存保留 800 万像素预算，单屏图片较多时进一步采样。换文件和 `clear()` 会清理缓存并异步关闭暂存 ZIP；临时脱离窗口后可重新挂载，宿主销毁时需调用 `clear()`。Demo 已接入销毁清理。

Android 读取预算改为按每个 ZIP 条目实际读取的最大字节数计费，重复翻页和失败重试不会对相同字节重复扣额度。文件、ZIP 解压和单条目上限保持不变。

| 环节 | 本轮结果 |
| --- | --- |
| Android 构建 | viewer Release AAR、测试 APK、demo Debug/Release APK 构建通过 |
| 静态检查 | `:viewer:lintDebug`：0 错误、1 个已有触摸可访问性警告；`git diff --check` 通过 |
| 独立源码复查 | 发现 API 23 的 `removeIf` 兼容性、失败读取重复扣额度、页外图片误加载问题，均已修正 |
| 测试代码 | 增加 `-e suite images`，构造 16 张不同的 1000 x 1000 图片，覆盖重复翻页超过 128 MiB 读取、像素绘制、缓存释放、重新挂载及旧回调抑制；测试 APK 已编译 |
| 设备回归 | 当前 adb 无设备，未执行本轮图片、布局和容器回归；不可沿用前两轮真机通过结果 |

尚未获得截图中的原始 PPTX，未确认该文件的实际显示效果。当前缓存预算不是应用总内存上限；系统绘制引用、解码临时数据和等待回收的旧 Bitmap 会增加瞬时内存。未完成低内存设备压力测试。

## 文件限制调整记录

Rust core 与 Android URI 暂存层同步将输入文件上限从 32 MiB 提高到 64 MiB，ZIP 声明的解压总量从 64 MiB 提高到 128 MiB。单个 XML、图片、累计读取量、像素和模型数量限制保持不变，没有新增依赖。

| 环节 | 本轮结果 |
| --- | --- |
| Rust | `cargo test --workspace --offline --locked`：41 项测试通过；fmt 与 Clippy `-D warnings` 通过 |
| 精确边界 | 输入恰好 64 MiB、解压总量恰好 128 MiB 可接受；分别超过 1 字节时拒绝 |
| Android 构建 | viewer Release AAR、测试 APK、demo Debug/Release APK 构建通过 |
| 包内容 | ARM32 与 ARM64 的 so 分别在 AAR、测试 APK、Release demo APK 中具有相同 SHA-256 |
| 设备路径 | 小米 API 36，分别指定 `arm64-v8a` 和 `armeabi-v7a` 安装并运行 `-e suite limits`，均输出 `ALL LIMIT CHECKS PASSED` |
| 接受用例 | 带 48 MiB 不压缩填充、96 MiB 可压缩填充的 DOCX，经 `file://` URI 暂存、JNI 解析和模型解码成功 |
| 拒绝与清理 | 65 MiB 不压缩填充触发输入限制，129 MiB 可压缩填充触发解压限制；暂存 ZIP 清理检查通过 |

测试 APK 的本地与安装文件 SHA-256 为：

```text
4321f2aaba122873e4e5e24c33c88b3cc363152d453ef89d68d0a467d2ad402d
```

本轮双 ARM AAR 为 650,433 B，demo Release APK 为 1,051,831 B。设备用例是在小型样例中添加未被文档引用的 ZIP 填充项，用于验证容器大小边界，不代表大型真实文档的渲染性能或低内存压力测试。本轮未重跑系统文件选择器、布局和手势用例，其历史验证边界见下方。

## 排版与页码升级记录

本轮实现 PPTX 文本框顶端/居中/底端对齐、DOCX 倍数/固定/最小行距和首行/悬挂/左右缩进、PPTX 矩形图片裁剪，以及当前页/总页数/跳页/页码监听接口。Demo 接入上一页/下一页，四个工具栏按钮改用统一的 24dp 矢量资源和禁用状态颜色。没有新增运行时依赖。

| 环节 | 该轮结果 |
| --- | --- |
| Rust | `cargo test --workspace --offline --locked`：39 项测试通过；fmt 与 Clippy `-D warnings` 通过 |
| Android 构建 | viewer Release AAR、测试 APK、demo Debug/Release APK 构建通过；本轮 Gradle 使用 JDK 25 |
| 包内容 | AAR 与 APK 保留 ARM32/ARM64；ARM32 so 在 AAR 和测试 APK 中的 SHA-256 一致；Release APK 通过 16 KiB zipalign 检查 |
| 新增功能运行 | 小米 API 36，指定 ARM32 和 ARM64 进程分别输出 `ALL LAYOUT CHECKS PASSED` |
| 断言范围 | DOCX 实际基线距离、文字起点与像素边界，PPTX 对齐位置与裁剪像素，页码注册/跳转/拖动/缩放/重载/清空/错误状态，以及布局前跳页 |
| 画面 | 1080 x 1600、1800 x 1000 Canvas 截图及实际窗口截图；人工和两轮独立复查未发现异常重叠或裁剪；Demo 矢量图标已安装检查 |
| Demo 路径 | Debug APK 的私有文件 URI 打开与图标显示已验证；系统选择器及 Release APK 的设备运行仍未验证 |

该轮测试 APK 的本地与安装文件 SHA-256 均为：

```text
068b48bad5b7c7e333754bd18626dec11cfb9f1668a392eb1c788a1fe2ad7b1d
```

该轮双 ARM AAR 为 650,425 B，demo Release APK 为 1,051,831 B。初版大小见历史表格；该轮未重新测量单 ABI 和空应用增量。

独立代码审查发现布局前调用 `jumpToPage` 的时序问题，已改为记录目标并在首次有效布局后执行，真机回归通过。缩进测试最初误用表示滚动边界的 `getLineLeft`，已改为文字坐标与实际绘制像素断言；生产缩进实现无需调整。窗口截图增加实际绘制等待和样例背景检查。

华为 API 31 的 ARM64 完整回归曾通过；此后仅调整截图检查。最终包在小米 ARM32 上的旧双击注入用例只收到一组 DOWN/UP，断言失败，未更改生产手势代码或删除该用例。新功能专用测试在两种 ABI 上通过，不能将这一结果描述为最终包所有系统触摸注入均稳定通过。

内部模型升级为 schema 2，core 与 viewer 需同时更新。运行验证仍局限于构造样例，不代表真实 Office 文件的完整兼容率。

## 初版记录

以下为升级前的验证结果和包体积。

## 完成证据

| 环节 | 结果 |
| --- | --- |
| Rust 源码 | `cargo test --workspace --offline`：29 项集成测试通过；Clippy `-D warnings` 和 `cargo fmt --all -- --check` 通过 |
| Android 构建 | viewer Release AAR、demo Debug/Release APK、测试 APK、baseline Release APK 构建通过；Release 使用 R8 |
| 原生产物 | ARM32、ARM64 和 x86_64 均交叉编译成功；默认 AAR/APK 包含 `armeabi-v7a` 和 `arm64-v8a` 的 `libjz_office.so` |
| JNI | 唯一导出入口为 `Java_cn_jingzhuan_lib_office_NativeCore_parse`；R8 mapping 保留 `NativeCore` 类名 |
| 对齐 | 打包后三种 ABI 的 ELF LOAD 段均为 `0x4000`；表中三种 Release APK 均通过 `zipalign -c -P 16 4` |
| 安装一致性 | 真机测试 APK 与本地 APK 的 SHA-256 相同，见下方 |
| viewer 运行 | Android API 36 真机的 ARM64 和指定 ARM32 进程各有 `ALL CHECKS PASSED` 记录；ARM32 触摸复跑存在窗口干扰，见下方 |
| demo 运行 | 安装返回 `INSTALL_FAILED_USER_RESTRICTED`；未绕过设备限制，系统选择器和 demo Activity 流程未验证 |

原 ARM64 测试 APK 的 SHA-256：

```text
c568cfd066bb8da45e8b1acfa19d6ab64381bf684dc622b796125256ef1372cb
```

补充 ARM32 后的双 ARM 测试 APK（含触摸诊断）SHA-256：

```text
710933e3db1c67e6da8189c7c6e38fc3e94e21dcc287b9106dd099563d0a33d5
```

通过 `adb install -r --abi armeabi-v7a` 安装，包信息显示 `primaryCpuAbi=armeabi-v7a`，再用 `am instrument --abi armeabi-v7a` 启动。安装文件与本地产物哈希一致，测试 APK 内的 ARM32 so 与交付 AAR 中的 so 哈希一致。该 so 为 ELF32/ARM，JNI 导出入口正确。

构建环境：Rust 1.87、JDK 21、Gradle 9.5.1、AGP 9.2.1、SDK 35、NDK 28.2.13676358。实际使用本机已有 Gradle 发行版执行构建；未完成 wrapper 的首次联网下载验证。构建保留了 Android/Gradle API 弃用提示，没有编译或 lint-vital 错误。

本机一个全局 Gradle init 脚本会注入其他工程的仓库，与项目仓库策略冲突。验证使用独立 Gradle 用户配置目录并复用已有依赖缓存，没有修改全局脚本。

## 真机覆盖

使用测试 ContentProvider 返回的管道型 `content://`，没有真实路径，也不提供文件长度。测试走完整的 URI 复制、Rust JNI 解析、Android 图片解码和 Canvas 渲染路径。

- DOCX：中英文、粗斜体、下划线、换行、图片和矩形表格。
- PPTX：两页演示顺序、绝对坐标文本、图片、矩形、椭圆、直线和表格。
- 渲染：1080 x 1600、1800 x 1000 Canvas 截图及实际窗口截图，人工检查样例内容没有异常重叠。
- 交互：注入实际触摸事件，双击从 1 倍变为 2 倍；程序缩放和复位。
- 错误与生命周期：损坏/缺失文件回调、快速更换 URI 抑制旧回调、`clear()` 和临时 ZIP 清理。

设备会拦截测试进程直接从后台启动 Activity。线程栈和系统日志确认后，仅测试入口改用 UiAutomation shell 启动，等待 Activity 设置了超时；生产 viewer 不依赖这条测试路径。

ARM32 的前两次执行在双击断言处失败，解析与初步渲染均已完成。测试补充触摸事件记录后，一次全套用例通过，记录中的四个 DOWN/UP 事件到达 viewer，缩放值为 2。后续复跑收到 `Targeted input event injection ... was not directed at a window owned by uid ...`，系统拒绝向不属于测试应用的窗口注入触摸；停止了进一步触摸注入。前两次失败原因未完全确认，不能把该双击用例记为稳定通过；保留成功和窗口阻断两份运行记录。生产渲染与手势代码未因此修改。

## 包体积

单位为实际文件字节；KiB = 1024 字节，MiB = 1048576 字节。APK 使用同一套 Release/R8 配置，未签名。默认打包 ARM32 + ARM64；无内置字体、WebView 或 JVM 第三方运行库。

| 配置 | viewer Release AAR | demo Release APK | 相对空应用的 APK 增量 |
| --- | ---: | ---: | ---: |
| **ARM32 + ARM64（默认）** | **639,723 B（624.73 KiB）** | **1,029,932 B** | **1,027,692 B（0.98 MiB）** |
| ARM64 | 351,074 B（342.85 KiB） | 601,399 B | 599,159 B（585.12 KiB） |
| ARM64 + x86_64 | 687,528 B（671.41 KiB） | 1,258,871 B | 1,256,631 B（1.20 MiB） |

空应用 Release APK 为 2,240 B。打包后原生库：ARM32 为 422,104 B；ARM64 为 583,472 B；x86_64 为 651,048 B。AAR 中的 so 会压缩，APK 中按未压缩原生库对齐存放，因此 AAR 大小不能直接当作 APK 增量。

这个增量包含 demo 的文件选择与状态 UI，是接入估算，不是对任意宿主应用的精确预测。签名、宿主 R8、打包配置和 ABI 分发策略都会改变最终结果。默认双 ARM Debug APK 为 1,473,396 B，不用于估算 Release 增量。

## 输入边界修复

独立代码检查发现 ZIP 2.4.2 会在暴露条目前合并同名项，导致 core 原先的重复名称检查无效。新增测试先复现失败；修复后在 ZIP 库读取前校验原始目录条数与边界，再与库暴露的条数比较。重复项、超限声明、不一致目录、路径逃逸、DTD、外部关系和截断文件的相关回归均通过。合法 ZIP 注释也有覆盖。

最终修复后的独立复查被服务端策略拦截；本地源码复查、回归、构建和真机验证已完成，不把它记作独立复查通过。

## 验证边界

- 仅验证仓库内可复现样例和构造用例，没有以大量实际 Office 文件验证兼容率或像素级版式一致性。
- `.doc/.ppt`、加密文件、ZIP64/分卷容器、复杂图表/SmartArt/公式/精确分页不支持。
- ARM32 运行验证在支持 32 位应用的 ARM64 设备上完成，没有使用旧款纯 32 位设备。x86_64 只验证构建及打包，没有在对应设备运行。
- API 23 为最低声明版本，实际运行只验证 API 36。16 KiB 为产物对齐检查，没有在 16 KiB 内核设备运行。
- 没有做大文档性能基准、低内存压力测试、双指缩放或长时间滚动测试；当前使用受限的完整文档模型。
- R8 Release 的 JNI 保留规则已检查，真机运行的是 Debug Java + Release Rust 的测试 APK，未安装 Release demo。

复现命令和接入方式见 `README.md`。
