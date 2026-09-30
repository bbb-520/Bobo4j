package com.bbb.exercise.agentdemo1_0.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.bbb.exercise.agentdemo1_0.conversation")
public class PersistenceConfig {
}
