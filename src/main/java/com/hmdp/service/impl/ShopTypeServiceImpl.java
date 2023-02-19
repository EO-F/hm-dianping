package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result listCacheType() {

        //若缓存中有数据，直接向缓存中进行获取
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0,-1);

        if(!shopTypeJson.isEmpty()){
            List<ShopType> typeList = new ArrayList<>();
            for(String str : shopTypeJson){
                ShopType shopType = JSONUtil.toBean(str, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }

        //若缓存中没有数据，直接查询数据库，并且加入到缓存当中
        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(ShopType::getSort);

        List<ShopType> shopTypes = list(queryWrapper);

        if(shopTypes == null || shopTypes.size() > 0){
            return Result.fail("没有该商品类型信息");
        }

        List<String> typeListJson = new ArrayList<>();

        for(ShopType shopType : shopTypes){
            String s = JSONUtil.toJsonStr(shopType);
            typeListJson.add(s);
        }

        stringRedisTemplate.opsForList().leftPushAll(CACHE_SHOP_TYPE_KEY,typeListJson);

        return Result.ok(shopTypes);
    }
}
