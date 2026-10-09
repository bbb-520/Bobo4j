package com.bbb.exercise.agentdemo.auth.model;
import com.bbb.exercise.agentdemo.auth.AuthService;
import com.bbb.exercise.agentdemo.auth.PlatformModels;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class ModelProfileService {
    private final AuthService auth;private final PlatformModels models;
    public ModelProfileService(AuthService auth,PlatformModels models) {this.auth=auth;this.models=models;}
    public List<ModelProfile> list(ChatIdentity identity) {
        auth.requireUserId(identity);return List.of(ModelCapability.CHAT,ModelCapability.VISION,ModelCapability.IMAGE,ModelCapability.EMBEDDING,ModelCapability.RERANK,ModelCapability.EVALUATION,ModelCapability.FALLBACK).stream()
                .map(c -> {var m=models.select(c);return new ModelProfile(m.provider(),c,m.model(),true,null);}).toList();
    }
    public ModelProfile save(ChatIdentity identity,SaveRequest ignored) {auth.requireUserId(identity);throw new AuthService.AuthException(410,"平台模型由管理员统一配置");}
    public void clear(ChatIdentity identity,ModelCapability ignored) {auth.requireUserId(identity);throw new AuthService.AuthException(410,"平台模型由管理员统一配置");}
    public SelectedModel resolve(ChatIdentity identity,ModelCapability c) {auth.requireUserId(identity);return models.select(c);}
    public record SaveRequest(String provider,String capability,String model,String apiKey) {}
    public record SelectedModel(ModelProvider provider,ModelCapability capability,String model,String apiKey,String baseUrl,String credentialId) {
        public SelectedModel(ModelProvider provider,ModelCapability capability,String model,String apiKey) {this(provider,capability,model,apiKey,null,"platform");}
        @Override public String toString() {return "SelectedModel[provider="+provider+",capability="+capability+",model="+model+"]";}
    }
}
