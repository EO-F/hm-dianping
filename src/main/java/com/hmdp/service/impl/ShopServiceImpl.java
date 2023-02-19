package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {

        Shop shop = queryWithMutex(id);

        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {

        Shop shop = null;
        try {
            String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            if(shopJson != null){
                return null;
            }

            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            while(!isLock){
                Thread.sleep(50);
                isLock = tryLock(LOCK_SHOP_KEY + id);
            }

            shop = getById(id);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(LOCK_SHOP_KEY + id);
        }

        return shop;
    }

    private void saveShop2Redis(Long id,Long expireSeconds){
        Shop shop = getById(id);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    public Shop queryWithLogicalExpire(Long id) {

        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);

        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                this.saveShop2Redis(id,20L);
                unlock(LOCK_SHOP_KEY + id);
            });
        }

        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithPassThrough(Long id) {

        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);;
        }

        if(shopJson != null){
            return null;
        }

        Shop shop = getById(id);
        if(shop == null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //直接返回Boolean进行拆箱成boolean可能会产生空指针，应该使用工具类进行判断
        return BooleanUtil.isTrue(false);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {

        Long id = shop.getId();

        if(id == null){
            return Result.fail("店铺id不能为空");
        }

        updateById(shop);

        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
