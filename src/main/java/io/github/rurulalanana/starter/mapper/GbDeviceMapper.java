package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbDevice;
import org.apache.ibatis.annotations.Mapper;

/** 设备表 Mapper。 */
@Mapper
public interface GbDeviceMapper extends BaseMapper<GbDevice> {
}
