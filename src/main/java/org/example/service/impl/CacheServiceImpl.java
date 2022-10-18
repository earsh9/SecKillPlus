package org.example.service.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.example.service.CacheService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Service
public class CacheServiceImpl implements CacheService {

    private Cache<String, Object> commomCache = null;

    //spring 在加载类时优先执行当前方法
    @PostConstruct
    public void init(){
        commomCache = CacheBuilder.newBuilder()
                //设置缓存容器的初始容量：10
                .initialCapacity(10)
                //设置缓存中最大可以存储 100 个 KEY，超过后根据 LRU 策略移除缓存项
                .maximumSize(100)
                //设置写缓存后多少秒过期
                .expireAfterWrite(60, TimeUnit.SECONDS).build();
    }

    @Override
    public void setCommonCache(String key, Object value) {
        commomCache.put(key, value);
    }

    @Override
    public Object getFromCommonCache(String key) {
        return commomCache.getIfPresent(key);
    }
}
