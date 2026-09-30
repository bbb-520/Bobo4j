package com.bbb.exercise.agentdemo1_0.generation;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationIntent;

/** Pure command boundary; provider calls and persistence are added behind this boundary. */
public final class GenerationOrchestrator {
    private GenerationOrchestrator() {}

    public static GenerationCommand command(ConversationIntent intent,
                                            String prompt,
                                            String sourceAssetId,
                                            String parentVariantId,
                                            int variantCount) {
        GenerationCommand.Mode mode = switch (intent) {
            case GENERATE -> GenerationCommand.Mode.GENERATE;
            case REMIX -> GenerationCommand.Mode.REMIX;
            default -> throw new IllegalArgumentException("当前对话意图不能创建图片生成任务: " + intent);
        };
        return new GenerationCommand(mode, prompt, sourceAssetId, parentVariantId, variantCount);
    }
}
