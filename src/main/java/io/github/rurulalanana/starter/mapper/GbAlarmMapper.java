package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbAlarm;
import org.apache.ibatis.annotations.Mapper;

/** 报警表 Mapper。 */
@Mapper
public interface GbAlarmMapper extends BaseMapper<GbAlarm> {
}
