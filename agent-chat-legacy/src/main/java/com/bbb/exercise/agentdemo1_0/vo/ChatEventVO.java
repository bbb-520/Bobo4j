package com.bbb.exercise.agentdemo1_0.vo;

import com.bbb.exercise.agentdemo1_0.enums.ChatEventTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 对外 SSE 事件载体：流式对话中每推送一条事件，就是本对象被序列化后的结果。
 * 前端按 {@code eventType} 分派处理（正文 / 工具直播 / 用量 / 结束 等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEventVO {

    /** 事件携带的数据 */
    private Object eventData;

    /** 事件类型，见 {@link ChatEventTypeEnum#getValue()} */
    private int eventType;
}
