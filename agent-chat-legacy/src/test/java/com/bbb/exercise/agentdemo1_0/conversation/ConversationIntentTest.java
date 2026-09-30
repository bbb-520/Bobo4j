package com.bbb.exercise.agentdemo1_0.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationIntentTest {

    @Test
    void textOnlyMessageIsAnsweredWithoutAnImage() {
        assertEquals(ConversationIntent.ANSWER,
                ConversationIntent.classify(true, false, false));
    }

    @Test
    void imageOnlyMessageWaitsForAnInstruction() {
        assertEquals(ConversationIntent.WAIT_FOR_INSTRUCTION,
                ConversationIntent.classify(false, true, false));
    }

    @Test
    void imageWithGenerationInstructionStartsGeneration() {
        assertEquals(ConversationIntent.GENERATE,
                ConversationIntent.classify(true, true, false));
    }

    @Test
    void pendingSceneCardTurnsFollowUpIntoRemix() {
        assertEquals(ConversationIntent.REMIX,
                ConversationIntent.classify(true, false, true));
    }
}
