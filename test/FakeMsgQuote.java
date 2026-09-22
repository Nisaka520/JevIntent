// FakeMsgQuote.java —— 模拟「引用回复」消息（isText()==false，content 是 appmsg XML）
public class FakeMsgQuote {
    public String talker = "wxid_test_group";
    public String content = "<appmsg appid=\"\" sdkver=\"0\"><title>这个记得改一下单价，客户那边催了</title><des></des><type>57</type><refermsg><type>1</type><svrid>99</svrid><fromusr>wxid_a</fromusr><chatusr>wxid_a</chatusr><displayname>老王</displayname><content>报价单我明天给你</content><createtime>1790010000000</createtime><msgsource></msgsource></refermsg><appattach><totallen>0</totallen></appattach></appmsg>";
    public String rawContent = "";
    public String sendTalker = "wxid_test_sender";
    public int isSend = 0;
    public long msgId = 2001;
    public long msgSvrId = 6001;
    public long createTime = 1790010555000L;
    public int type = 49;

    public boolean isText() { return false; }          // ← 微信里引用消息就是这样
    public boolean isGroupChat() { return true; }
}
