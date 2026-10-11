package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合，返回错误信息
            return Result.fail("手机号不合法");
        }
        // 符合，生产验证码
        String code = RandomUtil.randomNumbers(6);
        // key:手机号 value:验证码，存入redis中，设置有效期为2分钟，使用常量更加优雅
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone , code , LOGIN_CODE_TTL , TimeUnit.MINUTES);
        // 发送验证码
        log.info("发送验证码：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        // 1 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 1.1如果不符合，返回错误信息
            return Result.fail("手机号不合法");
        }

        // 2 校验验证码，改成从redis中校验
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode  == null || !cacheCode.equals(code)){
            // 2.1不一致，报错
            return Result.fail("验证码失效");
        }

        // 3 一致，到库里查询
        // mybatisplus的功能，可高效实现查询
        User user = query().eq("phone", phone).one();

        // 4 用户不存在，创建新用户并且返回
        if(user == null){
            user = createUserWithPhone(phone);
            log.info("存入mysql：{}",phone);
        }

        // 5 保存用户信息到redis中 采取hash的结构，key是随机Token，value就是用户信息
        // 5.1 随机生成一个token，作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 5.2 将user类型转换成hashmap类型
        UserDTO userDTO = BeanUtil.copyProperties(user , UserDTO.class);

        // 1.先转map，setIgnoreNullValue直接把原始null字段全部剔除，不会进editor
        Map<String, Object> tempMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
        );
        // 2.此时 tempMap里面已经没有value为null的键值对了
        Map<String,String> usermap = new HashMap<>();
        for (Map.Entry<String, Object> entry : tempMap.entrySet()) {
            usermap.put(entry.getKey(), entry.getValue().toString());
        }

        // 5.3 存储到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token , usermap);

        // 5.4 设置token有效期，只要30分钟不操作就踢出
        stringRedisTemplate.expire(LOGIN_USER_KEY + token , LOGIN_USER_TTL  , TimeUnit.SECONDS);

        // 5.4 把token返回到前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user1 = new User();
        user1.setPhone(phone);
        user1.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // MyBatis的功能
        boolean save = save(user1);
        log.info("保存成功了吗：{}",save);
        return user1;
    }
}
