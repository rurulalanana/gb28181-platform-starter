package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 通道表 Mapper。 */
@Mapper
public interface GbChannelMapper extends BaseMapper<GbChannel> {

    /**
     * 刷新某设备下所有通道的状态（单条 UPDATE）。
     *
     * <p>注册 / 心跳 / 离线巡检都会调用，千台规模下必须避开「先查再逐条改」的 N+1。
     *
     * @return 受影响行数（状态本来就一致的行不计入）
     */
    @Update("UPDATE gb_channel SET status = #{status}, update_time = NOW() "
            + "WHERE device_id = #{deviceId} AND status IS DISTINCT FROM #{status}")
    int updateStatusByDeviceId(@Param("deviceId") String deviceId, @Param("status") String status);

    /** 批量把设备下的通道标记为离线（单条 UPDATE）。 */
    @Update("<script>"
            + "UPDATE gb_channel SET status = 'OFF', update_time = NOW() "
            + "WHERE status IS DISTINCT FROM 'OFF' AND device_id IN "
            + "<foreach item='id' collection='deviceIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int offlineByDeviceIds(@Param("deviceIds") List<String> deviceIds);
}
