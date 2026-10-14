package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 录像下载信息。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DownloadVo {

    private String fileName;
    private String filePath;
    private Long fileSize;

    /**
     * 下载地址。
     *
     * <p>本地存储时为平台文件流接口
     * （{@code /api/v1/cloudrecord/file/{recordId}}，需携带登录态）；
     * 对象存储时为签名 URL 或公共读外链（可直接交给浏览器）。
     */
    private String downloadUrl;

    /** 存储类型：{@code LOCAL} / {@code OSS}。 */
    private String storageType;
}
