package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbRecord;
import org.apache.ibatis.annotations.Mapper;

/** 云端录像表 Mapper。 */
@Mapper
public interface GbRecordMapper extends BaseMapper<GbRecord> {
}
