package com.bbb.exercise.agentdemo.api.model;

import java.util.Locale;

/** 模型能力分类，用于选择聊天、视觉、图片生成、检索及评估路由。 */
public enum ModelCapability {
    CHAT,
    VISION,
    IMAGE,
    EMBEDDING,
    RERANK,
    EVALUATION,
    FALLBACK,
    VISION_FALLBACK;

    public static ModelCapability parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型能力不能为空");
        }

        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
