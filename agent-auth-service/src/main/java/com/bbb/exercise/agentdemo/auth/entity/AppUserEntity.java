package com.bbb.exercise.agentdemo.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 用户账户表的持久化映射，保存账户状态、公开标识、邮箱及密码哈希。 */
@TableName("app_user")
public class AppUserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String status = "ACTIVE";

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime v) {
        updatedAt = v;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime v) {
        lastLoginAt = v;
    }

    @TableField("public_user_id")
    private String publicUserId;

    @TableField("qq_email")
    private String qqEmail;

    public String getPublicUserId() {
        return publicUserId;
    }

    public void setPublicUserId(String v) {
        publicUserId = v;
    }

    public String getQqEmail() {
        return qqEmail;
    }

    public void setQqEmail(String v) {
        qqEmail = v;
    }

    @TableField("password_hash")
    private String passwordHash;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String v) {
        passwordHash = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime v) {
        createdAt = v;
    }
}
