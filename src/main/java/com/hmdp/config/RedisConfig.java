package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        // 添加redis单点地址，并未做集群，添加集群方法config.useClusterServers()
        config.useSingleServer().setAddress("redis://192.168.100.129:6379").setPassword("1242776548love");
        return Redisson.create(config);
    }

}
