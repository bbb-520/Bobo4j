package com.bbb.exercise.agentdemo.chatservice.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 聊天会话的 MyBatis 数据访问接口。 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {}
