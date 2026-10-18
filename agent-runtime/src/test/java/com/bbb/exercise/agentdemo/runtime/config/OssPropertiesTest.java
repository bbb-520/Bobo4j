package com.bbb.exercise.agentdemo.runtime.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证对象存储使用预期的默认归档目录。 */
class OssPropertiesTest {
    @Test
    void privateImageAndDownloadLinksUseHttpsForEveryAcceptedEndpointForm() {
        for (String endpoint : java.util.List.of("oss-cn-hangzhou.aliyuncs.com",
                "http://oss-cn-hangzhou.aliyuncs.com/", "https://oss-cn-hangzhou.aliyuncs.com")) {
            var properties = new OssProperties();
            properties.setBucket("test-bucket");
            properties.setEndpoint(endpoint);
            properties.setAccessKeyId("test-key");
            properties.setAccessKeySecret("test-secret");
            var storage = new com.bbb.exercise.agentdemo.runtime.storage.OssStorageService(properties);
            try {
                assertThat(java.net.URI.create(storage.signedGetUrl("output/test.png")).getScheme())
                        .isEqualTo("https");
                assertThat(java.net.URI.create(storage.signedDownloadUrl("output/test.png", "test.png",
                                java.time.Duration.ofMinutes(1))).getScheme()).isEqualTo("https");
            } finally {
                storage.close();
            }
        }
    }

    @Test
    void usesArchiveAsTheDefaultArchivePrefix() {
        assertThat(new OssProperties().getArchivePrefix()).isEqualTo("archive");
    }
}
