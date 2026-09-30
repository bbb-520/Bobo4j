package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Runtime configuration for the image-editing provider. */
@ConfigurationProperties(prefix = "app.zine")
public class ZineProperties {

    private boolean enabled = true;
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String endpoint = "/services/aigc/multimodal-generation/generation";
    private String model = "qwen-image-3.0-pro";
    private String size = "1024*1707";
    private boolean promptExtend = true;
    private boolean watermark = false;
    private Duration timeout = Duration.ofSeconds(180);
    private int maxUploadBytes = 10 * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public boolean isPromptExtend() {
        return promptExtend;
    }

    public void setPromptExtend(boolean promptExtend) {
        this.promptExtend = promptExtend;
    }

    public boolean isWatermark() {
        return watermark;
    }

    public void setWatermark(boolean watermark) {
        this.watermark = watermark;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(int maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }
}
