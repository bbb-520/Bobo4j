package com.bbb.exercise.agentdemo.auth.email;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import com.bbb.exercise.agentdemo.auth.AuthService.AuthException;

@Component
public class SmtpEmailSender implements EmailSender {
    private final ObjectProvider<JavaMailSender> sender; private final String from;
    public SmtpEmailSender(ObjectProvider<JavaMailSender> sender,@Value("${app.email.from:}") String from) {this.sender=sender;this.from=from;}
    @Override public void send(String email,String code) {
        var mail=sender.getIfAvailable(); if(mail==null||from.isBlank()) throw new AuthException(503,"邮件服务尚未配置");
        var message=new SimpleMailMessage(); message.setFrom(from);message.setTo(email);message.setSubject("BoboWorld 登录验证码");
        message.setText("验证码："+code+"。5 分钟内有效，请勿向他人透露。");
        try {mail.send(message);} catch(org.springframework.mail.MailException e) {throw new AuthException(503,"验证码发送失败，请稍后重试");}
    }
}
