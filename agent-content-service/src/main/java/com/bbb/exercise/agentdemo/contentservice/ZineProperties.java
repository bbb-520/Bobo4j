package com.bbb.exercise.agentdemo.contentservice;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
@ConfigurationProperties(prefix="app.zine") public class ZineProperties {
 private boolean enabled=true,promptExtend=true,watermark=false; private String baseUrl="https://dashscope.aliyuncs.com/api/v1",endpoint="/services/aigc/multimodal-generation/generation",model="qwen-image-3.0-pro",size="1024*1707"; private Duration timeout=Duration.ofSeconds(180); private int maxUploadBytes=10*1024*1024;
 public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;} public String getEndpoint(){return endpoint;} public void setEndpoint(String v){endpoint=v;} public String getModel(){return model;} public void setModel(String v){model=v;} public String getSize(){return size;} public void setSize(String v){size=v;} public boolean isPromptExtend(){return promptExtend;} public void setPromptExtend(boolean v){promptExtend=v;} public boolean isWatermark(){return watermark;} public void setWatermark(boolean v){watermark=v;} public Duration getTimeout(){return timeout;} public void setTimeout(Duration v){timeout=v;} public int getMaxUploadBytes(){return maxUploadBytes;} public void setMaxUploadBytes(int v){maxUploadBytes=v;}
}
