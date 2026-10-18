package com.bbb.exercise.agentdemo.contentservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Content owns upload/composition limits; all supplier settings live in Media. */
@ConfigurationProperties(prefix = "app.zine")
public class ZineCompositionProperties {
    private int maxUploadBytes = 20 * 1024 * 1024;
    public int getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(int value) {
        if (value < 1 || value > 20 * 1024 * 1024) throw new IllegalArgumentException("Zine upload limit must be at most 20 MiB");
        maxUploadBytes = value;
    }
}
