package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryTypeList() {
        // 先到Redis中查询分类列表
        List<String> cacheList = stringRedisTemplate.opsForList().range(RedisConstants.SHOP_TYPE_KEY, 0, -1);
        // Redis中有数据，直接返回
        if(cacheList != null && !cacheList.isEmpty()){
            return Result.ok(cacheList.stream()
                                        .map(string -> JSONUtil.toBean(string, ShopType.class))
                                        .collect(Collectors.toList()));
        }
        // Redis中没有数据，去mysql中查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // mysql中无数据，返回fail
        if(shopTypeList == null || shopTypeList.isEmpty()){
            return Result.fail("查询不到该数据");
        }
        // mysql中有数据，把数据写入Redis
        List<String> list1Json = shopTypeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.SHOP_TYPE_KEY , list1Json);
        // 返回list数据
        return Result.ok(shopTypeList);
    }
}
