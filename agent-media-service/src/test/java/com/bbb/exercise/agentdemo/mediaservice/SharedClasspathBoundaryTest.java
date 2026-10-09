package com.bbb.exercise.agentdemo.mediaservice;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

class SharedClasspathBoundaryTest {
    @Test
    void ossAdapterHasOneSharedDefinition() throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(
                "com/bbb/exercise/agentdemo/runtime/storage/OssStorageService.class")))
                .as("OSS adapter must have one runtime definition").hasSize(1);
    }
}
