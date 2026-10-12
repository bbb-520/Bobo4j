package com.bbb.exercise.agentdemo.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bbb.exercise.agentdemo.auth.entity.AppUserEntity;
import org.apache.ibatis.annotations.Mapper;

/** 用户账户的 MyBatis 数据访问接口，支持账户查询及事务内行锁。 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUserEntity> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM app_user WHERE id=#{id} FOR UPDATE")
    AppUserEntity lockById(@org.apache.ibatis.annotations.Param("id") long id);
}
