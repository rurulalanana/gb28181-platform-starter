package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbAcl;
import org.apache.ibatis.annotations.Mapper;

/** 接入控制（黑白名单）表 Mapper。 */
@Mapper
public interface GbAclMapper extends BaseMapper<GbAcl> {
}
