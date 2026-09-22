// FakeMsg.java —— 模拟 FkWeChat 宿主的 Msg 对象（字段与文档一致）
public class FakeMsg {
    public String talker = "wxid_test_talker";
    public String content = "这个报价单你什么时候能给我？明天就要签合同了";
    public String rawContent = "";
    public String sendTalker = "wxid_test_sender";
    public int isSend = 0;
    public long msgId = 123456;
    public long msgSvrId = 654321;
    public long createTime = 1790010555000L;
    public int type = 1;

    public boolean isText() { return true; }
    public boolean isGroupChat() { return false; }
}
