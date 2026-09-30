package com.bbb.exercise.agentdemo1_0.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("app_user")
public class AppUserEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String username;
    @TableField("password_hash") private String passwordHash;
    @TableField("created_at") private LocalDateTime createdAt;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; } public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; } public void setPasswordHash(String v) { passwordHash = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
