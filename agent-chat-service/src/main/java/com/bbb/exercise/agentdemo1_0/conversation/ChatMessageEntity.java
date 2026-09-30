package com.bbb.exercise.agentdemo1_0.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 长期保存的对话消息。 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationDbId;
    private String role;
    private String content;
    private Integer completed;
    /** 服务端接收并持久化这条消息的时间；不是客户端提交的时间。 */
    private LocalDateTime createdAt;
}
