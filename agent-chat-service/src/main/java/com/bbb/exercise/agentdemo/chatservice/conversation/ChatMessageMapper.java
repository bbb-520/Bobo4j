package com.bbb.exercise.agentdemo.chatservice.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 聊天消息的 MyBatis 数据访问接口。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {}
