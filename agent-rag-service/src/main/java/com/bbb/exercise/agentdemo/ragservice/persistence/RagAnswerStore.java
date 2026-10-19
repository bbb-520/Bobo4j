package com.bbb.exercise.agentdemo.ragservice.persistence;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.rag.RagContracts.*;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.Scope;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository.AnswerLease;
import com.google.gson.Gson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.BiFunction;
import static com.bbb.exercise.agentdemo.ragservice.persistence.RagRepository.conflict;

/** Answer receipts, history and fenced leases. Scope locking remains shared with document ownership. */
final class RagAnswerStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Gson json;
    private final BiFunction<ChatIdentity,String,Scope> lockScope;
    RagAnswerStore(JdbcTemplate db,TransactionTemplate tx,Gson json,BiFunction<ChatIdentity,String,Scope> lockScope) {
        this.db=db; this.tx=tx; this.json=json; this.lockScope=lockScope;
    }

    public List<History> history(ChatIdentity who, String id) {
        return db.query(
                        "SELECT call_id,question,answer_json FROM rag_answer WHERE tenant_id=? AND user_id=? AND conversation_id=? AND status='COMPLETED' ORDER BY updated_at DESC LIMIT 12",
                        (r, n) ->
                                new History(
                                        r.getString(1),
                                        r.getString(2),
                                        json.fromJson(r.getString(3), Answer.class)),
                        who.tenantId(),
                        who.userId(),
                        id)
                .reversed();
    }

    public AnswerLease begin(ChatIdentity who, AnswerRequest request) {
        String input = request.conversationId() + "\n" + request.question();
        if (request.imageEvidence() != null) {
            input += "\n" + com.bbb.exercise.agentdemo.ragservice.domain.CanonicalJson.write(request.imageEvidence());
        }
        String hash = SemanticChunker.hash(input);
        return tx.execute(
                s -> {
                    lockScope.apply(who, request.conversationId());
                    var rows =
                            db.queryForList(
                                    "SELECT *,CASE WHEN lease_until>CURRENT_TIMESTAMP(6) THEN 1 ELSE 0 END AS live FROM rag_answer WHERE tenant_id=? AND user_id=? AND call_id=? FOR UPDATE",
                                    who.tenantId(),
                                    who.userId(),
                                    request.callId());

                    if (rows.isEmpty()) {
                        db.update(
                                "INSERT INTO rag_answer(call_id,tenant_id,user_id,conversation_id,request_hash,question,status,lease_until) VALUES(?,?,?,?,?,?,'RUNNING',TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)))",
                                request.callId(),
                                who.tenantId(),
                                who.userId(),
                                request.conversationId(),
                                hash,
                                request.question());
                        return new AnswerLease(1, null);
                    }

                    var row = rows.getFirst();

                    if (!hash.equals(row.get("request_hash"))) {
                        throw conflict("CALL_ID_PARAMETER_CONFLICT");
                    }

                    if ("COMPLETED".equals(row.get("status"))) {
                        return new AnswerLease(
                                ((Number) row.get("fence")).longValue(),
                                json.fromJson((String) row.get("answer_json"), Answer.class));
                    }

                    if (((Number) row.get("live")).intValue() > 0) {
                        throw conflict("ANSWER_IN_PROGRESS");
                    }

                    long fence = ((Number) row.get("fence")).longValue() + 1;

                    db.update(
                            "UPDATE rag_answer SET status='RUNNING',fence=?,lease_until=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND user_id=? AND call_id=?",
                            fence,
                            who.tenantId(),
                            who.userId(),
                            request.callId());

                    return new AnswerLease(fence, null);
                });
    }

    public void renewAnswer(ChatIdentity who, AnswerRequest request, long fence) {
        tx.executeWithoutResult(
                s -> {
                    lockScope.apply(who, request.conversationId());
                    int updated =
                            db.update(
                                    "UPDATE rag_answer SET lease_until=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND user_id=? AND call_id=? AND conversation_id=? AND fence=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)",
                                    who.tenantId(),
                                    who.userId(),
                                    request.callId(),
                                    request.conversationId(),
                                    fence);

                    if (updated != 1) {
                        throw conflict("STALE_ANSWER_LEASE");
                    }
                });
    }

    public void commit(ChatIdentity who, AnswerRequest request, long fence, Answer answer) {
        tx.executeWithoutResult(
                s -> {
                    lockScope.apply(who, request.conversationId());
                    int updated =
                            db.update(
                                    "UPDATE rag_answer SET status='COMPLETED',answer_json=?,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND call_id=? AND fence=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)",
                                    json.toJson(answer),
                                    who.tenantId(),
                                    who.userId(),
                                    request.callId(),
                                    fence);

                    if (updated != 1) {
                        throw conflict("STALE_ANSWER_LEASE");
                    }
                });
    }

    public void answerError(ChatIdentity who, AnswerRequest request, long fence, String state) {
        db.update(
                "UPDATE rag_answer SET status=?,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=? AND call_id=? AND fence=?",
                state,
                who.tenantId(),
                who.userId(),
                request.callId(),
                fence);
    }
}
