package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 控制命令的下发结果。
 *
 * <p>{@code answered=false} 时 {@code result} 为空：多数设备对云台类命令不做应用层应答，
 * 这是正常情况，不代表失败。界面据此区分「设备说了 OK」「设备说了 ERROR」「设备没说话」，
 * 而不是一律显示成功。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ControlResultVo {

    /** 命令是否已发出（到 SIP 层）。 */
    private boolean sent;

    /** 设备是否回了 MANSCDP 应答。 */
    private boolean answered;

    /** 设备应答的执行结果：{@code OK} / {@code ERROR}；未答时为 {@code null}。 */
    private String result;

    /** 本次命令序号，可与抓包对照。 */
    private Integer sn;

    /** 升级类命令的会话 ID（设备在结果通知里原样带回）。 */
    private String sessionId;

    /** 给界面直接展示的说明。 */
    private String note;

    /** 构造「设备已答」。 */
    public static ControlResultVo answered(int sn, String result) {
        ControlResultVo vo = new ControlResultVo();
        vo.sent = true;
        vo.answered = true;
        vo.result = result;
        vo.sn = sn;
        vo.note = "OK".equalsIgnoreCase(result)
                ? "设备已受理（OK 仅表示命令被接受，动作是否生效以画面为准）"
                : "设备拒绝了该命令（" + result + "）";
        return vo;
    }

    /** 构造「设备未答」。 */
    public static ControlResultVo noAnswer(int sn, String note) {
        ControlResultVo vo = new ControlResultVo();
        vo.sent = true;
        vo.answered = false;
        vo.sn = sn;
        vo.note = note;
        return vo;
    }
}
