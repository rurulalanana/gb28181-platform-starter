package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 角色与角色权限 Mapper。 */
@Mapper
public interface GbRoleMapper extends BaseMapper<GbRole> {

    /** 某角色拥有的权限点编码。 */
    @Select("SELECT permission_code FROM gb_role_permission WHERE role_code = #{roleCode}")
    List<String> selectPermissions(@Param("roleCode") String roleCode);

    /** 清空某角色的权限点（保存时先删后插，避免逐条 diff）。 */
    @Delete("DELETE FROM gb_role_permission WHERE role_code = #{roleCode}")
    int deletePermissions(@Param("roleCode") String roleCode);

    /**
     * 写入一条角色权限。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而不是普通 INSERT：
     * 保存时前端可能重复提交同一个权限点，靠主键兜住比在 Java 里
     * 去重更省心（去重逻辑还得再维护一份权限点清单）。
     */
    @Insert("INSERT INTO gb_role_permission (role_code, permission_code) "
            + "VALUES (#{roleCode}, #{permissionCode}) ON CONFLICT DO NOTHING")
    int insertPermission(@Param("roleCode") String roleCode,
                         @Param("permissionCode") String permissionCode);

    /** 统计某角色被多少用户使用（删除前的占用检查）。 */
    @Select("SELECT COUNT(1) FROM gb_user WHERE ',' || REPLACE(role, ' ', '') || ',' LIKE '%,' || #{roleCode} || ',%'")
    long countUsersOfRole(@Param("roleCode") String roleCode);
}
