package com.hmdp.service.impl;

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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();

    /**
     * 抢秒杀券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckollVouncher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 2.先判断当前秒杀券活动是否开始，是否结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 3.再判断该秒杀券是否还有库存
        if (voucher.getStock() < 1) {
            return Result.fail("秒杀已经被抢光了！");
        }
        // 6. 返回订单id
        Long id = UserHolder.getUser().getId();

        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + id);
        if(!lock.tryLock(5)) {
            return Result.fail("不许开挂，老老实实抢一单！");
        }
        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVouncherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    // 处理高并发情况，单独拎出来加锁
    public Result createVouncherOrder(Long voucherId) {
        // 4.判断该用户有无历史订单
        Long id = UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId)
                .eq("user_id", id)
                .count();
        if (count > 0) {
            return Result.fail("该用户已参与过该活动！");
        }
        // 5.返回数据库中修改券的状态

        // 5.1 库存减一
        // 因为数据库的修改是串行化，所以不用担心并发问题，这里把并发问题丢给数据库
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1 ")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        // 5.2 新增订单
        if (!success) {
            return Result.fail("排队中...");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(id);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }

}
