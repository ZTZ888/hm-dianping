package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData<Object> shopRedisData = new RedisData<>();
        shopRedisData.setData(value);
        shopRedisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopRedisData));
    }

    public <R, ID>R queryWithPassThrough(String KeyPrefix, ID id , Class<R> type , Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = KeyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }
        // 4. 不存在，根据 id 查询数据库
        R r = dbFallback.apply(id);
        //5. 值不存在，返回错误
        if (r == null) {
            // 将空值写入 redis
            set(key, "" , CACHE_NULL_TTL , TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        //6. 存在，写入 redis
        set(key, r, time, unit);
        return r;
    }


    //线程池
    private static final ExecutorService CACHE_REBULID_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID>R queryWithLogicTTL(String keyPrefix, ID id, Class<R> type, Function<ID, R>dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果未命中，返回空
        if(StrUtil.isBlank(json)){
            return null;
        }
        // 如果命中，判断是否过期，未过期，获取值，返回
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject dataJson = (JSONObject) redisData.getData();
        R data = JSONUtil.toBean(dataJson, type);
        LocalDateTime timeExpired = redisData.getExpireTime();
        if(LocalDateTime.now().isBefore(timeExpired)){
            return data;
        }
        // 过期，获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 判断是否获取锁
        if(isLock){
            // 再次判断redis中数据是否过期
            if(LocalDateTime.now().isBefore(timeExpired)){
                return data;
            }
            // 是，开启独立线程，修改值
            CACHE_REBULID_EXECUTOR.submit(()->{
                try{
                    // 重建缓存
                    R newData = dbFallback.apply(id);
                    setWithLogicalExpire(key, newData, time, unit)    ;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放互斥锁
                    unlock(lockKey);
                }
            });
        }
        // 返回旧数据
        return data;
    }



    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
