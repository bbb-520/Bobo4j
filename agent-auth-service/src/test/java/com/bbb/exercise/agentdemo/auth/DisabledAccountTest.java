package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.auth.entity.*;
import com.bbb.exercise.agentdemo.auth.mapper.*;
import com.bbb.exercise.agentdemo.runtime.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpCookie;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;

/** 验证停用或不存在的账户无法签发会话、登录或解析有效身份。 */
class DisabledAccountTest {
    @Test
    void disabledAccountIsRejectedByAllIdentityAndLoginPaths() {
        var users = mock(AppUserMapper.class);
        var sessions = mock(AuthSessionMapper.class);
        var passwords = mock(PasswordHasher.class);
        var stored = new AppUserEntity();

        stored.setId(1L);
        stored.setUsername("bbb");
        stored.setStatus("DISABLED");
        stored.setPasswordHash("hash");
        when(users.selectById(1L)).thenReturn(stored);
        when(users.lockById(1L)).thenReturn(stored);
        when(users.selectOne(any())).thenReturn(stored);
        when(passwords.matches("password123", "hash")).thenReturn(true);
        var session = new AuthSessionEntity();

        session.setUserId(1L);
        when(sessions.selectOne(any())).thenReturn(session);
        var auth = new AuthService(users, sessions, passwords, new AppProperties());

        assertThatThrownBy(() -> auth.login("bbb", "password123"))
                .isInstanceOf(AuthService.AuthException.class);
        assertThatThrownBy(() -> auth.issueSession(new AuthService.User(1, "bbb", "hash")))
                .isInstanceOf(AuthService.AuthException.class);
        assertThat(auth.resolveSessionToken("old")).isNull();
        assertThat(auth.resolvePublicUser("b5240720-c317-3554-8d4e-36a48e6e1c31", "local"))
                .isNull();
        assertThat(auth.identityForPublicUser("b5240720-c317-3554-8d4e-36a48e6e1c31", "local"))
                .isNull();
        var ex =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/auth/me")
                                .cookie(new HttpCookie("bbb_agent_session", "old")));

        assertThat(auth.resolve(ex)).isNull();
        verify(sessions, never()).insert(any(AuthSessionEntity.class));
    }

    @Test
    void missingAccountCannotIssueOrResolveSession() {
        var users = mock(AppUserMapper.class);
        var sessions = mock(AuthSessionMapper.class);
        var auth =
                new AuthService(users, sessions, mock(PasswordHasher.class), new AppProperties());
        var session = new AuthSessionEntity();

        session.setUserId(1L);
        when(sessions.selectOne(any())).thenReturn(session);
        assertThat(auth.resolveSessionToken("old-session")).isNull();
        var ex =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/auth/me")
                                .cookie(new HttpCookie("bbb_agent_session", "old-session")));

        assertThat(auth.resolve(ex)).isNull();
        assertThatThrownBy(() -> auth.issueSession(new AuthService.User(1, "deleted", "hash")))
                .isInstanceOf(AuthService.AuthException.class);
        verify(sessions, never()).insert(any(AuthSessionEntity.class));
    }
}
