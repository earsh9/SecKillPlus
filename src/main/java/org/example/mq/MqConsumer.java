package org.example.mq;

/*
* 定义 rocketmq 消息发送方 和 接收方
* */

import com.alibaba.fastjson.JSON;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.example.dao.ItemStockDaoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Component
public class MqConsumer {
    @Value("${mq.nameserver.addr}")            //从配置文件中读取值
    private String nameAdr;

    @Value("${mq.topicname}")
    private String topicName;

    @Autowired
    private ItemStockDaoMapper itemStockDaoMapper;

    private DefaultMQPushConsumer consumer;         //队列收到消息后就会 push 给 consumer

    @PostConstruct
    public void init() throws MQClientException {
        consumer = new DefaultMQPushConsumer("stock_consumer_group");
        consumer.setNamesrvAddr(nameAdr);
        consumer.subscribe(topicName, "*");     //可以消息过滤，这里全接收

        consumer.registerMessageListener(new MessageListenerConcurrently() {                //监听到消息过来后处理的逻辑
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext consumeConcurrentlyContext) {
                Message msg = list.get(0);
                String jsonString = new String(msg.getBody());
                Map map = JSON.parseObject(jsonString, Map.class);
                Integer itemId = (Integer) map.get("itemId");
                Integer amount = (Integer) map.get("amount");
                //数据库完成扣减
                int result = itemStockDaoMapper.decreaseStock(itemId, amount);

                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        consumer.start();
    }
}
