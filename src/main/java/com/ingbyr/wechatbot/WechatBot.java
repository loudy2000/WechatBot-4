package com.ingbyr.wechatbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ingbyr.wechatbot.utils.DisplayUtils;
import com.ingbyr.wechatbot.utils.NetUtils;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created on 17-2-6.
 *
 * @author ing
 * @version 1.0
 */
final public class WechatBot implements Runnable {
    // todo 终端日志颜色区分
    private final Logger log = LoggerFactory.getLogger(WechatBot.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // bot参数
    private boolean debugFile = false;
    private boolean serverRun = false;
    private int msgCached = 5;
    private int msgFrequency = 100;
    private BlockingQueue msgQueue = new ArrayBlockingQueue<Map<String, String>>(msgCached);
    private Path dir = Paths.get(".", "RunTime");

    // 状态码
    private static final String SUCCESS = "200";
    private static final String SCANED = "201";
    private static final String TIMEOUT = "408";

    // 帮助消息
    public static String help = "";

    // 参数
    private String loginUrl = "";
    private String uuid = "";
    private String qrcodeUrl = "";
    private String redirectUrl = "";
    private String baseUrl = "";
    private String baseHost = "";
    private String baseRequestContent = "";
    private String syncKeyStr = "";
    private String syncHost = "";
    private boolean isBigContact = false;

    // 媒体文件
    private int fileIndex = 0;

    private Map<String, String> initData = new HashMap<>();
    private Map<String, String> baseRequest = new HashMap<>();
    private Map<String, Object> syncKey = new HashMap<>();
    private Map<String, Object> myAccount = new HashMap<>(); // 当前账户
    private List<HashMap<String, Object>> memberList = new ArrayList<>(); // 联系人, 公众号, 群组, 特殊账号
    private List<HashMap<String, Object>> contactList = new ArrayList<>(); // 联系人列表
    private List<HashMap<String, Object>> publicList = new ArrayList<>(); // 公众账号列表
    private List<HashMap<String, Object>> specialList = new ArrayList<>(); // 特殊账号列表
    private List<HashMap<String, Object>> groupList = new ArrayList<>(); // 群聊列表

// 消息相关
//    private String[] fullUserNameList = null;
//    private HashMap groupMembers; // 所有群组的成员 {'group_id1': [member1, member2, ...], ...}
//    private HashMap accountInfo; // 所有账户 {'group_member':{'id':{'type':'group_member', 'info':{}}, ...}, 'normal_member':{'id':{}, ...}}

    public WechatBot() {
        //　避免SSL报错
        System.setProperty("jsse.enableSNIExtension", "false");

        this.loginUrl = "https://login.weixin.qq.com/jslogin?appid=wx782c26e4c19acffb&redirect_uri=https%3A%2F%2Fwx.qq.com%2Fcgi-bin%2Fmmwebwx-bin%2Fwebwxnewloginpage&fun=new&lang=zh_CN&_=";
        this.qrcodeUrl = "https://login.weixin.qq.com/qrcode/";
        this.baseUrl = "https://wx.qq.com/cgi-bin/mmwebwx-bin";
        this.baseHost = "wx.qq.com";

        if (!dir.toFile().exists()) try {
            Files.createDirectory(dir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public WechatBot getUuid() throws IOException {
        loginUrl = loginUrl + new Date().getTime();
        log.trace("loginUrl: " + loginUrl);
        String response = NetUtils.request(loginUrl);
        // e.g: window.QRLogin.code = 200; window.QRLogin.uuid = "wejZcbBd2w==";
        String code = StringUtils.substringBetween(response, "window.QRLogin.code = ", ";");
        uuid = StringUtils.substringBetween(response, "window.QRLogin.uuid = \"", "\";");

        log.debug("window.QRLogin.code = " + code);
        log.debug("window.QRLogin.uuid = " + uuid);
        return this;
    }

    public WechatBot generateQrcode() throws IOException {
        qrcodeUrl += uuid;
        log.trace("qrcode url: " + qrcodeUrl);
        byte[] qrcodeDate = NetUtils.requestForBytes(qrcodeUrl);
        Path imageUrl = Paths.get(".", "RunTime", "qrcode.png");
        Files.write(imageUrl, qrcodeDate);
        try {
            if (!serverRun) {
                //跨平台显示二维码图片
                Desktop desktop = Desktop.getDesktop();
                desktop.open(imageUrl.toFile());
            }
        } finally {
            log.info("请扫描二维码登陆微信");
            log.info("二维码保存在　" + imageUrl);
            DisplayUtils.displayQRCodeInConsole(imageUrl);
        }
        return this;
    }

    public WechatBot waitForLogin() throws IOException, InterruptedException {
        String url = StringUtils.join("https://login.weixin.qq.com/cgi-bin/mmwebwx-bin/login?uuid=", uuid, "&tip=1&_=", new Date().getTime());
        log.trace("login url: " + url);
        while (true) {
            String response = NetUtils.request(url);
            log.trace("response: " + response);

            // 登陆过程中
            if (StringUtils.startsWith(response, "window.code=")) {
                String code = StringUtils.substringBetween(response, "window.code=", ";");
                log.debug("code: " + code);
                if (StringUtils.equals(code, SUCCESS)) {
                    log.info("登陆成功");
                    response = NetUtils.request(url);
                    redirectUrl = StringUtils.substringBetween(response, "window.redirect_uri=\"", "\";");
                    if (StringUtils.isNotEmpty(redirectUrl)) {
                        log.debug("redirectUrl: " + redirectUrl);
                        break;
                    }
                } else if (StringUtils.equals(code, SCANED))
                    log.info("请点击确认按钮");
                else if (StringUtils.equals(code, TIMEOUT)) {
                    log.info("登陆超时");
                    System.exit(-1);
                } else {
                    log.info("未知错误");
                    log.error(response);
                    System.exit(-1);
                }
            }

            //　轮询时间　1s
            Thread.sleep(1000);
        }
        return this;
    }

    public WechatBot login() throws IOException {
        if (redirectUrl.length() < 4) {
            log.info("登陆失败");
            System.exit(-1);
        }
        //　获取uin和sid
        redirectUrl += "&fun=new";

        String response = NetUtils.request(redirectUrl);
        log.trace("response: " + response);
        initData = parseInitData(response);

        // 组装请求body
        baseRequest.put("Uin", initData.get("wxuin"));
        baseRequest.put("Sid", initData.get("wxsid"));
        baseRequest.put("Skey", initData.get("skey"));
        baseRequest.put("DeviceID", initData.get("deviceID"));

        Map<String, Map> tempData = new HashMap<>();
        tempData.put("BaseRequest", baseRequest);
        log.debug(tempData.toString());

        // 转换为json格式
        baseRequestContent = mapper.writeValueAsString(tempData);
        return this;
    }

    public WechatBot init() throws IOException {
        String url = baseUrl + "/webwxinit?r=" + (new Date().getTime()) + "&lang=en_US&pass_ticket=" + initData.get("pass_ticket");
        log.trace("url: " + url);
        log.debug("baseRequestContent " + baseRequestContent);

        byte[] response = NetUtils.requestWithJsonForBytes(url, baseRequestContent);

        // 保存json数据到文件temp.json
        if (debugFile) {
            Files.write(Paths.get(dir.toString(), "init.json"), response);
        }

        Map map = mapper.readValue(response, Map.class);
        syncKey = (Map<String, Object>) map.get("SyncKey");
        myAccount = (Map<String, Object>) map.get("User");

        log.debug("syncKey: " + syncKey);

        List<HashMap<String, Integer>> syncKeyList = (List<HashMap<String, Integer>>) syncKey.get("List");
        syncKeyStr = NetUtils.genSyncStr(syncKeyList);

        log.debug("syncKeyStr: " + syncKeyStr);
        log.debug("myAccount: " + myAccount);
        return this;
    }

    public WechatBot statusNotify() throws IOException {
        String url = baseUrl + "/webwxstatusnotify?lang=zh_CN&pass_ticket=" + initData.get("pass_ticket");
        Map<String, Object> tempData = new HashMap<>();
        tempData.put("BaseRequest", baseRequest);
        tempData.put("Code", 3);
        tempData.put("FromUserName", myAccount.get("UserName"));
        tempData.put("ToUserName", myAccount.get("UserName"));
        tempData.put("ClientMsgId", new Date().getTime());
        NetUtils.requestWithJson(url, mapper.writeValueAsString(tempData));
        return this;
    }

    public WechatBot getContact() throws Exception {
        // todo 如果通讯录联系人过多，这里会直接获取失败
        if (isBigContact) {
            throw new Exception("Need fixed: big contact");
        }

        String url = baseUrl + "/webwxgetcontact?pass_ticket=" + initData.get("pass_ticket")
                + "&skey=" + initData.get("skey") + "&r=" + new Date().getTime();
        log.trace("url: " + url);


        try {
            byte[] response = NetUtils.requestWithJsonForBytes(url, "{}");
            if (debugFile) {
                Files.write(Paths.get(dir.toString(), "contacts.json"), response);
            }

            Map map = mapper.readValue(response, Map.class);
            memberList = (ArrayList<HashMap<String, Object>>) map.get("MemberList");

            // 特殊用户名单
            List<String> specialUsers = Arrays.asList("newsapp", "fmessage", "filehelper", "weibo", "qqmail",
                    "fmessage", "tmessage", "qmessage", "qqsync", "floatbottle",
                    "lbsapp", "shakeapp", "medianote", "qqfriend", "readerapp",
                    "blogapp", "facebookapp", "masssendapp", "meishiapp",
                    "feedsapp", "voip", "blogappweixin", "weixin", "brandsessionholder",
                    "weixinreminder", "wxid_novlwrv3lqwv11", "gh_22b87fa7cb3c",
                    "officialaccounts", "notification_messages", "wxid_novlwrv3lqwv11",
                    "gh_22b87fa7cb3c", "wxitil", "userexperience_alarm", "notification_messages");

            for (HashMap<String, Object> contact : memberList) {
                if ((Integer.parseInt(contact.get("VerifyFlag").toString()) & 8) != 0) {
                    // 公众号
                    publicList.add(contact);
                    log.debug("公众号: " + contact.get("NickName"));
                } else if (specialUsers.contains(contact.get("UserName").toString())) {
                    // 特殊账户
                    specialList.add(contact);
                } else if (contact.get("UserName").toString().startsWith("@@")) {
                    // 群聊
                    groupList.add(contact);
                    log.debug("群聊: " + contact.get("NickName"));
                } else if (StringUtils.equals(contact.get("UserName").toString(), myAccount.get("UserName").toString())) {
                    // 自己
                    log.info("欢迎:　" + contact.get("NickName"));
                } else {
                    contactList.add(contact);
                    log.debug("好友: " + contact.get("NickName"));
                }
            }
        } catch (Exception e) {
            isBigContact = true;
            log.info("联系人数量过多，尝试重新获取中");
            e.printStackTrace();
        }

        return this;
    }

    private void testSyncCheck() throws IOException {
// webpush2 Failed to connect to webpush2.wx.qq.com/222.221.5.252:443
//        String[] hosts = {"webpush.", "webpush2."};
//        for (String host1 : hosts) {
//            syncHost = host1 + baseHost;
//            syncCheck();
//        }

        syncHost = "webpush." + baseHost;
        syncCheck();
    }

    private String[] syncCheck() throws IOException {
        Map<String, Object> request = new HashMap<>();
        request.put("r", new Date().getTime());
        request.put("sid", initData.get("wxsid"));
        request.put("uin", initData.get("wxuin"));
        request.put("skey", initData.get("skey"));
        request.put("deviceid", initData.get("deviceID"));
        request.put("synckey", syncKeyStr);
        request.put("_", new Date().getTime());

        String url = "https://" + syncHost + "/cgi-bin/mmwebwx-bin/synccheck?" + NetUtils.getUrlParamsByMap(request, false);
        log.trace("url: " + url);
        String response = NetUtils.request(url);
        log.trace("syncCheck response: " + response);

        String retcode = StringUtils.substringBetween(response, "retcode:\"", "\",");
        String selector = StringUtils.substringBetween(response, "selector:\"", "\"}");
        return new String[]{retcode, selector};
    }

    private Map sync() {
        String url = baseUrl + "/webwxsync?sid=" + initData.get("wxsid") + "&skey="
                + initData.get("skey") + "&lang=en_US&pass_ticket=" + initData.get("pass_ticket");
        log.trace("url: " + url);
        Map<String, Object> request = new HashMap<>();
        request.put("BaseRequest", baseRequest);
        request.put("SyncKey", syncKey);
        request.put("rr", ~(new Date().getTime()));
        String requestContent;
        try {
            requestContent = mapper.writeValueAsString(request);
            String response = NetUtils.requestWithJson(url, requestContent);
            Map dataMap = mapper.readValue(response, Map.class);
            int ret = (int) ((Map<String, Object>) dataMap.get("BaseResponse")).get("Ret");
            if (ret == 0) {
                syncKey = (Map<String, Object>) dataMap.get("SyncKey");
                List<HashMap<String, Integer>> syncKeyList = (List<HashMap<String, Integer>>) ((Map<String, Object>) dataMap.get("SyncKey")).get("List");
                syncKeyStr = NetUtils.genSyncStr(syncKeyList);
                log.trace("regenerate syncKeyStr: " + syncKeyStr);
            }
            log.trace("sync response: \n" + response);
            return dataMap;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void procMsg() throws IOException, InterruptedException {
        testSyncCheck();
        String[] status;
        long checkTime;
        Map msg = new HashMap<String, String>();
        log.info("Wechat Bot 启动完成");
        while (true) {
            checkTime = new Date().getTime();
            try {
                status = syncCheck();
                if (StringUtils.equals(status[0], "1100")) {
                    log.info("从微信客户端上登出");
                    break;
                } else if (StringUtils.equals(status[0], "1101")) {
                    log.info("从其它设备上登了网页微信");
                    break;
                } else if (StringUtils.equals(status[0], "0")) {
                    if (StringUtils.equals(status[1], "2")) {
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "3")) {
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "4")) {
                        sync();
                    } else if (StringUtils.equals(status[1], "6")) {
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "7")) {
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "0")) {

                    } else {
                        handleMsg(sync());
                    }
                } else {
                    log.debug("未知的status");
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            checkTime = new Date().getTime() - checkTime;
            if (checkTime < 0.8) {
                Thread.sleep(1 - checkTime);
            }

            // 读取消息队列
            log.debug("Read msg from queue, remainingCapacity: " + msgQueue.remainingCapacity());
            msg = (Map) msgQueue.poll();
            if (msg != null) {
                for (Object k : msg.keySet()) {
                    String v = (String) msg.get(k.toString());
                    sendMsgByUid(v, k.toString());
                }
            } else {
                log.debug("get null msg");
            }
        }
    }

    /**
     * msg_type_id:
     * 0 -> Init
     * 1 -> Self
     * 2 -> FileHelper
     * 3 -> Group
     * 4 -> Contact
     * 5 -> Public
     * 6 -> Special
     * 99 -> Unknown
     *
     * @param map
     */
    public void handleMsg(Map map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        List<Map<String, Object>> msgList = (List<Map<String, Object>>) map.get("AddMsgList");
        int msgTypeId = -1;

        for (Map<String, Object> msg : msgList) {
            if ((int) msg.get("MsgType") == 51 && (int) msg.get("StatusNotifyCode") == 4) {
                // 消息初始化
                msgTypeId = 0;
            } else if (StringUtils.equals(msg.get("FromUserName").toString(), myAccount.get("UserName").toString())) {
                // 自己给自己发送的消息
                msgTypeId = 1;
            } else if (isContact(msg.get("FromUserName").toString())) {
                // 好友发送的消息
                msgTypeId = 4;
            }
            handleMsgAll(msgTypeId, msg);
        }
    }

    private boolean isContact(String uid) {
        for (HashMap<String, Object> contact : contactList) {
            if (StringUtils.equals(uid, contact.get("UserName").toString())) {
                return true;
            }
        }
        return false;
    }

    private String getNameByUidFromContact(String uid, boolean isRemarkName) {
        for (HashMap<String, Object> contact : contactList) {
            if (StringUtils.equals(uid, contact.get("UserName").toString())) {
                if (isRemarkName) {
                    return contact.get("RemarkName").toString();
                } else {
                    return contact.get("NickName").toString();
                }
            }
        }
        return null;
    }

    private void sendMsgByUid(String data, String dst) {
        String url = baseUrl + "/webwxsendmsg?pass_ticket=" + initData.get("pass_ticket");
        log.trace("url: " + url);
        Map params = new HashMap<String, Object>();
        Map msg = new HashMap<String, Object>();
        String temp = String.valueOf((new Date().getTime() * 10000) + ThreadLocalRandom.current().nextInt(0, 9999));
        String msgId = StringUtils.substring(temp, 0, temp.length() - 4);

        msg.put("Type", 1);
        msg.put("Content", data);
        msg.put("FromUserName", myAccount.get("UserName").toString());
        msg.put("ToUserName", dst);
        msg.put("LocalID", msgId);
        msg.put("ClientMsgId", msgId);

        params.put("BaseRequest", baseRequest);
        params.put("Msg", msg);


        String paramsContent;
        try {
            paramsContent = mapper.writeValueAsString(params);
            log.debug("send msg: " + paramsContent);
            String response = NetUtils.requestWithJson(url, paramsContent);
            log.trace("response: " + response);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            log.error("消息发送失败");
            e.printStackTrace();
        }
    }

    private String uploadMedia(Path filePath, boolean isImage) throws IOException {
        log.debug("filePath: ", filePath.toString());
        if (!Files.exists(filePath)) {
            log.error("File not exists");
            return null;
        }
        // 共两个上传服务器
        String url1 = "https://file." + baseHost + "/cgi-bin/mmwebwx-bin/webwxuploadmedia?f=json";
        String url2 = "https://file2." + baseHost + "/cgi-bin/mmwebwx-bin/webwxuploadmedia?f=json";
        String id = "WU_FILE_" + fileIndex;
        String mediaType;

        // 判断是图片还是文件
        if (isImage)
            mediaType = "pic";
        else
            mediaType = "doc";

        Map<String, Object> requestContent = new HashMap<>();
        requestContent.put("BaseRequest", baseRequest);
        requestContent.put("ClientMediaId", new Date().getTime());
        requestContent.put("TotalLen", Files.size(filePath));
        requestContent.put("StartPos", 0);
        requestContent.put("DataLen", Files.size(filePath));
        requestContent.put("MediaType", 4);
        String passTicket = initData.get("pass_ticket");
        fileIndex++;

        String res = NetUtils.postImage(id, url1, new File(filePath.toString()), mediaType, requestContent, passTicket);
        log.debug("res(url1): " + res);
        Map response = mapper.readValue(res, Map.class);

        if (((int) ((Map) response.get("BaseResponse")).get("Ret")) != 0) {
            res = NetUtils.postImage(id, url2, new File(filePath.toString()), mediaType, requestContent, passTicket);
            response = mapper.readValue(res, Map.class);
            log.debug("res(url2): " + res);
        }

        if (((int) ((Map) response.get("BaseResponse")).get("Ret")) != 0) {
            log.error("Upload media failure");
        }

        // 获得文件标志
        String mediaId = response.get("MediaId").toString();
        log.debug("MediaId" + mediaId);
        return mediaId;
    }

    public boolean sendImgMsgByUid(Path imagePath, String uid) {
        try {
            String mid = uploadMedia(imagePath, true);
            if (StringUtils.isBlank(mid)) {
                return false;
            }
            String url = baseUrl + "/webwxsendmsgimg?fun=async&f=json";

            Map<String, Object> msg = new HashMap<>();
            msg.put("Type", 3);
            msg.put("MediaId", mid);
            msg.put("FromUserName", myAccount.get("UserName").toString());
            msg.put("ToUserName", uid);
            msg.put("LocalID", new Date().getTime());
            msg.put("ClientMsgId", new Date().getTime());
            if (imagePath.endsWith("gif")) {
                url = baseUrl + "/webwxsendemoticon?fun=sys";
                msg.put("Type", 47);
                msg.put("EmojiFlag", 2);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("BaseRequest", baseRequest);
            data.put("Msg", msg);

            String response = NetUtils.requestWithJson(url, mapper.writeValueAsString(data));
            log.debug("response: " + response);
            Map res = mapper.readValue(response, Map.class);
            if ((int) ((Map) res.get("BaseResponse")).get("Ret") == 0) {
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void sendFileMsgByUid() {
        // todo 发送任意格式文件
    }

    public void handleMsgAll(int msgType, Map<String, Object> msg) {
        String msgContent = msg.get("Content").toString();
        System.out.println("msgType: " + msgType);
        System.out.println("msgContent: " + msgContent);
        System.out.println("FromUserName: " + msg.get("FromUserName").toString());

        if (msgType == 1 && msgContent.startsWith("/")) {
            // 自己发送给自己的消息
            System.out.println(("自己发来的消息: " + msgContent));
            sendMsgByUid("Test Wechatbot", msg.get("FromUserName").toString());

        }
    }

    public void setDebugFile(boolean debugFile) {
        this.debugFile = debugFile;
    }

    public void setServerRun(boolean serverRun) {
        this.serverRun = serverRun;
    }

    public void setMsgFrequency(int msgFrequency) {
        this.msgFrequency = msgFrequency;
    }

    private Map<String, String> parseInitData(String data) {
        try {
            Map<String, String> ussData = new HashMap<>();
            Document document = DocumentHelper.parseText(data);
            Element root = document.getRootElement();
            Iterator iter = root.elementIterator();
            while (iter.hasNext()) {
                Element ele = (Element) iter.next();
                log.debug("name:" + ele.getName() + " value:" + ele.getStringValue());
                ussData.put(ele.getName(), ele.getStringValue());
            }

            //　随机device id
            String deviceID = "e";
            for (int i = 0; i < 3; i++) {
                int randomNum = ThreadLocalRandom.current().nextInt(10000, 99999);
                deviceID += randomNum;
            }
            ussData.put("deviceID", deviceID);
            return ussData;
        } catch (DocumentException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void addMsgToQueue(String data, String dst) {
        Map msg = new HashMap<String, String>();
        msg.put(dst, data);
        msgQueue.add(msg);
    }

    /**
     * 调用顺序
     * getUuid();
     * generateQrcode();
     * waitForLogin();
     * login();
     * init();
     * statusNotify();
     * getContact();
     * procMsg();
     */
    @Override
    public void run() {
        try {
            this.getUuid()
                    .generateQrcode()
                    .waitForLogin()
                    .login()
                    .init()
                    .statusNotify()
                    .getContact()
                    .procMsg();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        WechatBot w = new WechatBot();
        new Thread(w).start();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String data = scanner.nextLine();
            String dst = scanner.nextLine();
            w.addMsgToQueue(data, dst);
        }
    }
}
