package com.bbb.exercise.agentdemo1_0.conversation;

public enum ConversationTurnState {
    READY,
    ANALYZING_IMAGE,
    WAITING_FOR_INSTRUCTION,
    ANSWERING,
    GENERATING,
    COMPLETED,
    FAILED
}
