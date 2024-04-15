package coms.pbft;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONReader;
import coms.pbft.Message;
import coms.pbft.Utils.FlyShareLocation;
import coms.pbft.Utils.sendUtil;
import coms.pbft.Utils.timePositionTask;
import coms.pbft.Utils.timeTaskUtil;
import coms.pbft.constant.Constant;
import coms.pbft.constant.Varible;
import coms.pbft.pojo.*;
import coms.pbft.target.Target1;
import coms.pbft.time.TestLongSend;
import lombok.Data;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.*;
@Data
public class PbftNode {
    private int node;
    private int view;
    private String ip;
    private int port;
    private boolean isGood;
    private int x;
    private int y;

    //所有集群节点信息
    private List<Node> NodeList=new ArrayList<Node>();
    //prepare记录投票
    private Map<Integer, Map<String,Integer>>prepareVoteList=new HashMap<>();
    //commit记录投票
    private Map<Integer, Map<String,Integer>>commitVoteList=new HashMap<>();
    //reply记录返回
    private Map<Integer,Map<String,Integer>>replyVoteList=new HashMap<>();

    //记录在prepare和commit函数内部已经发送的对应COMMIT和REPLY，防止当票数达到界限值就发送，之后的票来，就不能再去操作了，否则会重复发送，故设置这两个变量，控制
    //里面记录 prepare1或者commit1或者reply或者viewChange或者viewChangeAck 请求来当对应函数接收到请求后，直接忽略，因为已经投票成功了
    private Set<String> defendVoteList=new HashSet<>();

    public PbftNode(int node, String ip, int port,boolean isGood) throws FileNotFoundException {
        this.node = node;
        this.ip = ip;
        this.port = port;
        this.isGood=isGood;
        LoadNodes();
    }
    public void start(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ServerSocket serverSocket = new ServerSocket(port);

                    while (true) {
                        Socket clientSocket = serverSocket.accept();
//                        System.out.println("获取到socket");
                        try {
                            InputStream inputStream = clientSocket.getInputStream();
                            while(true){
                                byte[] bytes = new byte[Constant.BYTE_LENGTH];
                                int count = inputStream.read(bytes, 0, Constant.BYTE_LENGTH);
                                if(count>0){;
                                    Message message= JSON.parseObject(new String(bytes), Message.class);
                                    //用于分行，更好的显示输出
                                    if(message.getType()==Constant.REQUEST){
                                        System.out.println("*****************新消息*****************");
//                                        TestLongSend.endTime=System.currentTimeMillis();
//                                        System.out.println("指挥塔发送主节点传送消息时长为："+(TestLongSend.endTime-TestLongSend.startTime));
                                    }
                                    if(message.getType()==Constant.PRE_PREPARE){
                                        System.out.println("*****************新消息*****************");
                                    }
                                    System.out.println("节点"+(node)+" 收到来源于"+message.getOrgNode()+"的消息"+message);
                                    doAction(message);
                                }else{
                                    break;
                                }
                            }
                        } catch (SocketException e) {
//                            System.out.println("Client disconnected.");
                        } finally  {
                            clientSocket.close();
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

        }).start();
    }

    private void doAction(Message message) throws IOException {
        if(message!=null){

            switch(message.getType()){
                case Constant.REQUEST:
                        onRequest(message);
                    break;
                case Constant.PRE_PREPARE:
                    onPrePrepare(message);
                    break;
                case Constant.PREPARE:
                    onPrepare(message);
                    break;
                case Constant.COMMIT:
                    onCommit(message);
                    break;
                case Constant.REPLY:
                    onReply(message);
                    break;
                case Constant.FIND:
                    onFind(message);
                default:
                    break;
            }
        }
    }


    private void onRequest(Message message) throws IOException {
//        根据朝代计算主节点
        //也就主节点能接收到
        int mainNode=view%(NodeList.size());
        if(message.getToNode()==mainNode){
            int msgNumber = message.getNumber();
            String msgValue = message.getValue();
            /**
             * 如果重发的话，先清除各个节点的上次信息的投票记录,此处只针对 主节点
             */
//            ClearPrepareCommitReplyVoteDefendMain(msgNumber);
            //client向主节点发送请求情况
            //发送广播
            sendAllNodes(message,Constant.PRE_PREPARE);
            System.out.println("Pre-prepare阶段主节点广播..............");
            //自己向自己发送，实际是修改prepareVoteList数值
            //这部分相当于主节点在pre-prepare发送时候已经在prepare投票了，所以要都提前添加值,
            //此时是只投了自己,其余人要在他们的节点线程中处理，我放在了pre-prepare函数里面
            requestSendToSelf(msgNumber,msgValue);


        }else{
            //client向非主节点发送请求情况
        }

    }



    private void onPrePrepare(Message message) throws IOException {
        //相当于主节点再pre-prepare阶段就已经投了，prepare的票了
        int msgNumber = message.getNumber();
        String msgValue = message.getValue();
        /**
         * 如果重发的话，先清除各个节点的上次信息的投票记录,此处只针对非主节点
         */
//        ClearPrepareCommitReplyVoteDefendOther(msgNumber);
        //因为ClearPrepareCommitReplyVoteDefend删除了onRequest传入的
//        requestSendToSelf(msgNumber,msgValue);
        requestSendToSelf(msgNumber,msgValue);
        sendAllNodes(message,Constant.PREPARE);
        System.out.println("prepare阶段节点广播..............");
        //自己向自己发送，实际是修改prepareVoteList数值
        msgNumber = message.getNumber();
        msgValue = message.getValue();
        prepareSendToSelf(msgNumber,msgValue);
    }


    private void onPrepare(Message message) throws IOException {
        //已经票数够了，发送了，所以不用操作了
        if(defendVoteList.contains("prepare"+message.getNumber())){
            return;
        }
        int msgNumber = message.getNumber();
        String msgValue = message.getValue();
        /**
         * prepare阶段投票
         */
        prepareVote(msgNumber,msgValue);
        Map<String, Integer> voteValue = prepareVoteList.get(msgNumber);
        Set<String> voteKeySet = voteValue.keySet();
//        System.out.println(prepareVoteList);、
        for (String voteKey : voteKeySet) {
            Integer voteNumber = voteValue.get(voteKey);
            //prepare投票判断
            //2*((NodeList.size()+1)/3)+1
            if(voteNumber>=2*((NodeList.size())/3)+1){
                sendAllNodes(message,Constant.COMMIT);
                System.out.println("commit阶段主节点广播..............");
                ///////////////////////////
                //自己向自己发送，实际是修改commitVoteList数值
                msgNumber = message.getNumber();
                msgValue = message.getValue();
                commitSendToSelf(msgNumber,msgValue);
                //投票成功后，防止之后后面慢的票进来，重复操作
                defendVoteList.add("prepare"+message.getNumber());
            }
        }

    }


    private void onCommit(Message message) throws IOException {
        //已经票数够了，发送了，后面就不要操作了
        if(defendVoteList.contains("commit"+message.getNumber())){
            return;
        }
//        System.out.println(commitVoteList);
        int msgNumber = message.getNumber();
        String msgValue = message.getValue();
        /**
         * commit阶段投票
         */
        commitVote(msgNumber,msgValue);

        Map<String, Integer> voteValue = commitVoteList.get(msgNumber);
        Set<String> voteKeySet = voteValue.keySet();
        for (String voteKey : voteKeySet) {
            Integer voteNumber = voteValue.get(voteKey);
            //2*((NodeList.size()+1)/3)+1
            if(voteNumber>=2*((NodeList.size())/3)+1){

                if(message.getControllerType()==Constant.OPERATION2){
                    Position position = JSON.parseObject(msgValue, Position.class);
                    this.setX(position.getX());
                    this.setY(position.getY());
                    //这个只针对我们移动位置了，然后target目标看到了触发的
                    System.out.println(Target1.NodeList);
                    /**
                     * 由于是不同的main运行的，所以targetMain跑的代码对NodeList维护，我的pbftNodeMain是没有的，我的仍然是原来的NodeList
                     * 也就是空，我考虑让无人机移动一次就给target发信息来达到模拟目标，定时搜索时候也是一样
                     */
//                    Target1.NodeList.get(this.getNode()).setX(position.getX());
//                    Target1.NodeList.get(this.getNode()).setY(position.getY());
                    Message messagetoTarget1 = new Message();
                    messagetoTarget1.setOriOrgNode(this.getNode());
                    messagetoTarget1.setOrgNode(this.getNode());
                    messagetoTarget1.setToNode(-2);
                    messagetoTarget1.setTime(LocalDateTime.now());
                    //实际就是将接收到的移动位置坐标，自己移动setX,Y之后，将其发送给target1,作用类似找到目标
                    messagetoTarget1.setValue(message.getValue());
                    sendUtil.sendNode("127.0.0.1",8888,messagetoTarget1);

                }
                if(message.getControllerType()==Constant.OPERATION4){
                    //根据控制台发出的消息 开启无人机搜索
                    Field field = JSON.parseObject(msgValue, Field.class);
                    timePositionTask.addTimeTask(this,field);
                }
                if(message.getControllerType()==Constant.OPERATION3){
                    //根据控制台发出的消息 关闭无人机搜索
                    timePositionTask.cancelTimeTask(this);
                }
                if(message.getControllerType()==Constant.OPERATION6){
                    //根据控制台发出的消息 开启无人机位置共享
                    FlyShareLocation.addTimeTask(this);
                }
                if(message.getControllerType()==Constant.OPERATION7){
                    //根据控制台发出的消息 关闭无人机位置共享
                    FlyShareLocation.cancelTimeTask(this);
                }
                if(message.getControllerType()==Constant.OPERATION1){
                    //收到一个无人机广播消息，来维护NodeLIst中其他无人机位置
                    String value = message.getValue();
                    Position position = JSON.parseObject(value, Position.class);
                    this.getNodeList().get(message.getOriOrgNode()).setX(position.getX());
                    this.getNodeList().get(message.getOriOrgNode()).setY(position.getY());
                    //找到目标后关闭无人机搜索
                    //根据控制台发出的消息 关闭无人机搜索
//                    timePositionTask.cancelTimeTask(this);
                }
                if(message.getControllerType()==Constant.FLYFINDSHARE){
                    //当发现有无人机找到目标，立即修改位置前往，找到目标的无人机旁边
                    System.out.println("飞翔");
                    if(message.getOriOrgNode()!=this.getNode()){
                        String value = message.getValue();
                        Position ShareNodePosition = JSON.parseObject(value, Position.class);
                        this.setX(ShareNodePosition.getX());
                        this.setY(ShareNodePosition.getY());
                        System.out.println("飞翔成功");
                        System.out.println("更新后坐标"+this.getX()+" "+this.getY());
                        //显示找不到定时任务可以加个if语句
                        if(timePositionTask.timerMapList.get(this.getNode())!=null&&timePositionTask.timeroutTaskMapList.get(this.getNode())!=null)
                        timePositionTask.cancelTimeTask(this);
                    }
                }
                replyClient(message,Constant.REPLY);
                System.out.println("本节点发送reply");
                defendVoteList.add("commit"+message.getNumber());

            }
        }
    }
    private void onReply(Message message) {
//        int msgNumber = message.getNumber();
//        String msgValue = message.getValue();
//        Map<String, Integer> voteValue = replyVoteList.get(msgNumber);
//        Set<String> voteKeySet = voteValue.keySet();
//        for (String voteKey : voteKeySet) {
//            Integer count = voteValue.get(voteKey);
//            int maxf=(NodeList.size())/3;
//            //&&voteKey==this.orgClientMessageValue
//            //这里不知道为啥只能用equals  voteKey.equals(this.orgClientMessageValue)
//            if(count>=maxf+1&&(voteKey.equals(this.getMessageValueCheckList().get(msgNumber)))){
//                timeTaskUtil.cancelTimeTask(msgNumber);
//                System.out.println("Client "+node+"接收到共识reply，共识完成！共实现息为"+message.getValue());
//                defendVoteList.add("reply"+message.getNumber());
//            }
//        }

    }
    private void onFind(Message message) {
        System.out.println(this.getNode()+" 找到目标拉！！！！");
//        String value="节点"+this.node+":我找到目标了！！！快来";
        Position position = new Position(this.getX(), this.getY());
        String value = JSON.toJSONString(position);
        timePositionTask.cancelTimeTask(this);
        Message msgClient = new Message();
        msgClient.setOriOrgNode(this.getNode());
        msgClient.setControllerType(Constant.FLYFINDSHARE);
        msgClient.setType(Constant.REQUEST);
        msgClient.setToNode(0);
        msgClient.setTime(LocalDateTime.now());
        msgClient.setOrgNode(this.getNode());
        //TODO 这个序号和控制台的序号不通用，所以之前造成过bug(在prepare阶段序号是0会被防住的)，所以我想弄个随机负数,
        Random random = new Random();
        int iii = random.nextInt(100000) + 1;
        msgClient.setNumber(-1*iii);
        msgClient.setView(this.getView());
        msgClient.setValue(value);
        //只在消息上弄客户端ip，端口
        msgClient.setClientIp(this.getIp());
        msgClient.setClientPort(this.getPort());
        int mainIndex = 0;
        try {
            sendUtil.sendNode(this.getNodeList().get(mainIndex).getIp(), this.getNodeList().get(mainIndex).getPort(), msgClient);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 功能性函数
     */

    /**
     * 广播其他节点
     * @param message
     * @param type
     * @throws IOException
     */
    public void sendAllNodes(Message message,int type) throws IOException {
        String msgValue= message.getValue();
        int number = message.getNumber();
        for (Node nodeElse : NodeList) {
            if(nodeElse.getNode()==this.node){
                //跳过自己广播
                System.out.println("跳过自己广播");
                continue;
            }
            Message messageSend = new Message();
            messageSend.setOriOrgNode(message.getOriOrgNode());
            messageSend.setControllerType(message.getControllerType());
            messageSend.setOrgNode(this.node);
            //根据记录表中数据，发送去向节点编号
            messageSend.setToNode(nodeElse.getNode());
            messageSend.setView(this.view);
            messageSend.setTime(LocalDateTime.now());
            messageSend.setValue(msgValue);
            messageSend.setType(type);
            messageSend.setNumber(number);
            messageSend.setClientIp(message.getClientIp());
            messageSend.setClientPort(message.getClientPort());
            String ipSend = nodeElse.getIp();
            int portSend = nodeElse.getPort();
            if(isGood){
                sendUtil.sendNode(ipSend,portSend,messageSend);
            }else{
                messageSend.setValue("坏节点的错误信息");
                sendUtil.sendNode(ipSend,portSend,messageSend);
            }
        }

    }


    /**
     * 回复外部客户端
     * @param message
     * @param type
     * @throws IOException
     */
    private void replyClient(Message message,int type) throws IOException {
        if(message.getControllerType()==Constant.TIMEOPERATION5){
            Status status = new Status(this.x, this.y,100);
            String statusStirng = JSON.toJSONString(status);
            message.setValue(statusStirng);
        }

        String msgValue= message.getValue();
        int number = message.getNumber();
        Message messageSend = new Message();
        messageSend.setControllerType(message.getControllerType());
        messageSend.setOriOrgNode(message.getOriOrgNode());
        messageSend.setOrgNode(this.node);
        //向客户端发送回复
        messageSend.setToNode(-1);

        messageSend.setView(this.view);
        messageSend.setTime(LocalDateTime.now());
        messageSend.setValue(msgValue);
        messageSend.setType(type);
        messageSend.setNumber(number);
        messageSend.setClientIp(message.getClientIp());
        messageSend.setClientPort(message.getClientPort());
        String ipSend = message.getClientIp();
        int portSend = message.getClientPort();
        if(isGood){
            sendUtil.sendNode(ipSend,portSend,messageSend);
        }else{
            messageSend.setValue("坏节点的错误信息");
            sendUtil.sendNode(ipSend,portSend,messageSend);
        }
    }

    /**
     * prepare阶段模拟自己给自己发送信息
     * @param msgNumber
     * @param msgValue
     */
    //并不是自己给自己发送网络数据包，而是将对应阶段的投票变为1，代表自己给自己投票
    private void prepareSendToSelf(int msgNumber,String msgValue) {
        if(!prepareVoteList.containsKey(msgNumber)){
            HashMap<String, Integer> mapValue = new HashMap<>();
            mapValue.put(msgValue,1);
            prepareVoteList.put(msgNumber,mapValue);
        }else{
            Map<String, Integer> mapValue = prepareVoteList.get(msgNumber);
            if(!mapValue.containsKey(msgValue)){
                //理论上给自己投票，只会进入该域，但是保险和懒起见，直接复制整个if语句
                mapValue.put(msgValue,1);
            }else{
                mapValue.put(msgValue,mapValue.get(msgValue)+1);
            }
        }
    }

    /**
     * commit阶段模拟自己给自己发送信息
     * @param msgNumber
     * @param msgValue
     */
    //并不是自己给自己发送网络数据包，而是将对应阶段的投票变为1，代表自己给自己投票
    private void commitSendToSelf(int msgNumber,String msgValue) {

        if(!commitVoteList.containsKey(msgNumber)){
            HashMap<String, Integer> mapValue = new HashMap<>();
            mapValue.put(msgValue,1);
            commitVoteList.put(msgNumber,mapValue);
        }else{
            Map<String, Integer> mapValue = commitVoteList.get(msgNumber);
            if(!mapValue.containsKey(msgValue)){
                //理论上给自己投票，只会进入该域，但是保险和懒起见，直接复制整个if语句
                mapValue.put(msgValue,1);
            }else{
                mapValue.put(msgValue,mapValue.get(msgValue)+1);
            }
        }

    }
    private void requestSendToSelf(int msgNumber, String msgValue) {
        if(!prepareVoteList.containsKey(msgNumber)){
            HashMap<String, Integer> mapValue = new HashMap<>();
            mapValue.put(msgValue,1);
            prepareVoteList.put(msgNumber,mapValue);
        }else{
            Map<String, Integer> mapValue = prepareVoteList.get(msgNumber);
            if(!mapValue.containsKey(msgValue)){
                //理论上给自己投票，只会进入该域，但是保险和懒起见，直接复制整个if语句
                mapValue.put(msgValue,1);
            }else{
                mapValue.put(msgValue,mapValue.get(msgValue)+1);
            }
        }
    }

    /**
     * prepare 投票阶段
     * @param msgNumber
     * @param msgValue
     */
    private void prepareVote(int msgNumber, String msgValue) {
        /**
         * {
         *     "1":{
         *         "yes":2,
         *         "no":1
         *     }
         * }
         * 所以以下if语句是记录投票的逻辑处理
         */
        if(!prepareVoteList.containsKey(msgNumber)){
            HashMap<String, Integer> mapValue = new HashMap<>();
            mapValue.put(msgValue,1);
            prepareVoteList.put(msgNumber,mapValue);
        }else{
            Map<String, Integer> mapValue = prepareVoteList.get(msgNumber);
            if(!mapValue.containsKey(msgValue)){
                mapValue.put(msgValue,1);
            }else{
                mapValue.put(msgValue,mapValue.get(msgValue)+1);
            }
        }
    }

    /**
     * commit 投票阶段
     * @param msgNumber
     * @param msgValue
     */
    private void commitVote(int msgNumber, String msgValue) {
        if(!commitVoteList.containsKey(msgNumber)){
            HashMap<String, Integer> mapValue = new HashMap<>();
            mapValue.put(msgValue,1);
            commitVoteList.put(msgNumber,mapValue);
        }else{
            Map<String, Integer> mapValue = commitVoteList.get(msgNumber);
            if(!mapValue.containsKey(msgValue)){
                mapValue.put(msgValue,1);
            }else{
                Integer add = mapValue.get(msgValue);
                mapValue.put(msgValue,add+1);
            }
        }
    }

    /**
     * 导入本地节点存储的其他节点信息
     * @throws FileNotFoundException
     */
    public void LoadNodes() throws FileNotFoundException {

        FileReader fileReader   = new FileReader("src/main/resources/Nodes.JSON");
        JSONReader reader = new JSONReader(fileReader);
        JSONObject jsonObject = (JSONObject) reader.readObject();
        List<JSONObject>list = (List<JSONObject>) jsonObject.get("Nodes");
        for (JSONObject object : list) {
            int nodeElse= (int) object.get("node");
            String ipElse=(String)object.get("ip");
            int portElse=(int)object.get("port");
            // 还是将包括本节点放到nodeList里面吧，后面广播时候也会广播到本节点，之后如果有其他要求不需要本节点，加if语句就可以
//            if(nodeElse!=this.node){
            NodeList.add(new Node(nodeElse,ipElse,portElse,0,0));
//            }
        }
    }


}