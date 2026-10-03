"""把 MP4 第一条视频轨的 tkhd 显示矩阵改成 90° 旋转（等价于手机竖拍的方向元数据）。

用法: python patch_rotate.py <file.mp4> [90|180|270]
2028 的老 ffmpeg 写不进 rotate 元数据，直接改 tkhd 最可靠。
"""
import struct
import sys

# 16.16/2.30 定点旋转矩阵（Android/iPhone 竖拍用的标准 90° 矩阵）
MATRICES = {
    90: [0x00000000, 0x00010000, 0x00000000,
         0xFFFF0000, 0x00000000, 0x00000000,
         0x00000000, 0x00000000, 0x40000000],
    180: [0xFFFF0000, 0x00000000, 0x00000000,
          0x00000000, 0xFFFF0000, 0x00000000,
          0x00000000, 0x00000000, 0x40000000],
    270: [0x00000000, 0xFFFF0000, 0x00000000,
          0x00010000, 0x00000000, 0x00000000,
          0x00000000, 0x00000000, 0x40000000],
}

path = sys.argv[1]
angle = int(sys.argv[2]) if len(sys.argv) > 2 else 90

with open(path, "rb") as f:
    data = bytearray(f.read())

# 定位第一个 tkhd（本素材只有视频轨）
idx = data.find(b"tkhd")
if idx < 0:
    sys.exit("tkhd not found")
box_start = idx - 4                      # size 字段起点
version = data[box_start + 8]            # fullbox version（紧跟在 type 后）
# 矩阵紧跟 body 头部字段之后：v0 头部 36 字节，v1 时间戳翻倍后 48 字节
matrix_off = box_start + 12 + (48 if version else 36)

data[matrix_off:matrix_off + 36] = struct.pack(">9I", *MATRICES[angle])
with open(path, "wb") as f:
    f.write(data)
print(f"patched {path} -> rotate {angle}")
