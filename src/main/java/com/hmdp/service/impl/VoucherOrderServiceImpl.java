package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;

    // 一个用户id对应一个锁对象
    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();

    private static final ExecutorService SECKILL_ORDER_EXECUTORS = Executors.newSingleThreadExecutor();

    @PostConstruct  // 该注解可以使当前方法在类初始化时开启
    private void init(){
        // 要求线程任务在用户提交之前就开启，也就是在当前类初始化时开启
        SECKILL_ORDER_EXECUTORS.submit(new VoucherOrderHandler());
    }


    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";

        @Override
        public void run() {
            while(true){
                try {
                    // 获取信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 获取失败，继续下次循环
                    if(list == null || list.isEmpty()){
                        continue;
                    }

                    // 获取成功，处理信息
                    MapRecord<String, Object, Object> data = list.get(0);
                    Map<Object, Object> value = data.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", data.getId());
                } catch (Exception e) {
                    log.info("处理异常方法：{}", e);
                    handlePendingList();
                }
            }
        }

        // 抛异常，在pending list中处理信息
        private void handlePendingList(){
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> data = list.get(0);
                    Map<Object, Object> value = data.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", data.getId());
                } catch (Exception e) {
                    log.error("处理pendingList中的订单异常：{}", e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }


    // 在主线程中获取代理对象
    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean tryLock = lock.tryLock();
        if(!tryLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVouncherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    /**
     * 抢秒杀券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckollVouncher(Long voucherId) throws InterruptedException {
        // 查询优惠券
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 先判断当前秒杀券活动是否开始，是否结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        // 执行lua脚本，去做库存预扣
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                String.valueOf(voucherId),
                String.valueOf(userId),
                String.valueOf(orderId)
        );
        // 不为0，没有购买资格
        int res = result.intValue();
        if(res != 0){
            return Result.fail(res == 1?"手慢了，秒杀券已经被抢光了！":"一个用户只可以抢一张秒杀券！");
        }
        // 为0
        // proxy
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckollVouncher(Long voucherId) throws InterruptedException {
        // 查询优惠券
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 先判断当前秒杀券活动是否开始，是否结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        // 执行lua脚本，去做库存预扣
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                String.valueOf(voucherId),
                String.valueOf(userId)
        );
        // 不为0，没有购买资格
        int res = result.intValue();
        if(res != 0){
            return Result.fail(res == 1?"手慢了，秒杀券已经被抢光了！":"一个用户只可以抢一张秒杀券！");
        }
        // 为0
        long orderId = redisIdWorker.nextId("order");
        // proxy
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 封装一个voucher对象，保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        boolean offer = orderTasks.offer(voucherOrder);
        if(!offer){
            return Result.fail("系统繁忙，请稍后再试");
        }

        // 返回订单id
        return Result.ok(orderId);
    }*/

    @Transactional
    public void createVouncherOrder(VoucherOrder voucherOrder) {
        // 按理来说不用再去判断了，但是这里再做一个兜底
        int count = query().eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if(count > 0){
            log.error("禁止开挂，只需购买一单！");
            return;
        }

        // 返回数据库中修改券的状态

        // 库存减一
        // 因为数据库的修改是串行化，所以不用担心并发问题，这里把并发问题丢给数据库
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1 ")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("排队中...");
            return ;
        }
        // 新增订单
        save(voucherOrder);
    }
}
