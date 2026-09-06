# 批量视频拼接器 (VideoStitcher)

一个只做一件事的安卓小工具：把多组视频分别首尾相连拼成一个 MP4，支持批量处理多个分组。
免费、无广告、不联网（未声明任何网络权限），所有处理都在手机本地完成。

A minimal Android app that stitches multiple groups of videos into one MP4 each — free, ad-free, and fully offline.

## 功能特性

- **批量分组**：多选视频自动成组，或「从文件夹导入」一个总目录、每个子文件夹自动成一组
- **只做无损拼接，不做转码**，共两级引擎：
  1. **无损拼接**（秒级、零画质损失）——组内编码/分辨率/拍摄方向/音频参数一致且为 MP4/MOV/M4V，直接在容器层拼接（mp4parser）；帧率不同也能拼（输出可变帧率）；竖拍视频的方向元数据原样保留
  2. **无损转封装**（快、零画质损失）——参数一致（无旋转元数据）但容器为 MKV/WebM/TS 等时，直接拷贝流换壳成 MP4
  3. **参数不一致直接中止**——红字写明差在哪里（编码/分辨率/方向/音频），提示自行转码成参数一致的普通 MP4。真机上转码管线不可控（会产出拉伸/错乱/无法播放的成品），1.4 起彻底移除，只交付比特级正确的结果
- **成品自检**：拼完先核对时长、首/中/尾三点试解码、检查采样表，自检不过自动换更稳妥方式重拼，仍失败则中止——绝不把解不动的文件标成成功
- **编码格式显示**：每个视频下方直接显示 `H.265 1920×1080 · AAC`、`AV1 1072×1920 · Opus` 等
- 自然排序（`01、02、…、10`）、拖动排序（↑↓ 正确交换不覆盖）、分组自动保存、成品输出到 `相册/Movies/VideoStitcher/`

## 已知缺陷与限制

- **参数不一致的视频拼不了（核心限制）**：不同编码（如 H.264 混 H.265、混 AV1）、不同分辨率、
  不同拍摄方向（横竖混）、音频不一致（含部分无声）——这种分组会**直接中止**并红字提示差异，
  不会产出成品。无损拼接要求同一条视频轨是同一种编码的压缩流，这是视频格式的基本原理。
- **没有转码能力**：无法统一编码/分辨率、无法压缩体积、无法去音轨、HDR 无法压 SDR。
  转码路径在 1.4 被刻意移除——真机上各硬件编码会话参数集有差异、media3 序列对旋转
  元数据处理不可靠，实测会产出无法播放或拉伸错乱的成品（详细复盘见 [ROADMAP.md](ROADMAP.md)）。
  需要混合参数拼接时，请先用格式工厂/剪映等工具统一转码再导入。
- **无损转封装范围有限**：仅支持能直封 MP4 的编码。MP3/AC3/FLAC 音频、MPEG-2 视频等
  会中止提示；带旋转元数据的 MKV/WebM 也不支持转封装（竖拍视频请用 MP4/MOV 原件，
  容器层拼接可以原样保留方向）。
- **封装不规范的文件会中止**：个别来源特殊的视频（网页下载/聊天转发）编码引擎读不动，
  直接中止提示自行转码。没有自动修复——1.2 的自动修复实测会产出更多坏文件，已移除。
- **WMV/ASF、RM/RMVB 容器无法导入**（安卓无对应解码组件）。
- **拼接边界小瑕疵**：个别组在两段视频交界处存在 dts 时间戳重复（历史遗留问题），
  主流播放器无感，极严格的播放器或电视盒子上可能在交界点轻微卡顿。

## 未来优化空间

详细方案见 [ROADMAP.md](ROADMAP.md)，概要：

1. **路线 A（计划采用）：自研受控硬件转码管线** —— 逐视频独立转码（不进任何多项序列）、
   OpenGL 显式矩阵把旋转烤进像素、**拼接前逐段校验编码参数集（SPS/PPS 字节级比对）**、
   成品自检兜底。目标是让混合编码/分辨率/方向也能拼，同时"坏文件"在机制上不可能产出
   （最坏情况是中止）。预估 2~4 周，须真机验收。
2. **路线 B（备选）：内嵌 ffmpeg 社区 fork**（官方 ffmpeg-kit 已退役）—— 软编码行为
   全机型一致，代价是 APK 增重 30~80MB、x264 的 GPL 协议与 MIT 仓库冲突、转码慢且发热。
3. **路线 C（观望）：media3 上游修复序列旋转缺陷** —— 若官方修复可低成本恢复转码路径
   （旧实现在 git 历史 `08e3fe4`），目前最新版 1.11.0 尚无修复迹象。
4. 顺带项：AV1 软解码集成（dav1d/libgav1）、更多音频编码的转封装支持。

## 截图

![应用截图](screenshot.png)

## 下载安装

前往 [**Releases**](https://github.com/november-is-your-birthday/VideoStitcher/releases) 下载最新的 `批量视频拼接器.apk`，直接安装（允许未知来源即可）。

支持 Android 7.0（API 24）及以上。

## 构建方法

```bash
git clone https://github.com/november-is-your-birthday/VideoStitcher.git
cd VideoStitcher
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或用 Android Studio 打开项目直接运行。`local.properties` 不在仓库中，命令行构建时 Gradle 会自动定位 SDK，也可自行创建并写入 `sdk.dir`。

> settings.gradle.kts 中配置了阿里云 Maven 镜像（同时保留 google()/mavenCentral() 兜底），国内环境下载依赖更快，海外环境也可正常构建。

## 技术实现

- **语言/UI**：Kotlin + XML View + Material Components，单 Activity，无第三方 UI 库
- **无损拼接**：[mp4parser (org.mp4parser:muxer)](https://central.sonatype.com/artifact/org.mp4parser/muxer) 容器级追加音视频轨；安卓上需从 assets 注入 box parser 配置（仓库内已处理）
- **无损转封装**：[Media3 Transformer](https://developer.android.com/media/media3/transformer)（ExoPlayer 家族官方编辑引擎）transmux 模式，仅拷贝流不重编码（其它容器 → MP4）
- **成品自检**：MediaMetadataRetriever 三点抽帧解码 + MediaExtractor 采样表全量检查（小文件），不过自动降级重拼
- **输出**：MediaStore（无需存储权限，API 29+），旧版本走运行时权限
- **探测**：MediaExtractor 读取编码/分辨率/旋转/音频参数，条目内直接展示

详细用法见 [使用说明.md](使用说明.md)。

## License

[MIT](LICENSE)
