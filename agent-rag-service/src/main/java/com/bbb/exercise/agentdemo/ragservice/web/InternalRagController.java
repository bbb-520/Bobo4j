package com.bbb.exercise.agentdemo.ragservice.web;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.processing.RagModelGateway;
import com.bbb.exercise.agentdemo.ragservice.retrieval.HybridRetriever;
import com.bbb.exercise.agentdemo.ragservice.answer.DocumentAnswerService;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.function.Function;

@RestController
@RequestMapping("/internal/rag")
public class InternalRagController {
    private final PrincipalKeyRing keys;private final HybridRetriever retriever;private final DocumentAnswerService answers;private final RagRepository repository;private final RagModelGateway models;
    public InternalRagController(@Value("${app.security.internal-principal-secrets:}") String secrets,@Value("${app.security.internal-principal-active-key-id:current}") String active,HybridRetriever retriever,DocumentAnswerService answers,RagRepository repository,RagModelGateway models){this.keys=new PrincipalKeyRing(secrets,active);this.retriever=retriever;this.answers=answers;this.repository=repository;this.models=models;}
    @PostMapping("/retrieve") public Mono<Retrieval> retrieve(@RequestBody RetrieveRequest request,ServerWebExchange ex){return signed(ex,who->{String id=request.callId()==null?"retrieve:"+UUID.randomUUID():request.callId();var stable=new RetrieveRequest(request.conversationId(),request.question(),id,request.tokenBudget());return retriever.retrieve(who,stable,"ragr:"+SemanticChunker.hash(who.tenantId()+":"+who.userId()+":"+id).substring(0,48));});}
    @PostMapping("/answer") public Mono<Answer> answer(@RequestBody AnswerRequest request,ServerWebExchange ex){return signed(ex,who->answers.answer(who,request));}
    @GetMapping("/calls/{callId}") public Mono<Map<String,Object>> status(@PathVariable String callId,ServerWebExchange ex){return signed(ex,who->models.status(who,callId));}
    @GetMapping("/documents/{id}/sections") public Mono<List<Evidence>> sections(@PathVariable String id,@RequestParam String conversationId,@RequestParam(defaultValue="-1") int afterOrdinal,@RequestParam(defaultValue="8") int limit,ServerWebExchange ex){return signed(ex,who->{var scope=repository.scope(who,conversationId);Integer version=scope.versions().get(id);if(version==null)throw new RagException("DOCUMENT_OUTSIDE_CONVERSATION",HttpStatus.FORBIDDEN);if(limit<1||limit>8||afterOrdinal< -1)throw new RagException("SECTION_RANGE_INVALID",HttpStatus.BAD_REQUEST);return repository.chunks(who,id,version).stream().filter(c->c.ordinal()>afterOrdinal).limit(limit).map(c->new Evidence(c.id(),c.documentId(),c.version(),c.text(),c.title(),c.page(),c.ordinal(),1)).toList();});}
    private <T>Mono<T> signed(ServerWebExchange ex,Function<ChatIdentity,T> action){return Mono.fromCallable(()->{com.bbb.exercise.agentdemo.common.security.SignedPrincipal.Scoped principal;try{principal=keys.verify(ex.getRequest().getHeaders().getFirst("X-Internal-Principal"),"agent-rag-service",ex.getRequest().getMethod().name()+" "+ex.getRequest().getURI().getPath());}catch(IllegalArgumentException bad){throw new RagException("INTERNAL_PRINCIPAL_INVALID",HttpStatus.UNAUTHORIZED);}if(principal==null)throw new RagException("INTERNAL_PRINCIPAL_INVALID",HttpStatus.UNAUTHORIZED);if(!Set.of("agent-orchestrator-service","agent-chat-service").contains(principal.service()))throw new RagException("INTERNAL_CALLER_FORBIDDEN",HttpStatus.FORBIDDEN);return action.apply(new ChatIdentity(principal.tenant(),principal.subject(),true));}).subscribeOn(Schedulers.boundedElastic());}
}
