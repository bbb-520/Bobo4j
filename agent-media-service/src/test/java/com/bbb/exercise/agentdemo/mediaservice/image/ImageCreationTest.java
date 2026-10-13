package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.billing.BillingContracts.Wallet;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.runtime.client.*;
import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises our API and creation logic while replacing external auth/OSS/MySQL transports. */
class ImageCreationTest {
    static final String REQUEST="b96c98f4-5588-47aa-8fdc-c24a14b0d2f8";
    static final String CONVERSATION="2fe09439-371d-418f-857e-1fb809058b40";
    final MemoryJdbc db=new MemoryJdbc();
    final ImageAssetService assets=mock(ImageAssetService.class);
    final AuthModelClient models=mock(AuthModelClient.class);
    final BillingClient billing=mock(BillingClient.class);
    final ChatIdentityResolver identities=mock(ChatIdentityResolver.class);
    final ChatIdentity owner=new ChatIdentity("local","alice",true);
    WebTestClient web;

    @BeforeEach void setup() {
        when(identities.resolveRequired(any())).thenReturn(Mono.just(owner));
        when(models.resolve(any(),eq(ModelCapability.IMAGE))).thenReturn(new AuthModelClient.SelectedModel(ModelProvider.QWEN,ModelCapability.IMAGE,"qwen-image-3.0-pro","test-key"));
        when(billing.wallet(any())).thenReturn(Mono.just(new Wallet(0,0,0,false,true,false,null,1000000)));
        var jobs=new ImageJobService(db,assets,new OssProperties(),mock(OssStorageService.class),mock(AuthCredentialClient.class),models,billing);
        web=WebTestClient.bindToController(new ImageJobController(identities,jobs)).build();
    }

    Map<String,Object> body(String prompt) {return Map.of("requestId",REQUEST,"conversationId",CONVERSATION,"prompt",prompt);}
    String create(String prompt) {
        byte[] content=web.post().uri("/api/image-jobs").bodyValue(body(prompt)).exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("QUEUED").returnResult().getResponseBody();
        return JsonMapper.builder().build().readTree(content).path("jobId").asText();
    }

    @Test void createsTextOnlyTaskWithoutInventingASourceAsset() {
        String id=create("生成一张海边日落图片");
        assertThat(db.rows).hasSize(1);
        assertThat(db.rows.get(id)).containsEntry("source_object_key",null).containsEntry("source_asset_id",null).containsEntry("mode","text-to-image");
        assertThat(db.rows.get(id).get("output_object_key")).asString().contains(id);
        verifyNoInteractions(assets);
    }

    @Test void sameRequestReturnsSameTaskEvenWhileWalletIsBlocked() {
        String id=create("a red panda");
        when(billing.wallet(any())).thenReturn(Mono.just(new Wallet(0,0,0,false,true,true,"in-flight",1000000)));
        assertThat(create("a red panda")).isEqualTo(id);
        assertThat(db.rows).hasSize(1);
        web.get().uri("/api/image-jobs/by-request/"+REQUEST).exchange().expectStatus().isOk().expectBody().jsonPath("$.jobId").isEqualTo(id);
    }

    @Test void conflictingReplayIsRejected() {
        create("a red panda");
        web.post().uri("/api/image-jobs").bodyValue(body("a green panda")).exchange().expectStatus().isEqualTo(409);
        assertThat(db.rows).hasSize(1);
    }

    @Test void requestLookupDoesNotCrossOwners() {
        create("a red panda");
        when(identities.resolveRequired(any())).thenReturn(Mono.just(new ChatIdentity("local","bob",true)));
        web.get().uri("/api/image-jobs/by-request/"+REQUEST).exchange().expectStatus().isNotFound();
        create("a blue panda");
        assertThat(db.rows).hasSize(2);
    }

    @Test void validatesEmptyPromptAndMultipleSourcesBeforeCreatingAnything() {
        web.post().uri("/api/image-jobs").bodyValue(body("  ")).exchange().expectStatus().isBadRequest();
        var input=new HashMap<>(body("draw"));input.put("attachments",List.of(Map.of("assetId","a"),Map.of("assetId","b")));
        web.post().uri("/api/image-jobs").bodyValue(input).exchange().expectStatus().isBadRequest();
        input=new HashMap<>(body("draw"));input.put("requestId","too-long-or-invalid");
        web.post().uri("/api/image-jobs").bodyValue(input).exchange().expectStatus().isBadRequest();
        assertThat(db.rows).isEmpty();
    }

    @Test void referenceImageStillRequiresOwnedReadyAsset() {
        when(assets.requireReady(owner,"asset-1")).thenReturn(new ImageAssetService.AssetRecord("asset-1","source/alice/photo.png","image/png",500,"READY",LocalDateTime.now().plusDays(1)));
        var input=new HashMap<>(body("保留主体改成水彩画"));input.put("attachments",List.of(Map.of("assetId","asset-1")));
        web.post().uri("/api/image-jobs").bodyValue(input).exchange().expectStatus().isAccepted();
        assertThat(db.rows.values().iterator().next()).containsEntry("source_object_key","source/alice/photo.png");
        verify(assets).requireReady(owner,"asset-1");
    }

    @Test void expiredOrUnownedReferenceIsATerminalClientError() {
        when(assets.requireReady(owner,"expired-asset")).thenThrow(new IllegalArgumentException("asset expired"));
        var input=new HashMap<>(body("watercolor"));input.put("attachments",List.of(Map.of("assetId","expired-asset")));
        web.post().uri("/api/image-jobs").bodyValue(input).exchange().expectStatus().isBadRequest();
        assertThat(db.rows).isEmpty();verifyNoInteractions(billing);
    }

    static final class MemoryJdbc extends JdbcTemplate {
        final Map<String,Map<String,Object>> rows=new LinkedHashMap<>();
        @Override public int update(String sql,Object... args) {
            if(!sql.startsWith("INSERT INTO image_job"))throw new AssertionError(sql);
            String[] columns=sql.substring(sql.indexOf('(')+1,sql.indexOf(')')).split(",");
            String[] values=sql.substring(sql.indexOf("VALUES (")+8,sql.lastIndexOf(')')).split(",");
            Map<String,Object> row=new HashMap<>();int a=0;
            for(int i=0;i<columns.length;i++)row.put(columns[i].trim(),values[i].trim().equals("?")?args[a++]:values[i].trim().replace("'",""));
            rows.put((String)row.get("id"),row);return 1;
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            if(args.length!=3)throw new AssertionError(sql);
            List<T> out=new ArrayList<>();
            for(var row:rows.values()) {
                String field=sql.contains("WHERE client_request_id=")?"client_request_id":"id";
                if(!Objects.equals(args[0],row.get(field))||!Objects.equals(args[1],row.get("tenant_id"))||!Objects.equals(args[2],row.get("user_id")))continue;
                ResultSet rs=mock(ResultSet.class,invocation->{Object value=row.get(String.valueOf((Object)invocation.getArgument(0)));
                    return switch(invocation.getMethod().getName()){case "getString"->value==null?null:value.toString();case "getTimestamp"->value instanceof LocalDateTime date?Timestamp.valueOf(date):null;case "getObject"->value;default->null;};});
                try {out.add(mapper.mapRow(rs,out.size()));} catch(SQLException e){throw new AssertionError(e);}
            }
            return out;
        }
    }
}
