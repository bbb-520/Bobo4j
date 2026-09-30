package com.bbb.exercise.agentdemo1_0.utils;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConversationKeysTest {

    @Test
    void blankInputMeansCreateNewAndGeneratedIdsAreIndependent() {
        assertThat(ConversationKeys.requireExistingOrNull(null)).isNull();
        assertThat(ConversationKeys.requireExistingOrNull("  ")).isNull();
        assertThat(ConversationKeys.newConversationId())
                .isNotEqualTo(ConversationKeys.newConversationId());
    }

    @Test
    void malformedOrModifiedIdsAreRejectedInsteadOfRewritten() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConversationKeys.requireExistingOrNull("chat-default"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConversationKeys.requireExistingOrNull("a b"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConversationKeys.requireExistingOrNull(
                        "00000000-0000-4000-8000-000000000000 " ));
    }

    @Test
    void memoryKeyContainsTrustedIdentityScope() {
        ChatIdentity identity = new ChatIdentity("tenant-a", "user-a", true);
        String conversationId = "00000000-0000-4000-8000-000000000000";

        assertThat(ConversationKeys.memoryKey(identity, conversationId))
                .isEqualTo("chat:tenant-a:user-a:" + conversationId);
    }
}
