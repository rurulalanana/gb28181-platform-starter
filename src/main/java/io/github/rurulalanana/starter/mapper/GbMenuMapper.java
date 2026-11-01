package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbMenu;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 菜单节点与种子版本的 Mapper。 */
@Mapper
public interface GbMenuMapper extends BaseMapper<GbMenu> {

    /** 读种子版本（没有记录时返回 {@code null}）。 */
    @Select("SELECT version FROM gb_menu_seed WHERE id = #{id}")
    String selectSeedVersion(@Param("id") String id);

    /** 写种子版本（幂等：已有记录则覆盖）。 */
    @Insert("INSERT INTO gb_menu_seed (id, version, update_time) VALUES (#{id}, #{version}, NOW()) "
            + "ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version, update_time = NOW()")
    int upsertSeedVersion(@Param("id") String id, @Param("version") String version);
}
