package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String code = RandomUtil.randomNumbers(6);

        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送短信验证码成功，验证码为{}", code);

        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();

        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式错误");
        }

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();

        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }

        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone,phone);
        User user = getOne(queryWrapper);

        if(user == null){
            user = createUserWithPhone(phone);
        }

        String token = UUID.randomUUID().toString();

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);

        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MILLISECONDS);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;

        int dayOfMonth = now.getDayOfMonth();
        Boolean isSuccess = stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {

        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;

        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if((num & 1) == 0){
                break;
            }else {
                count++;
            }
            num >>>= 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {

        User user = new User();

        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        save(user);
        return user;
    }
}
