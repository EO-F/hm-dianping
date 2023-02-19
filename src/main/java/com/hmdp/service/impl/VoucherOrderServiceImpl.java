package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();

        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

//
//
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//
//        boolean isLock = lock.tryLock(1200);
//
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, voucher);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            return;
        }

        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq(" voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            return;
        }
        save(voucherOrder);


    }
}
