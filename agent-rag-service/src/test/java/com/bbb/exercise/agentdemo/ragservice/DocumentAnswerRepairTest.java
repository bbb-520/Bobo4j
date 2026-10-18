package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.answer.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.HybridRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentAnswerRepairTest {
    final ChatIdentity who = new ChatIdentity("tenant", "alice", true);
    final AnswerRequest request = new AnswerRequest("repair-call", "conversation", "分析该简历", 16000L);
    final RagRepository repository = mock(RagRepository.class);
    final RagModelGateway models = mock(RagModelGateway.class);
    final HybridRetriever retriever = mock(HybridRetriever.class);
    final List<String> draftRequests = new ArrayList<>();
    final List<String> generationRules = new ArrayList<>();
    final AtomicInteger drafts = new AtomicInteger();
    final String cited = "简历中列出了 Java 21、Spring Boot 和 Redis 技术，并描述了文档检索项目。[S1]";
    final String broken = "该简历体现的技术技能和项目经验如下，所有陈述均严格依据所提供证据：\n\n"
            + cited + "\n\n所有陈述均直接引自所提供证据，无跨源推断，无对缺失信息的补充。";

    DocumentAnswerService service(String secondDraft, boolean supported) {
        when(repository.begin(who, request)).thenReturn(new RagRepository.AnswerLease(1, null));
        when(retriever.retrieve(eq(who), any(), anyString())).thenReturn(new Retrieval("技术技能", List.of(
                new Evidence("chunk", "document", 1, "Java 21、Spring Boot、Redis；文档检索项目。", "简历", 1, 0, 1)), false));
        when(models.budget(who, request.callId())).thenReturn(new RagModelGateway.Budget(16000, 200, 0));
        when(models.accounting(who, request.callId())).thenReturn(new RagModelGateway.Accounting(150, 50, 0));
        when(models.complete(eq(who), eq(request.callId()), anyString(), eq("DOCUMENT_QA"), anyString(), anyString(), eq(true)))
                .thenAnswer(call -> {
                    generationRules.add(call.getArgument(4)); draftRequests.add(call.getArgument(5));
                    return new Completion(drafts.getAndIncrement() == 0 ? broken : secondDraft, 10, 10, "fixture", false);
                });
        when(models.complete(eq(who), eq(request.callId()), anyString(), eq("EVALUATION"), anyString(), anyString(), eq(false)))
                .thenAnswer(call -> new Completion(((String) call.getArgument(2)).endsWith(":relevance") ? "YES"
                        : supported ? "{\"supported\":true,\"unsupportedClaims\":[]}"
                        : "{\"supported\":false,\"unsupportedClaims\":[\"unsupported\"]}", 10, 1, "fixture", false));
        return new DocumentAnswerService(repository, retriever, models,
                new GroundingEvaluator(models, new SimpleMeterRegistry()));
    }

    @Test
    void productionStylePreambleGetsActionableRepairWithoutChangingTheUserQuestion() {
        var service = service(cited, true);
        try {
            var answer = service.answer(who, request);
            assertThat(answer.evaluation()).isEqualTo("PASS");
            assertThat(answer.sources()).extracting(Source::id).containsExactly("S1");
            assertThat(drafts.get()).isEqualTo(2);
            var repaired = JsonMapper.builder().build().readTree(draftRequests.get(1));
            assertThat(repaired.path("originalQuestion").asString()).isEqualTo("分析该简历");
            assertThat(repaired.path("repairFeedback").asString()).contains("UNCITED_CLAIM", "paragraph 1", "paragraph 3", "preamble");
            assertThat(generationRules.get(0)).contains("list item", "preamble");
            verify(repository).commit(eq(who), eq(request), eq(1L), eq(answer));
        } finally { service.shutdown(); }
    }

    @Test
    void uncitedClaimsAfterRepairRemainRejected() {
        var service = service(broken, true);
        try {
            var answer = service.answer(who, request);
            assertThat(answer.evaluation()).isEqualTo("FAIL");
            assertThat(answer.sources()).isEmpty();
            assertThat(drafts.get()).isEqualTo(2);
        } finally { service.shutdown(); }
    }

    @Test
    void validCitationsDoNotBypassFactualSupportEvaluation() {
        var service = service(cited, false);
        try {
            assertThat(service.answer(who, request).evaluation()).isEqualTo("FAIL");
            assertThat(drafts.get()).isEqualTo(2);
        } finally { service.shutdown(); }
    }
}
