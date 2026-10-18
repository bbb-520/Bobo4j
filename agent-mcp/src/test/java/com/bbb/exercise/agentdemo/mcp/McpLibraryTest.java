package com.bbb.exercise.agentdemo.mcp;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.mcp.schema.ToolSchema;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class McpLibraryTest {
    @Test void libraryConfigurationConstructsWithoutAnOrchestratorOrDatabase() {
        new ApplicationContextRunner().withUserConfiguration(McpConfiguration.class)
                .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                .withPropertyValues("app.mcp.enabled=false","app.mcp.public-docs.tenants=")
                .run(context->{assertThat(context).hasNotFailed().hasSingleBean(McpConnectionManager.class);
                    var manager=context.getBean(McpConnectionManager.class);
                    assertThat(manager.tools(new ChatIdentity("tenant","user",true))).isEmpty();
                    assertThat(manager.status()).isEmpty();});
    }
    @Test void extractedCanonicalFingerprintPreservesExistingReviewedPins() {
        String input="{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"query\"],\"additionalProperties\":false}";
        assertThat(new ToolSchema(input).fingerprint()).isEqualTo("9c2fe9762111c1e93a19017a10e9403fb537083419ba45c46312aafe8b95aaab");
    }
}
