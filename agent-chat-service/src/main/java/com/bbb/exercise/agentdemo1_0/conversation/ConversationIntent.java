package com.bbb.exercise.agentdemo1_0.conversation;

import java.util.Locale;

/** Intent boundary between a chat turn and the image-generation pipeline. */
public enum ConversationIntent {
    DESCRIBE,
    ANSWER,
    GENERATE,
    REMIX,
    WAIT_FOR_INSTRUCTION;

    public static ConversationIntent classify(boolean questionPresent,
                                              boolean imagePresent,
                                              boolean hasPendingSceneCard) {
        if (imagePresent && !questionPresent) return WAIT_FOR_INSTRUCTION;
        if (hasPendingSceneCard && questionPresent && !imagePresent) return REMIX;
        if (imagePresent && questionPresent) return GENERATE;
        if (questionPresent) return ANSWER;
        return WAIT_FOR_INSTRUCTION;
    }

    public static ConversationIntent classifyText(String question,
                                                   boolean imagePresent,
                                                   boolean hasPendingSceneCard) {
        if (question == null || question.isBlank()) {
            return classify(false, imagePresent, hasPendingSceneCard);
        }
        String text = question.toLowerCase(Locale.ROOT);
        if (imagePresent && text.matches(".*(识别|描述|分析|看到了什么|主体|构图).*")) {
            return DESCRIBE;
        }
        if (hasPendingSceneCard && !imagePresent) return REMIX;
        if (imagePresent && text.matches(".*(生成|创作|改成|修改|二次|风格|海报|纸刊|重绘|remix).*")) {
            return GENERATE;
        }
        return classify(true, imagePresent, hasPendingSceneCard);
    }
}
