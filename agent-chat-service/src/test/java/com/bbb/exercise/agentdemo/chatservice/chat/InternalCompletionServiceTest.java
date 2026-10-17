package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.bbb.exercise.agentdemo.chatservice.conversation.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 验证内部生成的幂等回执、归属检查、历史提交事务和证据隔离。 */
class InternalCompletionServiceTest {
    JdbcTemplate jdbc;
    InternalCompletionService service;
    FakeModel model;
    FakeConversations conversations;
    ChatIdentity identity = new ChatIdentity("tenant", "alice", true);

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");

        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE chat_completion(call_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(128),tenant_id VARCHAR(128),parameter_hash VARCHAR(64),model_user CLOB,status VARCHAR(16),receipt CLOB,history_committed BOOLEAN DEFAULT FALSE,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        conversations = new FakeConversations();
        model = new FakeModel();
        service =
                new InternalCompletionService(
                        jdbc,
                        new TransactionTemplate(new DataSourceTransactionManager(ds)),
                        model,
                        conversations);
    }

    @Test
    void completedCallReturnsCachedReceiptAndCommitsOnlyOneHistoryTurn() {
        var request = new CompletionRequest("call", "CHAT", "rules", "question", "cid");

        assertThat(service.complete(identity, request).text()).isEqualTo("answer");
        assertThat(service.complete(identity, request).text()).isEqualTo("answer");
        assertThat(model.calls).isEqualTo(1);
        assertThat(conversations.turns).isEqualTo(1);
    }

    @Test
    void changedParametersAndOtherOwnerAreRejectedBeforeProviderCall() {
        service.complete(
                identity, new CompletionRequest("call", "CHAT", "rules", "question", "cid"));
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        identity,
                                        new CompletionRequest(
                                                "call", "CHAT", "rules", "changed", "cid")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        new ChatIdentity("tenant", "bob", true),
                                        new CompletionRequest(
                                                "call", "CHAT", "rules", "question", "cid")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void failedHistoryCommitRollsBackChatReceiptThenRecoversTheSameStableCall() {
        conversations.fail = true;
        var request = new CompletionRequest("call", "CHAT", "rules", "question", "cid");

        assertThatThrownBy(() -> service.complete(identity, request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT receipt FROM chat_completion WHERE call_id='call'",
                                String.class))
                .isNull();
        String prepared=jdbc.queryForObject("SELECT model_user FROM chat_completion WHERE call_id='call'",String.class);
        conversations.history="history changed before an unfinished call is retried";
        conversations.fail = false;
        assertThat(service.complete(identity, request).text()).isEqualTo("answer");
        assertThat(conversations.turns).isEqualTo(1);
        assertThat(model.lastId).isEqualTo("call");
        assertThat(model.lastUser).isEqualTo(prepared).doesNotContain("history changed");
    }

    @Test
    void documentEvidenceDoesNotBecomeSystemInstructionsOrRegularChatHistory() {
        service.complete(
                identity,
                new CompletionRequest("call", "DOCUMENT_QA", "rules", "[S1] evidence", null));
        assertThat(model.lastSystem).isEqualTo("rules");
        assertThat(model.lastUser).isEqualTo("[S1] evidence");
        assertThat(conversations.turns).isZero();
    }

    @Test
    void streamingTerminalReceiptWaitsForTheAtomicHistoryCommit() {
        var events = new ArrayList<GenerationEvent>();

        service.completeStreaming(
                identity,
                new CompletionRequest("call", "CHAT", "rules", "question", "cid"),
                event -> {
                    if (event.type().equals("answer_committed")) {
                        assertThat(conversations.turns).isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT status FROM chat_completion WHERE call_id='call'",
                                                String.class))
                                .isEqualTo("SUCCEEDED");
                    }

                    events.add(event);
                });
        assertThat(events)
                .extracting(GenerationEvent::type)
                .containsExactly("answer_delta", "answer_committed");
    }

    @Test
    void sharedHistoryRenderingAppearsOnceAndCachedCallsKeepTheirPreparedInput() {
        conversations.history=ConversationHistory.render(List.of(new ChatTurn("user","past"),new ChatTurn("assistant","previous")));
        String snapshot=ConversationHistory.withQuestion(conversations.history,"CURRENT");
        var request=new CompletionRequest("history-call","CHAT","rules","CURRENT","cid");
        service.complete(identity,request);
        assertThat(model.lastUser).isEqualTo(snapshot);
        assertThat(model.lastSystem).isEqualTo("rules");
        conversations.history="history changed after dispatch";
        service.complete(identity,request);
        assertThat(model.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT model_user FROM chat_completion WHERE call_id='history-call'",String.class)).isEqualTo(snapshot);
    }

    /** 模型生成测试替身，记录调用次数和提示词以验证重放及输入隔离。 */
    static class FakeModel extends ModelCallClient {
        int calls;
        String lastId, lastSystem, lastUser;

        FakeModel() {
            super(null, null, org.springframework.web.reactive.function.client.WebClient.builder());
        }

        @Override
        public Completion complete(
                ChatIdentity i,
                String id,
                String mode,
                String system,
                String user,
                boolean fallback) {
            calls++;
            lastId = id;
            lastSystem = system;
            lastUser = user;

            return new Completion("answer", 12, 3, "model", false);
        }

        @Override
        public Completion complete(
                ChatIdentity i,
                String id,
                String mode,
                String system,
                String user,
                boolean fallback,
                Long budget) {
            return complete(i, id, mode, system, user, fallback);
        }

        @Override
        public Completion completeStreaming(
                ChatIdentity i,
                String id,
                String mode,
                String system,
                String user,
                boolean fallback,
                java.util.function.Consumer<GenerationEvent> events) {
            events.accept(new GenerationEvent("answer_delta", 1, Map.of("text", "answer")));
            var result = complete(i, id, mode, system, user, fallback);

            events.accept(ModelCallClient.committedEvent(result));

            return result;
        }

        @Override
        public Completion completeStreaming(
                ChatIdentity i,
                String id,
                String mode,
                String system,
                String user,
                boolean fallback,
                Long budget,
                java.util.function.Consumer<GenerationEvent> events) {
            return completeStreaming(i, id, mode, system, user, fallback, events);
        }
    }

    /** 会话持久化测试替身，统计历史提交并模拟失败以验证事务恢复。 */
    static class FakeConversations extends ConversationPersistenceService {
        int turns;
        boolean fail;
        String history="user: past\nassistant: previous";

        FakeConversations() {
            super(null, null);
        }

        @Override
        public ConversationSession openOrCreate(String id, ChatIdentity i) {
            return new ConversationSession(1L, id, i, false);
        }

        @Override
        public String committedHistory(ConversationSession s) {
            return history;
        }

        @Override
        public void appendTurn(
                ConversationSession s, String user, String answer, boolean complete) {
            if (fail) {
                throw new IllegalStateException("storage failed");
            }

            turns++;
        }
    }
}
