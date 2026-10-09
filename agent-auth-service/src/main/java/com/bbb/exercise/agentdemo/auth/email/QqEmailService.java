package com.bbb.exercise.agentdemo.auth.email;

import com.bbb.exercise.agentdemo.auth.AuthService.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Codes are purpose-bound, HMAC-protected, rate limited, and consumed by an atomic DB transaction. */
@Service
public class QqEmailService {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final EmailSender mail;
    private final String secret; private final SecureRandom random=new SecureRandom();
    public QqEmailService(JdbcTemplate jdbc,TransactionTemplate tx,EmailSender mail,@Value("${app.email.otp-secret:}") String secret) {this.jdbc=jdbc;this.tx=tx;this.mail=mail;this.secret=secret;}
    public void send(String value,String purpose,String remoteIp) {
        String email=normalize(value); checkPurpose(purpose); checkSecret();
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        // Initialize keys outside the reservation transaction; duplicate insert is harmless.
        String ipKey="ip:"+digest(remoteIp==null?"unknown":remoteIp);
        initLimit("email:"+email);initLimit(ipKey);
        tx.executeWithoutResult(s -> {
            limit("email:"+email,1,60); limit(ipKey,20,3600);
            int updated=jdbc.update("UPDATE email_code SET code_hash=?,expires_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP),attempts=0,consumed=FALSE WHERE email=? AND purpose=?",digest(email+":"+purpose+":"+code),email,purpose);
            if(updated==0) jdbc.update("INSERT INTO email_code(email,purpose,code_hash,expires_at) VALUES (?,?,?,TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP))",email,purpose,digest(email+":"+purpose+":"+code));
        });
        // No DB locks are held during SMTP. No plaintext code is returned or logged.
        mail.send(email,code);
    }
    public String consume(String value,String purpose,String code) {
        String email=normalize(value); checkPurpose(purpose); checkSecret();
        if(code==null||!code.matches("[0-9]{6}")) throw new AuthException(400,"验证码格式错误");
        boolean valid=Boolean.TRUE.equals(tx.execute(s -> {
            var rows=jdbc.queryForList("SELECT *,CASE WHEN expires_at>CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS unexpired FROM email_code WHERE email=? AND purpose=? FOR UPDATE",email,purpose);
            if(rows.isEmpty()) return false; var row=rows.getFirst();
            if(Boolean.TRUE.equals(row.get("consumed"))||((Number)row.get("attempts")).intValue()>=5||((Number)row.get("unexpired")).intValue()!=1) return false;
            boolean match=MessageDigest.isEqual(((String)row.get("code_hash")).getBytes(StandardCharsets.UTF_8),digest(email+":"+purpose+":"+code).getBytes(StandardCharsets.UTF_8));
            jdbc.update("UPDATE email_code SET attempts=attempts+1,consumed=? WHERE email=? AND purpose=?",match,email,purpose);return match;
        }));
        if(!valid) throw new AuthException(401,"验证码错误、已使用或已过期");return email;
    }
    private void initLimit(String key) {try {jdbc.update("INSERT INTO email_send_limit(limit_key,reset_at) VALUES (?,CURRENT_TIMESTAMP)",key);} catch(DuplicateKeyException ignored) {}}
    private void limit(String key,int maximum,int seconds) {
        var row=jdbc.queryForMap("SELECT *,CASE WHEN reset_at<=CURRENT_TIMESTAMP THEN 1 ELSE 0 END AS window_expired FROM email_send_limit WHERE limit_key=? FOR UPDATE",key);
        boolean reset=((Number)row.get("window_expired")).intValue()==1;
        int count=reset?0:((Number)row.get("count_value")).intValue();
        if(count>=maximum) throw new AuthException(429,"验证码发送过于频繁，请稍后再试");
        if(reset) jdbc.update("UPDATE email_send_limit SET count_value=?,reset_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP) WHERE limit_key=?",count+1,seconds,key);
        else jdbc.update("UPDATE email_send_limit SET count_value=? WHERE limit_key=?",count+1,key);
    }
    public static String normalize(String value) {
        String email=value==null?"":value.trim().toLowerCase(Locale.ROOT);
        if(!email.matches("[a-z0-9][a-z0-9._-]{0,63}@qq\\.com")) throw new AuthException(400,"请使用有效的 QQ 邮箱（@qq.com）");return email;
    }
    private static void checkPurpose(String p) {if(p==null||!Set.of("REGISTER","LOGIN","BIND").contains(p)) throw new AuthException(400,"验证码用途无效");}
    private void checkSecret() {if(secret.length()<32) throw new AuthException(503,"验证码服务密钥未配置");}
    private String digest(String value) {try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));} catch(Exception e) {throw new IllegalStateException(e);}}
}
