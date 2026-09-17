# 验证记录

日期：2026-09-17。版本：0.1.0。

项目 `JZOfficeAndroid`，Rust crate `jz-office-core`，Android namespace `cn.jingzhuan.lib.office`。以下先记录本轮图片缓存调整，后续章节为历史验证结果，不将历史结果视为当前产物证明。

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
