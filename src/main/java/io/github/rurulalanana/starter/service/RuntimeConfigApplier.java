package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.zlm.Gb28181MediaPlatform;
import io.github.rurulalanana.gb28181.sip.zlm.RecordingService;
import io.github.rurulalanana.gb28181.sip.zlm.ZlmMediaServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把运行时配置推送到媒体层（ZLM / 录像服务）。
 *
 * <p><b>为什么需要单独一层</b>：{@link RuntimeConfigService} 只负责
 * 「值从哪来、怎么校验、存哪里」，它不知道也不该知道这些值<b>被谁消费</b>。
 * 而那些消费方分两类：
 * <ul>
 *   <li><b>定时任务</b>（清理、对账、保活）—— 每次执行时现读，
 *       天然热更新，不需要本类；</li>
 *   <li><b>媒体层</b>（ZLM 的原生配置、录像分片时长）—— 只在上电/开录时读，
 *       必须<b>主动推送</b>新值，否则改了不生效。</li>
 * </ul>
 * 本类只处理第二类。
 *
 * <p>推送失败只告警不抛异常：配置已经在库里，下次启动会重新推送，
 * 不该因为 ZLM 暂时不可用而让「保存配置」这个接口失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigApplier {

    private final RuntimeConfigService config;
    private final Gb28181MediaPlatform platform;

    /**
     * 应用全部可推送配置。
     *
     * <p><b>不要用 {@code @PostConstruct}</b>：实测组件初始化顺序不确定 ——
     * 本类可能先于 {@code StreamEventBridge#attach()} 里的
     * {@code runtimeConfig.reload()} 执行，此时缓存里还是 yml 默认值，
     * 于是 ZLM 拿到的是 1800 而库里配的是 600（日志里两者前后矛盾，
     * 界面显示 600 但实际按 1800 录）。
     *
     * <p>改由 {@code StreamEventBridge#attach()} 在 reload 之后显式调用，
     * 顺序确定：先载入配置，再推送到媒体层。
     */
    public void applyAll() {
        applyRecordMaxSecond();
        applyStreamNoneReaderDelay();
    }

    /**
     * 录像分片时长。
     *
     * <p>推到 {@link RecordingService} 的运行时覆盖字段，
     * 下一次 {@code startRecording} 时读取 —— 正在录制的分片不受影响
     * （ZLM 在 {@code mk_recorder_start} 时就把 max_second 定下了）。
     */
    public void applyRecordMaxSecond() {
        RecordingService rs = platform.recordingService();
        if (rs == null) {
            log.debug("录像服务未就绪，跳过分片时长应用");
            return;
        }
        int seconds = config.getInt(ConfigKeys.RECORD_MAX_SECOND);
        try {
            rs.setRecordMaxSecond(seconds);
            log.info("已应用录像分片时长：{} 秒（下一次开始录像生效）", seconds);
        } catch (Exception e) {
            log.warn("应用录像分片时长失败: {}", e.getMessage());
        }
    }

    /**
     * 「无人观看」判定时长。
     *
     * <p>能热更新是因为 ZLM 在<b>每次判断</b>时才读这个配置
     * （不像端口号那样只在启动时读一次）。写入后对已在推的流也生效。
     */
    public void applyStreamNoneReaderDelay() {
        ZlmMediaServer media = platform.mediaServer();
        if (media == null || !media.isStarted()) {
            log.debug("媒体服务未就绪，跳过无人观看时长应用");
            return;
        }
        long ms = config.getLong(ConfigKeys.STREAM_NONE_READER_DELAY_MS);
        try {
            media.applyOption("general.streamNoneReaderDelayMS", String.valueOf(ms));
        } catch (Exception e) {
            log.warn("应用无人观看时长失败: {}", e.getMessage());
        }
    }

    /** 按 key 分发到对应的推送逻辑（保存配置后调用）。 */
    public void apply(String key) {
        switch (key) {
            case ConfigKeys.RECORD_MAX_SECOND -> applyRecordMaxSecond();
            case ConfigKeys.STREAM_NONE_READER_DELAY_MS -> applyStreamNoneReaderDelay();
            default -> {
                // 其余配置都是「任务执行时现读」，无需推送
            }
        }
    }
}
