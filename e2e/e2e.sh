#!/bin/bash
# E2E 辅助命令集：bash e2e/e2e.sh <cmd> [args...]
#
# 模拟器 UI 自动化测试：生成测试素材 → 导入 App → 拼接 → 校验状态。
# 路径均可用环境变量覆盖：
#   ADB         adb 可执行文件（默认取 %LOCALAPPDATA% 下的 SDK）
#   FFMPEG_BIN  桌面端 ffmpeg（genmix 生成素材用，默认取 PATH 里的 ffmpeg）
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
UI="${TMPDIR:-/tmp}/ui_e2e.xml"
PKG="com.kai.videostitcher"

dump() {
  MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" exec-out cat /sdcard/ui.xml > "$UI" 2>/dev/null
}

center() { python "$DIR/uiparse.py" "$UI" "$1" "$2" 2>/dev/null; }

tap() { # tap <center_xxx mode> <value>
  dump
  local p; p=$(center "$1" "$2")
  if [ -z "$p" ]; then echo "TAP_MISS $2"; return 1; fi
  "$ADB" shell input tap $p >/dev/null 2>&1
}

texts() { dump; python "$DIR/uiparse.py" "$UI" listtexts "$1" 2>/dev/null; }

grant() {
  "$ADB" shell pm grant $PKG android.permission.READ_MEDIA_VIDEO 2>/dev/null
  "$ADB" shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
}

cleardata() {
  "$ADB" shell pm clear $PKG >/dev/null
  grant
  "$ADB" shell am start -n $PKG/.MainActivity >/dev/null 2>&1
  sleep 3
}

select_file() { # 滚动查找并点选文件（SAF 单击即勾选）
  local f="$1" p attempt
  for attempt in 1 2 3 4 5 6; do
    dump
    p=$(center center_text "$f")
    if [ -n "$p" ]; then
      "$ADB" shell input tap $p >/dev/null 2>&1
      sleep 1
      return 0
    fi
    MSYS_NO_PATHCONV=1 "$ADB" shell input swipe 540 1700 540 700 300 >/dev/null 2>&1
    sleep 1.5
  done
  return 1
}

confirm_saf() {
  dump
  local p; p=$(center center_tcon "保存")
  [ -z "$p" ] && p=$(center center_tcon "打开")
  [ -z "$p" ] && p=$(center center_resid "container_save")
  if [ -z "$p" ]; then echo "NO_CONFIRM"; return 1; fi
  "$ADB" shell input tap $p >/dev/null 2>&1
  sleep 3
}

newgroup() { # newgroup name1 name2 ...  （走 App 内置相册选择器，多选）
  tap center_tcon "从相册选" || return 1
  sleep 2.5
  local f
  for f in "$@"; do
    select_file "$f" || { echo "SEL_MISS $f"; return 1; }
  done
  tap center_resid "btnAlbumConfirm" || { echo "NO_CONFIRM"; return 1; }
  sleep 2.5
  texts "tvFileName"
}

genmix() { # 桌面生成混合参数测试素材并推送到 /sdcard/Download/vtest_mix（触发 v1.5 第三级转码）
  local FF="${FFMPEG_BIN:-ffmpeg}"
  [ -x "$FF" ] || { echo NO_FFMPEG; exit 1; }
  local out="${TMPDIR:-/tmp}/vtest_mix"
  rm -rf "$out"
  mkdir -p "$out"/mix_codec "$out"/mix_res "$out"/mix_rot "$out"/mix_audio "$out"/mix_container
  local T="-f lavfi -i testsrc2"
  # 1) 混编码：H.264 混 H.265（同分辨率 640x360）
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -c:v libx264 "$out/mix_codec/a_h264.mp4"
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -c:v libx265 -tag:v hvc1 "$out/mix_codec/b_hevc.mp4"
  # 2) 混分辨率：1920x1080 混 1280x720（同为 H.264）→ 转码走 scale+pad 统一画布
  "$FF" -y -loglevel error $T=size=1920x1080:rate=30:duration=2 -pix_fmt yuv420p -c:v libx264 "$out/mix_res/a_1080.mp4"
  "$FF" -y -loglevel error $T=size=1280x720:rate=30:duration=3 -pix_fmt yuv420p -c:v libx264 "$out/mix_res/b_720.mp4"
  # 3) 横竖混向：带 rotate=90 元数据的横拍 混 真竖拍像素（探测方向 90 vs 0）
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -c:v libx264 "$out/mix_rot/a_rot90.mp4"
  python "$DIR/patch_rotate.py" "$out/mix_rot/a_rot90.mp4" 90 >/dev/null
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -vf transpose=1 -c:v libx264 "$out/mix_rot/b_portrait.mp4"
  # 4) 无声混有声：一段只有视频，一段带 440Hz 正弦音轨 → 转码补静音轨
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -c:v libx264 "$out/mix_audio/a_silent.mp4"
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -f lavfi -i sine=frequency=440:duration=3 -map 0:v -map 1:a -pix_fmt yuv420p -c:v libx264 -c:a aac -shortest "$out/mix_audio/b_sound.mp4"
  # 5) 混容器+混编码：H.265 的 MKV 混 H.264 的 MP4
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -c:v libx265 "$out/mix_container/a_hevc.mkv"
  "$FF" -y -loglevel error $T=size=640x360:rate=30:duration=3 -pix_fmt yuv420p -c:v libx264 "$out/mix_container/b_h264.mp4"
  MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$out")" /sdcard/Download/ >/dev/null || { echo PUSH_FAIL; return 1; }
  echo GEN_OK
}

import_folder() { # import_folder <name>：从文件夹导入 /sdcard/Download/<name>（各子文件夹自动成组）
  tap center_resid "btnImportFolders" || { echo NO_IMPORT_BTN; return 1; }
  sleep 3
  select_file "Download" || { echo NO_DOWNLOAD; return 1; }
  sleep 2
  select_file "$1" || { echo NO_FOLDER; return 1; }
  sleep 2
  local ok=0 i
  for i in 1 2 3 4 5; do
    if tap center_text "ALLOW"; then ok=1; break; fi
    if tap center_tcon "此文件夹" || tap center_tcon "USE THIS"; then ok=1; break; fi
    sleep 2
  done
  [ "$ok" = "1" ] || { echo NO_USE_BTN; return 1; }
  sleep 4
  texts "tvFileName"
}

startmerge() { # 点开始拼接并轮询到整体结束（tvStatus 出现"完成"），输出耗时与各组状态
  sleep 1
  dump
  local p; p=$(center center_resid "btnStart")
  [ -z "$p" ] && { echo "NO_START"; return 1; }
  local t0=$(date +%s)
  "$ADB" shell input tap $p >/dev/null 2>&1
  local ov=""
  for i in $(seq 1 600); do
    sleep 1
    dump
    ov=$(python "$DIR/uiparse.py" "$UI" listtexts "tvStatus" 2>/dev/null | head -1)
    case "$ov" in *"拼接中"*) continue;; esac
    case "$ov" in *"完成"*) break;; esac
  done
  local t1=$(date +%s)
  echo "ELAPSED=$((t1-t0))s"
  echo "OVERALL: $ov"
  echo "GROUP_STATUS: $(texts 'tvGroupStatus' | tr '\n' ' | ')"
}

case "$1" in
  grant)   grant ;;
  open)    "$ADB" shell am start -n $PKG/.MainActivity >/dev/null; sleep 3 ;;
  clear)   cleardata ;;
  dump)    dump; echo done ;;
  taptext) tap center_text "$2" ;;
  taptcon) tap center_tcon "$2" ;;
  tapres)  tap center_resid "$2" ;;
  texts)   texts "$2" ;;
  newgroup) shift; newgroup "$@" ;;
  merge)   startmerge ;;
  scen)    shift; cleardata; newgroup "$@"; startmerge ;;
  importtree) # 从文件夹导入 /sdcard/Download/vtest（各子文件夹自动成组）
    cleardata
    import_folder "vtest" || exit 1
    ;;
  genmix)    genmix ;;
  mixscen)   # 混合参数全套 e2e：清数据 → 生成并推送素材 → 导入 → 拼接 → 报告状态
    cleardata
    genmix >/dev/null || { echo GEN_FAIL; exit 1; }
    import_folder "vtest_mix" || exit 1
    startmerge
    ;;
  reorder) # 排序验证：先报告当前顺序，再点第1行的 ↓，报告新顺序
    echo "BEFORE: $(texts 'tvFileName' | tr '\n' ' ')"
    dump
    p=$(python "$DIR/uiparse.py" "$UI" center_resid btnDown 2>/dev/null)
    [ -z "$p" ] && { echo NO_BTNDOWN; exit 1; }
    "$ADB" shell input tap $p >/dev/null 2>&1
    sleep 2
    echo "AFTER_DOWN: $(texts 'tvFileName' | tr '\n' ' ')"
    ;;
  *) echo "unknown cmd"; exit 1 ;;
esac
