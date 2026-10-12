package com.bbb.exercise.agentdemo.chatservice.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 聊天持久化配置，注册会话和消息的数据访问组件。 */
@Configuration
@MapperScan("com.bbb.exercise.agentdemo.chatservice.conversation")
public class PersistenceConfig {}
