package com.bbb.exercise.agentdemo.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("user_api_key")
public class UserApiKeyEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("user_id") private Long userId;
    @TableField("qwen_api_key_ciphertext") private String qwenApiKeyCiphertext;
    @TableField("tavily_api_key_ciphertext") private String tavilyApiKeyCiphertext;
    @TableField("updated_at") private LocalDateTime updatedAt;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getQwenApiKeyCiphertext() { return qwenApiKeyCiphertext; } public void setQwenApiKeyCiphertext(String v) { qwenApiKeyCiphertext = v; }
    public String getTavilyApiKeyCiphertext() { return tavilyApiKeyCiphertext; } public void setTavilyApiKeyCiphertext(String v) { tavilyApiKeyCiphertext = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
