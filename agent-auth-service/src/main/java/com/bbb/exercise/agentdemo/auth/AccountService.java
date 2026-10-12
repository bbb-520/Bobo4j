package com.bbb.exercise.agentdemo.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bbb.exercise.agentdemo.auth.entity.AppUserEntity;
import com.bbb.exercise.agentdemo.auth.mapper.AppUserMapper;
import com.bbb.exercise.agentdemo.auth.email.QqEmailService;
import com.bbb.exercise.agentdemo.auth.billing.BillingService;
import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

/** 账户业务协调器，串联邮箱验证、注册、默认角色和初始钱包创建。 */
@Service
public class AccountService {
    private final AuthService auth;
    private final AppUserMapper users;
    private final QqEmailService codes;
    private final BillingService billing;
    private final TransactionTemplate tx;
    private final com.bbb.exercise.agentdemo.auth.rbac.RbacService rbac;

    public AccountService(
            AuthService auth,
            AppUserMapper users,
            QqEmailService codes,
            BillingService billing,
            TransactionTemplate tx,
            com.bbb.exercise.agentdemo.auth.rbac.RbacService rbac) {
        this.auth = auth;
        this.users = users;
        this.codes = codes;
        this.billing = billing;
        this.tx = tx;
        this.rbac = rbac;
    }

    // 1. 账户注册：邮箱验证通过后，统一创建账户、默认角色和初始钱包。
    public AuthService.User register(String username, String password, String email, String code) {
        // 1.1. 先消费 REGISTER 验证码；验证失败时不进入账户创建事务。
        String verified = codes.consume(email, "REGISTER", code);

        try {
            // 1.2. 账户、角色、邮箱和钱包共享事务，避免注册成功但配套数据缺失。
            return tx.execute(
                    s -> {
                        var user = auth.register(username, password);

                        // 1.2.1. 授予默认角色，失败时连同本次新建账户一起回滚。
                        rbac.registerUser(user.id());
                        users.update(
                                null,
                                new LambdaUpdateWrapper<AppUserEntity>()
                                        .eq(AppUserEntity::getId, user.id())
                                        .set(AppUserEntity::getQqEmail, verified));
                        // 1.2.2. 用公开用户 ID 创建钱包，并发放初始免费 Token。
                        billing.createWallet(auth.publicUserId(user), 0, 1_000_000);

                        return user;
                    });
        } catch (DuplicateKeyException e) {
            throw new AuthService.AuthException(409, "QQ 邮箱已绑定其他账户");
        }
    }

    // 2. 邮箱登录：消费 LOGIN 验证码，再为已绑定账户签发会话。
    public AuthService.LoginResult emailLogin(String email, String code) {
        String verified = codes.consume(email, "LOGIN", code);
        var user =
                users.selectOne(
                        new LambdaQueryWrapper<AppUserEntity>()
                                .eq(AppUserEntity::getQqEmail, verified));

        if (user == null) {
            throw new AuthService.AuthException(401, "邮箱尚未绑定账户，请先注册或使用密码登录后绑定");
        }

        return auth.issueSession(
                new AuthService.User(user.getId(), user.getUsername(), user.getPasswordHash()));
    }

    // 3. 邮箱绑定：仅允许首次绑定，邮箱唯一约束同时防止跨账户占用。
    public void bind(ChatIdentity identity, String email, String code) {
        long id = auth.requireUserId(identity);
        String verified = codes.consume(email, "BIND", code);

        // Binding is one-time; changing a login identity needs a dedicated recovery flow.
        try {
            int changed =
                    users.update(
                            null,
                            new LambdaUpdateWrapper<AppUserEntity>()
                                    .eq(AppUserEntity::getId, id)
                                    .isNull(AppUserEntity::getQqEmail)
                                    .set(AppUserEntity::getQqEmail, verified));

            if (changed != 1) {
                throw new AuthService.AuthException(409, "账户已绑定 QQ 邮箱");
            }
        } catch (DuplicateKeyException e) {
            throw new AuthService.AuthException(409, "QQ 邮箱已绑定其他账户");
        }
    }

    // 3.1. 绑定状态查询：以当前登录账户为准，不接受外部传入的账户 ID。
    public Map<String, Object> email(ChatIdentity identity) {
        var user = users.selectById(auth.requireUserId(identity));
        return Map.of(
                "qqEmail",
                user.getQqEmail() == null ? "" : user.getQqEmail(),
                "emailBound",
                user.getQqEmail() != null);
    }
}
