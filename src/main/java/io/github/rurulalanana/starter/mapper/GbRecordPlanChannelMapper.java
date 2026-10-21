package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbRecordPlanChannel;
import org.apache.ibatis.annotations.Mapper;

/** 录像计划-通道关联表 Mapper。 */
@Mapper
public interface GbRecordPlanChannelMapper extends BaseMapper<GbRecordPlanChannel> {
}
