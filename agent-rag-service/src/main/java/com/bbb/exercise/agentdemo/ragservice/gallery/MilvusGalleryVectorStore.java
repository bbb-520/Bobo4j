package com.bbb.exercise.agentdemo.ragservice.gallery;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import io.milvus.v2.client.*;
import io.milvus.v2.common.*;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.FloatVec;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.Filter;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;

/** Persistent Spring AI VectorStore on the existing Milvus v2 SDK, with atomic upsert. */
public final class MilvusGalleryVectorStore implements VectorStore, AutoCloseable {
    private final GalleryProperties properties;
    private final Supplier<EmbeddingModel> embeddings;
    private final Supplier<MilvusClientV2> clients;
    private final Gson gson = new Gson();
    private volatile MilvusClientV2 client;
    public MilvusGalleryVectorStore(GalleryProperties properties, Supplier<EmbeddingModel> embeddings) {
        this(properties, embeddings, () -> new MilvusClientV2(ConnectConfig.builder()
                .uri(properties.getMilvusUri()).token(properties.getMilvusToken())
                .connectTimeoutMs(5000L).rpcDeadlineMs(30000L).build()));
    }
    MilvusGalleryVectorStore(GalleryProperties properties, Supplier<EmbeddingModel> embeddings, Supplier<MilvusClientV2> clients) {
        this.properties=properties; this.embeddings=embeddings; this.clients=clients;
        if (!properties.getCollection().matches("agent_prompt_gallery_[A-Za-z0-9_]{1,100}"))
            throw new IllegalArgumentException("Gallery collection must begin agent_prompt_gallery_ and use only ASCII letters, digits and underscore");
        if (properties.getDimensions()<1 || properties.getDimensions()>32768)
            throw new IllegalArgumentException("Gallery dimensions must be 1..32768");
        if (properties.getEmbeddingSpace()==null || properties.getEmbeddingSpace().isBlank())
            throw new IllegalArgumentException("Set app.prompt-gallery.embedding-space to identify the exact embedding model/version");
    }
    String schemaDescription() {
        String identity=properties.getEmbeddingSpace()+"\n"+properties.getBaseUrl()+"\n"+properties.getEmbeddingModel()+"\n"+properties.getDimensions();
        try { return "prompt-gallery-v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private synchronized MilvusClientV2 client() {
        if (client!=null) return client;
        MilvusClientV2 candidate=clients.get();
        try {
            if (!candidate.hasCollection(HasCollectionReq.builder().collectionName(properties.getCollection()).build())) {
                var schema=CreateCollectionReq.CollectionSchema.builder().enableDynamicField(false).build();
                schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar).maxLength(64).isPrimaryKey(true).autoID(false).build());
                schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(65535).build());
                schema.addField(AddFieldReq.builder().fieldName("metadata").dataType(DataType.JSON).build());
                schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(properties.getDimensions()).build());
                candidate.createCollection(CreateCollectionReq.builder().collectionName(properties.getCollection())
                        .description(schemaDescription()).collectionSchema(schema).consistencyLevel(ConsistencyLevel.STRONG)
                        .indexParams(List.of(IndexParam.builder().fieldName("embedding").indexType(IndexParam.IndexType.HNSW)
                                .metricType(IndexParam.MetricType.COSINE).extraParams(Map.of("M",16,"efConstruction",128)).build())).build());
            }
            var described=candidate.describeCollection(DescribeCollectionReq.builder().collectionName(properties.getCollection()).build());
            if (!schemaDescription().equals(described.getDescription()) || described.getCollectionSchema()==null
                    || !described.getFieldNames().containsAll(List.of("id","content","metadata","embedding"))
                    || !Objects.equals(described.getCollectionSchema().getField("embedding").getDimension(),properties.getDimensions()))
                throw new GalleryNotReadyException("Gallery embedding space or schema differs; configure a new agent_prompt_gallery_ collection and reimport");
            candidate.loadCollection(LoadCollectionReq.builder().collectionName(properties.getCollection()).build());
            client=candidate; return client;
        } catch(RuntimeException e) { candidate.close(); throw e; }
    }
    @Override public void add(List<Document> documents) {
        Objects.requireNonNull(documents);
        if(documents.isEmpty()) return;
        if(documents.size()>100) throw new IllegalArgumentException("Gallery batch exceeds 100 documents");
        for(var doc:documents) {
            if(doc.getId()==null || doc.getId().length()>64 || doc.getText()==null || doc.getText().isBlank()
                    || doc.getText().getBytes(StandardCharsets.UTF_8).length>65535)
                throw new IllegalArgumentException("Gallery document requires id <=64 chars and nonempty text <=65535 UTF-8 bytes");
        }
        var model=embeddings.get(); // Validate credentials without a model call before touching Milvus.
        var persistent=client();
        var vectors=model.embed(documents.stream().map(Document::getText).toList());
        if(vectors.size()!=documents.size()) throw new GalleryNotReadyException("Gallery embedding count mismatch");
        var rows=new ArrayList<JsonObject>();
        for(int i=0;i<documents.size();i++) {
            float[] vector=checked(vectors.get(i));
            var row=new JsonObject();row.addProperty("id",documents.get(i).getId());row.addProperty("content",documents.get(i).getText());
            row.add("metadata",gson.toJsonTree(documents.get(i).getMetadata()));row.add("embedding",gson.toJsonTree(vector));rows.add(row);
        }
        persistent.upsert(UpsertReq.builder().collectionName(properties.getCollection()).data(rows).build());
    }
    @Override public List<Document> similaritySearch(SearchRequest request) {
        Objects.requireNonNull(request);
        if(request.getTopK()<1 || request.getTopK()>20 || request.getQuery()==null || request.getQuery().isBlank() || request.getQuery().length()>2000)
            throw new IllegalArgumentException("Gallery search requires nonempty query <=2000 chars and topK 1..20");
        var values=new LinkedHashMap<String,Object>();
        String filter=request.hasFilterExpression()?expression(request.getFilterExpression(),values):"";
        var model=embeddings.get();var persistent=client();
        float[] vector=checked(model.embed(request.getQuery()));
        var response=persistent.search(SearchReq.builder().collectionName(properties.getCollection()).annsField("embedding")
                .data(List.of(new FloatVec(vector))).topK(request.getTopK()).metricType(IndexParam.MetricType.COSINE)
                .filter(filter).filterTemplateValues(values).outputFields(List.of("id","content","metadata"))
                .consistencyLevel(ConsistencyLevel.STRONG).build());
        if(response.getSearchResults().isEmpty()) return List.of();
        var results=new ArrayList<Document>();
        for(var hit:response.getSearchResults().getFirst()) {
            if(hit.getScore()<request.getSimilarityThreshold()) continue;
            var entity=hit.getEntity();
            Map<String,Object> metadata=gson.fromJson(gson.toJsonTree(entity.get("metadata")),new TypeToken<LinkedHashMap<String,Object>>(){}.getType());
            results.add(Document.builder().id(String.valueOf(hit.getId())).text(String.valueOf(entity.get("content")))
                    .metadata(metadata==null?Map.of():metadata).score(hit.getScore().doubleValue()).build());
        }
        return List.copyOf(results);
    }
    @Override public void delete(List<String> ids) {
        if(ids==null || ids.isEmpty()) return;
        client().delete(DeleteReq.builder().collectionName(properties.getCollection()).ids(new ArrayList<Object>(ids)).build());
    }
    @Override public void delete(Filter.Expression filter) {
        var values=new LinkedHashMap<String,Object>();
        String encoded=expression(filter,values);
        client().delete(DeleteReq.builder().collectionName(properties.getCollection()).filter(encoded).filterTemplateValues(values).build());
    }
    private static String expression(Filter.Expression expression,Map<String,Object> values) {
        if(expression==null) throw new IllegalArgumentException("Gallery filter is required");
        if(expression.type()==Filter.ExpressionType.AND && expression.left() instanceof Filter.Expression left && expression.right() instanceof Filter.Expression right)
            return "("+expression(left,values)+" and "+expression(right,values)+")";
        if(expression.type()!=Filter.ExpressionType.EQ || !(expression.left() instanceof Filter.Key key)
                || !(expression.right() instanceof Filter.Value value) || !Set.of("category","source_commit","content_hash").contains(key.key())
                || !(value.value() instanceof String literal) || literal.length()>256)
            throw new IllegalArgumentException("Gallery supports equality filters on category, source_commit or content_hash only");
        String parameter="gallery_filter_"+values.size();values.put(parameter,literal);
        return "metadata[\""+key.key()+"\"] == {"+parameter+"}";
    }
    private float[] checked(float[] vector) {
        if(vector==null || vector.length!=properties.getDimensions()) throw new GalleryNotReadyException("Gallery embedding dimensions mismatch; verify configured model and collection");
        boolean nonzero=false;
        for(float value:vector) {
            if(!Float.isFinite(value)) throw new GalleryNotReadyException("Gallery embedding contains nonfinite value");
            if(value!=0) nonzero=true;
        }
        if(!nonzero) throw new GalleryNotReadyException("Gallery embedding is a zero vector; cosine similarity requires a nonzero vector");
        return vector;
    }
    @Override public synchronized void close() { if(client!=null) { client.close();client=null; } }
}
