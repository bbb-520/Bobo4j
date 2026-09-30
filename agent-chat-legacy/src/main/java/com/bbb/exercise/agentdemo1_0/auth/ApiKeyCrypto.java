package com.bbb.exercise.agentdemo1_0.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher; import javax.crypto.spec.GCMParameterSpec; import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom; import java.util.Base64;

/**
 * Versioned AES-256-GCM envelope; legacy plaintext rows fail closed.
 */
@Component
public class ApiKeyCrypto {
    private static final String PREFIX="v1:"; private final byte[] key; private final SecureRandom random=new SecureRandom();
    public ApiKeyCrypto(@Value("${app.security.api-key-encryption-key:}") String encodedKey){try{key=Base64.getDecoder().decode(encodedKey==null?"":encodedKey);}catch(IllegalArgumentException e){throw new IllegalStateException("API Key 加密密钥必须是 Base64",e);}}

    public String encrypt(String value) {
        if(value==null||value.isBlank())return null;requireKey();try{byte[] iv=new byte[12];random.nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] b=c.doFinal(value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));byte[] all=new byte[12+b.length];System.arraycopy(iv,0,all,0,12);System.arraycopy(b,0,all,12,b.length);return PREFIX+Base64.getEncoder().encodeToString(all);}catch(Exception e){throw new IllegalStateException("API Key 加密失败",e);}
    }

    public String decrypt(String value) {
        if(value==null||value.isBlank())return null;requireKey();if(!value.startsWith(PREFIX))throw new IllegalStateException("检测到未迁移的 API Key");try{byte[] all=Base64.getDecoder().decode(value.substring(PREFIX.length()));if(all.length<=12)throw new IllegalArgumentException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,java.util.Arrays.copyOf(all,12)));return new String(c.doFinal(java.util.Arrays.copyOfRange(all,12,all.length)),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("API Key 解密失败",e);}
    }
    private void requireKey(){if(key.length!=32)throw new IllegalStateException("必须配置 32 字节 Base64 API Key 加密密钥");}
}
