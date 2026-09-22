# -*- coding: utf-8 -*-
"""package_release.py —— 打 Release 安装包（zip）

用法：python tools/package_release.py
产物：dist/JevIntent-v<版本>.zip  ← 拖到 GitHub Release 的 assets 里

包含：插件本体 + 配置模板 + README + LICENSE + docs/（含"如何获取 API Key"指南）
就是"解压后拷到手机插件目录就能用"的那几个文件；不含任何密钥、不含测试目录
"""
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
DIST = os.path.join(ROOT, "dist")

FILES = ["main.java", "info.prop", "config.example.properties", "README.md", "LICENSE",
         "docs/如何获取API密钥.md", "docs/详细文档.md"]

# ── 1) 先跑密钥自检，不干净就不打包 ──
chk = subprocess.run([sys.executable, os.path.join(HERE, "..", "test", "check_secrets.py")],
                     capture_output=True, text=True, encoding="utf-8", errors="replace")
print(chk.stdout.strip() or chk.stderr.strip())
if chk.returncode != 0:
    print("\n❌ 自检没过，已中止打包")
    sys.exit(1)

# ── 2) 版本号从 info.prop 读 ──
info = io.open(os.path.join(ROOT, "info.prop"), encoding="utf-8").read()
m = re.search(r"^version=(.+)$", info, re.M)
ver = m.group(1).strip() if m else "0.0.0"
name = f"JevIntent-v{ver}"
print(f"\n版本：{ver}")

# ── 3) 打包 ──
os.makedirs(DIST, exist_ok=True)
out = os.path.join(DIST, name + ".zip")
staging = os.path.join(DIST, "_staging", name)
if os.path.isdir(os.path.join(DIST, "_staging")):
    shutil.rmtree(os.path.join(DIST, "_staging"))
os.makedirs(staging)
for f in FILES:
    src = os.path.join(ROOT, f)
    if not os.path.exists(src):
        print(f"  ⚠ 缺少 {f}，跳过")
        continue
    dst = os.path.join(staging, f)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    print(f"  + {f}")

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for dirpath, _dirs, filenames in os.walk(staging):          # 递归，docs/ 也要进包
        for fn in sorted(filenames):
            full = os.path.join(dirpath, fn)
            z.write(full, f"{name}/{os.path.relpath(full, staging).replace(os.sep, '/')}")
shutil.rmtree(os.path.join(DIST, "_staging"))

# ── 4) 回读校验：内容 + 不含密钥 ──
# 特征串同样用拼接写，免得本文件被 check_secrets 命中
PAT = re.compile("apikey" + r"_[0-9a-fA-F]{8}|outlook" + r"\.com|tammy" + "marquez|wxid" + r"_[a-z0-9]{10,}")
bad = 0
with zipfile.ZipFile(out) as z:
    print("\nzip 内容：")
    for i in z.infolist():
        zz = z.read(i.filename).decode("utf-8", errors="replace")
        hits = PAT.findall(zz)
        flag = "  ⚠ 含敏感串！" if hits else ""
        print(f"  {i.filename:<44} {i.file_size:>7,}B{flag}")
        bad += len(hits)

size = os.path.getsize(out)
sha = hashlib.sha256(open(out, "rb").read()).hexdigest()
print(f"\n产物：{out}")
print(f"大小：{size:,} B（{size / 1024:.1f} KB）")
print(f"SHA256：{sha}")
print("✅ 可以拖到 GitHub Release 的 assets 里" if bad == 0 else f"❌ 发现 {bad} 处敏感串，别上传！")
sys.exit(0 if bad == 0 else 1)
