package com.bbb.exercise.agentdemo.chatservice.conversation.sync;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owner-scoped browser view snapshots; never used as model receipts or trusted execution input. */
@Service
public class ConversationSnapshotService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> EPHEMERAL = Set.of("previewUrl", "imageUrl", "zineResult");

    public ConversationSnapshotService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Index> list(ChatIdentity owner, int offset, int limit) {
        requireAuthenticated(owner);
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw problem(400,"无效分页参数");
        return jdbc.query("SELECT conversation_id,revision,deleted,title,updated_at FROM conversation_snapshot WHERE tenant_id=? AND user_id=? ORDER BY updated_at DESC,conversation_id LIMIT ? OFFSET ?",
            (rs,n)->new Index(rs.getString(1),rs.getLong(2),rs.getBoolean(3),rs.getString(4),rs.getLong(5)),
            owner.tenantId(),owner.userId(),limit,offset);
    }

    /** Keyset order is stable even when newer conversations are added between page requests. */
    public Page recent(ChatIdentity owner,String cursor,int limit) {
        requireAuthenticated(owner);
        if(limit<1||limit>20)throw problem(400,"每页会话数量须为 1～20");
        Long before=null;String afterId=null;
        if(cursor!=null&&!cursor.isBlank()) {
            try {
                if(cursor.length()>2000)throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\n",-1);
                if(parts.length!=4||!parts[0].equals(owner.tenantId())||!parts[1].equals(owner.userId()))throw new IllegalArgumentException();
                before=Long.parseLong(parts[2]);afterId=parts[3];requireId(afterId);
                if(before<0)throw new IllegalArgumentException();
            }catch(RuntimeException invalid){throw problem(400,"无效会话分页游标");}
        }
        String sql="SELECT conversation_id,revision,deleted,title,updated_at FROM conversation_snapshot WHERE tenant_id=? AND user_id=? AND deleted=FALSE";
        var args=new ArrayList<Object>();args.add(owner.tenantId());args.add(owner.userId());
        if(before!=null){sql+=" AND (updated_at<? OR (updated_at=? AND conversation_id>?))";args.add(before);args.add(before);args.add(afterId);}
        sql+=" ORDER BY updated_at DESC,conversation_id ASC LIMIT ?";args.add(limit+1);
        var rows=jdbc.query(sql,(rs,n)->new Index(rs.getString(1),rs.getLong(2),rs.getBoolean(3),rs.getString(4),rs.getLong(5)),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        String next=null;
        if(more){var last=items.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((owner.tenantId()+"\n"+owner.userId()+"\n"+last.updatedAt()+"\n"+last.id()).getBytes(StandardCharsets.UTF_8));}
        return new Page(items,next);
    }

    public View get(ChatIdentity owner, String id) {
        requireAuthenticated(owner); requireId(id);
        var rows = jdbc.query("SELECT revision,deleted,snapshot_json FROM conversation_snapshot WHERE tenant_id=? AND user_id=? AND conversation_id=?",
            (rs,n)->new View(id,rs.getLong(1),rs.getBoolean(2),rs.getString(3)==null?null:json.readValue(rs.getString(3),Map.class)),owner.tenantId(),owner.userId(),id);
        if(rows.isEmpty()) throw problem(404,"会话不存在");
        return rows.getFirst();
    }

    public View put(ChatIdentity owner, String id, Write request) {
        requireAuthenticated(owner); requireId(id);
        if(request==null || request.expectedRevision()<0) throw problem(400,"缺少版本号");
        Map<String,Object> clean = null;
        String payload = null;
        String title = "";
        if(!request.deleted()) {
            var snapshot = request.snapshot();
            if(snapshot==null || !id.equals(snapshot.get("id")) || !(snapshot.get("messages") instanceof List<?> messages)
                || messages.size()>2000 || !(snapshot.get("title") instanceof String text)) throw problem(400,"无效会话快照");
            if(text.length()>255) throw problem(400,"会话标题过长");
            title = text;
            clean = (Map<String,Object>) sanitize(snapshot,0);
            payload = json.writeValueAsString(clean);
            if(payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw problem(413,"会话超过 1 MiB 同步限制，本地记录仍保留");
        }
        long now = System.currentTimeMillis();
        if(request.expectedRevision()==0) {
            try {
                jdbc.update("INSERT INTO conversation_snapshot(tenant_id,user_id,conversation_id,revision,deleted,title,snapshot_json,updated_at) VALUES(?,?,?,1,?,?,?,?)",
                    owner.tenantId(),owner.userId(),id,request.deleted(),title,payload,now);
            } catch(DuplicateKeyException conflict) { throw problem(409,"会话已被其他页面修改，请刷新后合并"); }
            return new View(id,1,request.deleted(),clean);
        }
        var current = get(owner,id);
        if(current.deleted() && !request.deleted()) throw problem(410,"会话已删除，请创建新会话");
        int changed = jdbc.update("UPDATE conversation_snapshot SET revision=revision+1,deleted=?,title=?,snapshot_json=?,updated_at=? WHERE tenant_id=? AND user_id=? AND conversation_id=? AND revision=? AND (deleted=FALSE OR ?=TRUE)",
            request.deleted(),title,payload,now,owner.tenantId(),owner.userId(),id,request.expectedRevision(),request.deleted());
        if(changed!=1) throw problem(409,"会话已被其他页面修改，请刷新后合并");
        return new View(id,request.expectedRevision()+1,request.deleted(),clean);
    }

    private static Object sanitize(Object value,int depth) {
        if(depth>40) throw problem(400,"会话嵌套层级过深");
        if(value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<String,Object>();
            map.forEach((key,item)->{ if(key instanceof String field && !EPHEMERAL.contains(field)) result.put(field,sanitize(item,depth+1)); });
            return result;
        }
        if(value instanceof List<?> list) return list.stream().map(item->sanitize(item,depth+1)).toList();
        return value;
    }

    private static void requireId(String id) {
        if(id==null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) throw problem(400,"无效会话 ID");
    }
    private static void requireAuthenticated(ChatIdentity owner) { if(owner==null || !owner.authenticated()) throw problem(401,"请先登录"); }
    private static ResponseStatusException problem(int status,String detail) { return new ResponseStatusException(HttpStatus.valueOf(status),detail); }
    public record Write(long expectedRevision, boolean deleted, Map<String,Object> snapshot) {}
    public record View(String id,long revision,boolean deleted,Map<String,Object> snapshot) {}
    public record Index(String id,long revision,boolean deleted,String title,long updatedAt) {}
    public record Page(List<Index> items,String nextCursor) {}
}
