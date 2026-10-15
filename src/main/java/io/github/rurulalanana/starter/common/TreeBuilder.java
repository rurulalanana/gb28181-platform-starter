package io.github.rurulalanana.starter.common;

import io.github.rurulalanana.starter.entity.GbChannel;
import io.github.rurulalanana.starter.vo.TreeNodeVo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通道树 / 设备树组装工具。
 *
 * <p>GBS 的 {@code channeltree} 返回扁平节点列表（懒加载）或带 {@code children} 的树，
 * 由 {@code subfetch} 参数控制；本类负责按 {@code parentId} 组装。
 */
public final class TreeBuilder {

    private TreeBuilder() {
    }

    /** 通道节点：{@code id} 形如 {@code <设备编号>:<通道编号>}。 */
    public static TreeNodeVo node(GbChannel channel, List<TreeNodeVo> children) {
        TreeNodeVo vo = new TreeNodeVo();
        String code = channel.getChannelId();
        vo.setId(channel.getDeviceId() + ":" + code);
        vo.setName(channel.displayName());
        vo.setSerial(channel.getDeviceId());
        vo.setCode(code);
        vo.setStatus(channel.isOnline() ? "ON" : "OFF");
        vo.setParental(channel.getParental() != null && channel.getParental() > 0);
        vo.setPtzType(channel.getPtzType());
        vo.setManufacturer(channel.getManufacturer());
        vo.setParentId(channel.getParentId());
        vo.setSubCount(children == null ? 0 : children.size());
        vo.setChildren(children == null || children.isEmpty() ? null : children);
        return vo;
    }

    /** 设备节点（通道树的顶级节点）。 */
    public static TreeNodeVo deviceNode(String deviceId, String name, String status,
                                        int channelCount, long onlineCount,
                                        List<TreeNodeVo> children) {
        TreeNodeVo vo = new TreeNodeVo();
        vo.setId(deviceId);
        vo.setName(name);
        vo.setSerial(deviceId);
        vo.setStatus(status);
        vo.setSubCount(channelCount);
        vo.setSubCountDevice(channelCount);
        vo.setOnlineSubCount(onlineCount);
        vo.setChildren(children == null || children.isEmpty() ? null : children);
        return vo;
    }

    /** 分组节点。 */
    public static TreeNodeVo groupNode(String groupCode, String groupName,
                                       List<TreeNodeVo> children) {
        TreeNodeVo vo = new TreeNodeVo();
        vo.setId(groupCode);
        vo.setName(groupName);
        vo.setSerial(groupCode);
        vo.setStatus("ON");
        vo.setSubCount(children == null ? 0 : children.size());
        vo.setChildren(children == null || children.isEmpty() ? null : children);
        return vo;
    }

    /**
     * 按 {@code parentId} 组装通道树。
     *
     * @param rootParentId 根节点的 parentId（通常为设备编号）
     * @param all          全部通道
     * @return 根节点列表
     */
    public static List<TreeNodeVo> build(String rootParentId, List<GbChannel> all) {
        Map<String, List<GbChannel>> byParent = new LinkedHashMap<>();
        for (GbChannel c : all) {
            byParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        byParent.values().forEach(list -> list.sort(Comparator.comparing(
                c -> c.getChannelNo() == null ? 0 : c.getChannelNo())));
        return buildLevel(rootParentId, byParent, new HashSet<>());
    }

    /**
     * 递归组装一层。
     *
     * <p><b>必须带 {@code visited} 做环检测</b>：直连摄像机（IPC）的通道是
     * 「设备自身即通道」，其 {@code parentId == channelId}，纯递归会
     * 自己找自己、无限下钻直到 {@link StackOverflowError}。
     * 同理设备上报的 {@code ParentID} 也可能成环。
     */
    private static List<TreeNodeVo> buildLevel(String parentId,
                                               Map<String, List<GbChannel>> byParent,
                                               Set<String> visited) {
        List<TreeNodeVo> out = new ArrayList<>();
        for (GbChannel c : byParent.getOrDefault(parentId, List.of())) {
            String code = c.getChannelId();
            // 自引用（自身即通道）或已出现过 → 作为叶子节点，不再下钻
            if (code == null || code.equals(parentId) || !visited.add(code)) {
                out.add(node(c, null));
                continue;
            }
            out.add(node(c, buildLevel(code, byParent, visited)));
        }
        return out;
    }
}
