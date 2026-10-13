package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbCascadeChannel;
import org.apache.ibatis.annotations.Mapper;

/** 级联共享通道表 Mapper。 */
@Mapper
public interface GbCascadeChannelMapper extends BaseMapper<GbCascadeChannel> {
}
