package com.bbb.exercise.agentdemo.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 验证注册请求不能通过额外字段为新账户自行授予角色。 */
class RegistrationFieldsTest {
    @Test
    void registrationCannotAcceptSelfAssignedRoles() {
        var mapper = JsonMapper.builder().build();
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"username\":\"alice\",\"password\":\"password123\",\"qqEmail\":\"2@qq.com\",\"code\":\"123456\",\"roles\":[\"SUPER_ADMIN\"]}",
                                        AuthController.Registration.class))
                .isInstanceOf(RuntimeException.class);
    }
}
