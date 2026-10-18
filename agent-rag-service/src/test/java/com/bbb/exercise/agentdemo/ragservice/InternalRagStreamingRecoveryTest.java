package com.bbb.exercise.agentdemo.ragservice;

import com.bbb.exercise.agentdemo.api.model.ModelContracts.GenerationEvent;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.AnswerRequest;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.ragservice.answer.DocumentAnswerService;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import com.bbb.exercise.agentdemo.ragservice.web.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalRagStreamingRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings={"ANSWER_EVALUATION_UNAVAILABLE","MODEL_OUTCOME_UNKNOWN"})
    void failureAfterStatusIsAnErrorFrameAndNeverRewritesCommittedHttp(String code) {
        var read=new CountDownLatch(1);var answers=mock(DocumentAnswerService.class);
        when(answers.answerStreaming(any(),any(),any())).thenAnswer(invocation->{
            Consumer<GenerationEvent> events=invocation.getArgument(2);
            events.accept(new GenerationEvent("model_status",3,Map.of("state","SUCCEEDED")));
            if(!read.await(5,TimeUnit.SECONDS))throw new AssertionError("Client did not read status");
            if(code.equals("MODEL_OUTCOME_UNKNOWN"))throw new ModelCallClient.ModelCallException(code,false,409);
            throw new RagException(code,HttpStatus.SERVICE_UNAVAILABLE);
        });
        String secret="current="+Base64.getEncoder().encodeToString(new byte[32]);
        var keys=new PrincipalKeyRing(secret,"current");
        var http=WebTestClient.bindToController(new InternalRagController(secret,"current",null,answers,null,null))
                .controllerAdvice(new RagExceptionHandler()).build();
        var events=http.post().uri("/internal/rag/answer-stream")
                .header("X-Internal-Principal",keys.sign("agent-orchestrator-service","u","t","agent-rag-service","POST /internal/rag/answer-stream"))
                .bodyValue(new AnswerRequest("call","conversation","question",64000L))
                .exchange().expectStatus().isOk().returnResult(GenerationEvent.class).getResponseBody()
                .doOnNext(event->read.countDown()).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(GenerationEvent::type).containsExactly("model_status","error");
        assertThat(events.getLast().generationVersion()).isEqualTo(3);
        assertThat(events.getLast().payload()).containsEntry("code",code);
    }
}
