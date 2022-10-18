package org.example.service.impl;

import org.example.dao.OrderDaoMapper;
import org.example.dao.SequenceDaoMapper;
import org.example.dao.StockLogDaoMapper;
import org.example.entity.OrderDao;
import org.example.entity.SequenceDao;
import org.example.entity.StockLogDao;
import org.example.error.BusinessException;
import org.example.error.EmBusinessError;
import org.example.service.ItemService;
import org.example.service.OrderService;
import org.example.service.UserService;
import org.example.service.model.ItemModel;
import org.example.service.model.OrderModel;
import org.example.service.model.UserModel;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ItemService itemService;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderDaoMapper orderDaoMapper;

    @Autowired
    private SequenceDaoMapper sequenceDaoMapper;

    @Autowired
    private StockLogDaoMapper stockLogDaoMapper;

    @Override
    @Transactional
    public OrderModel createOrder(Integer userId, Integer itemId, Integer promoId, Integer amount, String stockLogId) throws BusinessException {        //商品校验&用户校验均在生成秒杀令牌处完成，减负。留着的是为了后续获取价格
        //1.校验下单状态，下单的商品是否存在，用户是否合法，购买数量是否正确
//        ItemModel itemModel = itemService.getItemById(itemId);
        ItemModel itemModel = itemService.getItemByIdInCache(itemId);               //改为到缓存里去拿
        if (itemModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "商品信息不存在");
        }
//
////        UserModel userModel = userService.getUserById(userId);
//        UserModel userModel = userService.getUserByIdInCache(userId);               //用户信息一样从缓存中拿
//        if (userModel == null) {
//            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "用户信息不存在");
//        }

        if (amount <= 0 || amount > 99) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "数量信息不存在");
        }
        //校验活动信息
//        if (promoId != null) {
//            //(1)校验对应活动是否存在这个适用商品
//            if (promoId.intValue() != itemModel.getPromoModel().getId()) {
//                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "活动信息不正确");
//                //(2)校验活动是否正在进行中
//            } else if (itemModel.getPromoModel().getStatus() != 2) {
//                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "活动还未开始");
//            }
//        }

        //2.落单减库存（比起支付减库存更能保证不会超卖）
        boolean result = itemService.decreaseStock(itemId, amount);
        if (!result) {
            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
        }

        //3.订单入库
        OrderModel orderModel = new OrderModel();
        orderModel.setUserId(userId);
        orderModel.setItemId(itemId);
        orderModel.setPromoId(promoId);
        orderModel.setAmount(amount);

        if (promoId != null) {                  //promoId 非空时使用活动价格，反之使用普通售卖价格
            orderModel.setItemPrice(itemModel.getPromoModel().getPromoItemPrice());
        } else {
            orderModel.setItemPrice(itemModel.getPrice());
        }

        orderModel.setOrderPrice(orderModel.getItemPrice().multiply(BigDecimal.valueOf(amount)));

        //生成交易流水号
        orderModel.setId(generateOrderNo());
        OrderDao orderDao = this.convertFromOrderModel(orderModel);
        orderDaoMapper.insertSelective(orderDao);
        //加上商品的销量
        itemService.increaseSales(itemId, amount);

        //设置库存流水状态为成功
        StockLogDao stockLogDao = stockLogDaoMapper.selectByPrimaryKey(stockLogId);
        if (stockLogDao == null){
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }
        stockLogDao.setStatus(2);
        stockLogDaoMapper.updateByPrimaryKeySelective(stockLogDao);

//        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
//            @Override
//            public void afterCommit() {             //在最后一个 Transactional 标签执行完之后再执行
//                //所有部分完成成功，再异步发送消息更新库存。同时设置回滚redis库存以防万一
//                boolean mqResult = itemService.asyncDecreaseStock(itemId, amount);
////                if (!mqResult){
////                    itemService.increaseStock(itemId, amount);          //消息发送失败，回滚redis库存。防止少卖
////                    throw new BusinessException(EmBusinessError.MQ_SEND_FAIL);
////                }
//            }
//        });

        //4.返回前端
        return orderModel;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    //不管该方法是否在事务中，都会开启一个新的事务，不管外部事务是否成功
    //最终都会提交掉该事务，为了保证订单号的唯一性，防止下单失败后订单号的回滚
    String generateOrderNo() {
        //订单有16位
        StringBuilder stringBuilder = new StringBuilder();
        //前8位为时间信息，年月日
        LocalDateTime now = LocalDateTime.now();
        String nowDate = now.format(DateTimeFormatter.ISO_DATE).replace("-", "");
        stringBuilder.append(nowDate);

        //中间6位为自增序列
        //获取当前sequence
        int sequence = 0;
        SequenceDao sequenceDao = sequenceDaoMapper.getSequenceByName("order_info");

        sequence = sequenceDao.getCurrentValue();
        sequenceDao.setCurrentValue(sequenceDao.getCurrentValue() + sequenceDao.getStep());     //从数据库中取出的sequence已经用掉了，自增以后再更新回对应的sequence以保证序列唯一
        sequenceDaoMapper.updateByPrimaryKeySelective(sequenceDao);
        //拼接
        String sequenceStr = String.valueOf(sequence);
        for (int i = 0; i < 6 - sequenceStr.length(); i++) {        //总共要拼接齐六位，先看缺几位给补零
            stringBuilder.append(0);
        }
        stringBuilder.append(sequenceStr);

        //最后两位为分库分表位,暂时不考虑
        stringBuilder.append("00");

        return stringBuilder.toString();
    }

    private OrderDao convertFromOrderModel(OrderModel orderModel) {
        if (orderModel == null) {
            return null;
        }
        OrderDao orderDao = new OrderDao();
        BeanUtils.copyProperties(orderModel, orderDao);
        return orderDao;
    }
}

