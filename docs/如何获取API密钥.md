# 如何获取自己的 TypeSafe / Jev API Key

> 本插件**不内置任何密钥**，必须自己申请一把。流程免费、大约 1 分钟。
> 下面是 2026-09 实地访问官网核实过的步骤（比网上流传的"要等 waitlist 审批"简单）。

## 一、认准这几个官方地址

| 用途 | 地址 |
|---|---|
| 官网 | https://typesafe.ai |
| **控制台（登录 / 建 key）** | **https://console.typesafe.ai** |
| API Key 页（登录后） | https://console.typesafe.ai/api-keys |
| 官方文档 | https://docs.typesafe.ai |
| API 端点（插件里已写死） | `https://api.typesafe.ai/v1/systemone` |

> 官方文档 Quick start 原话：**"Get your API key from the dashboard"**，然后 `POST https://api.typesafe.ai/v1/systemone`。

## 二、注册 / 登录（二合一，不用密码）

1. 打开 **https://console.typesafe.ai/**（会自动跳到 `/login`）
2. 页面显示 **"Welcome to TypeSafe"**，两种方式任选：

   - **Continue with Google** —— 用 Google 账号点一下、授权即可
   - **邮箱**：填邮箱 → `Continue` → 收邮件验证码（或点 `Email me a code instead`）→ 填码

3. **第一次登录就等于注册**（自动建账号）—— 不需要单独"申请"、也没有等待审批这一步。

## 三、创建 API Key

1. 登录后打开 **https://console.typesafe.ai/api-keys**（或从 Dashboard 左侧菜单进 API keys）
2. 点 **Create API key / New key** 一类的按钮，给它起个名字（例如 `JevIntent`）
3. **复制生成的 `apikey_…`** —— 这串很长（约 100 个字符），通常**只完整显示一次**，先存到备忘录/密码管理器
4. 万一泄露：回这个页面 **Revoke / 删除** 它，再建一把新的（旧的立刻失效）

## 四、填进插件（两种方式任选）

**方式 A（推荐，不用找配置文件）**：打开插件目录里的 `设置.properties`，在最上面第【1】项后面直接写：

```properties
接口密钥=apikey_你的key
```

保存 → **下次长按消息立即生效**（不用重载插件）。

**方式 B**：`config.properties` 里写 `api_key=apikey_你的key`。
两边都填以 **`设置.properties` 为准**；那边留空会自动回落到 `config.properties`。

## 五、验证是否生效

长按一条消息 → 点「意图」：

| 结果 | 说明 |
|---|---|
| 弹出「意图 / 情绪 / 着急 / 建议」 | ✅ key 正常 |
| Toast：**还没读到 api_key，看插件日志** | key 没读到 —— 检查是不是漏了 `apikey_` 前缀、复制不完整、或写错了项名 |
| Toast：**Jev 失败：…**（带 401/403 之类） | key 无效或被撤销 → 回控制台确认它还在、没被 revoke |
| 一直超时 | 网络/代理问题，不是 key 的问题 |

## 六、注意事项

- **key 是私密的**：别贴进公开仓库、截图或群里。本插件仓库不含任何 key，
  并且 `.gitignore` 已挡住 `config.properties`；仓库里还有 `test/check_secrets.py` 提交前自检。
- **免费额度**：据公开报道 Jev 已全网开放并赠送大额免费 token（报道称 1.2 亿），
  具体额度与计费以控制台显示为准 —— 在控制台能看到用量。
- key 与**模型**无关：插件默认 `model=jev-latest`，也可以在 `设置.properties` 里固定成 `jev-1.13.0`。
