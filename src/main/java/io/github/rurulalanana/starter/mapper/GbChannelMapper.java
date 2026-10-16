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

    /**
     * 清空某通道的 {@code stream_id}（显式置 NULL）。
     *
     * <p><b>为什么必须用显式 UPDATE</b>：MyBatis-Plus 的 {@code updateById}
     * 默认按 {@code FieldStrategy.NOT_NULL} 生成 SQL，<b>null 字段会被整个跳过</b>，
     * 于是 {@code channel.setStreamId(null) + updateById} 实际什么都没改 ——
     * 通道会永久挂着一条指向已关闭会话的 {@code stream_id}，
     * 界面显示「有流」而实际点不开。实测踩过。
     *
     * @return 受影响行数（本来就是 NULL 的行不计入）
     */
    @Update("UPDATE gb_channel SET stream_id = NULL, update_time = NOW() "
            + "WHERE device_id = #{deviceId} AND channel_id = #{channelId} "
            + "AND stream_id IS NOT NULL")
    int clearStreamId(@Param("deviceId") String deviceId, @Param("channelId") String channelId);

    /** 清空某通道的 {@code stream_id}（仅按通道编码，跨设备可重复时慎用）。 */
    @Update("UPDATE gb_channel SET stream_id = NULL, update_time = NOW() "
            + "WHERE channel_id = #{channelId} AND stream_id IS NOT NULL")
    int clearStreamIdByChannel(@Param("channelId") String channelId);

    /**
     * 启动收敛：把离线设备的通道状态置 OFF 并清空 stream_id（单条 UPDATE）。
     *
     * <p>平台重启后内存会话全空，而 {@code status} / {@code stream_id} 是上次运行
     * 留下的库表数据。设备已离线时其通道必然没有流，这两个字段就是脏的
     * （实测有通道显示 ON 达数小时）。
     */
    @Update("<script>"
            + "UPDATE gb_channel SET status = 'OFF', stream_id = NULL, update_time = NOW() "
            + "WHERE (status IS DISTINCT FROM 'OFF' OR stream_id IS NOT NULL) "
            + "AND device_id IN "
            + "<foreach item='id' collection='deviceIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int resetByDeviceIds(@Param("deviceIds") List<String> deviceIds);

    /**
     * 启动收敛：清空这些设备下所有通道的 stream_id（单条 UPDATE）。
     *
     * <p>只清 {@code stream_id} 不动 {@code status}：设备在线时通道状态由
     * 真实的流上下线事件驱动，不该被启动流程改写。
     * 但 {@code stream_id} 指向的会话已随重启消失，必然过期，必须清掉 ——
     * 否则界面会显示「有流」而实际播不出来。
     */
    @Update("<script>"
            + "UPDATE gb_channel SET stream_id = NULL, update_time = NOW() "
            + "WHERE stream_id IS NOT NULL AND device_id IN "
            + "<foreach item='id' collection='deviceIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int clearStreamIdsByDeviceIds(@Param("deviceIds") List<String> deviceIds);
}
