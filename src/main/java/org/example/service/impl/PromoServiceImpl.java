package org.example.service.impl;

import org.example.dao.PromoDaoMapper;
import org.example.entity.PromoDao;
import org.example.error.BusinessException;
import org.example.error.EmBusinessError;
import org.example.service.ItemService;
import org.example.service.PromoService;
import org.example.service.UserService;
import org.example.service.model.ItemModel;
import org.example.service.model.PromoModel;
import org.example.service.model.UserModel;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
public class PromoServiceImpl implements PromoService {

    @Autowired
    private PromoDaoMapper promoDaoMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private UserService userService;

    //根据itemId获取即将开始的或者正在进行的活动
    @Override
    public PromoModel getPromoByItemId(Integer itemId) {

        //获取商品对应的秒杀信息
        PromoDao promoDao = promoDaoMapper.selectByItemId(itemId);

        //dao->model
        PromoModel promoModel = convertFromDataObject(promoDao);
        if (promoModel == null) {
            return null;
        }

        //判断当前时间是否秒杀活动即将开始或正在进行
//        DateTime now = new DateTime();
        if (promoModel.getStartDate().isAfterNow()) {
            promoModel.setStatus(1);
        } else if (promoModel.getEndDate().isBeforeNow()) {
            promoModel.setStatus(3);
        } else {
            promoModel.setStatus(2);
        }

        return promoModel;
    }

    //实际发布动作由运营同学在后台管理来完成，这里让 itemController 来完成调用
    @Override
    public void publishPromo(Integer promoId) {
        //通过活动 id 获得活动信息
        PromoDao promoDao = promoDaoMapper.selectByPrimaryKey(promoId);
        if (promoDao.getItemId() == null || promoDao.getItemId().intValue() == 0){      //活动不存在
            return;
        }
        /*
        * 根据 promo 表中的 item_id 获得商品信息（库存信息）
        * 无法区分活动商品的库存和普通商品的库存 -> 实际生产中是通过商品上下架的方式限制用户的购买行为
        * 本业务只实现秒杀商品的逻辑，--> 默认获取到的商品库存不会发生改变
        */
        ItemModel itemModel = itemService.getItemById(promoDao.getItemId());

        //将库存同步到 redis 中
        redisTemplate.opsForValue().set("promo_item_stock_" + itemModel.getId(), itemModel.getStock());     //K:活动对应的商品id; V:商品库存

        //将大闸的限制数字设置到 redis 内
        redisTemplate.opsForValue().set("promo_door_count_" + promoId, itemModel.getStock().intValue() * 5);
    }

    //生成秒杀令牌
    @Override
    public String generateSecondKillToken(Integer promoId, Integer itemId, Integer userId) {
        //判断是否库存已售罄，若对应的售罄 key 存在，直接返回下单失败
        if (redisTemplate.hasKey("promo_item_stock_invalid_" + itemId)){
            return null;
        }

        //1. 获取 promoModel
        //获取商品对应的秒杀信息
        PromoDao promoDao = promoDaoMapper.selectByPrimaryKey(promoId);

        //dao->model
        PromoModel promoModel = convertFromDataObject(promoDao);
        if (promoModel == null) {
            return null;
        }

        //判断当前时间是否秒杀活动即将开始或正在进行
        if (promoModel.getStartDate().isAfterNow()) {
            promoModel.setStatus(1);
        } else if (promoModel.getEndDate().isBeforeNow()) {
            promoModel.setStatus(3);
        } else {
            promoModel.setStatus(2);
        }

        //判断活动是否正在进行
        if (promoModel.getStatus() != 2){               //不在进行中的秒杀活动也不给生成 秒杀令牌
            return null;
        }

        //判断 item 信息是否存在
        ItemModel itemModel = itemService.getItemByIdInCache(itemId);               //改为到缓存里去拿
        if (itemModel == null) {
            return null;
        }

        //判断用户是否存在
        UserModel userModel = userService.getUserByIdInCache(userId);               //用户信息一样从缓存中拿
        if (userModel == null) {
            return null;
        }

        //获取秒杀大闸数量
        Long result = redisTemplate.opsForValue().increment("promo_door_count_" + promoId, -1);
        if (result < 0){
            return null;
        }

        //生成 token, 存入 redis，并设置过期时间为 5min
        String token = UUID.randomUUID().toString().replace("-","");
        //一个用户在同一时间对同一个秒杀活动中的一个商品的令牌生成
        redisTemplate.opsForValue().set("promo_token_"+promoId+"_userid_"+userId+"_itemid_"+itemId, token);
        redisTemplate.expire("promo_token_"+promoId+"_userid_"+userId+"_itemid_"+itemId, 5, TimeUnit.MINUTES);

        return token;
    }

    private PromoModel convertFromDataObject(PromoDao promoDao) {
        if (promoDao == null) {
            return null;
        }
        PromoModel promoModel = new PromoModel();
        BeanUtils.copyProperties(promoDao, promoModel);
        promoModel.setStartDate(new DateTime(promoDao.getStartDate()));
        promoModel.setEndDate(new DateTime(promoDao.getEndDate()));

        return promoModel;
    }
}
