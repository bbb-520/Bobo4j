package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.runtime.config.AppProperties;
import org.junit.jupiter.api.Test;
import com.bbb.exercise.agentdemo.auth.entity.AppUserEntity;
import com.bbb.exercise.agentdemo.auth.mapper.AppUserMapper;
import com.bbb.exercise.agentdemo.auth.mapper.AuthSessionMapper;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServicePersistenceTest {
    @Test
    void registrationAndLoginPersistOnlyPasswordHashAndSessionHash() {
        AppUserMapper users = mock(AppUserMapper.class);
        AuthSessionMapper sessions = mock(AuthSessionMapper.class);
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash("password123")).thenReturn("bcrypt-hash");
        when(passwords.matches("password123", "bcrypt-hash")).thenReturn(true);
        AppUserEntity stored = new AppUserEntity(); stored.setId(7L); stored.setUsername("alice"); stored.setPasswordHash("bcrypt-hash");
        when(users.selectOne(any())).thenReturn(stored);
        doAnswer(inv -> { ((AppUserEntity) inv.getArgument(0)).setId(7L); return 1; }).when(users).insert(any(AppUserEntity.class));

        AuthService service = new AuthService(users, sessions, passwords, new AppProperties());

        AuthService.User registered = service.register("alice", "password123");
        AuthService.LoginResult login = service.login("alice", "password123");

        assertThat(registered.username()).isEqualTo("alice");
        assertThat(login.token()).isNotBlank();
        verify(users, atLeastOnce()).insert(any(AppUserEntity.class));
        verify(sessions).insert(any(com.bbb.exercise.agentdemo.auth.entity.AuthSessionEntity.class));
        verify(passwords).hash("password123");
    }

    @Test
    void internalLookupRejectsUnknownUserAndWrongTenant() {
        AppUserMapper users = mock(AppUserMapper.class);
        when(users.selectOne(any())).thenReturn(user(7L, "alice"));
        AuthService service = new AuthService(users, mock(AuthSessionMapper.class), mock(PasswordHasher.class), new AppProperties());
        String publicId = service.publicUserId(new AuthService.User(7L, "alice", ""));
        assertThat(publicId).isEqualTo("ad26db5c-7e4d-39bd-87b3-e1991585dca2");
        assertThat(service.resolvePublicUser(publicId, "local")).isNotNull();
        assertThat(service.resolvePublicUser("forged", "local")).isNull();
        assertThat(service.resolvePublicUser(publicId, "other-tenant")).isNull();
        verify(users,never()).selectList(any());
    }

    @Test
    void registrationDoesNotHideInfrastructureFailuresAsDuplicateUser() {
        AppUserMapper users = mock(AppUserMapper.class);
        when(users.selectOne(any())).thenReturn(null);
        doThrow(new IllegalStateException("database unavailable"))
                .when(users).insert(any(AppUserEntity.class));
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash("password123")).thenReturn("bcrypt-hash");

        AuthService service = new AuthService(users, mock(AuthSessionMapper.class), passwords, new AppProperties());

        assertThatThrownBy(() -> service.register("alice", "password123"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    private static AppUserEntity user(long id, String name) { AppUserEntity u = new AppUserEntity(); u.setId(id); u.setUsername(name); return u; }
}
