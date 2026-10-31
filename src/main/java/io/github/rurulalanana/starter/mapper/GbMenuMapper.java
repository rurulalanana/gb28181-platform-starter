package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbMenu;
import org.apache.ibatis.annotations.Mapper;

/** 菜单覆盖 Mapper。 */
@Mapper
public interface GbMenuMapper extends BaseMapper<GbMenu> {
}
