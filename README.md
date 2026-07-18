# DashCam

[![Android](https://img.shields.io/badge/Android-API%2036%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

DashCam 是一款将 Android 手机变成行车记录仪的开源应用。它基于 CameraX 构建，提供分段录制、循环存储、自动画质、安全降档、本地视频管理和外部记录仪访问等能力。

> [!IMPORTANT]
> 项目目前处于早期实验阶段，主要面向自用、学习和二次开发。不同厂商对后台相机、锁屏录制、编码器和温控的限制差异较大，请务必在目标真机上充分测试后再用于实际行车场景。

## 主要功能

### 行车录制

- 通过前台服务持续录制，并显示常驻通知。
- 支持 1、2、3、5、10 分钟分段保存。
- 达到存储配额或安全空间阈值时自动清理最旧片段。
- 支持后置主摄、超广角镜头选择，以及 1.0x～2.0x 中心裁剪放大。
- 可按设备能力选择 720p、1080p、4K，24、30、60 fps，以及 H.264、H.265 编码。
- 支持 HDR、视频防抖、录音开关和节省空间、标准、高画质三档质量设置。
- 自动画质可根据设备能力和可用空间调整分辨率、帧率、编码、质量、HDR 与防抖。

### 安全与后处理

- 存储不足时自动清理或降低画质。
- 监控设备温度、电量和录制管线状态，必要时提示、降档或停止录制。
- 默认预留 10% 系统存储空间。
- 录制结束后分析音频，仅对明确的低频风噪、共振和高频宽带噪声进行降噪处理。
- 降噪任务仅在应用可见、屏幕亮起、未录制且未播放视频时运行，处理失败不会覆盖原视频。

### 视频管理

- 查看、播放和连续播放本地录像。
- 删除单个视频或按时间批量清理。
- 导出视频到系统媒体库 `Movies/DashCam`。
- 通过 Android 系统分享面板分享视频。

### 外部记录仪

- 目前支持 **艾尔优 DC1**。
- 支持实时预览、分类读取远端录像、在线播放和断点下载。
- 支持记录并导出设备连接与操作日志，便于定位问题。
- 其他品牌和型号尚未适配，后续将逐步接入。

## 快速开始

### 环境要求

- Android Studio，或可用的 Android Gradle 命令行环境。
- JDK 17。
- Android SDK 36。
- Android 设备 API 36 或更高版本。
- 用于验证相机、编码器、发热和锁屏录制行为的真实设备。

当前应用版本为 `0.1.0`，项目配置如下：

```kotlin
compileSdk = 36
minSdk = 36
targetSdk = 36
```

### 构建

```powershell
git clone https://github.com/xxxifan/DashCam.git
cd DashCam
.\gradlew.bat assembleDebug
```

生成的 Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 安装

连接已启用 USB 调试的 Android 设备后执行：

```powershell
.\gradlew.bat installDebug
```

也可以使用 Android Studio 打开项目并直接运行 `app` 模块。

## 使用说明

### 首次录制

1. 授予相机、通知等必要权限；需要录音时再授予麦克风权限。
2. 首次使用建议从 `1080p30 + H.265 + 标准` 开始测试。
3. 根据可用空间设置循环录制配额，并录制几个完整片段确认设备兼容性。
4. 长时间录制时建议连接车充，并留意设备温度。

如果手机镜头视角过广，可尝试 `1.2x` 或 `1.4x` 裁剪放大。如果锁屏后录制不稳定，可保持应用前台或屏幕常亮，并尝试降低帧率、关闭 HDR 或增强防抖。

### 连接外部记录仪

1. 在 Android 系统设置中连接艾尔优 DC1 的 Wi-Fi。
2. 打开 DashCam 底部的“设备”页面。
3. 等待应用发现设备，然后使用实时预览、远端录像播放或下载功能。

当前仅对艾尔优 DC1 提供支持，其他设备即使具有相似的连接方式，也不保证能够正常使用。

## 文件存储

手机录制的视频默认保存在应用专属外部目录：

```text
Android/data/com.xxxifan.dashcam/files/Movies/DashCam/records
```

目录中的 `.nomedia` 文件会阻止未导出视频自动出现在系统相册。执行“导出”后，视频会复制到：

```text
Movies/DashCam
```

外部记录仪下载的文件保存在：

```text
Android/data/com.xxxifan.dashcam/files/Movies/DashCam/devices/<device-id>
```

> [!WARNING]
> 卸载应用可能会同时删除应用专属目录中的录像。需要长期保留的视频请提前导出。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 相机 | 录制视频 |
| 麦克风 | 在开启录音时采集音频 |
| 通知 | 显示录制中的常驻通知 |
| 前台服务 | 在录制期间维持前台服务运行 |
| 唤醒锁 | 尽量保持录制过程稳定 |
| 网络与 Wi-Fi 状态 | 发现并连接外部记录仪 |
| 网络访问 | 预览、播放和下载外部记录仪内容 |

部分系统还会限制后台运行、锁屏相机或高耗电行为。建议在系统设置中允许 DashCam 后台运行，并关闭针对本应用的过度省电限制。

## 技术栈

- Kotlin
- Jetpack Compose / Material 3
- CameraX VideoCapture / Camera2Interop
- Media3 ExoPlayer / RTSP / Transformer
- MMKV
- Kotlin Coroutines
- Gradle Kotlin DSL

## 项目结构

```text
app/src/main/java/com/xxxifan/dashcam
├── camera/       # 相机能力、镜头选择和预览绑定
├── data/         # 设置、录制记录、缩略图和事件日志
├── device/       # 设备信息与外部记录仪接入
├── recording/    # 录制服务、画质策略、音频后处理和录制状态
├── safety/       # 存储、温度、电量和录制管线安全策略
├── storage/      # 循环录制空间估算和清理
└── MainActivity.kt
```

## 诊断日志

应用会在私有目录写入录制事件日志，用于排查设备能力、录制参数、分段结果、空间清理和异常停止原因。外部记录仪的连接与操作日志保存在：

```text
files/device_logs/device-events-YYYYMMDD.log
```

日志不会自动上传。提交 issue 前，请先检查日志是否包含本地文件路径、设备信息或其他不希望公开的内容。

## 已知限制

- 锁屏录制稳定性取决于设备厂商、电池策略和系统版本。
- CameraX 的目标帧率是请求值，不代表所有设备都能严格输出相同帧率。
- HDR、防抖、物理镜头和 H.265 等能力均依赖设备支持。
- 外部记录仪目前仅支持艾尔优 DC1。
- 项目暂未提供 Play Store 发布配置、Release 签名配置和正式发行包。
- 最低系统版本为 API 36，旧版 Android 设备无法直接安装。

## 路线图

- 适配更多外部记录仪品牌和型号。
- 增加更多 Android 设备兼容性验证。
- 增加录制参数与实际视频元数据的对照诊断。
- 优化横屏和车载场景下的界面体验。
- 增加后台稳定性测试、Release 构建和自动化检查。

路线图代表当前方向，不构成发布时间承诺。欢迎通过 [Issues](https://github.com/xxxifan/DashCam/issues) 参与讨论。

## 参与贡献

欢迎提交 issue 和 pull request。开始较大改动前，建议先创建 issue 说明需求和方案，避免重复工作。

反馈录制问题时，请尽量提供：

- 手机型号和 Android 版本。
- 分辨率、帧率、编码、质量、HDR、防抖和裁剪倍率等录制设置。
- 问题发生时是否锁屏、充电或明显发热。
- 已移除敏感信息的相关诊断日志。

反馈外部记录仪问题时，请同时提供设备型号、固件版本、问题复现步骤和已脱敏的设备日志。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
