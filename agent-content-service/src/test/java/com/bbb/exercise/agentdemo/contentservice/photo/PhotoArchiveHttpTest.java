package com.bbb.exercise.agentdemo.contentservice.photo;

import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PhotoArchiveHttpTest {
    @Configuration @EnableWebFlux
    @ComponentScan("com.bbb.exercise.agentdemo.contentservice.photo")
    static class Config {
        @Bean OssProperties properties() {
            var p = new OssProperties(); p.setArchivePrefix("one and one"); return p;
        }
        @Bean OssStorageService storage() {
            return new OssStorageService(properties()) {
                @Override public List<ArchiveImage> listArchiveImages(String prefix) {
                    assertThat(prefix).isEqualTo("one and one");
                    return List.of(new ArchiveImage("a b.jpg", 123), new ArchiveImage("unsupported.bmp", 99));
                }
                @Override public String signedGetUrl(String key, String process) {
                    assertThat(key).isEqualTo("one and one/a b.jpg");
                    return "https://images.example/signed";
                }
            };
        }
    }

    @Test void publicManifestAndImageUseTheConfiguredAlbumOnly() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            client.get().uri("/api/photo-archive").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$[0].filename").isEqualTo("a b.jpg")
                    .jsonPath("$.length()").isEqualTo(1)
                    .jsonPath("$[0].objectKey").doesNotExist();
            client.get().uri(java.net.URI.create("/api/photo-archive/a%20b.jpg")).exchange().expectStatus().isFound()
                    .expectHeader().valueEquals("Location", "https://images.example/signed");
            client.get().uri("/api/photo-archive/secret.txt").exchange().expectStatus().isBadRequest();
            client.get().uri("/api/photo-archive/a..jpg").exchange().expectStatus().isBadRequest();
            client.get().uri("/api/photo-archive/else/a.jpg").exchange().expectStatus().isNotFound();
        }
    }
}
