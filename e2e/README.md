# E2E 测试工具链

模拟器 UI 自动化测试：生成测试素材 → 推送到设备 → 从文件夹导入 → 拼接 → 校验分组状态。
仅在开发/验收时使用，App 本身不依赖本目录任何内容。

## 依赖

- Android SDK（adb + 模拟器）+ 一个已创建的 AVD
- Python 3（`uiparse.py` 解析 uiautomator dump，标准库实现）
- 桌面端 ffmpeg（仅 `genmix` 生成素材时需要；默认取 PATH，或用 `FFMPEG_BIN` 指定）

## 常用流程

```bash
# 1. 启动模拟器后：装最新构建并授权
adb install -r app/build/outputs/apk/debug/app-debug.apk
bash e2e/e2e.sh clear

# 2. 生成混合参数素材并推送（触发 v1.5 第三级转码）
bash e2e/e2e.sh genmix

# 3. 导入 vtest_mix（SAF 文件夹选择流程）并拼接
bash e2e/e2e.sh import_folder vtest_mix
bash e2e/e2e.sh merge

# 一键版：清数据 → 生成 → 导入 → 拼接
bash e2e/e2e.sh mixscen
```

无桌面 ffmpeg 时指定路径：`FFMPEG_BIN=/path/to/ffmpeg.exe bash e2e/e2e.sh genmix`

## 命令一览

| 命令 | 作用 |
| --- | --- |
| `clear` | 清应用数据、授权、启动 App |
| `genmix` | 桌面生成 5 组混合参数素材（混编码/混分辨率/横竖混向/无声混有声/混容器）并推送 |
| `import_folder <name>` | SAF 导入 /sdcard/Download/<name>（各子文件夹自动成组） |
| `merge` | 点开始拼接并轮询到结束，输出耗时与各组状态 |
| `mixscen` | clear + genmix + import_folder + merge 一键全流程 |
| `texts <id后缀>` | 输出当前界面匹配的文本（如 `tvGroupStatus`） |
| `newgroup <文件名...>` | 走相册选择器多选建组 |
| `reorder` | ↑↓ 排序交换验证 |

## 文件说明

- `e2e.sh`：命令入口与 UI 自动化辅助函数
- `uiparse.py`：从 uiautomator dump 的 XML 里取控件中心坐标/文本
- `patch_rotate.py`：把 MP4 的 tkhd 显示矩阵改成指定角度（生成带旋转元数据的测试素材用；
  部分老 ffmpeg 写不进 rotate 元数据，直接改 tkhd 最可靠）
