package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 对讲会话。
 *
 * <p>{@link #sampleRate} / {@link #channels} / {@link #format} 是<b>实测协商口径</b>：
 * 大华对讲固定 8 kHz / 16 bit / 单声道裸 PCM（见 {@code DahuaExtAccess#startTalk}
 * 里的 {@code NET_TALK_ENCODE_TYPE}）。前端必须按它重采样与建 AudioBuffer，
 * 不能硬编码 44.1 kHz —— 口径对不上听到的就是变调音（快放/慢放）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TalkSessionVo {

    /** 会话 ID，后续 {@code /talk/send} 与 {@code /talk/stop} 都要带。 */
    private String talkId;

    private String deviceId;

    /** 当前占用者（用于「谁在喊话」的提示）。 */
    private String owner;

    /** 上行采样率（Hz）：浏览器采集后必须重采样到这个值。 */
    private Integer sampleRate;

    /** 声道数：恒为 1。 */
    private Integer channels;

    /** 音频格式：{@code PCM16}（小端、有符号、单声道交织）。 */
    private String format;

    /**
     * 下行音频的 WebSocket 地址（含 talkId 与 token）。
     *
     * <p>按「本次请求的 Host」拼出。浏览器若发现主机名与页面不同（nginx 反代常见），
     * 应改用 {@code location.host} 重建 —— 与播放地址同一套处理。
     */
    private String wsUrl;
}
