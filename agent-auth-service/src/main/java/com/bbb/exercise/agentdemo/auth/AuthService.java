package com.bbb.exercise.agentdemo.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bbb.exercise.agentdemo.auth.entity.AppUserEntity;
import com.bbb.exercise.agentdemo.auth.entity.AuthSessionEntity;
import com.bbb.exercise.agentdemo.auth.mapper.AppUserMapper;
import com.bbb.exercise.agentdemo.auth.mapper.AuthSessionMapper;
import com.bbb.exercise.agentdemo.runtime.config.AppProperties;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/** Auth owns all user/session persistence. Database access is exclusively MyBatis-Plus. */
@Service
public class AuthService {
    private static final Duration SESSION_TTL = Duration.ofDays(30);
    private final AppUserMapper users; private final AuthSessionMapper sessions;
    private final PasswordHasher passwords; private final AppProperties properties;
    public AuthService(AppUserMapper users, AuthSessionMapper sessions, PasswordHasher passwords, AppProperties properties) { this.users=users; this.sessions=sessions; this.passwords=passwords; this.properties=properties; }
    public User register(String username,String password){ validate(username,password); try{ AppUserEntity e=new AppUserEntity(); e.setUsername(username.trim()); e.setPasswordHash(passwords.hash(password)); e.setCreatedAt(LocalDateTime.now()); users.insert(e); e.setPublicUserId(publicUserId(new User(e.getId(),e.getUsername(),""))); users.updateById(e); return new User(e.getId(),e.getUsername(),e.getPasswordHash()); }catch(DuplicateKeyException e){ throw new AuthException(409,"用户名已存在"); } }
    public LoginResult login(String username,String password){ validate(username,password); User u=find(username.trim()); if(u==null||!passwords.matches(password,u.passwordHash()))throw new AuthException(401,"用户名或密码错误"); return issueSession(u); }
    public LoginResult issueSession(User u){ String token=UUID.randomUUID()+"-"+UUID.randomUUID(); AuthSessionEntity s=new AuthSessionEntity(); s.setUserId(u.id()); s.setTokenHash(hash(token)); s.setExpiresAt(LocalDateTime.now().plus(SESSION_TTL)); s.setCreatedAt(LocalDateTime.now()); sessions.insert(s); return new LoginResult(u,token); }
    public ChatIdentity resolve(ServerWebExchange ex){ var c=ex.getRequest().getCookies().getFirst(properties.getSecurity().getSessionCookieName()); if(c==null||c.getValue().isBlank())return null; AuthSessionEntity s=sessions.selectOne(new LambdaQueryWrapper<AuthSessionEntity>().eq(AuthSessionEntity::getTokenHash,hash(c.getValue())).gt(AuthSessionEntity::getExpiresAt,LocalDateTime.now())); return s==null?null:new ChatIdentity(properties.getSecurity().getDefaultTenantId(),"user:"+s.getUserId(),true); }
    public UserIdentity resolveSessionToken(String token){ if(token==null||token.isBlank())return null; AuthSessionEntity s=sessions.selectOne(new LambdaQueryWrapper<AuthSessionEntity>().eq(AuthSessionEntity::getTokenHash,hash(token)).gt(AuthSessionEntity::getExpiresAt,LocalDateTime.now())); if(s==null)return null; AppUserEntity u=users.selectById(s.getUserId()); return u==null?null:new UserIdentity(publicUserId(new User(u.getId(),u.getUsername(),"")),properties.getSecurity().getDefaultTenantId(),true,u.getUsername()); }
    public void logout(ServerWebExchange ex){ var c=ex.getRequest().getCookies().getFirst(properties.getSecurity().getSessionCookieName()); if(c!=null)sessions.delete(new LambdaQueryWrapper<AuthSessionEntity>().eq(AuthSessionEntity::getTokenHash,hash(c.getValue()))); }
    public String sessionCookieName(){return properties.getSecurity().getSessionCookieName();} public Duration sessionTtl(){return SESSION_TTL;}
    public long requireUserId(ChatIdentity i){if(i==null||!i.authenticated()||!i.userId().startsWith("user:"))throw new AuthException(401,"请先登录");return Long.parseLong(i.userId().substring(5));}
    public String username(ChatIdentity i){AppUserEntity u=users.selectById(requireUserId(i));return u==null?null:u.getUsername();}
    public String publicUserId(User u){return UUID.nameUUIDFromBytes(("bobo:user:"+u.id()).getBytes(StandardCharsets.UTF_8)).toString();} public String publicUserId(ChatIdentity i){return publicUserId(new User(requireUserId(i),username(i),""));}
    public UserIdentity resolvePublicUser(String id,String tenant){AppUserEntity u=findPublicUser(id,tenant);return u==null?null:new UserIdentity(id,tenant,true,u.getUsername());}
    public ChatIdentity identityForPublicUser(String id,String tenant){AppUserEntity u=findPublicUser(id,tenant);return u==null?null:new ChatIdentity(tenant,"user:"+u.getId(),true);}
    private AppUserEntity findPublicUser(String id,String tenant){if(id==null||!id.matches("[0-9a-f-]{36}")||!properties.getSecurity().getDefaultTenantId().equals(tenant))return null;return users.selectOne(new LambdaQueryWrapper<AppUserEntity>().eq(AppUserEntity::getPublicUserId,id));}
    private User find(String name){AppUserEntity u=users.selectOne(new LambdaQueryWrapper<AppUserEntity>().eq(AppUserEntity::getUsername,name));return u==null?null:new User(u.getId(),u.getUsername(),u.getPasswordHash());}
    private static void validate(String u,String p){if(u==null||!u.matches("[A-Za-z0-9_.@-]{3,64}"))throw new AuthException(400,"用户名需为 3-64 位字母、数字或 _ . @ -");if(p==null||p.length()<8||p.length()>128)throw new AuthException(400,"密码长度需为 8-128 位");}
    private static String hash(String t){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public record User(long id,String username,String passwordHash){} public record LoginResult(User user,String token){} public record UserIdentity(String userId,String tenantId,boolean authenticated,String username){}
    public static class AuthException extends RuntimeException{private final int status;public AuthException(int s,String m){super(m);status=s;}public int status(){return status;}}
}
