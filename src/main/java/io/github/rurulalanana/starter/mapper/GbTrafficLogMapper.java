package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbTrafficLog;
import org.apache.ibatis.annotations.Mapper;

/** 流量统计表 Mapper。 */
@Mapper
public interface GbTrafficLogMapper extends BaseMapper<GbTrafficLog> {
}
