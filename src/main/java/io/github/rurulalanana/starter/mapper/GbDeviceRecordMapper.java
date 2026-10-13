package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbDeviceRecord;
import org.apache.ibatis.annotations.Mapper;

/** 设备侧录像目录表 Mapper。 */
@Mapper
public interface GbDeviceRecordMapper extends BaseMapper<GbDeviceRecord> {
}
