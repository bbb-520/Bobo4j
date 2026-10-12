package com.bbb.exercise.agentdemo.mediaservice;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证媒体服务运行时类路径中只有一份共享对象存储适配器定义。 */
class SharedClasspathBoundaryTest {
    @Test
    void ossAdapterHasOneSharedDefinition() throws Exception {
        assertThat(
                        Collections.list(
                                getClass()
                                        .getClassLoader()
                                        .getResources(
                                                "com/bbb/exercise/agentdemo/runtime/storage/OssStorageService.class")))
                .as("OSS adapter must have one runtime definition")
                .hasSize(1);
    }
}
