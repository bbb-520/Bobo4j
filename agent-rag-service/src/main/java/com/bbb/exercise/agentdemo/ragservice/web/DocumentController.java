package com.bbb.exercise.agentdemo.ragservice.web;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.ragservice.storage.DocumentStorage;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.function.Function;

@RestController
public class DocumentController {
    private final ChatIdentityResolver identities;private final RagRepository repository;private final DocumentStorage storage;
    public DocumentController(ChatIdentityResolver identities,RagRepository repository,DocumentStorage storage){this.identities=identities;this.repository=repository;this.storage=storage;}
    @PostMapping(value="/api/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<DocumentView> upload(@RequestPart("file") FilePart file,@RequestHeader("X-Request-ID") String request,ServerWebExchange exchange){return identities.resolveRequired(exchange).flatMap(who->storage.upload(who,request,file));}
    @GetMapping("/api/documents") public Mono<List<DocumentView>> list(ServerWebExchange ex){return with(ex,repository::documents);}
    @GetMapping("/api/documents/{id}") public Mono<DocumentView> get(@PathVariable String id,ServerWebExchange ex){return with(ex,who->repository.document(who,id));}
    @GetMapping("/api/documents/{id}/summary") public Mono<SummaryView> summary(@PathVariable String id,ServerWebExchange ex){return with(ex,who->repository.summary(who,id));}
    @PostMapping("/api/documents/{id}/retry") public Mono<DocumentView> retry(@PathVariable String id,ServerWebExchange ex){return with(ex,who->repository.retry(who,id));}
    @DeleteMapping("/api/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id,ServerWebExchange ex){return with(ex,who->{repository.delete(who,id);return true;}).then();}
    @GetMapping("/api/documents/{id}/sources/{chunkId}") public Mono<Source> source(@PathVariable String id,@PathVariable String chunkId,ServerWebExchange ex){return with(ex,who->{var document=repository.document(who,id);var chunk=repository.chunks(who,id,document.indexVersion()).stream().filter(c->c.id().equals(chunkId)).findFirst().orElseThrow(()->new RagException("SOURCE_NOT_FOUND",HttpStatus.NOT_FOUND));return new Source(chunk.id(),id,chunk.id(),chunk.title()+" · 字符 "+chunk.charStart()+"–"+chunk.charEnd(),chunk.page(),chunk.text());});}
    @PostMapping("/api/document-conversations") public Mono<ConversationView> create(@RequestBody ConversationRequest request,ServerWebExchange ex){return with(ex,who->repository.createConversation(who,request.documentIds()));}
    @GetMapping("/api/document-conversations/{id}") public Mono<ConversationView> conversation(@PathVariable String id,ServerWebExchange ex){return with(ex,who->repository.conversation(who,id));}
    @GetMapping("/api/document-conversations/{id}/retrieval-traces") public Mono<List<Map<String,Object>>> traces(@PathVariable String id,ServerWebExchange ex){return with(ex,who->repository.retrievalTraces(who,id));}
    private <T>Mono<T> with(ServerWebExchange ex,Function<ChatIdentity,T> action){return identities.resolveRequired(ex).flatMap(who->Mono.fromCallable(()->action.apply(who)).subscribeOn(Schedulers.boundedElastic()));}
}
