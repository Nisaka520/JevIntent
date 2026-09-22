# 本地测试（不用手机）

插件是 **bsh（BeanShell）脚本**，所以可以直接在 PC 上加载跑通整条流程 —— 改代码不必反复装机。

## 准备

1. **JDK**（能跑 `java` / `javac` 即可）
2. **bsh 2.0b6**：下载 `bsh-2.0b6.jar`（BeanShell 2.0b6）放进本目录
3. 编译两个测试用假消息类：

   ```bash
   javac FakeMsg.java FakeMsgQuote.java
   ```

4. `config.properties`（**没有也能跑**，只是最后那步真调 API 会失败）：把它放在
   `../main.java` 同级目录，或在 `tmpcfg/config.properties` 里放一份测试专用的。

## 跑

**先 cd 进本目录**（脚本里的路径是相对的：`./` = 本目录，`../` = 插件目录）：

```bash
cd test
# 1) 生成可在 stock bsh 里执行的副本（只做 lambda 替换，插件逻辑一行不改）
python mktest.py

# 2) 静态检查：有没有「中文 + 反斜杠转义」混写的字面量（★ 这个坑见下）
python lex_audit.py

# 3) 提交前自检：仓库里不允许出现密钥/账号（会检查是否误留 config.properties）
python check_secrets.py

# 3) 设置文件 + 菜单 + 关系循环（离线；真调 API 的那步由 RUN_LIVE 控制）
java -cp "bsh-2.0b6.jar;." bsh.Interpreter test_settings.bsh

# 4) 引用消息 / 菜单回归
java -cp "bsh-2.0b6.jar;." bsh.Interpreter test_quote.bsh
```

Windows 上中文输出要加编码参数（否则日志是乱码）：

```powershell
[Console]::OutputEncoding=[Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## 文件说明

| 文件 | 作用 |
|---|---|
| `mktest.py` | `../main.java` → `main.test.bsh`：把菜单 lambda 换成 `null`（stock bsh 不支持 lambda）。**它输出的「混合字面量拆分 N 处」必须为 0** |
| `lex_audit.py` | 扫 `../main.java` 里"中文 + 反斜杠转义"混写的字面量，**目标 0 处** |
| `test_settings.bsh` | 设置文件生成 / A-B-C 生效 / 写错字母兜底 / 菜单项 / 关系循环 |
| `test_quote.bsh` | 引用消息能长按、图片不给菜单等回归 |
| `FakeMsg.java` | 假消息对象（普通文本） |
| `FakeMsgQuote.java` | 假消息对象（带引用块） |

## ★ 为什么要有 lex_audit.py

**bsh 词法器对「非 ASCII 字符 + 反斜杠转义」混写在同一个字符串字面量里会直接崩**，
而且**不可捕获**（`catch` 不到、日志也不写），表现和"宿主没这个 API"一模一样。

```java
p = p + "⚙ 面板\n";          // ✗ 中文 + \n 混写 → 方法静默死掉
p = p + "⚙ 面板" + "\n";     // ✓ 拆开就没事
```

更坑的是：`mktest.py` 为了在 stock bsh 上跑，会**自动把这类字面量拆开**，
等于把"只有真机才会犯的错"掩盖掉。所以：

- 写代码时：**字面量要么纯中文、要么纯 ASCII + 转义**（混写就 `+` 拼接）
- 提交前：`lex_audit.py` 必须报 **0 处**
- `mktest.py` 输出的「混合字面量拆分 N 处」也必须为 **0** —— 不是 0 就说明本地测的不是真机上那份文件
