package com.bbb.exercise.agentdemo.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bbb.exercise.agentdemo.auth.entity.AuthSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 登录会话的 MyBatis 数据访问接口，负责会话记录的基础持久化操作。 */
@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSessionEntity> {}
