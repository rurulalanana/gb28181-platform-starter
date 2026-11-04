package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.entity.GbAcl;
import io.github.rurulalanana.starter.service.AccessControlService;
import io.github.rurulalanana.starter.vo.AclListVo;
import io.github.rurulalanana.starter.vo.AclVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 接入控制（黑白名单）接口（{@code /api/v1/acl/*}）：list / add / remove / stats。
 * 保存后立即生效：服务内部维护内存快照，增删后整体替换，SIP 信令线程读到的永远是一份完整规则。
 * 只有管理员能改：一条错误的黑名单足以让全平台设备掉线。
 */
@Slf4j
@RestController
@Tag(name = "接入控制", description = "IP 黑白名单")
@RequestMapping("/api/v1/acl")
@RequiredArgsConstructor
public class AclController {

    private final AccessControlService aclService;

    /** 查询规则。{@code listType}：BLACK / WHITE，留空返回全部。 */
    @Operation(summary = "查询规则")
    @GetMapping("/list")
    public ApiResponse<AclListVo> list(
            @RequestParam(value = "list_type", required = false) String listType) {
        StpUtil.checkLogin();
        List<GbAcl> rows = aclService.list(listType);
        List<AclVo> list = new ArrayList<>();
        for (GbAcl row : rows) {
            list.add(toVo(row));
        }
        AclListVo vo = new AclListVo();
        // 列表接口统一带 xxxCount + xxxList（项目约定）
        vo.setAclCount(list.size());
        vo.setAclList(list);
        vo.setBlackCount(aclService.count(AccessControlService.BLACK));
        vo.setWhiteCount(aclService.count(AccessControlService.WHITE));
        return ApiResponse.ok(vo);
    }

    /**
     * 新增规则（立即生效）。{@code listType}：BLACK / WHITE；{@code kind}：DEVICE / PREFIX / IP / CIDR；
     * {@code value} 为匹配值；{@code remark} 建议写明原因。
     */
    @Operation(summary = "新增规则（立即生效）")
    @PostMapping("/add")
    public ApiResponse<AclVo> add(
            @RequestParam(value = "list_type") String listType,
            @RequestParam String kind,
            @RequestParam String value,
            @RequestParam(required = false) String remark) {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        return ApiResponse.ok(toVo(aclService.add(listType, kind, value, remark)));
    }

    @Operation(summary = "删除规则（立即生效）")
    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam Long id) {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        aclService.remove(id);
        return ApiResponse.ok();
    }

    private static AclVo toVo(GbAcl row) {
        AclVo vo = new AclVo();
        vo.setId(row.getId());
        vo.setListType(row.getListType());
        vo.setKind(row.getKind());
        vo.setValue(row.getValue());
        vo.setRemark(row.getRemark());
        vo.setCreateTime(row.getCreateTime() == null ? null
                : io.github.rurulalanana.starter.common.TimeFormats.toDisplay(row.getCreateTime()));
        return vo;
    }
}
