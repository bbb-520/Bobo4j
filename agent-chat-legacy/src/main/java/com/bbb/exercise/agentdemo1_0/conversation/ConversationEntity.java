package com.bbb.exercise.agentdemo1_0.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话归属与生命周期元数据。 */
@Data
@TableName("chat_conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;
    private String userId;
    private String conversationId;
    private String title;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
