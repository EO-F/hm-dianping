package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {

        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        UserDTO userDto = UserHolder.getUser();
        if(userDto == null){
            return Result.fail("需要登录");
        }
        Long nowUserId = userDto.getId();

        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, nowUserId.toString());
        blog.setIsLike(score != null);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {

        Long userId = UserHolder.getUser().getId();

        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {

        String key = "blog:liked:" + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTO = userService.query().in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTO);
    }

    @Override
    public Result saveBlog(Blog blog) {

        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        boolean isSuccess = save(blog);
        if(isSuccess){
            return Result.fail("保存笔记失败");
        }

        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        Long userId = UserHolder.getUser().getId();

        String key = "feed:" + userId;

        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>();
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        //TODO 查询blog有关的用户
        //TODO 查询blog是否被点赞

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);

        return Result.ok(r);
    }
}
