package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageAssetPreviewTest {
    @Test void signsOnlyOwnedReadyAssetsEvenAfterUploadPolicyExpired() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE image_asset(id VARCHAR(36),tenant_id VARCHAR(128),user_id VARCHAR(255),object_key VARCHAR(512),mime_type VARCHAR(128),file_size BIGINT,status VARCHAR(32),expires_at TIMESTAMP)");
        jdbc.update("INSERT INTO image_asset VALUES('a','t','alice','source/owned.png','image/png',10,'READY',TIMESTAMP '2020-01-01 00:00:00')");
        var storage = mock(OssStorageService.class);
        when(storage.signedGetUrl("source/owned.png")).thenReturn("https://oss.example/fresh");
        var service = new ImageAssetService(jdbc,new OssProperties(),storage);
        assertThat(service.preview(new ChatIdentity("t","alice",true),"a").previewUrl()).isEqualTo("https://oss.example/fresh");
        assertThatThrownBy(()->service.preview(new ChatIdentity("t","bob",true),"a")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE image_asset SET status='PENDING'");
        assertThatThrownBy(()->service.preview(new ChatIdentity("t","alice",true),"a")).isInstanceOf(IllegalArgumentException.class);
        verify(storage,times(1)).signedGetUrl(anyString());
    }
}
