package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.starter.service.control.DeviceCapability;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 一台设备支持哪些控制能力。
 *
 * <h3>给谁用</h3>
 * 前端在渲染云台/预置位/重启等按钮前先取一次，把不支持的按钮<b>置灰并显示原因</b>，
 * 而不是让用户点完再收一个 400。
 *
 * <p>改造前这份信息只存在于 {@code ControlService} 的 31 个 {@code if} 分支里，
 * 前端无从得知 —— 只能「全都显示、点了再看报错」。
 *
 * <p>一次取回全部能力而不只返回「支持的列表」：
 * <b>不支持的项及其原因同样要展示</b>，否则用户看到按钮消失，会以为是自己找错地方。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceCapabilitiesVo {

    /** 接入方式：{@code gb28181} / {@code dahua}。 */
    private String kind;

    /** 支持的能力名（{@link DeviceCapability#name()}），供前端做集合判断。 */
    private List<String> supported;

    /** 不支持的项与原因。 */
    private List<Unsupported> unsupported;

    /** 一项不支持的能力。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Unsupported {
        /** 能力名。 */
        private String capability;
        /** 面向用户的能力中文名。 */
        private String label;
        /** 为什么做不到（厂商实现提供，可为空）。 */
        private String reason;
    }

    /**
     * @param kind        接入方式
     * @param supported   支持的能力集合
     * @param hintOf      取「不支持原因」的函数，由 {@code DeviceControl#unsupportedHint} 提供
     */
    public static DeviceCapabilitiesVo of(DeviceKind kind,
                                          java.util.Set<DeviceCapability> supported,
                                          Function<DeviceCapability, String> hintOf) {
        DeviceCapabilitiesVo vo = new DeviceCapabilitiesVo();
        vo.kind = kind == null ? null : kind.code();
        vo.supported = new ArrayList<>(supported.size());
        vo.unsupported = new ArrayList<>();
        for (DeviceCapability c : DeviceCapability.values()) {
            if (supported.contains(c)) {
                vo.supported.add(c.name());
                continue;
            }
            Unsupported u = new Unsupported();
            u.capability = c.name();
            u.label = c.label();
            u.reason = hintOf.apply(c);
            vo.unsupported.add(u);
        }
        return vo;
    }
}
