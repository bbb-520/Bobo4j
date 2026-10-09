package com.bbb.exercise.agentdemo.runtime.storage;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OssRemoteUriSecurityTest {
    @Test
    void rejectsNonHttpAndLocalTargetsBeforeDownloading() {
        assertThatThrownBy(() -> OssStorageService.validateRemoteUri("file:///etc/passwd"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> OssStorageService.validateRemoteUri("http://127.0.0.1/admin"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> OssStorageService.validateRemoteUri("http://localhost/admin"))
                .isInstanceOf(IOException.class);
    }
}
