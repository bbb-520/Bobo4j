package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.model.ModelCapability;
import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import com.bbb.exercise.agentdemo.runtime.client.AuthModelClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证图片任务执行前重新校验供应商选择，并拒绝配置变更或移除后的任务。 */
class ImageJobWorkerProviderContractTest {
    @Test
    void rejectsAQueuedJobWhenTheSelectedProviderChanged() {
        var selected =
                new AuthModelClient.SelectedModel(
                        ModelProvider.GPT, ModelCapability.IMAGE, "model", "secret");

        assertThatThrownBy(
                        () -> ImageJobWorker.validateSelectedProvider(ModelProvider.QWEN, selected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提供商已变更");
    }

    @Test
    void rejectsNonQwenJobWhenItsProfileWasRemoved() {
        assertThatThrownBy(() -> ImageJobWorker.validateSelectedProvider(ModelProvider.GPT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型配置已失效");
    }
}
