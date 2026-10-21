package com.bbb.exercise.agentdemo.runtime.storage;

import java.io.IOException;

/**
 * 永久交付失败：重试同一份供应商结果不会改变结论。
 *
 * <p>典型来源是结果地址 404/403、内容超出对象大小上限、内容不是受支持图片、
 * Base64 非法或空内容。与瞬态故障（连接失败、超时、5xx、429）区分开，
 * 使上层能够停止重试、保留回执并转人工核对，而不是无限回队列。
 */
public class PermanentDeliveryException extends IOException {

    public PermanentDeliveryException(String message) {
        super(message);
    }

    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
