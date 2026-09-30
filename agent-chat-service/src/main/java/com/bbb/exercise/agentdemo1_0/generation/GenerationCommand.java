package com.bbb.exercise.agentdemo1_0.generation;

public record GenerationCommand(Mode mode,
                                String prompt,
                                String sourceAssetId,
                                String parentVariantId,
                                int variantCount) {
    public enum Mode { GENERATE, REMIX }

    public GenerationCommand {
        if (mode == null) throw new IllegalArgumentException("生成模式不能为空");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("生成指令不能为空");
        if (sourceAssetId == null || sourceAssetId.isBlank()) throw new IllegalArgumentException("源图片不能为空");
        if (mode == Mode.REMIX && (parentVariantId == null || parentVariantId.isBlank())) {
            throw new IllegalArgumentException("Remix 必须指定父版本");
        }
        prompt = prompt.trim();
        sourceAssetId = sourceAssetId.trim();
        parentVariantId = parentVariantId == null || parentVariantId.isBlank() ? null : parentVariantId.trim();
        variantCount = Math.max(1, Math.min(4, variantCount));
    }
}
