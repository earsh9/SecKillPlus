package org.example.controller;

import com.google.common.util.concurrent.RateLimiter;
import org.apache.ibatis.executor.ExecutorException;
import org.example.error.BusinessException;
import org.example.error.EmBusinessError;
import org.example.mq.MqProducer;
import org.example.response.CommonReturnType;
import org.example.service.ItemService;
import org.example.service.OrderService;
import org.example.service.PromoService;
import org.example.service.model.OrderModel;
import org.example.service.model.UserModel;
import org.example.util.CodeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.*;


@Controller("order")
@RequestMapping("/order")
@CrossOrigin(origins = "http://localhost:63342", allowCredentials = "true")
public class OrderController extends BaseController {
    @Autowired
    private OrderService orderService;
    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    private RateLimiter orderCreateRateLimiter;

    @PostConstruct
    public void init(){
        executorService = Executors.newFixedThreadPool(20);     //生成大小为20的线程池
        orderCreateRateLimiter = RateLimiter.create(300);                //1 s 中允许通过 TPS 限制在 300
    }

    //生成验证码
    @RequestMapping(value = "/generateverifycode", method = {RequestMethod.GET,RequestMethod.POST})
    @ResponseBody
    public void generateverifycode(HttpServletResponse response) throws BusinessException, IOException {
        //根据 token 获取用户信息
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登录，不能生成验证码");
        }
        //获取用户的登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null){         //会话过期了
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登录，不能生成验证码");
        }

        //创建验证码图片并写到 HttpServletResponse 中并返回给前端用户
        Map<String,Object> map = CodeUtil.generateCodeAndPic();
        //将用户信息与验证码绑定到 redis 中
        redisTemplate.opsForValue().set("verify_code_"+userModel.getId(), map.get("code"));
        redisTemplate.expire("verify_code_"+userModel.getId(), 10, TimeUnit.MINUTES);
        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());
    }

    //生成秒杀令牌
    @RequestMapping(value = "/generatetoken", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name = "itemId") Integer itemId,
                                        @RequestParam(name = "promoId") Integer promoId,
                                        @RequestParam(name = "verifyCode") String verifyCode) throws BusinessException {         //promoId 必传，此时是为了产生 秒杀令牌
        //根据 token 获取用户信息
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登录，不能下单");
        }
        //获取用户的登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null){         //会话过期了
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登录，不能下单");
        }

        //通过verifyCode验证验证码的有效性
        String redisVerifyCode = (String) redisTemplate.opsForValue().get("verify_code_" + userModel.getId());
        if (StringUtils.isEmpty(redisVerifyCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"请求非法");
        }
        if (!redisVerifyCode.equalsIgnoreCase(verifyCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"请求非法，验证码错误");
        }

        //获取秒杀访问令牌
        String secondKillToken = promoService.generateSecondKillToken(promoId, itemId, userModel.getId());
        if (secondKillToken == null){                       //非法请求才会获得此异常
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"生成令牌失败");
        }

        return CommonReturnType.create(secondKillToken);
    }



    //封装下单请求
    @RequestMapping(value = "/createorder", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name = "itemId") Integer itemId,
                                        @RequestParam(name = "promoId",required = false) Integer promoId,           //不是必须的，为空就是以普通价格售卖
                                        @RequestParam(name = "amount") Integer amount,
                                        @RequestParam(name = "promoToken",required = false) String promoToken) throws BusinessException {
        if (!orderCreateRateLimiter.tryAcquire()){
            throw new BusinessException(EmBusinessError.RATE_LIMIT);
        }

        //获取用户登录信息
//        Boolean isLogin = (Boolean) httpServletRequest.getSession().getAttribute("IS_LOGIN");
        String token = httpServletRequest.getParameterMap().get("token")[0];

        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登录，不能下单");
        }
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
//        UserModel userModel = (UserModel) httpServletRequest.getSession().getAttribute("LOGIN_USER");
        if (userModel == null){         //会话过期了
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登录，不能下单");
        }

        //校验秒杀令牌是否正确
        if (promoToken != null){      //非活动商品可以没有秒杀令牌，但有令牌的我就要校验
            String inRedisPromoToken = (String) redisTemplate.opsForValue().get("promo_token_"+promoId+"_userid_"+userModel.getId()+"_itemid_"+itemId);
            if (inRedisPromoToken == null || !org.apache.commons.lang3.StringUtils.equals(promoToken, inRedisPromoToken)){   //前端传入与后端获取的秒杀令牌不一致
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"秒杀令牌校验失败");
            }
        }

//前置到 生成秒杀令牌时完成
//        //判断是否库存已售罄，若对应的售罄 key 存在，直接返回下单失败
//        if (redisTemplate.hasKey("promo_item_stock_invalid_" + itemId)){
//            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
//        }

        //同步调用线程池的 submit 方法
        //拥塞窗口为 20 的等待队列，用来队列化泄洪
        Future<Object> future = executorService.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                //加入库存流水 init 状态
                String stockLogId = itemService.initStockLog(itemId, amount);

                //再去完成对应的下单事务型消息机制
//        OrderModel orderModel = orderService.createOrder(userModel.getId(), itemId, promoId, amount);
                if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId)) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
                }
                return null;
            }
        });

        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }

        return CommonReturnType.create(null);
    }

}
