package com.bbb.exercise.agentdemo.chatservice.conversation;

/** Intent boundary between a chat turn and the image-generation pipeline. */
public enum ConversationIntent {
    DESCRIBE,
    ANSWER,
    GENERATE,
    REMIX,
    WAIT_FOR_INSTRUCTION;
}
