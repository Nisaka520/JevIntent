# -*- coding: utf-8 -*-
"""lex_audit.py —— 找出 main.java 里"中文 + 反斜杠转义"混在同一个字面量里的写法
（stock bsh 2.0b6 / 魔改 bsh 都可能因此在词法阶段崩掉，表现为调用该方法时不可捕获地静默死掉）"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 默认扫 ../main.java，也可以 python lex_audit.py <main.java 路径>
HERE = os.path.dirname(os.path.abspath(__file__))
P = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "main.java")
src = io.open(P, encoding="utf-8").read()
lines = src.split("\n")

# 简单扫描：一行里找 "..." 字面量（不处理多行），检查是否同时含非 ASCII 与反斜杠
lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
bad = []
for i, ln in enumerate(lines, 1):
    stripped = ln.split("//")[0]          # 去掉行尾注释
    for m in lit.finditer(stripped):
        text = m.group(1)
        has_cjk = any(ord(c) > 127 for c in text)
        has_esc = "\\" in text
        if has_cjk and has_esc:
            bad.append((i, text, ln.strip()))

print(f"{os.path.basename(P)} 共 {len(lines)} 行")
print(f"中文 + 反斜杠转义 混写的字面量：{len(bad)} 处")
for i, text, whole in bad:
    print(f"  L{i}: \"{text}\"")
    print(f"        {whole[:100]}")
if not bad:
    print("  （干净 ✓）")
