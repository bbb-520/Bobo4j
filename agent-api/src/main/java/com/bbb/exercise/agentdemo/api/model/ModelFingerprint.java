package com.bbb.exercise.agentdemo.api.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Embedding spaces include configuration identity and dimensions; API keys are never inputs. */
public final class ModelFingerprint {
    private ModelFingerprint() {}
    public static String embedding(String provider,String model,String endpoint,String credentialId,int dimensions) {
        if(dimensions<=0)throw new IllegalArgumentException("Embedding dimensions must be positive");
        ModelProvider canonical=ModelProvider.parse(provider);
        String resolved=endpoint==null||endpoint.isBlank()?ModelProviderRegistry.defaultRegistry().resolve(canonical,ModelCapability.EMBEDDING,model).defaultBaseUrl():endpoint;
        String[] parts={canonical.name(),Objects.requireNonNull(model),resolved.replaceAll("/+$",""),Objects.requireNonNull(credentialId),Integer.toString(dimensions)};
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String part:parts){byte[] bytes=part.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
}
