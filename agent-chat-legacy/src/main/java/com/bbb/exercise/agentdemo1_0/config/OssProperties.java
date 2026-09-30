package com.bbb.exercise.agentdemo1_0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 阿里云 OSS 私有 Bucket 配置。密钥只从运行环境读取。 */
@ConfigurationProperties(prefix = "app.oss")
public class OssProperties {
    private boolean enabled = true;
    private String region = "cn-beijing";
    private String bucket = "bbb-image-prod";
    private String endpoint = "oss-cn-beijing.aliyuncs.com";
    private String accessKeyId = "";
    private String accessKeySecret = "";
    private String sourcePrefix = "source";
    private String outputPrefix = "output";
    private String thumbnailPrefix = "thumbnail";
    /** 只读相册对象前缀；前端通过后端签名接口读取，不暴露 OSS 密钥。 */
    private String archivePrefix = "one and one";
    private Duration uploadPolicyTtl = Duration.ofMinutes(10);
    private Duration signedUrlTtl = Duration.ofMinutes(10);
    private long maxObjectBytes = 20L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
    public String getSourcePrefix() { return sourcePrefix; }
    public void setSourcePrefix(String sourcePrefix) { this.sourcePrefix = sourcePrefix; }
    public String getOutputPrefix() { return outputPrefix; }
    public void setOutputPrefix(String outputPrefix) { this.outputPrefix = outputPrefix; }
    public String getThumbnailPrefix() { return thumbnailPrefix; }
    public void setThumbnailPrefix(String thumbnailPrefix) { this.thumbnailPrefix = thumbnailPrefix; }
    public String getArchivePrefix() { return archivePrefix; }
    public void setArchivePrefix(String archivePrefix) { this.archivePrefix = archivePrefix; }
    public Duration getUploadPolicyTtl() { return uploadPolicyTtl; }
    public void setUploadPolicyTtl(Duration uploadPolicyTtl) { this.uploadPolicyTtl = uploadPolicyTtl; }
    public Duration getSignedUrlTtl() { return signedUrlTtl; }
    public void setSignedUrlTtl(Duration signedUrlTtl) { this.signedUrlTtl = signedUrlTtl; }
    public long getMaxObjectBytes() { return maxObjectBytes; }
    public void setMaxObjectBytes(long maxObjectBytes) { this.maxObjectBytes = maxObjectBytes; }
}
