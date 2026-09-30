package com.bbb.exercise.agentdemo1_0.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFlowContractTest {

    private static Path resource(String relativePath) {
        Path direct = Path.of("src", "main", "resources", relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("..", "src", "main", "resources", relativePath);
    }

    @Test
    void pageProvidesAKeyInputAndPersistsItThroughTheSettingsEndpoint() throws Exception {
        String html = Files.readString(resource("static/index.html"), StandardCharsets.UTF_8);
        String javascript = Files.readString(resource("static/app.js"), StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"qwen-api-key\"");
        assertThat(javascript).contains("/api/settings/keys");
        assertThat(javascript).contains("qwenApiKey");
        assertThat(javascript).contains("credentials: 'same-origin'");
    }

    @Test
    void globalOpenAiAutoConfigurationIsDisabledBecauseKeysAreUserScoped() throws Exception {
        String yaml = Files.readString(resource("application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains("org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration");
        assertThat(yaml).contains("org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration");
    }
}
