package com.bbb.exercise.agentdemo.chatservice.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.bbb.exercise.agentdemo.chatservice.conversation")
public class PersistenceConfig {
}
