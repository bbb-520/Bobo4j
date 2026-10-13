package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.ragservice.answer.GroundingEvaluator;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.Evidence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 验证答案相关性与事实依据均需通过，并将无效评估响应视为失败。 */
class GroundingEvaluatorTest {
    FakeGateway models = new FakeGateway();
    ChatIdentity who = new ChatIdentity("t", "u", true);
    List<Evidence> evidence =
            List.of(new Evidence("c", "d", 1, "Budget is97.", "Budget", null, 1, .9));

    @Test
    void actualSpringAiRelevancyEvaluatorAndFactJudgeMustBothPass() {
        models.responses.put("eval:relevance", new Completion("YES", 11, 1, "judge", false));
        models.responses.put(
                "eval:facts",
                new Completion(
                        "{\"supported\":true,\"unsupportedClaims\":[]}", 15, 4, "judge", false));
        var result =
                new GroundingEvaluator(
                                models,
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                        .evaluate(
                                who,
                                "root",
                                "eval",
                                "What is budget?",
                                evidence,
                                "Budget is97 [S1].");

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.inputTokens()).isEqualTo(26);
        assertThat(result.outputTokens()).isEqualTo(5);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MODEL_OUTCOME_UNKNOWN","AUTH_UNAVAILABLE","ACCOUNTING_UNAVAILABLE"})
    void judgeTransportFailuresArePropagatedRatherThanReportedAsMalformedVerdicts(String code) {
        RuntimeException failure=switch(code) {
            case "AUTH_UNAVAILABLE" -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            case "ACCOUNTING_UNAVAILABLE" -> new com.bbb.exercise.agentdemo.ragservice.domain.RagException("MODEL_ACCOUNTING_UNAVAILABLE",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            default -> new com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException("MODEL_OUTCOME_UNKNOWN",false,409);
        };
        var gateway=new FakeGateway() {
            @Override public Completion complete(ChatIdentity identity,String composite,String call,String mode,String system,String user,boolean fallback) {throw failure;}
        };
        var evaluator=new GroundingEvaluator(gateway,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThatThrownBy(()->evaluator.evaluate(who,"root","eval","Budget?",evidence,"Budget is97 [S1]."))
                .isSameAs(failure);
    }

    @Test
    void malformedJudgeResultIsErrorNeverPass() {
        models.responses.put("eval:relevance", new Completion("MAYBE", 11, 1, "judge", false));
        assertThat(
                        new GroundingEvaluator(
                                        models,
                                        new io.micrometer.core.instrument.simple
                                                .SimpleMeterRegistry())
                                .evaluate(
                                        who,
                                        "root",
                                        "eval",
                                        "Budget?",
                                        evidence,
                                        "Budget is97 [S1].")
                                .status())
                .isEqualTo("ERROR");
    }

    @Test
    void relevantButUnsupportedNumbersFail() {
        models.responses.put("eval:relevance", new Completion("YES", 11, 1, "judge", false));
        models.responses.put(
                "eval:facts",
                new Completion(
                        "{\"supported\":false,\"unsupportedClaims\":[\"Budget98\"]}",
                        15,
                        4,
                        "judge",
                        false));
        assertThat(
                        new GroundingEvaluator(
                                        models,
                                        new io.micrometer.core.instrument.simple
                                                .SimpleMeterRegistry())
                                .evaluate(
                                        who,
                                        "root",
                                        "eval",
                                        "Budget?",
                                        evidence,
                                        "Budget is98 [S1].")
                                .status())
                .isEqualTo("FAIL");
    }

    @Test
    void bothVerificationCallsPublishActualWorkingModelInOrder() {
        models.responses.put("eval:relevance", new Completion("YES", 11, 1, "judge", false));
        models.responses.put(
                "eval:facts",
                new Completion(
                        "{\"supported\":true,\"unsupportedClaims\":[]}", 15, 4, "judge", false));
        var events =
                new ArrayList<
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>();
        var result =
                new GroundingEvaluator(
                                models,
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                        .evaluateWithEvents(
                                who,
                                "root",
                                "eval",
                                "Budget?",
                                evidence,
                                "Budget is97 [S1].",
                                events::add);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(events)
                .extracting(e -> e.payload().get("state"))
                .containsExactly("RUNNING", "SUCCEEDED", "RUNNING", "SUCCEEDED");
        assertThat(events)
                .extracting(
                        com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent
                                ::generationVersion)
                .containsExactly(1, 1, 2, 2);
        assertThat(events)
                .allSatisfy(
                        e ->
                                assertThat(e.payload())
                                        .containsEntry("provider", "GPT")
                                        .containsEntry("capability", "EVALUATION"));
    }

    /** 答案评估测试替身，按顺序提供模型判定以覆盖成功和异常响应。 */
    static class FakeGateway extends RagModelGateway {
        final Map<String, Completion> responses = new HashMap<>();

        FakeGateway() {
            super(null, null, null);
        }

        @Override
        public Completion completeWithEvents(
                ChatIdentity identity,
                String composite,
                String call,
                String mode,
                String system,
                String user,
                boolean fallback,
                java.util.function.Consumer<
                                com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent>
                        events) {
            events.accept(
                    new com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent(
                            "model_status",
                            1,
                            Map.of(
                                    "provider",
                                    "GPT",
                                    "model",
                                    "gpt-4.1",
                                    "capability",
                                    "EVALUATION",
                                    "state",
                                    "RUNNING")));
            var response = complete(identity, composite, call, mode, system, user, fallback);

            events.accept(
                    new com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent(
                            "model_status",
                            1,
                            Map.of(
                                    "provider",
                                    "GPT",
                                    "model",
                                    "gpt-4.1",
                                    "capability",
                                    "EVALUATION",
                                    "state",
                                    "SUCCEEDED")));

            return response;
        }

        @Override
        public Completion complete(
                ChatIdentity identity,
                String composite,
                String call,
                String mode,
                String system,
                String user,
                boolean fallback) {
            if (!composite.equals("root") || !mode.equals("EVALUATION") || fallback) {
                throw new AssertionError("Judge call scope/policy changed");
            }

            Completion response = responses.get(call);

            if (response == null) {
                throw new AssertionError("Unexpected paid call " + call);
            }

            return response;
        }
    }
}
