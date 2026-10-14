package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbStatusLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 状态变更记录表 Mapper。 */
@Mapper
public interface GbStatusLogMapper extends BaseMapper<GbStatusLog> {

    /** 批量写入状态变更记录（单条 INSERT；离线巡检一次可能产生上千条）。 */
    @Insert("<script>"
            + "INSERT INTO gb_status_log "
            + "(device_id, channel_id, old_status, new_status, description, reason, create_time) VALUES "
            + "<foreach item='it' collection='list' separator=','>"
            + "(#{it.deviceId}, #{it.channelId}, #{it.oldStatus}, #{it.newStatus}, "
            + "#{it.description}, #{it.reason}, #{it.createTime})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<GbStatusLog> list);
}
