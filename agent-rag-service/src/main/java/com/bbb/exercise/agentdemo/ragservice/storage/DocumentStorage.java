package com.bbb.exercise.agentdemo.ragservice.storage;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.DocumentView;
import com.bbb.exercise.agentdemo.ragservice.domain.RagException;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.core.io.buffer.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.*;

@Component
public class DocumentStorage {
    public static final long MAX_BYTES=100L*1024*1024;
    private final Path root;private final RagRepository repository;
    public DocumentStorage(@Value("${rag.storage-root:./data/rag}") String root,RagRepository repository){this.root=Path.of(root).toAbsolutePath().normalize();this.repository=repository;}
    public Path root(){return root;}
    public Path work(String document,int version){if(!document.matches("[a-fA-F0-9-]{36}")||version<1)throw new IllegalArgumentException("Document path");return root.resolve("work").resolve(document).resolve("v"+version);}
    public Mono<DocumentView> upload(ChatIdentity who,String request,FilePart file) {
        if(request==null||!request.matches("[A-Za-z0-9_.:@-]{1,128}"))return Mono.error(new RagException("REQUEST_ID_REQUIRED",HttpStatus.BAD_REQUEST));
        String filename=file.filename().replaceAll("[\\p{Cntrl}]","");if(filename.length()>512)filename=filename.substring(0,512);final String display=filename;
        return Mono.usingWhen(Mono.fromCallable(()->{Files.createDirectories(root.resolve("original"));return new Upload(Files.createTempFile(root.resolve("original"),"document-",".upload"));}).subscribeOn(Schedulers.boundedElastic()),
            upload->DataBufferUtils.write(file.content().handle((buffer,sink)->{
                long bytes=upload.bytes.addAndGet(buffer.readableByteCount());
                if(bytes>MAX_BYTES){DataBufferUtils.release(buffer);sink.error(new RagException("UPLOAD_TOO_LARGE",HttpStatus.PAYLOAD_TOO_LARGE));return;}
                try(var iterator=buffer.readableByteBuffers()){while(iterator.hasNext())upload.digest.update(iterator.next().asReadOnlyBuffer());}
                sink.next(buffer);
            }).cast(DataBuffer.class).doOnDiscard(DataBuffer.class,DataBufferUtils::release),upload.path)
            .timeout(java.time.Duration.ofMinutes(30)).then(Mono.fromCallable(()->{
                if(upload.bytes.get()==0)throw new RagException("EMPTY_UPLOAD",HttpStatus.UNPROCESSABLE_ENTITY);
                String checksum=HexFormat.of().formatHex(upload.digest.digest());
                DocumentView doc=repository.register(who,request,display,checksum,upload.path.toString());
                upload.adopted.set(repository.usesPath(who,doc.documentId(),upload.path.toString()));return doc;
            }).subscribeOn(Schedulers.boundedElastic())),
            this::cleanup,(upload,error)->cleanup(upload),this::cleanup);
    }
    private Mono<Void> cleanup(Upload upload){return Mono.fromRunnable(()->{if(!upload.adopted.get())try{Files.deleteIfExists(upload.path);}catch(Exception e){throw new IllegalStateException("UPLOAD_CLEANUP_FAILED",e);}}).subscribeOn(Schedulers.boundedElastic()).then();}
    public void deleteOriginal(String path){Path resolved=Path.of(path).toAbsolutePath().normalize();if(!resolved.startsWith(root))throw new IllegalArgumentException("Storage path");try{Files.deleteIfExists(resolved);}catch(Exception e){throw new IllegalStateException("CLEANUP_FAILED",e);}}
    public void deleteWork(String document,int version){Path resolved=work(document,version).toAbsolutePath().normalize();Path allowed=root.resolve("work").toAbsolutePath().normalize();if(!resolved.startsWith(allowed)||resolved.equals(allowed))throw new IllegalArgumentException("Work cleanup path");if(!Files.exists(resolved))return;try(var files=Files.walk(resolved)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}catch(Exception error){throw new IllegalStateException("CLEANUP_FAILED",error);}}
    private static final class Upload {final Path path;final MessageDigest digest;final AtomicLong bytes=new AtomicLong();final AtomicBoolean adopted=new AtomicBoolean();Upload(Path path)throws Exception{this.path=path;this.digest=MessageDigest.getInstance("SHA-256");}}
}
