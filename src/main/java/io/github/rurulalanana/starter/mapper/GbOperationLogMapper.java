package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbOperationLog;
import org.apache.ibatis.annotations.Mapper;

/** 操作日志表 Mapper。 */
@Mapper
public interface GbOperationLogMapper extends BaseMapper<GbOperationLog> {
}
