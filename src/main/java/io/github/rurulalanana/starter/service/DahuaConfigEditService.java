package io.github.rurulalanana.starter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.starter.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 大华 JSON 配置项的<b>通用开关编辑</b>。
 *
 * <h3>为什么需要这一层</h3>
 * 大华很多「开 / 关」能力都藏在 JSON 配置项的某个布尔字段里，
 * 而不是独立的 SDK 接口。用例：
 * <pre>
 *   布防 / 撤防    → Alarm       配置项的 Enable
 *   录像计划       → Record      配置项的 TimeSection（另有专门服务）
 * </pre>
 * 每个都写一遍「读全量 → 改字段 → 写回 → 回读」会得到一堆复制粘贴，
 * 且每处都可能漏掉回读校验，所以统一放这里。
 *
 * <h3>三条铁律（写在代码里，不靠自觉）</h3>
 * <ol>
 *   <li><b>先读后写</b>：配置是整段覆盖语义，凭空构造会把其余字段清零</li>
 *   <li><b>只写业务层</b>：{@link DahuaExtAccess#writeConfig} 会自动剥壳，
 *       但读的时候也要用业务层来解析，否则改的是一个根本不存在的层级</li>
 *   <li><b>写完回读</b>：{@code CLIENT_SetNewDevConfig} 返回 true 不代表生效 ——
 *       实测某型 IPC 对视角类字段就是这样，写了等于没写</li>
 * </ol>
 */
@Slf4j
@Service
public class DahuaConfigEditService {

    private final DahuaCapabilityService dahuaCapability;
    private final ObjectMapper mapper;

    public DahuaConfigEditService(DahuaCapabilityService dahuaCapability, ObjectMapper mapper) {
        this.dahuaCapability = dahuaCapability;
        this.mapper = mapper;
    }

    /**
     * 把某个配置项的<b>顶层布尔字段</b>设成指定值，并回读校验。
     *
     * @param configCommand 配置项名，如 {@code "Alarm"}
     * @param field         顶层字段名，如 {@code "Enable"}
     * @param value         目标值
     * @return 回读得到的实际值
     */
    public boolean setTopLevelBoolean(String deviceId, String channelCode,
                                      String configCommand, String field, boolean value) {
        var session = dahuaCapability.requireSession(deviceId);
        int sdkChannel = dahuaCapability.sdkChannel(session, deviceId, channelCode);

        ObjectNode root = readObject(session, configCommand, sdkChannel);
        if (!root.has(field)) {
            throw BizException.badRequest("配置项 " + configCommand + " 里没有字段 " + field
                    + "（该型号可能不支持这项设置）");
        }
        root.put(field, value);

        String payload;
        try {
            payload = mapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BizException.badRequest("配置序列化失败：" + e.getOriginalMessage());
        }
        try {
            dahuaCapability.ext().writeConfig(session, configCommand, sdkChannel, payload);
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("写入配置 " + configCommand, e);
        }

        // 回读校验：设备说成功但没改是常态
        ObjectNode after = readObject(session, configCommand, sdkChannel);
        boolean actual = after.path(field).asBoolean(false);
        if (actual != value) {
            throw BizException.badRequest("配置项 " + configCommand + "." + field
                    + " 已下发，但设备回读仍是 " + actual
                    + " —— 该型号可能把这项做成只读，请在设备 Web 界面设置");
        }
        log.info("大华配置已修改: device={} channel={} {}.{}={}",
                deviceId, channelCode, configCommand, field, value);
        return actual;
    }

    /**
     * 读配置项的业务层并解析成<b>可变</b>对象树。
     *
     * <p>必须可变：{@code mapper.readTree} 在字段是对象时会返回 {@code ObjectNode}，
     * 但顶层若被当成不可变节点就无法 {@code put}，所以这里显式操作 {@code ObjectNode}。
     */
    private ObjectNode readObject(io.github.rurulalanana.dahua.internal.DahuaSdk.Session session,
                                  String configCommand, int sdkChannel) {
        String json;
        try {
            json = dahuaCapability.ext().readConfig(session, configCommand, sdkChannel);
        } catch (DahuaSdkException e) {
            throw dahuaCapability.translate("读取配置 " + configCommand, e);
        }
        if (json == null || json.isBlank()) {
            throw BizException.badRequest("设备未返回配置项 " + configCommand
                    + "（该型号可能不支持）");
        }
        String business = DahuaExtAccess.businessLayer(json);
        JsonNode node;
        try {
            node = mapper.readTree(business);
        } catch (Exception e) {
            throw BizException.badRequest("配置项 " + configCommand
                    + " 的返回不是合法 JSON，拒绝写入");
        }
        if (!(node instanceof ObjectNode object)) {
            throw BizException.badRequest("配置项 " + configCommand + " 的业务层不是 JSON 对象，拒绝写入");
        }
        return object;
    }
}
