package com.bbb.exercise.agentdemo1_0.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("user_model_profile")
public class ModelProfileEntity {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("user_id") private Long userId;
    private String provider; private String capability; private String model;
    @TableField("api_key_ciphertext") private String apiKeyCiphertext;
    private Boolean enabled;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public String getProvider(){return provider;} public void setProvider(String v){provider=v;}
    public String getCapability(){return capability;} public void setCapability(String v){capability=v;}
    public String getModel(){return model;} public void setModel(String v){model=v;}
    public String getApiKeyCiphertext(){return apiKeyCiphertext;} public void setApiKeyCiphertext(String v){apiKeyCiphertext=v;}
    public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
