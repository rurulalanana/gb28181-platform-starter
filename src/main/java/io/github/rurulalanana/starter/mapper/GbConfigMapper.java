package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbConfig;
import org.apache.ibatis.annotations.Mapper;

/** 运行时配置表 Mapper。 */
@Mapper
public interface GbConfigMapper extends BaseMapper<GbConfig> {
}
