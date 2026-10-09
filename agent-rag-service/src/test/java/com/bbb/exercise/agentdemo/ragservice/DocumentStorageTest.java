package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.storage.DocumentStorage;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.DocumentView;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.*;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
import static org.assertj.core.api.Assertions.*;
class DocumentStorageTest {
 @TempDir Path dir;ChatIdentity owner=new ChatIdentity("t","u",true);
 @Test void interruptedUploadLeavesNoTaskOrFile()throws Exception{var repo=new FakeRepository();var storage=new DocumentStorage(dir.toString(),repo);var content=Flux.concat(Flux.just(new DefaultDataBufferFactory().wrap("start".getBytes())),Flux.error(new IllegalStateException("CLIENT_DISCONNECTED")));assertThatThrownBy(()->storage.upload(owner,"req",part(content)).block()).hasMessage("CLIENT_DISCONNECTED");assertThat(repo.registered).isFalse();try(var files=Files.list(dir.resolve("original"))){assertThat(files.count()).isZero();}}
 @Test void durableRegistrationFailureCleansCompleteFile()throws Exception{var repo=new FakeRepository();repo.fail=true;assertThatThrownBy(()->new DocumentStorage(dir.toString(),repo).upload(owner,"req",part(Flux.just(new DefaultDataBufferFactory().wrap("text".getBytes())))).block()).hasMessage("DATABASE_UNAVAILABLE");try(var files=Files.list(dir.resolve("original"))){assertThat(files.count()).isZero();}}
 @Test void streamedOversizeStopsAt100MiBAndCleansTemp()throws Exception{var repo=new FakeRepository();var factory=new DefaultDataBufferFactory();Flux<DataBuffer> content=Flux.range(0,101).map(i->factory.wrap(new byte[1024*1024]));assertThatThrownBy(()->new DocumentStorage(dir.toString(),repo).upload(owner,"req",part(content)).block()).hasMessage("UPLOAD_TOO_LARGE");assertThat(repo.registered).isFalse();try(var files=Files.list(dir.resolve("original"))){assertThat(files.count()).isZero();}}
 @Test void digestIsComputedFromStreamAndOriginalIsAdopted()throws Exception{var repo=new FakeRepository();byte[] bytes="正文abc".getBytes(StandardCharsets.UTF_8);new DocumentStorage(dir.toString(),repo).upload(owner,"req",part(Flux.just(new DefaultDataBufferFactory().wrap(bytes)))).block();assertThat(repo.hash).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));assertThat(Files.readAllBytes(Path.of(repo.path))).isEqualTo(bytes);}
 FilePart part(Flux<? extends DataBuffer> content){return new FilePart(){public String filename(){return "report.txt";}public String name(){return "file";}public HttpHeaders headers(){return new HttpHeaders();}public Flux<DataBuffer> content(){return content.cast(DataBuffer.class);}public Mono<Void> transferTo(Path target){return Mono.error(new UnsupportedOperationException());}};}
 static class FakeRepository extends RagRepository{boolean registered,fail;String hash,path;FakeRepository(){super(null,null);}public DocumentView register(ChatIdentity who,String request,String filename,String hash,String path){if(fail)throw new IllegalStateException("DATABASE_UNAVAILABLE");registered=true;this.hash=hash;this.path=path;return new DocumentView("d",filename,"UPLOADED","PENDING","PENDING",0,0,null,0,"now");}public boolean usesPath(ChatIdentity who,String document,String path){return true;}}
}
