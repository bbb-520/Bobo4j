package com.bbb.exercise.agentdemo1_0;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类。
 *
 * <p>技术栈：Spring Boot 4 + WebFlux（全响应式，故不能再引入 spring-boot-starter-web）+
 * Spring AI 2.0（OpenAI 兼容端点，本项目指向阿里百炼 qwen）+ Redis 会话记忆。
 *
 * <p>对外提供图片创作、会话、用户作品管理和 Bobo's World 公共分享入口：
 * <ul>
 *   <li>{@code /api/chat} —— 业务对话（SSE 流式）；</li>
 *   <li>{@code /api/bobo} —— 公开作品浏览和用户作品管理。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AgentDemo10Application {

    public static void main(String[] args) {
        SpringApplication.run(AgentDemo10Application.class, args);
    }

}
