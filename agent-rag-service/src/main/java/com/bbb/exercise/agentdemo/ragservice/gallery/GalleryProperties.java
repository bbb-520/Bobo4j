package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.prompt-gallery")
public class GalleryProperties {
    private boolean enabled;
    private boolean chatEnabled;
    private String sourceRoot = "";
    private String sourceCommit = GallerySource.COMMIT;
    private String collection = "agent_prompt_gallery_openai_small_1536_v1";
    private String milvusUri = "http://localhost:19530";
    private String milvusToken = "";
    private int dimensions = 1536;
    private String embeddingSpace = "openai/text-embedding-3-small/v1";
    private String embeddingModel = "text-embedding-3-small";
    private String chatModel = "gpt-4.1-mini";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private int maxConcurrent = 2;
    private int maxRequestsPerMinute = 10;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled=value; }
    public boolean isChatEnabled() { return chatEnabled; }
    public void setChatEnabled(boolean value) { chatEnabled=value; }
    public String getSourceRoot() { return sourceRoot; }
    public void setSourceRoot(String value) { sourceRoot=value; }
    public String getSourceCommit() { return sourceCommit; }
    public void setSourceCommit(String value) { sourceCommit=value; }
    public String getCollection() { return collection; }
    public void setCollection(String value) { collection=value; }
    public String getMilvusUri() { return milvusUri; }
    public void setMilvusUri(String value) { milvusUri=value; }
    public String getMilvusToken() { return milvusToken; }
    public void setMilvusToken(String value) { milvusToken=value; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int value) { dimensions=value; }
    public String getEmbeddingSpace() { return embeddingSpace; }
    public void setEmbeddingSpace(String value) { embeddingSpace=value; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String value) { embeddingModel=value; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String value) { chatModel=value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl=value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey=value; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int value) { maxConcurrent=value; }
    public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
    public void setMaxRequestsPerMinute(int value) { maxRequestsPerMinute=value; }
}
