package com.bbb.exercise.agentdemo1_0.model;

import java.util.Locale;

/** Supported model families. Provider-specific HTTP details stay behind adapters. */
public enum ModelProvider {
    GPT,
    GEMINI,
    QWEN,
    GLM,
    HY;

    public static ModelProvider parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型 Provider 不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OPENAI" -> GPT;
            case "GOOGLE" -> GEMINI;
            case "DASHSCOPE" -> QWEN;
            case "HUNYUAN", "TENCENT" -> HY;
            default -> valueOf(normalized);
        };
    }
}
