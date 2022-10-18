package org.example.service;

import org.example.error.BusinessException;
import org.example.service.model.OrderModel;

public interface OrderService {

    //1.通过url上传过来秒杀活动id，然后下单接口内校验对应id是否属于对应商品且活动已开始 (可以适应不同途径(app)来的秒杀活动，可扩展性好)
    //2.直接在下单接口内判断对应的商品是否存在秒杀活动，若存在进行中的则以秒杀价格下单
    //倾向于使用第一种形式，因为对同一个商品可能存在不同的秒杀活动，而且第二种方案普通销售的商品也需要校验秒杀
    OrderModel createOrder(Integer userId, Integer itemId, Integer promoId, Integer amount, String stockLogId) throws BusinessException;
}
