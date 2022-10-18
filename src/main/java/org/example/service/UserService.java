package org.example.service;

import org.apache.catalina.User;
import org.example.error.BusinessException;
import org.example.service.model.UserModel;

public interface UserService {
    UserModel getUserById(Integer id);

    void register(UserModel userModel) throws BusinessException;

    //通过缓存获取用户对象
    UserModel getUserByIdInCache(Integer id);

    /*
    telphone:用户注册手机
    encrptPassowrd:用户加密后的密码
     */
    UserModel validateLogin(String telphone, String encrptPassword) throws BusinessException;

}