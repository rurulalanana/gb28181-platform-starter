package io.github.rurulalanana.starter.config;

import cn.dev33.satoken.stp.StpInterface;
import io.github.rurulalanana.starter.entity.GbUser;
import io.github.rurulalanana.starter.mapper.GbUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 权限数据源。
 *
 * <p>角色取自 {@code gb_user.role}（逗号分隔），权限暂不细分——
 * 平台规模小，管理员 / 普通用户两级足够，避免维护一整套权限表。
 */
@Component
@RequiredArgsConstructor
public class SaTokenPermissionProvider implements StpInterface {

    private final GbUserMapper userMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return new ArrayList<>();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return new ArrayList<>();
        }
        long userId;
        try {
            userId = Long.parseLong(String.valueOf(loginId));
        } catch (NumberFormatException e) {
            return new ArrayList<>();
        }
        GbUser user = userMapper.selectById(userId);
        if (user == null || user.getRole() == null || user.getRole().isBlank()) {
            return new ArrayList<>();
        }
        List<String> roles = new ArrayList<>();
        Arrays.stream(user.getRole().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(roles::add);
        return roles;
    }
}
