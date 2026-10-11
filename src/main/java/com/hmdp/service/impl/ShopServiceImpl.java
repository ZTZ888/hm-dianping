package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 查询逻辑
     * @param id
     * @return
     */
    @Override
    public Result queryByID(Long id) {
        // 解决缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id ,Shop.class ,id2->getById(id2) ,RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

        // 互斥锁解决缓存击穿


        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicTTL(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }


    /**
     * 互斥锁解决缓存击穿
     *         +
     * 写入空对象解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        // 先到redis中查询
        String shop = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // redis中查询得到有效信息，这里往下只剩""还有null
        if(StrUtil.isNotBlank(shop)){
            Shop shopBean = JSONUtil.toBean(shop, Shop.class);
            return shopBean;
        }
        // 判断返回是否为空值，如果为空值，继续往下查询，如果不为空值，则是骚扰信息，返回错误
        if(shop != null){
            return null;
        }
        String lockKey = null;
        Shop shopBean = null;
        try {
            // 获取锁
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            // 判断是否获取到锁
            if(!isLock) {
                // 失败，休眠，结束后再重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 成功，做第二次检查，redis缓存是否存在
            String shop1 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if(StrUtil.isNotBlank(shop1)){
                return JSONUtil.toBean(shop1, Shop.class);
            }

            // redis中无数据，到mysql中查询
            shopBean = getById(id);
            // 如果mysql中无数据
            if(shopBean == null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 把数据写入redis，预防下次查询，设置过期时间30分钟
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopBean), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        return shopBean;
    }

    /**
     * 手动更新
     * 使缓存和数据库数据统一
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 更新数据库数据
        updateById(shop);
        // 删除缓存
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    /**
     * 把shop还有逻辑过期时间存入redis
     * @param id,expireTime
     */
    public void savaShop2Redis(Long id, Long expireTime) throws InterruptedException {
        // 查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
