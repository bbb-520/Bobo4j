package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class AiConfig {
    /** Loads the image-focused system prompt. */
    @Bean
    public String systemPrompt(@Value("classpath:system_prompt") Resource systemPromptResource) {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取系统提示词失败: classpath:system_prompt", e);
        }
    }
}
