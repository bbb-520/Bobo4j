package com.bbb.exercise.agentdemo.contentservice.zine;

import java.util.Locale;

/** Zine 创作模式，区分素材汇集与内容提炼两类提示词编译策略。 */
public enum ZineMode {
    GATHERED,
    DISTILLATION;

    public static ZineMode parse(String v) {
        if (v == null || v.isBlank()) {
            return GATHERED;
        }

        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "gathered", "scenes-gathered-zine-v1-3", "实景拼贴" -> GATHERED;
            case "distillation", "scene-distillation-zine-v1-3", "影像蒸馏" -> DISTILLATION;
            default -> throw new IllegalArgumentException("mode 只能是 gathered 或 distillation");
        };
    }
}
