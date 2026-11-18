package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        // 判断是关注还是取关
        Long userId = UserHolder.getUser().getId();
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(id);
        // 关注，在中间表中新增关联数据
        String key = "follows:" + userId;
        if(isFollow){
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess = save(follow);
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        }else{
            // 取关，删除关联数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", id));
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollowed(Long id) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollow(Long bloggerId) {
        // 去redis中查到当前用户的关注列表
        Long userId = UserHolder.getUser().getId();
        String userKey = "follows:" + userId;
        // 再在redis中查到博主的关注列表
        String bloggerKey = "follows:" + bloggerId;
        // 做一个交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(userKey, bloggerKey);
        if(intersect == null ||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> data = intersect.stream()
                .map(Long::valueOf).collect(Collectors.toList());
        // 把交集返回
        List<UserDTO> users = userService.listByIds(data)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
