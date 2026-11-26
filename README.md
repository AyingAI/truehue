# 本色相机 · TrueHue

TrueHue 是一款开源 Android 相机。它通过 Camera2 使用设备开放的后置镜头，在手机本地将 Bayer RAW 显影为 JPEG，可同时保存一份 DNG 底片。

## v0.7.0

- **原彩成片**采用 Display P3 广色域，改善暗光彩噪和长焦暗部浮灰。自动白平衡会根据场景保留少量现场光的冷暖。
- **手持拍摄**在多帧合成时自动选锐帧，条件允许时缩短单帧曝光。白天光线充足时可自动切换为单帧原彩直出。
- **拍摄设置**精简为「拍摄」和「拍照辅助」，底部显示安装版本。亮彩、存底片、九宫格和使用指南在取景页顶部操作。

[完整更新说明](CHANGELOG.md) · [GitHub 发布页](https://github.com/AyingAI/truehue/releases/tag/v0.7.0)

## 支持范围

- 最低支持 Android 12（API 31）。是否能访问 RAW 和独立后置镜头，取决于设备通过 Camera2 暴露的能力。RAW 不可用时，应用会尝试原生 JPEG 或取景帧，并在界面显示使用的模式。
- 小米 17 上验证了 17 mm 超广角、23 mm 主摄和 60 mm 长焦。三枚镜头均可输出 4096 × 3072 RAW，原彩竖拍 JPEG 为 3072 × 4096。
- 原彩直出使用 Display P3，并在 JPEG 中嵌入 ICC 色彩配置。Android 14（API 34）及以上可开启「亮彩」生成带 Ultra HDR 增益图的 JPEG。支持增益图的相册和显示设备可呈现 HDR，普通相册仍可读取其中的 SDR 图像。

## 拍摄

- 点取景框下方的焦段切换镜头，按钮上标有等效焦距。轻点画面可对焦和测光，长按可锁定。可在取景区上下滑动或使用「亮度」拨盘调整曝光补偿，在「色调」中选择自动、预设或手动色温。
- 右下角可选原彩、光影、柔光、层次和黑白滤镜。普通取景叠加当前滤镜。想查看 RAW 显影的效果，可在拍摄设置里开启「原彩预览」，刷新会慢一些。
- 「原彩直出」使用 RAW，可在设备支持时启用多帧对齐降噪和「亮彩」。「极速快拍」保存当前取景帧。
- 顶部「存底片」可同时保存 DNG。多帧模式下，DNG 保存一张原始 RAW，JPEG 以选出的锐帧为参考进行合成。照片保存在 `DCIM/TrueHue/`。点击左下角缩略图可回看、打开系统相册或分享。
- 相机首次就绪时会自动打开新手指南，也可从顶部「使用指南」随时查看。在设置中的「拍照辅助」可开启直方图和专业参数。

## 构建和安装

从发布页下载源码，或用 Git 检出 `v0.7.0`。需要 **JDK 17** 和 Android SDK 中的 **Android Platform 35**、**Build Tools 35.0.0**。真机安装还需要 **Platform Tools**。可通过 Android Studio 的 SDK Manager 安装 SDK，并设置 `ANDROID_HOME`。项目包含 Gradle 8.9 Wrapper，首次构建会下载构建依赖。

macOS 和 Linux 运行以下命令。Windows 使用 `gradlew.bat`。

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。启用手机的 USB 调试并授权连接后，运行 `./gradlew :app:installDebug` 即可安装。`./gradlew :app:assembleRelease` 生成未签名 APK，可用自己的 Android 签名密钥签名。覆盖安装需要与手机上现有 APK 使用相同签名。

## 测试

纯 Java 检查使用合成像素和镜头标定参数，覆盖 Bayer 插值、白平衡、色彩矩阵、多帧融合、降噪、滤镜、影调及 HDR 增益图。以下命令另在 256 MiB Java 堆下验证 4096 × 3072 显影。快速检查可以省略 `--full`。

```sh
classes=$(mktemp -d)
javac -d "$classes" \
  app/src/main/java/com/truehue/camera/{BayerPipeline,BurstRawMerger,PhotoFilter,SensorColorMatrix,HighlightMeter,UltraHdrPatcher}.java \
  tests/RawDevelopmentCheck.java
java -Xmx256m -cp "$classes" com.truehue.camera.RawDevelopmentCheck --full
```

在已授予相机权限的小米 17 上运行真机检查。测试会拍照并写入系统相册。

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w com.truehue.camera.test/com.truehue.camera.LensSmoke
```

- 默认检查三枚镜头的 RAW 预览、JPEG 尺寸、EXIF、Display P3 ICC 配置和 Android 14 及以上的 Ultra HDR 增益图，并验证主摄可保存 DNG。
- 在测试类名前加 `-e exposureOnly true` 可检查高光测光、单帧曝光锁和重建相机会话后的曝光补偿。加 `-e settingsOnly true` 可检查设置、回看和滤镜保存。加 `-e panelsOnly true` 可检查首次启动指南、窄屏顶部按钮和快捷开关。
- 请保持手机解锁。如果系统拦截后台启动，在测试输出「正在打开本色相机」时从手机上打开应用。

## 隐私

TrueHue 只申请相机权限。取景、显影和编码在手机本地完成，应用没有网络权限、账号、遥测或上传功能。应用将拍摄设置和最近一张照片的引用保存在本地。打开相册或分享时，Android 会将所选照片的读取权限授予处理该操作的应用。

JPEG 和 DNG 可以包含拍摄时间、设备型号和曝光参数。原生 JPEG 和 DNG 可能保留系统相机提供的其他元数据。TrueHue 不请求定位权限，也不写入 GPS 坐标。公开照片前请检查画面和文件元数据。

## 许可证

TrueHue 使用 [Apache License 2.0](LICENSE)。Gradle Wrapper 保留了上游的许可证和版权声明。
