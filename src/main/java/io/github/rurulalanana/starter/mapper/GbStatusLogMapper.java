package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbStatusLog;
import org.apache.ibatis.annotations.Mapper;

/** 状态变更记录表 Mapper。 */
@Mapper
public interface GbStatusLogMapper extends BaseMapper<GbStatusLog> {
}
