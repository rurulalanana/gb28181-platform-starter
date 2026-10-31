package io.github.rurulalanana.starter.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.rurulalanana.starter.common.ApiResponse;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.entity.GbAcl;
import io.github.rurulalanana.starter.service.AccessControlService;
import io.github.rurulalanana.starter.vo.AclListVo;
import io.github.rurulalanana.starter.vo.AclVo;
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
 * 接入控制（黑白名单）接口（{@code /api/v1/acl/*}）。
 *
 * <pre>
 * GET  /api/v1/acl/list     查询规则（可按 list_type 过滤）
 * POST /api/v1/acl/add      新增
 * POST /api/v1/acl/remove   删除
 * GET  /api/v1/acl/stats    两种名单各自的条数（界面提示用）
 * </pre>
 *
 * <p><b>保存后立即生效</b>，不需要重启：服务内部维护一份内存快照，
 * 增删后整体替换，SIP 信令线程读到的永远是一份完整规则。
 *
 * <p>只有管理员能改：一条错误的黑名单足以让整个平台的设备全部掉线，
 * 这不该是普通运维随手能做的事。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/acl")
@RequiredArgsConstructor
public class AclController {

    private final AccessControlService aclService;

    /**
     * 查询规则。
     *
     * @param listType BLACK / WHITE；留空返回全部
     */
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
     * 新增规则（立即生效）。
     *
     * @param listType BLACK / WHITE
     * @param kind     DEVICE / PREFIX / IP / CIDR
     * @param value    匹配值
     * @param remark   备注（建议写明原因）
     */
    @PostMapping("/add")
    public ApiResponse<AclVo> add(
            @RequestParam(value = "list_type") String listType,
            @RequestParam String kind,
            @RequestParam String value,
            @RequestParam(required = false) String remark) {
        StpUtil.checkPermission(Permissions.SYSTEM_WRITE);
        return ApiResponse.ok(toVo(aclService.add(listType, kind, value, remark)));
    }

    /** 删除规则（立即生效）。 */
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
