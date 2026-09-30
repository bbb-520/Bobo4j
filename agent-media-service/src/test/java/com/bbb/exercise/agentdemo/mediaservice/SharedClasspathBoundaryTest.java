package com.bbb.exercise.agentdemo.mediaservice;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

class SharedClasspathBoundaryTest {
    @Test
    void ossAdapterHasOneSharedDefinition() throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(
                "com/bbb/exercise/agentdemo1_0/oss/OssStorageService.class")))
                .as("OSS adapter must be owned by common").hasSize(1);
    }
}
