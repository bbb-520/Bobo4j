package com.bbb.exercise.agentdemo1_0.model;

import java.util.Locale;

public enum ModelCapability {
    CHAT,
    VISION,
    IMAGE,
    EMBEDDING;

    public static ModelCapability parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型能力不能为空");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
