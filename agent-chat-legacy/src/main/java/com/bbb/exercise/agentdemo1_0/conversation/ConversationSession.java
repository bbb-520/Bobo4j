package com.bbb.exercise.agentdemo1_0.conversation;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;

/** 已完成归属校验、可用于本次对话的会话上下文。 */
public record ConversationSession(Long databaseId, String conversationId,
                                  ChatIdentity identity, boolean created) {
}
