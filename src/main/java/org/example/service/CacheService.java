package org.example.service;

/*
* 用于实现 本地缓存 的接口
* */

public interface CacheService {

    //将 K-V 存在 cache 中
    void setCommonCache(String key, Object value);

    //取方法
    Object getFromCommonCache(String key);
}
