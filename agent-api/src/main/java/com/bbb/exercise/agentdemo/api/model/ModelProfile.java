package com.bbb.exercise.agentdemo.api.model;

/** Public-safe model selection; secret material is intentionally absent. */
public record ModelProfile(ModelProvider provider, ModelCapability capability, String model,
                           boolean enabled, String maskedApiKey) {
    public ModelProfile {
        if (provider == null || capability == null) throw new IllegalArgumentException("Provider 和模型能力不能为空");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        model = model.trim();
    }

    public static String maskSecret(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= 8 ? "••••••••" : normalized.substring(0, 4) + "••••••••" + normalized.substring(normalized.length() - 4);
    }
}
