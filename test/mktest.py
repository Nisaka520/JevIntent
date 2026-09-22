# -*- coding: utf-8 -*-
"""mktest.py (v3) — 生成可在 stock bsh 2.0b6 里执行的 main.java 测试副本

stock bsh 2.0b6 的 lexer 限制（手机上是魔改 bsh，没这些毛病）：
  · 不支持菜单 lambda `() -> { ... }`
  · 字符串里同时出现「非 ASCII 字符」和「反斜杠转义」就词法报错（纯 ASCII 没事）

所以测试副本做两件事，插件本身逻辑一行不改：
  1. lambda → null
  2. 混合字面量自动拆成 `"中文" + "\\d+" + "中文"` 这种"每段只含一种字符"的拼接
"""
import io
import re
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 默认：读 ../main.java，写到本目录的 main.test.bsh（.bsh 测试脚本从这里 source）
# 也可以 python mktest.py <main.java> <输出路径>
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "main.java")
DST = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "main.test.bsh")

src = io.open(SRC, encoding="utf-8").read()
report = []


def split_segments(content):
    """把字面量内容切成「连续非 ASCII 段」和「连续 ASCII 段」（转义对算一个单元）"""
    units = []
    k = 0
    while k < len(content):
        if content[k] == "\\" and k + 1 < len(content):
            units.append(content[k:k + 2])
            k += 2
        else:
            units.append(content[k])
            k += 1
    segs, cur, cur_ascii = [], "", None
    for u in units:
        a = all(ord(ch) < 128 for ch in u)
        if cur and a != cur_ascii:
            segs.append(cur)
            cur = ""
        cur += u
        cur_ascii = a
    if cur:
        segs.append(cur)
    return segs


def rewrite_mixed(text):
    out, i, n, cnt = [], 0, len(text), 0
    while i < n:
        if text[i] != '"':
            out.append(text[i])
            i += 1
            continue
        j, raw, closed = i + 1, [], False
        while j < n:
            ch = text[j]
            if ch == "\\" and j + 1 < n:
                raw.append(text[j:j + 2])
                j += 2
                continue
            if ch == '"':
                closed = True
                j += 1
                break
            raw.append(ch)
            j += 1
        if not closed:
            out.append(text[i:])
            break
        content = "".join(raw)
        if any(ord(c) > 127 for c in content) and "\\" in content:
            segs = split_segments(content)
            out.append(" + ".join('"' + s + '"' for s in segs))
            cnt += 1
        else:
            out.append('"' + content + '"')
        i = j
    return "".join(out), cnt


# ── 1. lambda → null ─────────────────────────────────────────
pat = re.compile(r"\(\)\s*->\s*\{[^{}]*\}\)")
lams = pat.findall(src)
src = pat.sub("null)", src)
report.append(f"lambda 替换 {len(lams)} 处")

# ── 2. selfTest 里那条整句 JSON（拆分后太啰嗦，直接换成语义等价调用）──
old_line = ('String state = "{\\"关系\\":\\"普通朋友\\",\\"对方性别\\":\\"未知\\",'
            '\\"待分析消息\\":" + jsonStr(sample) + "}";')
new_line = 'String state = (msgLocal == null) ? "{}" : buildState(msgLocal, sample);'
if old_line in src:
    src = src.replace(old_line, new_line)
    report.append("selfTest 的 JSON 字面量 → buildState()")

# ── 3. 混合字面量自动拆分 ────────────────────────────────────
src, cnt = rewrite_mixed(src)
report.append(f"混合字面量（中文+转义）拆分 {cnt} 处")

io.open(DST, "w", encoding="utf-8").write(src)
print("已生成", DST)
for r in report:
    print("  ·", r)
