package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbPreset;
import org.apache.ibatis.annotations.Mapper;

/** 预置位表 Mapper。 */
@Mapper
public interface GbPresetMapper extends BaseMapper<GbPreset> {
}
