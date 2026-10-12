package com.bbb.exercise.agentdemo.api.model;

import java.util.Locale;

/** 受支持的模型供应商标识，提供统一的配置值解析入口。 */
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
