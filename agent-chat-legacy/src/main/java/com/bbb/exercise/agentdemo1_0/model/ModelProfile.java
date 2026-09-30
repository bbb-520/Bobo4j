package com.bbb.exercise.agentdemo1_0.model;

/** Public-safe model selection; secret material is intentionally absent. */
public record ModelProfile(ModelProvider provider,
                           ModelCapability capability,
                           String model,
                           boolean enabled,
                           String maskedApiKey) {

    public ModelProfile {
        if (provider == null || capability == null) {
            throw new IllegalArgumentException("Provider 和模型能力不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        model = model.trim();
    }

    public static String maskSecret(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() <= 8) return "••••••••";
        return trimmed.substring(0, 4) + "••••••••" + trimmed.substring(trimmed.length() - 4);
    }
}
