package com.bbb.exercise.agentdemo1_0;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.ai.chat.memory.repository.redis.initialize-schema=false"
})
class AgentDemo10ApplicationTests {

    @Test
    void contextLoads() {
    }

}
