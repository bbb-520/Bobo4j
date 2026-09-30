package com.bbb.exercise.agentdemo1_0.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceValidationTest {

    @Test
    void acceptsTextOnlyAndImageOnlyTurns() {
        assertThat(ChatService.validateQuestion("帮我规划三天行程", false)).isNull();
        assertThat(ChatService.validateQuestion(null, true)).isNull();
    }

    @Test
    void rejectsCompletelyEmptyTurn() {
        assertThat(ChatService.validateQuestion("  ", false)).isEqualTo("问题不能为空");
    }
}
