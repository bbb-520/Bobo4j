package com.bbb.exercise.agentdemo.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("app_user")
public class AppUserEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String username;
    @TableField("public_user_id") private String publicUserId;
    @TableField("qq_email") private String qqEmail;
    public String getPublicUserId() {return publicUserId;} public void setPublicUserId(String v) {publicUserId=v;}
    public String getQqEmail() {return qqEmail;} public void setQqEmail(String v) {qqEmail=v;}
    @TableField("password_hash") private String passwordHash;
    @TableField("created_at") private LocalDateTime createdAt;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; } public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; } public void setPasswordHash(String v) { passwordHash = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
