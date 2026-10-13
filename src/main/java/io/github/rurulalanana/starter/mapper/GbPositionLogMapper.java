package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbPositionLog;
import org.apache.ibatis.annotations.Mapper;

/** 移动位置记录表 Mapper。 */
@Mapper
public interface GbPositionLogMapper extends BaseMapper<GbPositionLog> {
}
