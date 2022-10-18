package org.example.service.impl;

import com.alibaba.druid.util.StringUtils;
import org.apache.catalina.User;
import org.example.dao.UserDaoMapper;
import org.example.dao.UserPasswordDaoMapper;
import org.example.entity.UserDao;
import org.example.entity.UserPasswordDao;
import org.example.error.BusinessException;
import org.example.error.EmBusinessError;
import org.example.service.UserService;
import org.example.service.model.UserModel;
import org.example.validator.ValidationResult;
import org.example.validator.ValidatorImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import java.util.concurrent.TimeUnit;


@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserDaoMapper userDaoMapper;

    @Autowired
    private UserPasswordDaoMapper userPasswordDaoMapper;

    @Autowired
    private ValidatorImpl validator;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public UserModel getUserById(Integer id) {
        //根据id获取得到对应的 UserDao 和 password，再进行合并封装后返回
        UserDao userDao = userDaoMapper.selectByPrimaryKey(id);
        if (userDao == null) {
            return null;
        }

        //通过用户id获取对应的用户加密密码信息
        UserPasswordDao userPasswordDao = userPasswordDaoMapper.selectByUserId(userDao.getId());

        return convertFromDataObject(userDao, userPasswordDao);
    }

    @Override
    @Transactional//声明事务
    public void register(UserModel userModel) throws BusinessException {
        //校验
//        if (userModel == null) {
//            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
//        }
//        if (StringUtils.isEmpty(userModel.getName())
//                || userModel.getGender() == null
//                || userModel.getAge() == null
//                || StringUtils.isEmpty(userModel.getTelphone())) {
//            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
//        }

        ValidationResult result = validator.validate(userModel);        //优化成让 hibernate-validator 帮助实现校验
        if (result.isHasErrors()) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, result.getErrMsg());
        }

        //实现model-> dao方法: dao 层不认 model；出去怎么封装的回来就得怎么解封装
        UserDao userDao = convertFromModel(userModel);
        //insertSelective相对于insert方法，不会覆盖掉数据库的默认值 (判空的字段跳过而不是放进去null，这样数据库的默认值就有用了)
        try {
            userDaoMapper.insertSelective(userDao);
        } catch (DuplicateKeyException ex) {        //手机号码出现唯一索引异常：注册时同一手机号收到不同验证码就能注册不同账号-->将手机号设为unique index
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "手机号已注册");
        }

        //需要 mapping 中设置 id 为自增id，再回过来设置给 userModel 以便于转发给对应的 userPassword
        userModel.setId(userDao.getId());

        //从 model 转回来的 dao 是俩，密码没有直接和用户信息放一起
        UserPasswordDao userPasswordDao = convertPasswordFromModel(userModel);
        userPasswordDaoMapper.insertSelective(userPasswordDao);

//        return;
    }

    //通过缓存获取用户对象
    @Override
    public UserModel getUserByIdInCache(Integer id) {
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get("user_validate_" + id);
        if (userModel == null){
            userModel = this.getUserById(id);
            redisTemplate.opsForValue().set("user_validate_" + id, userModel);
            redisTemplate.expire("user_validate_" + id, 10, TimeUnit.MINUTES);
        }
        return userModel;
    }

    @Override
    public UserModel validateLogin(String telphone, String encrptPassword) throws BusinessException {
        //通过用户手机获取用户信息
        UserDao userDao = userDaoMapper.selectByTelphone(telphone);
        if (userDao == null) {
            throw new BusinessException(EmBusinessError.USER_LOOGIN_FAIL);
        }
        UserPasswordDao userPasswordDao = userPasswordDaoMapper.selectByUserId(userDao.getId());
        UserModel userModel = convertFromDataObject(userDao, userPasswordDao);

        //比对用户信息内加密的密码是否和传输进来的密码相匹配
        if (!StringUtils.equals(encrptPassword, userModel.getEncrptPassword())) {
            throw new BusinessException(EmBusinessError.USER_LOOGIN_FAIL);
        }

        return userModel;
    }

    private UserPasswordDao convertPasswordFromModel(UserModel userModel) {
        if (userModel == null) {
            return null;
        }
        UserPasswordDao userPasswordDao = new UserPasswordDao();
        userPasswordDao.setEncrptPassword(userModel.getEncrptPassword());
        userPasswordDao.setUserId(userModel.getId());

        return userPasswordDao;
    }

    private UserDao convertFromModel(UserModel userModel) {
        if (userModel == null) {
            return null;
        }
        UserDao userDao = new UserDao();
        BeanUtils.copyProperties(userModel, userDao);
        return userDao;
    }

    private UserModel convertFromDataObject(UserDao userDao, UserPasswordDao userPasswordDao) {
        if (userDao == null) {
            return null;
        }

        UserModel userModel = new UserModel();
        BeanUtils.copyProperties(userDao, userModel);

        if (userPasswordDao != null) {
            userModel.setEncrptPassword(userPasswordDao.getEncrptPassword());
        }
        return userModel;
    }
}
