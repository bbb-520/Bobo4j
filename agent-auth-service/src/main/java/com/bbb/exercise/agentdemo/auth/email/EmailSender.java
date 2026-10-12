package com.bbb.exercise.agentdemo.auth.email;

/** 验证码邮件发送契约，使验证码业务与具体邮件传输方式分离。 */
@FunctionalInterface
public interface EmailSender {
    void send(String email, String code);
}
