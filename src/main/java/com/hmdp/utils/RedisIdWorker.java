package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    // 2026-01-01 0:0:0的时间戳
    private static final long BEGIN_TIMESTAMP = 1767225600L;
    // 序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyprefix){
        // 第一部分：31位时间戳 -- 拿下单的时候时间戳-BEGIN_TIMESTAMP
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowEpochSecond - BEGIN_TIMESTAMP;

        // 第二部分：32位序列号 key:icr + 业务字段 + 当天的日期
        String day = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr" + keyprefix + ":" + day);

        // 拼接
        long id = timeStamp << COUNT_BITS | count;

        return id;
    }


}
