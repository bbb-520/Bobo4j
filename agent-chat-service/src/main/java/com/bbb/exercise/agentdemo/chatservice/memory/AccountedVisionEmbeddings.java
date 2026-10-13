package com.bbb.exercise.agentdemo.chatservice.memory;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.runtime.client.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AccountedVisionEmbeddings implements VisionEmbeddings {
    private final ModelCallClient calls;private final AuthModelClient selection;private final int dimensions;
    public AccountedVisionEmbeddings(ModelCallClient calls,AuthModelClient selection,@Value("${app.vision-memory.dimensions:1024}") int dimensions) {
        if(!Set.of(64,128,256,512,768,1024,1536,2048).contains(dimensions))throw new IllegalArgumentException("视觉记忆向量维度无效");
        this.calls=calls;this.selection=selection;this.dimensions=dimensions;
    }
    public Space open(ChatIdentity identity,String callId,Long budget) {
        var selected=selection.resolve(identity,ModelCapability.EMBEDDING);
        if(selected==null)throw new ModelCallClient.ModelCallException("MODEL_NOT_CONFIGURED",false,503);
        String fingerprint=ModelFingerprint.embedding(selected.provider().name(),selected.model(),selected.baseUrl(),selected.credentialId(),dimensions);
        String ownedCall=callId.startsWith("vision:")?callId+":"+fingerprint.substring(0,16):callId;
        var tokens=new AtomicLong();
        EmbeddingModel model=new EmbeddingModel() {
            public EmbeddingResponse call(EmbeddingRequest request) {
                var result=calls.embed(identity,ownedCall,request.getInstructions(),dimensions,budget);
                if(!fingerprint.equals(result.modelFingerprint()))throw new IllegalStateException("视觉向量模型已变更，请重新索引");
                tokens.addAndGet(result.inputTokens());var embeddings=new ArrayList<Embedding>();
                for(int i=0;i<result.vectors().size();i++){var values=result.vectors().get(i);float[] vector=new float[values.size()];for(int j=0;j<vector.length;j++)vector[j]=values.get(j);embeddings.add(new Embedding(vector,i));}
                return new EmbeddingResponse(embeddings);
            }
            public float[] embed(Document document){return embed(document.getText());}
            public int dimensions(){return dimensions;}
        };
        return new Space(model,fingerprint,dimensions,tokens::get);
    }
}
