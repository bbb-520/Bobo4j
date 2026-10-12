package com.bbb.exercise.agentdemo.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bbb.exercise.agentdemo.auth.entity.UserApiKeyEntity;
import org.apache.ibatis.annotations.Mapper;

/** 用户密钥记录的 MyBatis 数据访问接口。 */
@Mapper
public interface UserApiKeyMapper extends BaseMapper<UserApiKeyEntity> {}
