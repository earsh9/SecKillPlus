package org.example.mq;

import com.alibaba.fastjson.JSON;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.example.dao.StockLogDaoMapper;
import org.example.entity.StockLogDao;
import org.example.error.BusinessException;
import org.example.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.Charset;
import java.util.*;

@Component
public class MqProducer {

    @Value("${mq.nameserver.addr}")            //从配置文件中读取值
    private String nameAdr;

    @Value("${mq.topicname}")
    private String topicName;

    private DefaultMQProducer producer;     //作为消息中间件的 producer 去使用

    private TransactionMQProducer transactionMQProducer;

    @Autowired
    private StockLogDaoMapper stockLogDaoMapper;

    @Autowired
    private OrderService orderService;

    @PostConstruct              //将类注入容器后执行的代码
    public void init() throws MQClientException {
        //初始化 mq producer
        producer = new DefaultMQProducer("producer_group"); //producer侧的group名没有实质意义，consumer侧会根据group名分组
        producer.setNamesrvAddr(nameAdr);
        producer.start();               //producer 获得 nameService 地址后开始去连接

        transactionMQProducer = new TransactionMQProducer("transaction_producer_group");
        transactionMQProducer.setNamesrvAddr(nameAdr);
        transactionMQProducer.start();

        transactionMQProducer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message message, Object args) {
                //返回值LocalTransactionState有三个状态：COMMIT_MESSAGE：将消息从prepare转换为commit状态；ROLLBACK_MESSAGE：撤回先前的prepare状态的消息；UNKNOW：状态未知，消息中间件继续维护，过段时间再来询问状态
                //正常要做的事情：创建订单
                Integer userId = (Integer) ((Map)args).get("userId");
                Integer itemId = (Integer) ((Map)args).get("itemId");
                Integer promoId = (Integer) ((Map)args).get("promoId");
                Integer amount = (Integer) ((Map)args).get("amount");
                String stockLogId = (String) ((Map)args).get("stockLogId");

                try {
                    orderService.createOrder(userId, itemId, promoId, amount, stockLogId);
                } catch (BusinessException e) {
                    e.printStackTrace();
                    //设置对应 stockLog 为回滚状态
                    StockLogDao stockLogDao = stockLogDaoMapper.selectByPrimaryKey(stockLogId);
                    stockLogDao.setStatus(3);
                    stockLogDaoMapper.updateByPrimaryKeySelective(stockLogDao);
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
                return LocalTransactionState.COMMIT_MESSAGE;
            }

            //消息默认状态是UNKNOW,即预防createOder()途中断电，而消息状态尚未更新时直接默认为 UNKNOW
            //所以有了如下定期 check 消息状态的方法（如果消息状态n久不更新，得去检查redis）
            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                //根据是否扣减库存成功，来判断要返回ROLLBACK_MESSAGE，COMMIT_MESSAGE 还是继续 UNKNOW
                String jsonString = new String(msg.getBody());
                Map<String, Object> map = JSON.parseObject(jsonString, Map.class);
                Integer itemId = (Integer) map.get("itemId");
                Integer amount = (Integer) map.get("amount");
                String stockLogId = (String) map.get("stockLogId");
                StockLogDao stockLogDao = stockLogDaoMapper.selectByPrimaryKey(stockLogId);
                if (stockLogDao == null){
                    return LocalTransactionState.UNKNOW;
                }
                if (stockLogDao.getStatus() == 2){
                    return LocalTransactionState.COMMIT_MESSAGE;
                }else if (stockLogDao.getStatus() == 1){
                    return LocalTransactionState.UNKNOW;
                }
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        });
    }

    //事务性同步库存扣减消息
    public boolean transactionAsyncReduceStock(Integer userId, Integer itemId, Integer promoId, Integer amount, String stockLogId){
        Map<String, Object> bodyMap = new HashMap<>();              //用于异步更新数据库的消息内容
        bodyMap.put("itemId", itemId);
        bodyMap.put("amount", amount);
        bodyMap.put("stockLogId", stockLogId);

        Map<String, Object> argsMap = new HashMap<>();              //用于创建订单的消息传递的内容
        argsMap.put("itemId", itemId);
        argsMap.put("amount", amount);
        argsMap.put("userId", userId);
        argsMap.put("promoId", promoId);
        argsMap.put("stockLogId", stockLogId);

        Message message = new Message(topicName, "increase",
                JSON.toJSON(bodyMap).toString().getBytes(Charset.forName("UTF-8")));
        TransactionSendResult sendResult = null;
        try {
            sendResult = transactionMQProducer.sendMessageInTransaction(message, argsMap);//发送事务性消息：1.发送消息为prepare状态而非可消费状态(此时消费者不可见),消息维护在broker中间件上 2.本地执行localTransaction
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        }
        return sendResult.getLocalTransactionState() == LocalTransactionState.COMMIT_MESSAGE;
    }


    //同步库存扣减消息
    public boolean asyncReduceStock(Integer itemId, Integer amount){        //返回值为是否消息发布成功
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("itemId", itemId);
        bodyMap.put("amount", amount);
        Message message = new Message(topicName, "increase",
                JSON.toJSON(bodyMap).toString().getBytes(Charset.forName("UTF-8")));
        try {
            producer.send(message);
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        } catch (RemotingException e) {
            e.printStackTrace();
            return false;
        } catch (MQBrokerException e) {
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
