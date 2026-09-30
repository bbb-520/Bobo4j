package com.bbb.exercise.agentdemo1_0.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bbb.exercise.agentdemo1_0.auth.ApiKeyCrypto;
import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Auth-owned model configuration persisted through MyBatis-Plus only. */
@Service
public class ModelProfileService {
    private final ModelProfileMapper profiles; private final AuthService auth; private final ApiKeyCrypto crypto; private final ModelProviderRegistry registry;
    public ModelProfileService(ModelProfileMapper profiles, AuthService auth, ApiKeyCrypto crypto, ModelProviderRegistry registry){this.profiles=profiles;this.auth=auth;this.crypto=crypto;this.registry=registry;}
    public List<ModelProfile> list(ChatIdentity identity){ long id=auth.requireUserId(identity); return profiles.selectList(new LambdaQueryWrapper<ModelProfileEntity>().eq(ModelProfileEntity::getUserId,id).orderByAsc(ModelProfileEntity::getCapability)).stream().map(e->new ModelProfile(ModelProvider.parse(e.getProvider()),ModelCapability.parse(e.getCapability()),e.getModel(),Boolean.TRUE.equals(e.getEnabled()),ModelProfile.maskSecret(e.getApiKeyCiphertext()))).toList(); }
    public ModelProfile save(ChatIdentity identity,SaveRequest req){ if(req==null)throw new ResponseStatusException(BAD_REQUEST,"模型配置不能为空"); long id=auth.requireUserId(identity); ModelProvider p=ModelProvider.parse(req.provider()); ModelCapability c=ModelCapability.parse(req.capability()); registry.resolve(p,c,req.model()); ModelProfileEntity e=profiles.selectOne(new LambdaQueryWrapper<ModelProfileEntity>().eq(ModelProfileEntity::getUserId,id).eq(ModelProfileEntity::getCapability,c.name())); String key=req.apiKey()==null?null:req.apiKey().trim(); if(key==null||key.isBlank()){if(e==null||e.getApiKeyCiphertext()==null||e.getApiKeyCiphertext().isBlank())throw new ResponseStatusException(BAD_REQUEST,"首次配置该模型时必须填写 API Key");} else key=crypto.encrypt(key); if(e==null){e=new ModelProfileEntity();e.setUserId(id);e.setCreatedAt(LocalDateTime.now());} e.setProvider(p.name());e.setCapability(c.name());e.setModel(req.model().trim());if(key!=null&&!key.isBlank())e.setApiKeyCiphertext(key);e.setEnabled(true);e.setUpdatedAt(LocalDateTime.now());if(e.getId()==null)profiles.insert(e);else profiles.updateById(e);return list(identity).stream().filter(x->x.capability()==c).findFirst().orElseThrow(); }
    public void clear(ChatIdentity identity,ModelCapability c){profiles.delete(new LambdaQueryWrapper<ModelProfileEntity>().eq(ModelProfileEntity::getUserId,auth.requireUserId(identity)).eq(ModelProfileEntity::getCapability,c.name()));}
    public SelectedModel resolve(ChatIdentity identity,ModelCapability c){long id=auth.requireUserId(identity);ModelProfileEntity e=profiles.selectOne(new LambdaQueryWrapper<ModelProfileEntity>().eq(ModelProfileEntity::getUserId,id).eq(ModelProfileEntity::getCapability,c.name()).eq(ModelProfileEntity::getEnabled,true));if(e!=null)return selected(e,c);if(c==ModelCapability.VISION){e=profiles.selectOne(new LambdaQueryWrapper<ModelProfileEntity>().eq(ModelProfileEntity::getUserId,id).eq(ModelProfileEntity::getCapability,"CHAT").eq(ModelProfileEntity::getEnabled,true));if(e!=null){registry.resolve(ModelProvider.parse(e.getProvider()),ModelCapability.VISION,e.getModel());return selected(e,ModelCapability.VISION);}}return null;}
    private SelectedModel selected(ModelProfileEntity e,ModelCapability c){return new SelectedModel(ModelProvider.parse(e.getProvider()),c,e.getModel(),crypto.decrypt(e.getApiKeyCiphertext()));}
    public record SaveRequest(String provider,String capability,String model,String apiKey){} public record SelectedModel(ModelProvider provider,ModelCapability capability,String model,String apiKey){}
}
