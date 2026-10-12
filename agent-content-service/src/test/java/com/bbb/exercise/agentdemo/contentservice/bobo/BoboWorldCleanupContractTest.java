package com.bbb.exercise.agentdemo.contentservice.bobo;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient;
import com.bbb.exercise.agentdemo.runtime.config.OssProperties;
import com.bbb.exercise.agentdemo.runtime.storage.OssStorageService;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证已删除作品会调度文件清理，且公开查询不暴露提示词或因缺少身份而崩溃。 */
class BoboWorldCleanupContractTest {
    @Test
    void schedulesCleanupForDeletedWorldObjects() {
        assertThat(
                        Arrays.stream(BoboWorldService.class.getDeclaredMethods())
                                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                                .map(Method::getName))
                .contains("cleanupDeletedObjects");
    }

    @Test
    void missingIdentityDoesNotCauseNullPointerDuringPublicLookup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        when(jdbc.query(
                        anyString(),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        anyString()))
                .thenReturn(java.util.List.of());
        BoboWorldService service =
                new BoboWorldService(
                        jdbc,
                        mock(InternalServiceClient.class),
                        mock(OssStorageService.class),
                        new OssProperties());

        assertThatThrownBy(() -> service.get(null, "private-item"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void publicItemDetailDoesNotExposeGenerationPrompt() {
        assertThat(
                        Arrays.stream(BoboWorldService.ItemDetail.class.getRecordComponents())
                                .map(component -> component.getName()))
                .doesNotContain("prompt");
    }
}
