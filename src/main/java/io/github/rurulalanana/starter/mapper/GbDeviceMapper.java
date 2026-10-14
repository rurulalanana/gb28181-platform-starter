package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 设备表 Mapper。 */
@Mapper
public interface GbDeviceMapper extends BaseMapper<GbDevice> {

    /**
     * 批量标记离线（单条 UPDATE）。
     *
     * <p>千台规模下逐台 {@code updateById} 是上千次往返，一次离线巡检要跑几分钟，
     * 事务长时间不释放会把落库线程一起拖住。
     */
    @Update("<script>"
            + "UPDATE gb_device SET online = false, status = 'OFF', update_time = NOW() "
            + "WHERE device_id IN "
            + "<foreach item='id' collection='deviceIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markOffline(@Param("deviceIds") List<String> deviceIds);
}
