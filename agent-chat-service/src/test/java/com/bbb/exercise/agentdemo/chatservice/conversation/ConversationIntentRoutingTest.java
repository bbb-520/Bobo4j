package com.bbb.exercise.agentdemo.chatservice.conversation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证图片问题默认进入理解流程，只有明确创作意图才触发生成。 */
class ConversationIntentRoutingTest {
    @Test
    void imageQuestionsUseUnderstandingUnlessCreationWasExplicitlyRequested() {
        assertThat(ConversationIntent.classifyText("这张图是什么？", true, false))
                .isEqualTo(ConversationIntent.DESCRIBE);
        assertThat(ConversationIntent.classifyText("图片上的文字是什么意思？", true, false))
                .isEqualTo(ConversationIntent.DESCRIBE);
        assertThat(ConversationIntent.classifyText("帮我生成一张海报", true, false))
                .isEqualTo(ConversationIntent.GENERATE);
        assertThat(ConversationIntent.classifyText("保留人物\n修改背景为夕阳", true, false))
                .isEqualTo(ConversationIntent.GENERATE);
    }
}
