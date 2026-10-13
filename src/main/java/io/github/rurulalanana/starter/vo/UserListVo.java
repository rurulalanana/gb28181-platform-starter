package io.github.rurulalanana.starter.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 用户列表。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserListVo {

    /** 用户总数。 */
    private Integer userCount;

    /** 用户列表。 */
    private List<UserVo> userList;
}
