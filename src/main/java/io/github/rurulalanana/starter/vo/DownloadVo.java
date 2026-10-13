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

    /** 文件流下载地址。 */
    private String downloadUrl;
}
