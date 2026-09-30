package com.bbb.exercise.agentdemo1_0.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("auth_session")
public class AuthSessionEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("user_id") private Long userId;
    @TableField("token_hash") private String tokenHash;
    @TableField("expires_at") private LocalDateTime expiresAt;
    @TableField("created_at") private LocalDateTime createdAt;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getTokenHash() { return tokenHash; } public void setTokenHash(String v) { tokenHash = v; }
    public LocalDateTime getExpiresAt() { return expiresAt; } public void setExpiresAt(LocalDateTime v) { expiresAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
