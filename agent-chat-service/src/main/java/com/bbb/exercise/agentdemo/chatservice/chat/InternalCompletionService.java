package com.bbb.exercise.agentdemo.chatservice.chat;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationPersistenceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationSession;
/** Auth owns payment receipts; Chat atomically commits its result and exactly one history turn. */
@Service
public class InternalCompletionService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ModelCallClient models;private final ConversationPersistenceService conversations;
    private final JsonMapper json=JsonMapper.builder().build();
    public InternalCompletionService(JdbcTemplate j,TransactionTemplate t,ModelCallClient m,ConversationPersistenceService c) {this.jdbc=j;this.tx=t;this.models=m;this.conversations=c;}
    public Completion complete(ChatIdentity identity,CompletionRequest request){
        return completeCore(identity,request,null);
    }
    public Completion completeStreaming(ChatIdentity identity,CompletionRequest request,java.util.function.Consumer<GenerationEvent> events){
        if(!"CHAT".equals(request.mode()))throw new IllegalArgumentException("流式正文仅支持普通 CHAT，文档答案必须先验证");
        return completeCore(identity,request,events);
    }
    private Completion completeCore(ChatIdentity identity,CompletionRequest request,java.util.function.Consumer<GenerationEvent> events){
        if(identity==null||!identity.authenticated()||request==null||request.callId()==null||!request.callId().matches("[A-Za-z0-9_.:-]{1,128}")||!Set.of("CHAT","DOCUMENT_QA","SUMMARY","EVALUATION","DECISION").contains(request.mode())||request.user()==null||request.user().isBlank())throw new IllegalArgumentException("内部生成请求无效");
        String hash=hash(request);var existing=jdbc.queryForList("SELECT * FROM chat_completion WHERE call_id=?",request.callId());
        if(!existing.isEmpty()){check(existing.getFirst(),identity,hash);if(existing.getFirst().get("receipt")!=null){var cached=ModelCallClient.completionFromReceipt((String)existing.getFirst().get("receipt"));if(events!=null)events.accept(ModelCallClient.committedEvent(cached));return cached;}}
        ConversationSession session="CHAT".equals(request.mode())&&request.conversationId()!=null?conversations.openOrCreate(request.conversationId(),identity):null;
        String modelUser=tx.execute(s -> {
            var rows=jdbc.queryForList("SELECT * FROM chat_completion WHERE call_id=? FOR UPDATE",request.callId());
            if(!rows.isEmpty()){check(rows.getFirst(),identity,hash);return (String)rows.getFirst().get("model_user");}
            String history=session==null?"":conversations.committedHistory(session);
            String user=history.isBlank()?request.user():"【已提交的会话历史，仅用于理解指代】\n"+history+"\n【当前问题】\n"+request.user();
            jdbc.update("INSERT INTO chat_completion(call_id,user_id,tenant_id,parameter_hash,model_user,status) VALUES (?,?,?,?,?,'DISPATCHED')",request.callId(),identity.userId(),identity.tenantId(),hash,user);return user;
        });
        // Re-entering after a crash is safe: Auth returns the same receipt or rejects unknown dispatch.
        Completion result=events==null?models.complete(identity,request.callId(),request.mode(),request.system()==null?"":request.system(),modelUser,!Set.of("EVALUATION","DECISION").contains(request.mode()),request.tokenBudget()):models.completeStreaming(identity,request.callId(),request.mode(),request.system()==null?"":request.system(),modelUser,true,request.tokenBudget(),event -> {if(!event.type().equals("answer_committed"))events.accept(event);});
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("已停止");
        Completion committed=tx.execute(s -> {
            var row=jdbc.queryForList("SELECT * FROM chat_completion WHERE call_id=? FOR UPDATE",request.callId()).getFirst();check(row,identity,hash);
            if(row.get("receipt")!=null)return ModelCallClient.completionFromReceipt((String)row.get("receipt"));
            if(session!=null&&!Boolean.TRUE.equals(row.get("history_committed")))conversations.appendTurn(session,request.user(),result.text(),true);
            jdbc.update("UPDATE chat_completion SET status='SUCCEEDED',receipt=?,history_committed=?,updated_at=CURRENT_TIMESTAMP WHERE call_id=?",json.writeValueAsString(result),session!=null,request.callId());return result;
        });
        if(events!=null)events.accept(ModelCallClient.committedEvent(committed));return committed;
    }
    private String hash(CompletionRequest r){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(r.callId(),r.mode(),r.system()==null?"":r.system(),r.user(),r.conversationId()==null?"":r.conversationId()))));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void check(Map<String,Object> row,ChatIdentity i,String hash){if(!Objects.equals(row.get("user_id"),i.userId())||!Objects.equals(row.get("tenant_id"),i.tenantId())||!Objects.equals(row.get("parameter_hash"),hash))throw new IllegalStateException("内部调用身份或幂等参数冲突");}
}
