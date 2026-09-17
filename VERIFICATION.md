# 验证记录

日期：2026-09-17。版本：0.1.0。

项目 `JZOfficeAndroid`，Rust crate `jz-office-core`，Android namespace `cn.jingzhuan.lib.office`。核心解析、JNI、URI viewer 和 demo 已实现。viewer 的样例真机路径已验证；demo 系统选择器路径未完成设备验证。

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
