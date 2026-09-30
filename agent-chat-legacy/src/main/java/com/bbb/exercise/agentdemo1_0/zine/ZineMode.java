package com.bbb.exercise.agentdemo1_0.zine;

import java.util.Locale;

/** Two creative paths defined by the Gathered Scenes Zine skill. */
public enum ZineMode {

    GATHERED,
    DISTILLATION;

    public static ZineMode parse(String value) {
        if (value == null || value.isBlank()) {
            return GATHERED;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "gathered", "scenes-gathered-zine-v1-3", "实景拼贴" -> GATHERED;
            case "distillation", "scene-distillation-zine-v1-3", "影像蒸馏" -> DISTILLATION;
            default -> throw new IllegalArgumentException("mode 只能是 gathered 或 distillation");
        };
    }
}
