package com.sayohno.marketing.identity.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IamUserMapper extends BaseMapper<IamUser> {
    @Select("SELECT session_version FROM iam_user WHERE id = #{userId}")
    Long sessionVersion(long userId);
}
