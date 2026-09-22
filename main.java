/**
 * JevIntent —— 长按消息 → 菜单「意图」→ 调 TypeSafe Jev 分析 → 结果发回会话
 *
 * 环境：FkWeChat 插件（魔改 BeanShell / Java 脚本）
 * 依赖：只用 JDK 自带类（HttpURLConnection / Properties / Thread），不需要 loadJar
 *
 * 官方菜单示例（doc-8196404）：
 *     onMsgMenu(msg) {
 *         addMenuItem("语音转文字", "test.png", () -> { ... });
 *     }
 *   ⇒ 菜单项在 onMsgMenu 里逐次注册，action 是「无参 lambda」，消息要靠闭包/脚本变量带进去。
 *
 * 流程：
 *   onLoad → 读 config.properties
 *   onMsgMenu(msg) → addMenuItem("意图", "", () -> analyzeAndSend(pendingMsg))
 *   analyzeAndSend → 取正文 → 组 Jev 请求 → 后台线程 POST → 解析 → sendText / 引用回复
 *
 * 注意：Jev 是决策模型，不出自由文本；「建议」是预置选项，由模型挑一个。
 *
 * v1.3 修复（引用消息不能用「意图」）：
 *   微信里「引用回复」的消息 type=49、isText()==false，而老版本
 *   `cfgBool("text_only") && !isTextMsg(msg)` 直接把菜单整个跳过了 → 长按没有「意图」。
 *   现在：① isTextMsg() 对「能抽出文字」的消息也返回 true；② msgText() 会把 appmsg 的
 *   XML 解析成正文（<title>/<des>）；③ 被引用的原文抽出来放进 state 的「本消息引用了」。
 *   抽不出文字的消息（图片/语音/视频/表情）仍然不给菜单，行为不变。
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;

// ─────────────────────────── 配置 ───────────────────────────

CFG = new Properties();

CFG_DEFAULTS = "api_key=;model=jev-latest;endpoint=https://api.typesafe.ai/v1/systemone;" +
               "timeout_ms=30000;reply_mode=toast;toast_gap_ms=2000;" +
               "quote_text_in=title;quote_displayname=false;" +
               "private_mode=false;self_talker=filehelper;whitelist=;show_confidence=true;" +
               "text_only=true;" +
               "emotion_top=3;show_legacy=true;analyzing_toast=true;" +
               
               "context_count=3;context_window_ms=1800000;" +
               "rel_default=普通朋友;sex_default=未知;" +
               "rel_options=普通朋友,陌生人,家人,情侣,死党,同事,客户,领导,同学,网友;" +
               "sex_options=未知,男,女;" +
               "ctx_options=3,1,5,7,0;" +
               // v1.9 速度三件套：预判（长按即开算）/ 结果缓存 / 连接复用
               "prefetch=1;cache_on=1;speed_log=1";

/** 内置兜底 key：**故意留空**（仓库里不放任何密钥）
 *  请把 key 填到同目录的 config.properties：api_key=apikey_xxx
 *  填了就用你的；读不到就提示「未配置 Jev key」。 */
BUILTIN_KEY = "";

/** 最近一次长按的消息（脚本级变量，供 lambda 闭包取用） */
pendingMsg = null;

// ══════════════════ v1.9 速度优化：预热 / 预判 / 缓存 / 计时 ══════════════════
// 实测（本机 java 探针 + 真接口，2026-09）：
//   · 服务端算完 ~250~330ms，是唯一的大头；客户端连接只要 3~15ms（TLS 会话复用已生效）
//   · 所以「省握手」没用，「把等待藏起来」才有用 → 长按菜单弹出时就把这次分析先发出去
//   · 尖刺（1.5~1.8s）来自服务端抖动，只能靠重试/预判消化
/** 预判完成的响应体：消息文本 → 响应 JSON */
Hashtable preDone = new Hashtable();
/** 预判进行中：消息文本 → 开始时间戳 */
Hashtable preBusy = new Hashtable();
/** 预判结果的有效期（毫秒） */
long PRE_TTL_MS = 90000L;
/** 最终输出缓存：state 键 → 结果文本（长按同一条消息反复看时秒回） */
Hashtable resCache = new Hashtable();
/** 缓存淘汰顺序 + 上限 */
ArrayList resCacheOrder = new ArrayList();
int RES_CACHE_MAX = 40;
/** 上一次 HTTP 的分段耗时 */
long httpConnectMs = 0L;
long httpServerMs = 0L;
long httpTotalMs = 0L;
/** 本次结果是不是预判命中的 */
boolean lastFromPrefetch = false;

/** 插件目录（宿主全局字段 pluginPath，兜底写死路径）
 *  注意：bsh 里不要「在 try 内 return」——finally/catch 会把方法返回值吞成 void，下同。 */
String pluginDir() {
    String p = null;
    try { p = pluginPath; } catch (Throwable ignore) { p = null; }
    if (p != null && p.trim().length() > 0) return p.trim();
    return "/storage/emulated/0/Android/media/com.tencent.mm/FkWeChat/Plugin/JevIntent/";
}

cfgFile = "";      // 实际读到的配置文件路径
cfgTried = "";     // 试过哪些路径（诊断）
cfgDirs = "";      // 扫过哪些目录（诊断）

void addCand(List l, String path) {
    if (path == null) return;
    String s = path.trim().replace("//", "/");
    if (s.length() == 0 || l.contains(s)) return;
    boolean has = false;
    try { has = new File(s).exists(); } catch (Throwable ignore) { has = false; }
    cfgTried = cfgTried + (cfgTried.length() > 0 ? " | " : "") + s + (has ? "✓" : "✗");
    if (has) l.add(s);
}

void scanDirs(List l, String base) {
    if (base == null || base.trim().length() == 0) return;
    File d = new File(base.trim());
    boolean isDir = false;
    try { isDir = d.exists() && d.isDirectory(); } catch (Throwable ignore) { isDir = false; }
    if (!isDir) { cfgDirs = cfgDirs + (cfgDirs.length() > 0 ? ", " : "") + base + "(无)"; return; }
    File[] fs = d.listFiles();
    cfgDirs = cfgDirs + (cfgDirs.length() > 0 ? ", " : "") + base + "(" + (fs == null ? "null" : String.valueOf(fs.length)) + "项)";
    if (fs == null) return;
    // 两轮：先挑名字里带 jev 的目录（避免误读别的插件的 config），再兜底扫其余目录
    for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < fs.length; i++) {
            if (!fs[i].isDirectory()) continue;
            String sub = fs[i].getAbsolutePath();
            boolean looksJev = sub.toLowerCase().indexOf("jev") >= 0;
            if (pass == 0 && !looksJev) continue;
            if (pass == 1 && looksJev) continue;
            addCand(l, sub + "/config.properties");
            addCand(l, sub + "/config.properties.txt");
            addCand(l, sub + "/config.txt");
        }
    }
}

/** 依次探测所有可能的配置文件位置 */
List cfgCandidates() {
    List l = new ArrayList();
    String p = pluginDir();
    if (p.length() > 0) {
        String q = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        addCand(l, q + "/config.properties");
        addCand(l, q + "/config.properties.txt");
        addCand(l, q + "/config.txt");
        if (q.endsWith(".java") || q.endsWith(".bsh") || q.endsWith(".dex")) {
            int i = q.lastIndexOf('/');
            if (i > 0) {
                String d = q.substring(0, i + 1);
                addCand(l, d + "config.properties");
                addCand(l, d + "config.properties.txt");
                addCand(l, d + "config.txt");
            }
        }
        int k = q.lastIndexOf('/');
        if (k > 0) scanDirs(l, q.substring(0, k));      // 扫 pluginPath 的父目录（文件夹被改名也能找到）
    }
    String[] bases = {
        "/storage/emulated/0/Android/media/com.tencent.mm/FkWeChat/Plugin/JevIntent",
        "/sdcard/Android/media/com.tencent.mm/FkWeChat/Plugin/JevIntent",
        "/storage/emulated/0/Android/data/com.tencent.mm/FkWeChat/Plugin/JevIntent"
    };
    for (int i = 0; i < bases.length; i++) {
        addCand(l, bases[i] + "/config.properties");
        addCand(l, bases[i] + "/config.properties.txt");
        addCand(l, bases[i] + "/config.txt");
    }
    scanDirs(l, "/storage/emulated/0/Android/media/com.tencent.mm/FkWeChat/Plugin");
    scanDirs(l, "/sdcard/Android/media/com.tencent.mm/FkWeChat/Plugin");
    return l;
}

String readTextFile(File f) {
    byte[] buf = new byte[(int) f.length()];
    FileInputStream in = new FileInputStream(f);
    int off = 0;
    int n = in.read(buf, 0, buf.length - off);
    while (n > 0 && off + n < buf.length) {
        off += n;
        n = in.read(buf, off, buf.length - off);
    }
    if (n > 0) off += n;
    in.close();
    String s = new String(buf, 0, off, "UTF-8");
    if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);   // 去 BOM
    return s;
}

/** 自己解析 key=value：不依赖 Properties.load（它按 ISO-8859-1 读，且受不了 BOM） */
void parseCfg(String text) {
    parseInto(CFG, text);
}

void parseInto(Properties target, String text) {
    String[] lines = text.split("\n");
    for (int i = 0; i < lines.length; i++) {
        String ln = lines[i].replace("\r", "").trim();
        if (ln.length() == 0) continue;
        if (ln.startsWith("#") || ln.startsWith(";") || ln.startsWith("//")) continue;
        int eq = ln.indexOf('=');
        if (eq <= 0) continue;
        String k = ln.substring(0, eq).replace("\uFEFF", "").trim();
        String v = ln.substring(eq + 1).trim();
        if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        target.setProperty(k, v);
    }
}

void loadConfig() {
    CFG.clear();
    String[] pairs = CFG_DEFAULTS.split(";");
    for (int i = 0; i < pairs.length; i++) {
        int eq = pairs[i].indexOf("=");
        if (eq > 0) CFG.setProperty(pairs[i].substring(0, eq), pairs[i].substring(eq + 1));
    }
    cfgFile = "";
    cfgTried = "";
    cfgDirs = "";
    List cands = cfgCandidates();
    for (int i = 0; i < cands.size(); i++) {
        String path = String.valueOf(cands.get(i));
        try {
            parseCfg(readTextFile(new File(path)));
            cfgFile = path;
            i = cands.size();                    // 找到就停（不用 return，避开 bsh 的坑）
        } catch (Throwable t) {
            log("JevIntent: 读配置失败 " + path + " -> " + t);
        }
    }
    if (cfgFile.length() == 0) {
        log("JevIntent ！！没找到 config.properties。试过：" + briefLong(cfgTried));
        log("JevIntent 扫过的目录：" + briefLong(cfgDirs));
    } else {
        log("JevIntent 配置文件 = " + cfgFile);
    }
    // ★ 设置文件无论有没有 config.properties 都要走一遍（否则没配置文件时永远不生成 设置.properties）
    //   顺序必须在读 config 之后，这样里面的 A/B/C 才能覆盖 config 里的默认值
    ensureSettingFile();       // 没有就生成一份带注释的 设置.properties
    loadSettingFile();         // 再用里面的 A/B/C 覆盖配置
    // 兜底：配置里没读到 key 才用内置 key（仓库版内置 key 为空，等于没有兜底）
    if (cfg("api_key").length() < 20) {
        CFG.setProperty("api_key", BUILTIN_KEY);
        if (BUILTIN_KEY.length() > 20) log("JevIntent: 配置里没有可用 api_key → 用内置 key");
        else log("JevIntent: 还没配置 api_key → 请在 config.properties 里填 api_key=apikey_xxx");
    }
}

/** 重载配置并报告结果（配置改完即时生效，菜单每次长按也会自动重读） */
reloadAndReport() {
    loadConfig();
    loadPresets();
    String k = cfg("api_key");
    if (k.length() > 20) {
        toast("配置已重载：key=" + k.substring(0, 14) + "…");
        log("JevIntent 重载成功 model=" + cfg("model") + " reply_mode=" + cfg("reply_mode") + " file=" + cfgFile);
    } else {
        toast("仍然没读到 key，看日志");
    }
}

// ─────────────────── 会话预设（关系 / 性别 / 上下文句数）───────────────────
// 存文件而不是宿主 KV：宿主 KV 函数万一没实现，bsh 会抛「不可捕获」错误直接打死执行。
// 文件位置优先跟 config.properties 同目录。

PRESETS = new Properties();
presetFile = "";

String presetFilePath() {
    if (cfgFile != null && cfgFile.length() > 0) {
        int i = cfgFile.lastIndexOf('/');
        if (i > 0) return cfgFile.substring(0, i + 1) + "presets.properties";
    }
    String p = pluginDir();
    if (!p.endsWith("/")) p = p + "/";
    return p + "presets.properties";
}

void loadPresets() {
    PRESETS.clear();
    presetFile = presetFilePath();
    try {
        File f = new File(presetFile);
        if (f.exists()) parseInto(PRESETS, readTextFile(f));
    } catch (Throwable t) {
        log("JevIntent: 读预设失败 " + brief(t));
    }
    // 清理 v1.5 操作台时代留下的键（g:xxx / console_target），现在设置都走 设置.properties
    try {
        List ks = new ArrayList(PRESETS.stringPropertyNames());
        boolean dirty = false;
        for (int i = 0; i < ks.size(); i++) {
            String k = String.valueOf(ks.get(i));
            if (k.startsWith("g:") || k.startsWith("console_")) {
                PRESETS.remove(k);
                dirty = true;
            }
        }
        if (dirty) savePresets();
    } catch (Throwable ignore2) { }
}

void savePresets() {
    StringBuilder sb = new StringBuilder();
    sb.append("# JevIntent 会话预设：rel:<会话ID>=关系｜sex:<会话ID>=性别｜ctx=上下文句数" + "\n");
    List keys = new ArrayList(PRESETS.stringPropertyNames());
    try { java.util.Collections.sort(keys); } catch (Throwable ignore) { }
    for (int i = 0; i < keys.size(); i++) {
        String k = String.valueOf(keys.get(i));
        sb.append(k).append("=").append(PRESETS.getProperty(k)).append("\n");
    }
    try {
        writeTextFile(presetFile, sb.toString());
    } catch (Throwable t) {
        log("JevIntent: 存预设失败 " + brief(t));
        toast("预设保存失败（看日志）");
    }
}

void writeTextFile(String path, String text) {
    FileOutputStream out = new FileOutputStream(path);
    out.write(text.getBytes("UTF-8"));
    out.flush();
    out.close();
}

/** 读会话预设：rel/sex 按会话存，ctx 全局 */
String presetOf(String field, String talker, String def) {
    String v = null;
    try { v = PRESETS.getProperty(field + ":" + talker); } catch (Throwable ignore) { v = null; }
    if (v == null || v.trim().length() == 0) v = def;
    return v == null ? "" : v.trim();
}

void presetPut(String field, String talker, String val) {
    PRESETS.setProperty(field + ":" + talker, val);
    savePresets();
}

/** 当前生效的上下文句数（预设优先，其次配置，默认 3） */
int ctxCount() {
    String v = null;
    try { v = PRESETS.getProperty("ctx"); } catch (Throwable ignore) { v = null; }
    if (v == null || v.trim().length() == 0) v = cfg("context_count");
    return parseInt(v, 3);
}

List optionList(String csv) {
    List l = new ArrayList();
    if (csv == null) return l;
    String[] parts = csv.split(",");
    for (int i = 0; i < parts.length; i++) {
        String s = parts[i].trim();
        if (s.length() > 0) l.add(s);
    }
    return l;
}

/** 轮换到下一个选项（菜单点一下切一格，没有弹窗 API 时这是最接近"设置面板"的做法） */
String nextOption(String csv, String cur) {
    List l = optionList(csv);
    if (l.size() == 0) return cur;
    for (int i = 0; i < l.size(); i++) {
        if (String.valueOf(l.get(i)).equals(cur)) return String.valueOf(l.get((i + 1) % l.size()));
    }
    return String.valueOf(l.get(0));
}

cycleRel(msg) {
    if (msg == null) { toast("先长按一条消息"); return; }
    String next = nextOption(cfg("rel_options"), presetOf("rel", msg.talker, cfg("rel_default")));
    presetPut("rel", msg.talker, next);
    toast("本会话关系 → " + next);
    log("JevIntent 预设：rel:" + msg.talker + "=" + next);
}



// ─────────── 设置.properties：除"每个会话的关系"外，所有设置都用 ABC 选项改 ───────────
// 宿主没有弹窗/输入框 API（文档里全部接口只有 toast），所以设置面板做成一个文件：
//   插件目录下的 设置.properties —— 改等号后面的字母 → 保存 → 长按消息立即生效
//   本文件优先级高于 config.properties，但只覆盖它认识的键；文件永不自动覆盖
// 关系是"每个会话"的，放不进全局文件 → 仍在长按菜单里点「关系＝…」切换

settingFile = "";
keyFromFile = false;      // key 是否来自 设置.properties（用于生成时的提示）

String settingFilePath() {
    if (cfgFile != null && cfgFile.length() > 0) {
        int i = cfgFile.lastIndexOf('/');
        if (i > 0) return cfgFile.substring(0, i + 1) + "设置.properties";
    }
    String p = pluginDir();
    if (!p.endsWith("/")) p = p + "/";
    return p + "设置.properties";
}

/** 每个设置的可选项（顺序就是 A/B/C…），内部键 → "值A,值B,值C" */
String optValues(String k) {
    if (k.equals("emotion_top")) return "1,3,5";
    if (k.equals("show_legacy")) return "true,false";
    if (k.equals("reply_mode")) return "toast,quote,text";
    if (k.equals("toast_gap_ms")) return "2000,1200,3000";
    if (k.equals("context_count")) return "0,3,5,7";
    if (k.equals("analyzing_toast")) return "true,false";
    if (k.equals("text_only")) return "true,false";
    if (k.equals("quote_text_in")) return "title,des";
    if (k.equals("model")) return "jev-latest,jev-1.13.0";
    if (k.equals("prefetch")) return "1,0";
    if (k.equals("cache_on")) return "1,0";
    if (k.equals("speed_log")) return "1,0";
    if (k.equals("rel_default")) return "普通朋友,同事,客户,家人,死党,陌生人,领导,同学";
    if (k.equals("sex_default")) return "未知,男,女";
    return "";
}

/** 选项的人话说明（写进文件注释），顺序与 optValues 一一对应 */
String optLabels(String k) {
    if (k.equals("emotion_top")) return "只显示 1 条,显示 3 条,显示 5 条";
    if (k.equals("show_legacy")) return "显示这四项,不显示";
    if (k.equals("reply_mode")) return "只弹提示（对方看不到）,引用回复（挂原消息下）,直接发消息";
    if (k.equals("toast_gap_ms")) return "2000 毫秒,1200 毫秒（更快）,3000 毫秒（更慢）";
    if (k.equals("context_count")) return "不带上下文,3 句,5 句,7 句";
    if (k.equals("analyzing_toast")) return "显示「分析中…」,不显示";
    if (k.equals("text_only")) return "只给文本消息挂菜单,所有消息都挂";
    if (k.equals("quote_text_in")) return "title（默认）,des（气泡空白时改这个）";
    if (k.equals("model")) return "jev-latest（跟最新）,固定 jev-1.13.0";
    if (k.equals("prefetch")) return "预判开（长按就开始算，点「意图」秒出；多花一次调用）,预判关";
    if (k.equals("cache_on")) return "缓存开（同一条消息再看秒回）,缓存关";
    if (k.equals("speed_log")) return "日志带耗时,不打耗时";
    if (k.equals("rel_default")) return "普通朋友,同事,客户,家人,死党,陌生人,领导,同学";
    if (k.equals("sex_default")) return "未知,男,女";
    return "";
}

/** 文件里的中文键 → 内部配置键（也认直接写内部键） */
String optKeyOf(String fileKey) {
    String f = fileKey == null ? "" : fileKey.trim();
    if (f.equals("接口密钥") || f.equals("密钥") || f.equals("api_key")) return "api_key";
    if (f.equals("情绪条数")) return "emotion_top";
    if (f.equals("附带老四项")) return "show_legacy";
    if (f.equals("结果方式")) return "reply_mode";
    if (f.equals("提示间隔")) return "toast_gap_ms";
    if (f.equals("上下文句数")) return "context_count";
    if (f.equals("分析中提示")) return "analyzing_toast";
    if (f.equals("只看文本消息")) return "text_only";
    if (f.equals("引用块字段")) return "quote_text_in";
    if (f.equals("模型")) return "model";
    if (f.equals("预判")) return "prefetch";
    if (f.equals("结果缓存")) return "cache_on";
    if (f.equals("耗时日志")) return "speed_log";
    if (f.equals("默认关系")) return "rel_default";
    if (f.equals("默认性别")) return "sex_default";
    return f;
}

/** A/B/C → 真实值；认不出就取第一个（稳妥兜底） */
String pickLetter(String csv, String letter) {
    List l = optionList(csv);
    if (l.size() == 0) return "";
    String s = letter == null ? "" : letter.trim().toUpperCase();
    int idx = s.length() > 0 ? "ABCDEFGH".indexOf(s.charAt(0)) : -1;
    if (idx < 0 || idx >= l.size()) idx = 0;
    return String.valueOf(l.get(idx));
}

/** 生成 设置.properties（已存在就永不覆盖） */
void ensureSettingFile() {
    settingFile = settingFilePath();
    try { if (new File(settingFile).exists()) return; } catch (Throwable ignore) { }
    try {                                  // 父目录不存在就先建（插件目录/测试沙盒都可能还没有）
        File dir = new File(settingFile).getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
    } catch (Throwable ignore) { }
    String[] keys = {"情绪条数", "附带老四项", "结果方式", "提示间隔", "上下文句数", "分析中提示", "只看文本消息", "引用块字段", "模型", "预判", "结果缓存", "耗时日志", "默认关系", "默认性别"};
    StringBuilder sb = new StringBuilder();
    sb.append("# ═══ JevIntent 设置 ═══ 改等号后面的字母，保存后长按消息立即生效（不用重载插件）").append("\n");
    sb.append("# 关系不在这里：每个会话的关系在长按菜单里点「关系＝…」切换，记在 presets.properties").append("\n");
    sb.append("# 这个文件永远不会被自动覆盖，随便改；删掉它下次长按会重新生成一份").append("\n");
    sb.append("\n");
    // 【1】接口密钥：不是 A/B/C 选项，直接把 apikey_xxx 写在等号后面（留空则用 config.properties）
    sb.append("# 【1】接口密钥 —— 把 apikey_xxx 整串直接写在等号后面（不是选 A/B/C）").append("\n");
    sb.append("#     留空 = 用 config.properties 里的 api_key；两边都填以这里为准").append("\n");
    String keyNow = cfg("api_key");
    sb.append("#     当前生效：").append(keyNow.length() > 20 ? "已配置（来自 " + (keyFromFile ? "本文件" : "config.properties") + "）" : "未配置 ⚠").append("\n");
    sb.append("接口密钥=").append("\n\n");
    for (int i = 0; i < keys.length; i++) {
        String ik = optKeyOf(keys[i]);
        List vs = optionList(optValues(ik));
        List ls = optionList(optLabels(ik));
        String cur = cfg(ik);
        int curIdx = -1;
        sb.append("# 【").append(i + 2).append("】").append(keys[i]).append("\n");   // 从【2】开始（【1】是密钥）
        for (int j = 0; j < vs.size(); j++) {
            String letter = String.valueOf((char) ('A' + j));
            String label = j < ls.size() ? String.valueOf(ls.get(j)) : String.valueOf(vs.get(j));
            sb.append("#     ").append(letter).append(". ").append(label).append("\n");
            if (String.valueOf(vs.get(j)).equals(cur)) curIdx = j;
        }
        char def = 'A';
        if (curIdx >= 0) def = (char) ('A' + curIdx);
        sb.append(keys[i]).append("=").append(def).append("\n\n");
    }
    try {
        writeTextFile(settingFile, sb.toString());
        log("JevIntent 已生成设置文件：" + settingFile);
    } catch (Throwable t) {
        log("JevIntent 生成设置文件失败：" + t);
    }
}

/** 读 设置.properties，把字母翻成真实值写回 CFG（优先级高于 config.properties） */
void loadSettingFile() {
    settingFile = settingFilePath();
    keyFromFile = false;
    try {
        File f = new File(settingFile);
        if (!f.exists()) return;
        Properties o = new Properties();
        parseInto(o, readTextFile(f));
        List names = new ArrayList(o.stringPropertyNames());
        int n = 0;
        for (int i = 0; i < names.size(); i++) {
            String fk = String.valueOf(names.get(i));
            String ik = optKeyOf(fk);
            String raw = String.valueOf(o.getProperty(fk)).trim();
            if (ik.equals("api_key")) {                      // 密钥：直接写值，不走字母表
                if (raw.length() >= 20) {
                    CFG.setProperty("api_key", raw);
                    keyFromFile = true;
                    n++;
                } else if (raw.length() > 0) {
                    log("JevIntent: 设置文件里的密钥太短，已忽略（要 apikey_ 开头的完整串）");
                }
            } else {
                String csv = optValues(ik);
                if (csv.length() > 0) {                      // 不认识的键直接忽略
                    String val = pickLetter(csv, raw);
                    if (val.length() > 0) {
                        CFG.setProperty(ik, val);
                        n++;
                    }
                }
            }
        }
        log("JevIntent 设置文件已生效：" + settingFile + "（" + n + " 项）"
            + (keyFromFile ? " key 来自设置文件" : ""));
    } catch (Throwable t) {
        log("JevIntent 读设置文件失败：" + t);
    }
}

/** 预设摘要，用于结果抬头 */
String presetLabel(String talker) {
    return presetOf("rel", talker, cfg("rel_default")) + "·" +
           presetOf("sex", talker, cfg("sex_default")) + "·" + ctxCount() + "句";
}

String cfg(String k) {
    String v = CFG.getProperty(k);
    return v == null ? "" : v.trim();
}

boolean cfgBool(String k) {
    return "true".equalsIgnoreCase(cfg(k)) || "1".equals(cfg(k));
}

// ─────────────────────────── 生命周期 ───────────────────────────

onLoad() {
    loadConfig();
    loadPresets();
    log("JevIntent 已加载：model=" + cfg("model") + " reply_mode=" + cfg("reply_mode") +
        " key=" + (cfg("api_key").length() > 16 ? cfg("api_key").substring(0, 16) + "…" : "(未配置)"));
    // 自查行：确认手机上跑的是哪一版、设置文件读到没有
    log("JevIntent v1.9 设置文件 = " + settingFile + " ｜ 情绪条数=" + cfg("emotion_top")
        + " 上下文=" + ctxCount() + "句 老四项=" + cfg("show_legacy")
        + " 间隔=" + cfg("toast_gap_ms") + "ms 结果方式=" + cfg("reply_mode"));
    toast("JevIntent 就绪 v1.9");
}

onUnload() {
    log("JevIntent 已卸载");
}

// ─────────────────────────── 长按菜单 ───────────────────────────

/** 长按消息弹出菜单前触发：注册菜单项（v1.5 起设置集成到「⚙ 设置」操作台，不再一项切一格） */
onMsgMenu(msg) {
    try {
        loadConfig();          // 每次长按都重读配置：改完 config.properties 立刻生效
        loadPresets();
        if (msg == null) {
            log("JevIntent: onMsgMenu 没拿到 msg");
        } else if (cfgBool("text_only") && !isTextMsg(msg)) {
            // 非文本消息不挂菜单
        } else if (!allowed(msg.talker)) {
            // 不在白名单
        } else {
            pendingMsg = msg;
            String rel = presetOf("rel", msg.talker, cfg("rel_default"));
            String sex = presetOf("sex", msg.talker, cfg("sex_default"));
            // 菜单标题保持纯中文短标题：宿主菜单对 emoji/全角括号/长标题的容忍度未知，
            // 出现过"⚙ 设置（…）"整项点不出来的情况 → 一律用最朴素的两个字
            addMenuItem("意图", "", () -> { analyzeAndSend(pendingMsg); });
            addMenuItem("关系＝" + rel, "", () -> { cycleRel(pendingMsg); });
            log("JevIntent 菜单已注册：意图 / 关系＝" + rel);
            // v1.9：菜单弹出的这一刻就把分析发出去 —— 等你点「意图」时结果基本已经回来了
            prefetchStart(pendingMsg, msgText(pendingMsg));
        }
    } catch (Throwable t) {
        log("JevIntent.onMsgMenu 出错：" + t);
    }
}

boolean isTextMsg(msg) {
    boolean ok = true;
    try { ok = msg.isText(); } catch (Throwable t) { ok = true; }
    if (ok) return true;
    // v1.3：引用回复（appmsg/type49）等 isText()==false，但 XML 里能抽出正文 → 照样挂菜单
    return msgText(msg).length() > 0;
}

boolean allowed(String talker) {
    String wl = cfg("whitelist");
    if (wl.length() == 0) return true;
    String[] parts = wl.split(",");
    for (int i = 0; i < parts.length; i++) {
        if (parts[i].trim().equals(talker)) return true;
    }
    return false;
}

// ─────────────────────────── 分析主流程 ───────────────────────────

// ─────────────────────────── v1.9 预判 / 缓存 ───────────────────────────

/**
 * 预判：用户长按弹出菜单的**那一刻**就把这次分析发出去。
 *
 * 为什么要这么干：实测服务端要 250~330ms，而人从长按到点中「意图」通常要 0.5~1.5 秒 ——
 * 这段"选菜单"的时间足够把请求跑完。等真的点了「意图」，结果已经在手里，直接出。
 * 常见路径（点「意图」）总 API 调用次数不变；只有点了「关系＝…」才会白跑一次，可用设置关掉。
 */
void prefetchStart(msg, String text) {
    try {
        if (!cfgBool("prefetch")) return;
        if (text == null || text.length() == 0) return;
        if (cfg("api_key").length() < 20) return;
        Object old = preDone.get(text);
        if (old != null) return;                        // 已经预判过同一条，别重复花钱
        if (preBusy.get(text) != null) return;          // 正在跑
        preBusy.put(text, String.valueOf(System.currentTimeMillis()));
        final Object msgLocal = msg;
        final String textLocal = text;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String state = buildState(msgLocal, textLocal);
                    String req = buildRequest(state);
                    int timeout = parseInt(cfg("timeout_ms"), 30000);
                    long t0 = System.currentTimeMillis();
                    String body = httpPost(cfg("endpoint"), cfg("api_key"), req, timeout);
                    long cost = System.currentTimeMillis() - t0;
                    preDone.put(textLocal, body);
                    if (fieldAfter(body, "emotion", "choice").length() == 0) {
                        preDone.remove(textLocal);          // 空答案不算数，留给正式流程重试
                    }
                    log("JevIntent 预判完成 " + cost + "ms（握手 " + httpConnectMs
                        + "ms + 服务端 " + httpServerMs + "ms）");
                } catch (Throwable t) {
                    log("JevIntent 预判失败（不影响正常流程）：" + brief(t));
                } finally {
                    preBusy.remove(textLocal);
                }
            }
        }).start();
    } catch (Throwable t) {
        log("JevIntent.prefetchStart 出错：" + t);
    }
}

/**
 * 取预判结果：拿不到就返回 null（调用方走正常请求）。
 * 如果预判还在跑，最多等 waitMs —— 等它比自己再发一次请求更省。
 */
String prefetchTake(String text, int waitMs) {
    if (text == null || text.length() == 0) return null;
    String got = null;
    try {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < waitMs) {
            Object v = preDone.get(text);
            if (v != null) {
                got = String.valueOf(v);
                preDone.remove(text);
                break;
            }
            if (preBusy.get(text) == null) break;        // 没在跑也没有结果 → 别等了
            try { Thread.sleep(40); } catch (Throwable ignore) { }
        }
    } catch (Throwable t) {
        log("JevIntent.prefetchTake 出错：" + t);
    }
    return got;
}

/** 结果缓存：state 一模一样就直接复用（长按同一条消息反复看时零延迟、零费用） */
String cacheGet(String stateKey) {
    try {
        if (!cfgBool("cache_on")) return null;
        Object v = resCache.get(stateKey);
        return v == null ? null : String.valueOf(v);
    } catch (Throwable t) {
        return null;
    }
}

void cachePut(String stateKey, String out) {
    try {
        if (!cfgBool("cache_on")) return;
        if (stateKey == null || stateKey.length() == 0) return;
        if (resCache.get(stateKey) == null) resCacheOrder.add(stateKey);
        resCache.put(stateKey, out);
        while (resCacheOrder.size() > RES_CACHE_MAX) {
            Object oldest = resCacheOrder.remove(0);
            resCache.remove(oldest);
        }
    } catch (Throwable t) {
        log("JevIntent.cachePut 出错：" + t);
    }
}

/** 加速相关状态的一行摘要（写日志用） */
String speedLine(boolean prefetched) {
    return "耗时：握手 " + httpConnectMs + "ms + 服务端 " + httpServerMs
        + "ms = 共 " + httpTotalMs + "ms" + (prefetched ? "（预判命中，没再发请求）" : "");
}

/** 菜单点击后的真正逻辑（也可以被 onMsg / 其他入口直接调用） */
analyzeAndSend(msg) {
    if (msg == null) { toast("JevIntent：没有拿到消息"); return; }
    String text = msgText(msg);
    if (text.length() == 0) { toast("这条消息没有可分析的文本"); return; }

    // key 空 → 现场重读一次配置（改完配置不用重载插件）
    if (cfg("api_key").length() < 20) {
        loadConfig();
        if (cfg("api_key").length() < 20) {
            toast("还没读到 api_key，看插件日志");
            log("JevIntent: api_key 为空。试过：" + briefLong(cfgTried));
            return;
        }
    }

    // v1.9：缓存键只依赖"很便宜就能拿到"的东西，避免为了查缓存先跑一遍取上下文 host 调用
    String ck = msg.talker + "|" + text + "|" + presetOf("rel", msg.talker, cfg("rel_default"))
              + "|" + presetOf("sex", msg.talker, cfg("sex_default")) + "|" + ctxCount()
              + "|" + cfg("model");
    String cached = cacheGet(ck);
    if (cached != null) {
        toast("JevIntent：这条刚看过，直接给你");
        log("JevIntent 缓存命中：" + oneLine(cached));
        sendResult(msg, cached);
        return;
    }

    if (cfgBool("analyzing_toast")) toast("Jev 分析中…");

    final Object msgLocal = msg;
    final String textLocal = text;
    final String ckLocal = ck;
    new Thread(new Runnable() {
        public void run() {
            try {
                long tStart = System.currentTimeMillis();
                String label = presetLabel(msgLocal.talker);
                log("JevIntent 预设：" + label);
                String out = null;

                // ① 预判命中 → 直接排版（这是"长按完点一下秒出"的来源）
                String pre = prefetchTake(textLocal, 2500);
                if (pre != null) {
                    lastFromPrefetch = true;
                    out = format(pre, textLocal, label);
                } else {
                    // ② 没命中 → 正常发一次
                    lastFromPrefetch = false;
                    String state = buildState(msgLocal, textLocal);
                    String req = buildRequest(state);
                    int timeout = parseInt(cfg("timeout_ms"), 30000);
                    String body = httpPost(cfg("endpoint"), cfg("api_key"), req, timeout);
                    // v1.4：实测约 3%（34 次里 1 次）会返回空答案 —— 网络/服务抖动，自动重试一次
                    // v1.9：等待从 600ms 降到 120ms（那 600ms 对"快"的体感伤害比服务端还大）
                    if (fieldAfter(body, "emotion", "choice").length() == 0) {
                        log("JevIntent: 这一次没拿到情绪答案，重试一次");
                        try { Thread.sleep(120); } catch (Throwable ignore) { }
                        body = httpPost(cfg("endpoint"), cfg("api_key"), req, timeout);
                    }
                    out = format(body, textLocal, label);
                }

                cachePut(ckLocal, out);
                sendResult(msgLocal, out);
                log("JevIntent 完成（总 " + (System.currentTimeMillis() - tStart) + "ms）：" + oneLine(out));
                if (cfgBool("speed_log")) log("JevIntent " + speedLine(lastFromPrefetch));
            } catch (Throwable t) {
                toast("Jev 失败：" + brief(t));
                log("JevIntent 失败 " + t);
            }
        }
    }).start();
}

/** 自测：不依赖真消息，直接分析一句样例并发到当前会话 */
selfTest(msg) {
    loadConfig();
    final Object msgLocal = msg;
    new Thread(new Runnable() {
        public void run() {
            try {
                String sample = "这个报价单你什么时候能给我？明天就要签合同了";
                String state = "{\"" + "关系" + "\":\"" + "普通朋友" + "\",\"" + "对方性别" + "\":\"" + "未知" + "\",\"" + "待分析消息" + "\":" + jsonStr(sample) + "}";
                String body = httpPost(cfg("endpoint"), cfg("api_key"), buildRequest(state),
                                       parseInt(cfg("timeout_ms"), 30000));
                String out = "【自测】" + sample + "\n" + format(body, sample, "自测·无上下文");
                if (msgLocal != null) sendResult(msgLocal, out); else toast(oneLine(out));
            } catch (Throwable t) {
                toast("自测失败：" + brief(t));
            }
        }
    }).start();
}

// ─────────────────────────── 请求构造 ───────────────────────────

/** 语音/图片等类型没正文时，退回 rawContent */
/** 取 XML 里第一个 <tag>…</tag> 的文本（去 CDATA）。bsh 约束：绝不在 try 内 return */
String xmlTag(String xml, String tag) {
    String out = "";
    try {
        if (xml != null) {
            int i = xml.indexOf("<" + tag + ">");
            if (i < 0) i = xml.indexOf("<" + tag + " ");
            if (i >= 0) {
                int st = xml.indexOf(">", i);
                if (st >= 0) {
                    int en = xml.indexOf("</" + tag + ">", st);
                    if (en >= 0) {
                        String v = xml.substring(st + 1, en);
                        v = v.replace("<![CDATA[", "").replace("]]>", "");
                        out = v.trim();
                    }
                }
            }
        }
    } catch (Throwable t) { out = ""; }
    return out;
}

/** 把消息正文变成「能被 Jev 读懂的纯文字」。
 *  引用回复 / 链接 / 文件 / 小程序 等 appmsg 的 content 是 XML（type 49），
 *  老版本直接把 XML 丢给 Jev，而且因为 isText()==false 连菜单都不挂 —— v1.3 修。 */
String extractReadable(String s) {
    String out = s == null ? "" : s.trim();
    try {
        if (out.length() > 0 && (out.charAt(0) == '<' || out.indexOf("<appmsg") >= 0)) {
            String best = xmlTag(out, "title");
            if (best.length() == 0) best = xmlTag(out, "des");
            if (best.length() == 0 && out.indexOf("<refermsg>") >= 0) best = xmlTag(out, "content");
            if (best.length() > 0) {
                out = best;
            } else if (out.indexOf("<appmsg") >= 0 || out.indexOf("<img") >= 0
                    || out.indexOf("<voicemsg") >= 0 || out.indexOf("<videomsg") >= 0
                    || out.indexOf("<emoji") >= 0) {
                out = "";   // 图片/语音/视频/表情：抽不出文字，就是「没有可分析的文字」
            }
        }
    } catch (Throwable t) { }
    return out;
}

/** 这条消息引用（回复）的原文；没有引用返回 "" */
String quotedText(msg) {
    String s = "";
    try { s = msg.content; } catch (Throwable ignore) { s = ""; }
    String out = "";
    try {
        if (s != null && s.indexOf("<refermsg>") >= 0) {
            String blk = s.substring(s.indexOf("<refermsg>"));
            String c = xmlTag(blk, "content");
            if (c.length() == 0) c = xmlTag(blk, "title");
            String dn = xmlTag(blk, "displayname");
            if (c.length() > 0) out = (dn.length() > 0 ? dn + "：" : "") + c;
        }
    } catch (Throwable t) { out = ""; }
    return out;
}

String msgText(msg) {
    String s = null;
    try { s = msg.content; } catch (Throwable ignore) {}
    if (s == null || s.trim().length() == 0) {
        try { s = msg.rawContent; } catch (Throwable ignore) {}
    }
    String out = extractReadable(s);
    if (out.length() == 0) {
        String raw = "";
        try { raw = msg.rawContent; } catch (Throwable ignore) { raw = ""; }
        out = extractReadable(raw);
    }
    return out == null ? "" : out.trim();
}

// ─────────────────── 上下文（前后各 N 句）───────────────────
// ⚠ 用宿主 getMsgs；bsh 里调用宿主没实现的函数会「不可捕获」地炸掉执行，
//   所以调用点必须先判断 ctxCount() > 0，不能只靠方法内部的 return。

List asList(Object o) {
    if (o == null) return null;
    if (o instanceof List) return (List) o;
    try {
        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            List l = new ArrayList();
            for (int i = 0; i < n; i++) l.add(java.lang.reflect.Array.get(o, i));
            return l;
        }
    } catch (Throwable t) { }
    return null;
}

long longOf(Object o) {
    long v = 0;
    try { v = Long.parseLong(String.valueOf(o)); } catch (Throwable t) { v = 0; }
    return v;
}

/** 目标消息前 n 句 + 后 n 句（靠 getMsgs 拉一段窗口再定位） */
List historyLines(msg, int n) {
    List out = new ArrayList();
    String talker = msg.talker;
    long target = longOf(msg.createTime);
    if (target <= 0) return out;
    long from = target - parseInt(cfg("context_window_ms"), 1800000);
    Object raw = null;
    try { raw = getMsgs(talker, from); } catch (Throwable t) { log("取上下文失败：" + brief(t)); return out; }
    List ms = asList(raw);
    if (ms == null || ms.size() == 0) return out;

    List times = new ArrayList();
    List lines = new ArrayList();
    for (int i = 0; i < ms.size(); i++) {
        Object el = ms.get(i);
        if (el == null) continue;
        long ct = longOf(el.createTime);
        String c = "";
        try { c = String.valueOf(el.content); } catch (Throwable t) { c = ""; }
        if (c == null || c.equals("null") || c.trim().length() == 0) continue;
        String who = "对方";
        try { if ("1".equals(String.valueOf(el.isSend))) who = "我"; } catch (Throwable t) { }
        times.add(String.valueOf(ct));
        lines.add(who + "：" + c.trim().replace("\n", " "));
    }
    if (lines.size() == 0) return out;

    // 找与目标时间最接近的一条作为锚点
    int idx = -1;
    long bestD = -1;
    for (int i = 0; i < times.size(); i++) {
        long ct = longOf(times.get(i));
        long d = ct > target ? ct - target : target - ct;
        if (idx < 0 || d < bestD) { idx = i; bestD = d; }
    }
    if (idx < 0) return out;
    int a = idx - n; if (a < 0) a = 0;
    int b = idx + n; if (b > lines.size() - 1) b = lines.size() - 1;
    for (int i = a; i <= b; i++) out.add(String.valueOf(lines.get(i)));
    log("JevIntent 上下文：目标前 " + n + " 句 + 后 " + n + " 句，实取 " + out.size() + " 行（锚点偏移 " + bestD + "ms）");
    return out;
}

String buildState(msg, String text) {
    String rel = presetOf("rel", msg.talker, cfg("rel_default"));
    String sex = presetOf("sex", msg.talker, cfg("sex_default"));
    int n = ctxCount();
    List ctx = new ArrayList();
    if (n > 0) ctx = historyLines(msg, n);        // ← 关键：不调用就永远不会碰到缺失的宿主 API
    String kind = "单聊";
    try { if (String.valueOf(msg.talker).endsWith("@chatroom")) kind = "群聊"; } catch (Throwable ignore) { }
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"" + "关系" + "\":").append(jsonStr(rel)).append(",");
    sb.append("\"" + "对方性别" + "\":").append(jsonStr(sex)).append(",");
    sb.append("\"" + "会话类型" + "\":").append(jsonStr(kind)).append(",");
    sb.append("\"" + "最近对话" + "\":[");
    for (int i = 0; i < ctx.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append(jsonStr(String.valueOf(ctx.get(i))));
    }
    sb.append("],");
    String q = quotedText(msg);
    if (q.length() > 0) {
        sb.append("\"" + "本消息引用了" + "\":").append(jsonStr(q)).append(",");
    }
    sb.append("\"" + "待分析消息" + "\":").append(jsonStr(text));
    sb.append("}");
    return sb.toString();
}

String buildRequest(String state) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"state\":");            sb.append(jsonStr(state)); sb.append(",");
    sb.append("\"model\":");            sb.append(jsonStr(cfg("model"))); sb.append(",");
    sb.append("\"questions\":{");

    // 1) 主要意图（choice）
    sb.append(jsonStr("intent")).append(":{");
    sb.append("\"type\":\"choice\",");
    sb.append("\"instructions\":").append(jsonStr("结合「关系」「对方性别」和「最近对话」上下文，判断【待分析消息】真实的意图是什么？关系会显著改变意图解读，务必先考虑关系。"));
    sb.append(",\"criteria\":{");
    sb.append(chr("打招呼或闲聊")).append(",");
    sb.append(chr("询问信息或情况")).append(",");
    sb.append(chr("请求帮忙或让我办事")).append(",");
    sb.append(chr("催促进度或催回复")).append(",");
    sb.append(chr("商务合作或推销")).append(",");
    sb.append(chr("投诉或表达不满")).append(",");
    sb.append(chr("通知或告知信息")).append(",");
    sb.append(chr("表达情绪或吐槽")).append(",");
    sb.append(chr("邀约见面或请客")).append(",");
    sb.append(chr("其他"));
    sb.append("}},");

    // 2) 着急程度（score，4 档）
    //    ⚠ 口径定稿：实测换一种问法，同一句分数平均漂 0.46、最大 1.25（0~3 量程）
    //      —— 所以这段 instructions 一旦上线就别再改字，改了等于换量尺、历史数据不可比
    sb.append(jsonStr("urgency")).append(":{");
    sb.append("\"type\":\"score\",");
    sb.append("\"instructions\":").append(jsonStr("结合关系和上下文，这条消息有多着急？0=不着急，1=一般，2=急，3=非常急。"));
    sb.append(",\"criteria\":[");
    sb.append(jsonStr("不着急")).append(",");
    sb.append(jsonStr("一般")).append(",");
    sb.append(jsonStr("急")).append(",");
    sb.append(jsonStr("非常急"));
    sb.append("]},");

    // 3) 是否等回复（noul）
    sb.append(jsonStr("need_reply")).append(":{");
    sb.append("\"type\":\"noul\",");
    sb.append("\"instructions\":").append(jsonStr("结合关系和上下文，对方是否在等我方回复？"));
    sb.append("},");

    // 4) 风险（noul）
    sb.append(jsonStr("risk")).append(":{");
    sb.append("\"type\":\"noul\",");
    sb.append("\"instructions\":").append(jsonStr("结合双方关系，判断【待分析消息】对「我」的实际风险有多大：会不会被骗钱、被盗号、泄露隐私或惹上法律麻烦、人情麻烦？（关系亲近且金额很小 → 风险低；陌生人/网友索要钱财、验证码、点链接 → 风险高）"));
    sb.append("},");

    // 5) 应对建议（choice）—— Jev 不出自由文本，建议必须做成选项
    sb.append(jsonStr("advice")).append(":{");
    sb.append("\"type\":\"choice\",");
    sb.append("\"instructions\":").append(jsonStr("结合双方关系和上下文，最合适的应对动作是哪一个？"));
    sb.append(",\"criteria\":{");
    sb.append(chr("立刻回复并给出明确答复")).append(",");
    sb.append(chr("简短确认收到，稍后详答")).append(",");
    sb.append(chr("先问清楚细节再答复")).append(",");
    sb.append(chr("礼貌婉拒")).append(",");
    sb.append(chr("不必回复")).append(",");
    sb.append(chr("涉及钱/账号，先人工核实再回")).append(",");
    sb.append(chr("需要上报或转给相关同事")).append(",");
    sb.append(chr("共情或闲聊式回应即可"));
    sb.append("}}");

    // 6) 情绪（v1.4 核心）：choice 9 格
    //    响应里的 probabilities 是完整分布 → 取前 N 名当「主情绪 + 比例」，不用开多个问题
    //    ⚠ 别为"3 个情绪"开 3 个问题：那样只有 3 个 argmax、没有比例、还会互相矛盾
    sb.append(",");
    sb.append(jsonStr("emotion")).append(":{");
    sb.append("\"type\":\"choice\",");
    sb.append("\"instructions\":").append(jsonStr("结合「关系」和「最近对话」上下文，判断【待分析消息】透露出的是哪种情绪？只能选一个最接近的。"));
    sb.append(",\"criteria\":{");
    sb.append(chr("平静")).append(",");
    sb.append(chr("高兴")).append(",");
    sb.append(chr("不满")).append(",");
    sb.append(chr("生气")).append(",");
    sb.append(chr("焦虑")).append(",");
    sb.append(chr("着急")).append(",");
    sb.append(chr("难过")).append(",");
    sb.append(chr("讽刺")).append(",");
    sb.append(chr("客套"));
    sb.append("}},");

    // 7) 回复姿态（v1.4 核心）：choice 11 格 —— 只判"用什么姿态回"，不生成措辞（Jev 不出自由文本）
    //    11 格是体检砍出来的：13 个候选里「建议明确答应」「建议坚定拒绝」一次都没被选中
    //    （分别被「正常交流」和「温和婉拒/先问清细节」吃掉），砍掉后 top1 置信度 0.64 → 0.69
    sb.append(jsonStr("reply_style")).append(":{");
    sb.append("\"type\":\"choice\",");
    sb.append("\"instructions\":").append(jsonStr("结合关系和上下文，我回复这句话最合适的姿态是哪一个？只判断姿态，不要判断内容。"));
    sb.append(",\"criteria\":{");
    sb.append(chr("建议调侃打趣")).append(",");
    sb.append(chr("建议正常交流")).append(",");
    sb.append(chr("建议礼貌客套")).append(",");
    sb.append(chr("建议肯定赞赏")).append(",");
    sb.append(chr("建议关心问候")).append(",");
    sb.append(chr("建议共情吐槽")).append(",");
    sb.append(chr("建议表达同情")).append(",");
    sb.append(chr("建议先安抚道歉")).append(",");
    sb.append(chr("建议先问清细节")).append(",");
    sb.append(chr("建议温和婉拒")).append(",");
    sb.append(chr("建议暂不回复"));
    sb.append("}}");

    sb.append("}}");
    return sb.toString();
}

/** choice 的 criteria 是 map：key 同时也是返回的 choice 值 */
String chr(String v) {
    return jsonStr(v) + ":" + jsonStr(v);
}

String jsonStr(String s) {
    if (s == null) return "\"\"";
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '"' || c == '\\') b.append('\\').append(c);
        else if (c == '\n') b.append("\\n");
        else if (c == '\r') b.append("\\r");
        else if (c == '\t') b.append("\\t");
        else if (c < 0x20) b.append(' ');
        else b.append(c);
    }
    return b.append('"').toString();
}

// ─────────────────────────── HTTP ───────────────────────────

String httpPost(String urlStr, String key, String body, int timeoutMs) {
    if (key.length() == 0) throw new RuntimeException("config.properties 里没有 api_key");
    HttpURLConnection c = null;
    String result = null;
    String err = null;
    long t0 = System.currentTimeMillis();
    try {
        c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Connection", "keep-alive");
        byte[] payload = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(payload.length);
        c.connect();                                   // 单独量握手（DNS+TCP+TLS）
        long t1 = System.currentTimeMillis();
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();
        int code = c.getResponseCode();                // 服务端算完（首字节）
        long t2 = System.currentTimeMillis();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        long t3 = System.currentTimeMillis();
        httpConnectMs = t1 - t0;
        httpServerMs = t2 - t1;
        httpTotalMs = t3 - t0;
        if (code < 200 || code >= 300) err = "HTTP " + code + " " + brief(text);
        else result = text;
    } catch (Throwable t) {
        err = brief(t);
    } finally {
        // v1.9：**成功时不 disconnect** —— 把连接还回 JVM 连接池，下次直接复用
        //（出错才断开，免得半死不活的连接被复用；实测复用后握手 3~15ms，省掉首连 190~740ms）
        if (c != null && err != null) c.disconnect();
    }
    if (err != null) throw new RuntimeException(err);
    return result;
}

String readAll(InputStream in) {
    if (in == null) return "";
    BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = r.readLine()) != null) sb.append(line).append('\n');
    r.close();
    return sb.toString();
}

// ─────────────────────────── 响应解析（极简，不引 JSON 库）───────────────────────────

/** 取某问题块里某个字段的原始值：fieldAfter(json, "intent", "choice") → 催促进度或催回复
 *  关键：必须匹配 `"字段":`（带冒号）。只匹配 `"字段"` 会先撞上 `"type":"choice"` 里的那个值。 */
String fieldAfter(String json, String id, String field) {
    int i = json.indexOf("\"" + id + "\":");
    if (i < 0) i = json.indexOf("\"" + id + "\"");
    if (i < 0) return "";
    String seg = json.substring(i, Math.min(json.length(), i + 2000));
    String pat = "\"" + field + "\":";
    int j = seg.indexOf(pat);
    if (j < 0) return "";
    int k = j + pat.length();
    while (k < seg.length() && seg.charAt(k) == ' ') k++;
    if (k >= seg.length()) return "";
    StringBuilder out = new StringBuilder();
    if (seg.charAt(k) == '"') {
        k++;
        while (k < seg.length()) {
            char c = seg.charAt(k);
            if (c == '\\') { out.append(seg.charAt(k + 1)); k += 2; continue; }
            if (c == '"') break;
            out.append(c);
            k++;
        }
    } else {
        while (k < seg.length()) {
            char c = seg.charAt(k);
            if (c == ',' || c == '}' || c == '\n') break;
            out.append(c);
            k++;
        }
    }
    return out.toString().trim();
}

double fieldNum(String json, String id, String field, double def) {
    double v = def;
    try {
        String s = fieldAfter(json, id, field);
        if (s.length() > 0) v = Double.parseDouble(s);
    } catch (Throwable t) {
        v = def;
    }
    return v;
}

int parseInt(String s, int def) {
    int v = def;
    try { v = Integer.parseInt(s.trim()); } catch (Throwable t) { v = def; }
    return v;
}

String pct(double p) {
    return Math.round(p * 100) + "%";
}

/** 取某个 choice 问题的完整概率分布前 n 名 → ["着急 82%", "不满 11%", "焦虑 4%"]
 *  v1.4 核心：Jev 本来就把整个分布算出来了，不该只取 argmax */
List topProbs(String json, String id, int n) {
    List out = new ArrayList();
    try {
        int i = json.indexOf("\"" + id + "\":");
        if (i >= 0) {
            String seg = json.substring(i, Math.min(json.length(), i + 4000));
            int j = seg.indexOf("\"probabilities\":");
            if (j >= 0) {
                int k = seg.indexOf("{", j);
                if (k >= 0) {
                    int depth = 0;
                    int end = k;
                    for (; end < seg.length(); end++) {
                        char c = seg.charAt(end);
                        if (c == '{') depth++;
                        else if (c == '}') { depth--; if (depth == 0) break; }
                    }
                    String blk = seg.substring(k + 1, Math.min(end, seg.length()));
                    List names = new ArrayList();
                    List vals = new ArrayList();
                    int p = 0;
                    while (p < blk.length()) {
                        int q1 = blk.indexOf('"', p);
                        if (q1 < 0) break;
                        int q2 = blk.indexOf('"', q1 + 1);
                        if (q2 < 0) break;
                        String nm = blk.substring(q1 + 1, q2);
                        int c2 = blk.indexOf(':', q2);
                        if (c2 < 0) break;
                        int e = c2 + 1;
                        while (e < blk.length() && ",}".indexOf(blk.charAt(e)) < 0) e++;
                        double v = 0;
                        try { v = Double.parseDouble(blk.substring(c2 + 1, e).trim()); } catch (Throwable t) { v = 0; }
                        names.add(nm);
                        vals.add(new Double(v));
                        p = e + 1;
                    }
                    for (int a = 0; a < vals.size(); a++) {
                        for (int b = a + 1; b < vals.size(); b++) {
                            if (((Double) vals.get(b)).doubleValue() > ((Double) vals.get(a)).doubleValue()) {
                                Object tn = names.get(a); names.set(a, names.get(b)); names.set(b, tn);
                                Object tv = vals.get(a); vals.set(a, vals.get(b)); vals.set(b, tv);
                            }
                        }
                    }
                    for (int a = 0; a < n && a < names.size(); a++) {
                        double pv = ((Double) vals.get(a)).doubleValue();
                        if (pv < 0.01) break;                       // 太小的不显示
                        out.add(String.valueOf(names.get(a)) + " " + pct(pv));
                    }
                }
            }
        }
    } catch (Throwable t) { }
    return out;
}

/** "着急 82% · 不满 11% · 焦虑 4%" */
String distLine(String json, String id, int n) {
    String out = "";
    List l = topProbs(json, id, n);
    for (int i = 0; i < l.size(); i++) {
        out = out + (i > 0 ? " · " : "") + String.valueOf(l.get(i));
    }
    return out;
}

/** 把 Jev 的 answers 拼成一条微信消息 */
String format(String respJson, String state, String label) {
    String model = fieldAfter(respJson, "model", "model");
    String intent = fieldAfter(respJson, "intent", "choice");
    double intentConf = fieldNum(respJson, "intent", "confidence", -1);
    double urgency = fieldNum(respJson, "urgency", "score", -1);
    double needReply = fieldNum(respJson, "need_reply", "noul", -1);
    double risk = fieldNum(respJson, "risk", "noul", -1);
    String advice = fieldAfter(respJson, "advice", "choice");
    String style = fieldAfter(respJson, "reply_style", "choice");
    boolean legacy = cfgBool("show_legacy");
    boolean showC = cfgBool("show_confidence");

    String[] urgencyLabel = {"不着急", "一般", "急", "非常急"};     // v1.4：4 档

    StringBuilder sb = new StringBuilder();
    sb.append("🤖 Jev 意图分析");
    if (model.length() > 0) sb.append("（").append(model);
    if (label != null && label.length() > 0) sb.append(" | ").append(label);
    if (model.length() > 0 || (label != null && label.length() > 0)) sb.append("）");
    sb.append("\n");

    // ★ 意图（v1.7 起是核心行，不再受「附带老四项」开关影响 —— 它是第一条 Toast 的头部）
    sb.append("• 意图：").append(nz(intent, "未识别"));
    if (showC && intentConf >= 0) sb.append("  ").append(pct(intentConf));
    sb.append("\n");

    // ★ 情绪：主情绪 + 比例（前 N 名，来自 probabilities 分布）
    String emo = distLine(respJson, "emotion", parseInt(cfg("emotion_top"), 3));
    sb.append("• 情绪：").append(nz(emo, "未识别")).append("\n");

    // ★ 着急程度（4 档 + 原始分）
    if (urgency >= 0) {
        int lv = (int) Math.round(urgency);
        if (lv < 0) lv = 0;
        if (lv > 3) lv = 3;
        sb.append("• 着急：").append(urgencyLabel[lv]).append(" · ").append(round2(urgency)).append("/3").append("\n");
    }

    // ★ 建议 = 回复姿态（只给姿态，不给措辞；Jev 不出自由文本）
    //   v1.7：标签从「回复」改成「建议」，并把值自带的"建议"前缀去掉（否则会显示成"建议：建议肯定赞赏"）
    if (style.length() > 0) {
        String sv = style;
        if (sv.startsWith("建议")) sv = sv.substring(2);
        sb.append("• 建议：").append(sv);
        double sc = fieldNum(respJson, "reply_style", "confidence", -1);
        if (showC && sc >= 0) sb.append("  ").append(pct(sc));
        sb.append("\n");
    }

    if (legacy) {
        if (needReply >= 0) sb.append("• 待回复：").append(needReply >= 0.5 ? "是" : "否").append("  ").append(pct(needReply)).append("\n");
        if (risk >= 0) sb.append("• 风险：").append(risk >= 0.5 ? "⚠ 有（被骗/盗号/损失风险）" : "无").append("  ").append(pct(risk)).append("\n");
        sb.append("• 应对：").append(nz(advice, "自行判断"));
    }
    return sb.toString();
}

String nz(String s, String def) { return (s == null || s.length() == 0) ? def : s; }

String round2(double v) { return String.valueOf(Math.round(v * 100.0) / 100.0); }

String oneLine(String s) { return s.replace('\n', ' '); }

String brief(Throwable t) {
    String m = t.getMessage();
    if (m == null) m = t.toString();
    return m.length() > 120 ? m.substring(0, 120) : m;
}

String brief(String s) { return s == null ? "" : (s.length() > 120 ? s.substring(0, 120) : s); }

/** 诊断用：保留更长内容（看日志排查读配置问题） */
String briefLong(String s) {
    if (s == null) return "";
    String t = oneLine(s);
    return t.length() > 700 ? t.substring(0, 700) + "…" : t;
}

// ─────────────────────────── 发送结果 ───────────────────────────

void sendResult(msg, String text) {
    String mode = cfg("reply_mode");
    // 方案②：只在本机弹 Toast，什么消息都不发（对方完全不知道）
    if ("toast".equalsIgnoreCase(mode)) {
        toastResult(text);
        return;
    }
    // 私密模式：结果发到自己的会话（默认文件传输助手），对方完全看不到
    String talker = msg.talker;
    if (cfgBool("private_mode")) {
        String st = cfg("self_talker");
        talker = st.length() > 0 ? st : "filehelper";
    }
    boolean sent = false;
    if ("quote".equalsIgnoreCase(mode)) {
        try {
            sendXml(talker, quoteXml(msg, text), 57);
            sent = true;
        } catch (Throwable t) {
            log("引用回复失败，改用普通消息：" + brief(t));
        }
    }
    if (!sent) {
        try {
            sendText(talker, text);
        } catch (Throwable t2) {
            log("发送失败：" + brief(t2));
            toast("发送失败：" + brief(t2));
        }
    }
}

/** v1.7：Toast 固定 3 条，不再按字数乱切
 *   ① 意图（+ 情绪换行）  ② 着急程度  ③ 建议（回复姿态） */
void toastResult(String text) {
    String intent = "", emo = "", urg = "", sug = "";
    String[] lines = text.split("\n");
    for (int i = 0; i < lines.length; i++) {
        String ln = lines[i].trim();
        if (ln.length() == 0) continue;
        if (ln.startsWith("🤖")) continue;                       // 去掉表头
        ln = ln.replace("•", "").replace("⚠", "!").replace("（被骗/盗号/损失风险）", "（骗钱/盗号/损失）");
        ln = cleanForToast(ln);
        if (intent.length() == 0 && ln.startsWith("意图")) intent = ln;
        else if (emo.length() == 0 && ln.startsWith("情绪")) emo = ln;
        else if (urg.length() == 0 && ln.startsWith("着急")) urg = ln;
        else if (sug.length() == 0 && ln.startsWith("建议")) sug = ln;
    }
    String t1 = intent;
    if (emo.length() > 0) t1 = t1.length() > 0 ? t1 + "\n" + emo : emo;
    List parts = new ArrayList();
    if (t1.length() > 0) parts.add(t1);
    if (urg.length() > 0) parts.add(urg);
    if (sug.length() > 0) parts.add(sug);
    int gap = parseInt(cfg("toast_gap_ms"), 2000);
    for (int i = 0; i < parts.size(); i++) {
        toast(String.valueOf(parts.get(i)));
        if (i < parts.size() - 1) {
            try { Thread.sleep(gap); } catch (Throwable ignore) { }
        }
    }
    log("JevIntent 完成（toast " + parts.size() + " 条）：" + oneLine(text));
}

/** Toast 文案瘦身：去掉 (2.0/2) 这类冗余分数、压缩多余空格 */
String cleanForToast(String s) {
    String t = s;
    try { t = t.replaceAll("（" + "\\d+(\\.\\d+)?/\\d+" + "）", ""); } catch (Throwable ignore) { }
    try { t = t.replaceAll("\\(\\d+(\\.\\d+)?/\\d+\\)", ""); } catch (Throwable ignore) { }
    try { t = t.replaceAll("\\s{2,}", " "); } catch (Throwable ignore2) { }
    return t.trim();
}


/** 我自己发的消息 → 发送者是我；否则是 sendTalker */
boolean isFromMe(msg) {
    boolean me = false;
    try { me = (msg.isSend == 1); } catch (Throwable t) { me = false; }
    return me;
}

String myWxid() {
    String me = "";
    try { me = myWxId; } catch (Throwable t) { me = ""; }
    return me == null ? "" : me;
}

/** 原消息的发送者 wxid（引用块 fromusr/chatusr 用） */
String senderWxid(msg) {
    String s = "";
    try { s = isFromMe(msg) ? myWxid() : msg.sendTalker; } catch (Throwable t) { s = ""; }
    if (s == null) s = "";
    if (s.length() == 0) {
        try { s = msg.talker; } catch (Throwable t2) { s = ""; }
    }
    return s == null ? "" : s;
}

/** 引用块里的显示名（群消息取群昵称，单聊取备注）
 *  ⚠ bsh 里调用宿主不存在的函数会抛「不可捕获」的错误，所以默认不查（quote_displayname=true 才查） */
String senderName(msg, String sender) {
    String n = "";
    if (!cfgBool("quote_displayname")) return n;
    String tk = msg.talker;
    try {
        if (tk != null && tk.endsWith("@chatroom")) n = getUserName(tk, sender);
        else n = getUserRemark(sender);
    } catch (Throwable t) { n = ""; }
    if (n == null) n = "";
    return n;
}

/** 构造 type=57 的引用回复 XML，让气泡挂在原消息下面 */
String quoteXml(msg, String text) {
    String sender = senderWxid(msg);
    String name = senderName(msg, sender);
    boolean inTitle = !"des".equalsIgnoreCase(cfg("quote_text_in"));
    StringBuilder sb = new StringBuilder();
    sb.append("<appmsg appid=\"\" sdkver=\"0\">");
    if (inTitle) {
        sb.append("<title>").append(xmlEsc(text)).append("</title>");
        sb.append("<des>Jev 意图分析</des>");
    } else {
        sb.append("<title>🤖 Jev 意图分析</title>");
        sb.append("<des>").append(xmlEsc(oneLine(text))).append("</des>");
    }
    sb.append("<type>57</type>");
    sb.append("<refermsg>");
    sb.append("<type>1</type>");
    sb.append("<svrid>").append(msg.msgSvrId).append("</svrid>");
    sb.append("<fromusr>").append(xmlEsc(sender)).append("</fromusr>");
    sb.append("<chatusr>").append(xmlEsc(sender.length() > 0 ? sender : msg.talker)).append("</chatusr>");
    sb.append("<displayname>").append(xmlEsc(name)).append("</displayname>");
    sb.append("<content>").append(xmlEsc(msgText(msg))).append("</content>");
    sb.append("<createtime>").append(msg.createTime).append("</createtime>");
    sb.append("<msgsource></msgsource>");
    sb.append("</refermsg>");
    sb.append("</appmsg>");
    return sb.toString();
}

String xmlEsc(String s) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '&') b.append("&amp;");
        else if (c == '<') b.append("&lt;");
        else if (c == '>') b.append("&gt;");
        else if (c == '"') b.append("&quot;");
        else b.append(c);
    }
    return b.toString();
}
