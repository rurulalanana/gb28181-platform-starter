package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbChannel;
import org.apache.ibatis.annotations.Mapper;

/** 通道表 Mapper。 */
@Mapper
public interface GbChannelMapper extends BaseMapper<GbChannel> {
}
