# -*- coding: utf-8 -*-
"""check_secrets.py —— 提交前自检：仓库里不允许出现密钥/账号痕迹
用法：python check_secrets.py        # 扫仓库根目录（本文件的上一级）
退出码 0 = 干净，1 = 有命中
"""
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))

# 命中任何一条就算失败
# 特征串用拼接写，免得本文件自己被自己命中（改的时候保持这个写法）
PATTERNS = [
    ("apikey" + r"_[0-9a-fA-F]{8}", "疑似真实 API key"),
    ("outlook" + r"\.com|gmail\.com|qq\.com", "邮箱账号（可能泄露身份/账号）"),
    ("tammy" + "marquez|2831" + "dd", "本机专用账号标识"),
    ("wxid" + r"_[a-z0-9]{10,}", "真实 wxid"),
]

# 这些文件本来就不该进仓库（哪怕被 .gitignore 挡住，也别留在发布目录里）
FORBIDDEN = ["config.properties", "设置.properties", "presets.properties"]

bad = 0
for dirpath, dirnames, filenames in os.walk(ROOT):
    if ".git" in dirpath:
        continue
    for fn in filenames:
        if fn.endswith((".class", ".jar", ".log")):
            continue
        if fn == os.path.basename(os.path.abspath(__file__)):
            continue                      # 跳过自己（本文件里就写着这些特征串）
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, ROOT)
        if fn in FORBIDDEN:
            print(f"  ✗ {rel} —— 不该出现在发布目录（含真实配置，删掉）")
            bad += 1
            continue
        try:
            s = open(p, encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        for pat, why in PATTERNS:
            for m in re.finditer(pat, s):
                line = s[:m.start()].count("\n") + 1
                print(f"  ✗ {rel}:{line}  {why} → {m.group(0)[:24]}")
                bad += 1

print(f"\n{'✅ 干净：没有密钥/账号痕迹' if bad == 0 else f'❌ 共 {bad} 处问题，修掉再提交'}")
sys.exit(0 if bad == 0 else 1)
