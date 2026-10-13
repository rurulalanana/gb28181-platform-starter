package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbCascade;
import org.apache.ibatis.annotations.Mapper;

/** 级联配置表 Mapper。 */
@Mapper
public interface GbCascadeMapper extends BaseMapper<GbCascade> {
}
