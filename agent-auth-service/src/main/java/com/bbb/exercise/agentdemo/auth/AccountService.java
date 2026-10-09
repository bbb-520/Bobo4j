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

@Service
public class AccountService {
    private final AuthService auth; private final AppUserMapper users; private final QqEmailService codes;
    private final BillingService billing; private final TransactionTemplate tx;
    public AccountService(AuthService auth,AppUserMapper users,QqEmailService codes,BillingService billing,TransactionTemplate tx) {this.auth=auth;this.users=users;this.codes=codes;this.billing=billing;this.tx=tx;}
    public AuthService.User register(String username,String password,String email,String code) {
        String verified=codes.consume(email,"REGISTER",code);
        try {return tx.execute(s -> {var user=auth.register(username,password);
            users.update(null,new LambdaUpdateWrapper<AppUserEntity>().eq(AppUserEntity::getId,user.id()).set(AppUserEntity::getQqEmail,verified));
            billing.createWallet(auth.publicUserId(user),0,1_000_000);return user;
        });} catch(DuplicateKeyException e) {throw new AuthService.AuthException(409,"QQ 邮箱已绑定其他账户");}
    }
    public AuthService.LoginResult emailLogin(String email,String code) {
        String verified=codes.consume(email,"LOGIN",code);
        var user=users.selectOne(new LambdaQueryWrapper<AppUserEntity>().eq(AppUserEntity::getQqEmail,verified));
        if(user==null) throw new AuthService.AuthException(401,"邮箱尚未绑定账户，请先注册或使用密码登录后绑定");
        return auth.issueSession(new AuthService.User(user.getId(),user.getUsername(),user.getPasswordHash()));
    }
    public void bind(ChatIdentity identity,String email,String code) {
        long id=auth.requireUserId(identity);String verified=codes.consume(email,"BIND",code);
        // Binding is one-time; changing a login identity needs a dedicated recovery flow.
        try {int changed=users.update(null,new LambdaUpdateWrapper<AppUserEntity>().eq(AppUserEntity::getId,id).isNull(AppUserEntity::getQqEmail).set(AppUserEntity::getQqEmail,verified));
            if(changed!=1) throw new AuthService.AuthException(409,"账户已绑定 QQ 邮箱");
        }catch(DuplicateKeyException e) {throw new AuthService.AuthException(409,"QQ 邮箱已绑定其他账户");}
    }
    public Map<String,Object> email(ChatIdentity identity) {var user=users.selectById(auth.requireUserId(identity));return Map.of("qqEmail",user.getQqEmail()==null?"":user.getQqEmail(),"emailBound",user.getQqEmail()!=null);}
}
