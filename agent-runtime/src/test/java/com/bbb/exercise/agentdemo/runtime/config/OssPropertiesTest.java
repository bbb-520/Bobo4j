package com.bbb.exercise.agentdemo.runtime.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OssPropertiesTest {
    @Test
    void usesArchiveAsTheDefaultArchivePrefix() {
        assertThat(new OssProperties().getArchivePrefix()).isEqualTo("archive");
    }
}
