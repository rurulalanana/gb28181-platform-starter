package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbUser;
import org.apache.ibatis.annotations.Mapper;

/** 用户表 Mapper。 */
@Mapper
public interface GbUserMapper extends BaseMapper<GbUser> {
}
