package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.dto.ChatRequest;
import com.bbb.exercise.agentdemo1_0.observability.RequestTimingWebFilter;
import com.bbb.exercise.agentdemo1_0.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;


/**
 * 对话入口（WebFlux 响应式 HTTP 层）。
 *
 * <p>只做三件事：接收请求参数、解析会话键、把 {@link ChatService} 返回的 {@link Flux} 原样返回。
 * 业务逻辑（记忆、图片任务）全部下沉到 {@link ChatService}，本类保持「薄控制器」。
 *
 * <p>唯一端点：{@code POST /api/chat} —— SSE 流式对话。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver identityResolver;


    /**
     * 流式对话：以 SSE（text/event-stream）持续推送事件，直到发送 {@code STOP} 事件后关闭。
     * 返回类型是 {@code Flux<ChatEventVO>}，由 WebFlux 逐条序列化写出，无需手动 flush。
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ChatEventVO>>> chat(@RequestBody(required = false) ChatRequest request,
                                                        ServerWebExchange exchange) {
        Instant receivedAt = Instant.now();
        String requestId = exchange.getAttribute(RequestTimingWebFilter.REQUEST_ID_ATTRIBUTE);
        // 允许空 body：request 为 null 时用空对象兜底，交给 ChatService 做参数校验
        ChatRequest safe = request == null ? new ChatRequest() : request;
        String invalid = ChatService.validateQuestion(safe.getQuestion(),
                safe.getAttachments() != null && !safe.getAttachments().isEmpty());
        if (invalid != null) {
            log.info("[chat] message_rejected requestId={} receivedAt={} reason={}", requestId, receivedAt, invalid);
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(Flux.just(
                            ChatEventVO.builder().eventType(com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum.ERROR.getValue()).eventData(invalid).build(),
                            ChatEventVO.builder().eventType(com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum.STOP.getValue()).build())));
        }

        log.info("[chat] message_received requestId={} receivedAt={} sessionIdPresent={} questionLength={}",
                requestId, receivedAt,
                safe.getSessionId() != null && !safe.getSessionId().isBlank(),
                safe.getQuestion() == null ? 0 : safe.getQuestion().codePointCount(0, safe.getQuestion().length()));
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> chatService.openConversation(safe.getSessionId(), identity)
                        .map(session -> ResponseEntity.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(chatService.chat(safe.getQuestion(), session, safe.getAttachments()))))
                .doOnError(error -> log.warn("[chat] message_setup_failed requestId={} receivedAt={} errorType={} message={}",
                        requestId, receivedAt, error.getClass().getSimpleName(), safeMessage(error)));
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? (error == null ? "unknown" : error.getClass().getSimpleName())
                : message.replaceAll("[\\r\\n]+", " ");
    }
}
