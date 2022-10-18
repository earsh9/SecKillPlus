package org.example.service.impl;


import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.example.dao.ItemDaoMapper;
import org.example.dao.ItemStockDaoMapper;
import org.example.dao.StockLogDaoMapper;
import org.example.entity.ItemDao;
import org.example.entity.ItemStockDao;
import org.example.entity.StockLogDao;
import org.example.error.BusinessException;
import org.example.error.EmBusinessError;
import org.example.mq.MqProducer;
import org.example.service.ItemService;
import org.example.service.PromoService;
import org.example.service.model.ItemModel;
import org.example.service.model.PromoModel;
import org.example.validator.ValidationResult;
import org.example.validator.ValidatorImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ItemServiceImpl implements ItemService {

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ValidatorImpl validator;

    @Autowired
    private ItemDaoMapper itemDaoMapper;

    @Autowired
    private ItemStockDaoMapper itemStockDaoMapper;

    @Autowired
    private PromoService promoService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private StockLogDaoMapper stockLogDaoMapper;

    @Override
    @Transactional
    public ItemModel createItem(ItemModel itemModel) throws BusinessException {

        //校验入参
        ValidationResult result = validator.validate(itemModel);
        if (result.isHasErrors()) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, result.getErrMsg());
        }
        //转化itemmodel->dao
        ItemDao itemDao = this.convertItemDaoFromItemModel(itemModel);

        //写入数据库
        itemDaoMapper.insertSelective(itemDao);
        itemModel.setId(itemDao.getId());

        ItemStockDao itemStockDao = this.convertItemStockDaoFromItemModel(itemModel);
        itemStockDaoMapper.insertSelective(itemStockDao);

        //返回创建完成的对象
        return this.getItemById(itemModel.getId());     //直接通过id去数据库查一遍再回来
    }

    private ItemDao convertItemDaoFromItemModel(ItemModel itemModel) {
        if (itemModel == null) {
            return null;
        }
        ItemDao itemDao = new ItemDao();
        BeanUtils.copyProperties(itemModel, itemDao);
        return itemDao;
    }

    private ItemStockDao convertItemStockDaoFromItemModel(ItemModel itemModel) {
        if (itemModel == null) {
            return null;
        }
        ItemStockDao itemStockDao = new ItemStockDao();
        itemStockDao.setItemId(itemModel.getId());
        itemStockDao.setStock(itemModel.getStock());

        return itemStockDao;
    }

    @Override
    public List<ItemModel> listItem() {
        List<ItemDao> itemDaoList = itemDaoMapper.listItem();

        //使用Java8的stream API 聚合查到的 item 信息并返回
        List<ItemModel> itemModelList = itemDaoList.stream().map(ItemDao -> {
            ItemStockDao itemStockDao = itemStockDaoMapper.selectByItemId(ItemDao.getId());
            ItemModel itemModel = this.convertModelFromDataObject(ItemDao, itemStockDao);
            return itemModel;
        }).collect(Collectors.toList());

        return itemModelList;
    }

    @Override
    public ItemModel getItemById(Integer id) {      //传入的id是item_id，库存库中有个item_id 与之关联
        ItemDao itemDao = itemDaoMapper.selectByPrimaryKey(id);
        if (itemDao == null) {
            return null;
        }
        //操作获得库存数量 （通过 item_id）
        ItemStockDao itemStockDao = itemStockDaoMapper.selectByItemId(itemDao.getId());

        //将 dao-> Model
        ItemModel itemModel = convertModelFromDataObject(itemDao, itemStockDao);

        //获取活动商品信息(之前聚合了 promoModel)
        PromoModel promoModel = promoService.getPromoByItemId(itemModel.getId());
        if (promoModel != null && promoModel.getStatus().intValue() != 3) { //当秒杀活动状态不是结束状态时，可以将商品信息加上秒杀活动信息一起给前端展示
            itemModel.setPromoModel(promoModel);
        }
        return itemModel;
    }

    //item 及 promoModel 缓存模型：实现商品信息的缓存预热
    @Override
    public ItemModel getItemByIdInCache(Integer id){
        ItemModel itemModel = (ItemModel) redisTemplate.opsForValue().get("item_validate_" + id);
        if (itemModel == null){
            itemModel = this.getItemById(id);
            redisTemplate.opsForValue().set("item_validate_" + id, itemModel);
            redisTemplate.expire("item_validate_" + id, 10, TimeUnit.MINUTES);
        }
        return itemModel;
    }

    @Override
    @Transactional
    public boolean decreaseStock(Integer itemId, Integer amount) throws BusinessException {
//        int affectedRow = itemStockDaoMapper.decreaseStock(itemId, amount);     //sql语句update后返回受影响的行数，如果为0说明没有扣减成功；比起update后再去查询update前后商品数量是否有变化更高效
        Long result = redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount.intValue() * -1);//乘以 -1 相当于减掉库存
        //result 为执行完成后的结果
        if (result > 0) {
            //更新库存成功
            return true;
        } else if (result == 0){
            //打上 库存已售罄 标识
            redisTemplate.opsForValue().set("promo_item_stock_invalid_" + itemId, "true");
            return true;
        }else {
            increaseStock(itemId, amount);
            return false;
        }
    }

    //更新库存失败，redis回滚
    @Override
    public boolean increaseStock(Integer itemId, Integer amount) throws BusinessException {
        //不考虑redis本身失败的情况，固定返回 true
        redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount);
        return true;
    }

    //异步更新库存
    @Override
    public boolean asyncDecreaseStock(Integer itemId, Integer amount) {
        return mqProducer.asyncReduceStock(itemId, amount);
    }

    @Override
    @Transactional
    public void increaseSales(Integer itemId, Integer amount) throws BusinessException {
        itemDaoMapper.increaseSales(itemId,amount);
    }

    //初始化库存流水
    @Override
    @Transactional
    public String initStockLog(Integer itemId, Integer amount) {
        StockLogDao stockLogDao = new StockLogDao();
        stockLogDao.setItemId(itemId);
        stockLogDao.setAmount(amount);
        stockLogDao.setStockLogId(UUID.randomUUID().toString().replace("-",""));
        stockLogDao.setStatus(1);                   //1表示初始状态，2表示下单扣减库存成功，3表示下单回滚

        stockLogDaoMapper.insertSelective(stockLogDao);
        return stockLogDao.getStockLogId();
    }

    private ItemModel convertModelFromDataObject(ItemDao itemDao, ItemStockDao ItemStockDao) {
        ItemModel itemModel = new ItemModel();
        BeanUtils.copyProperties(itemDao, itemModel);
        itemModel.setStock(ItemStockDao.getStock());
        return itemModel;
    }
}
