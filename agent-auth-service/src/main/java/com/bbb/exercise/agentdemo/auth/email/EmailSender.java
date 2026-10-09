package com.bbb.exercise.agentdemo.auth.email;

@FunctionalInterface
public interface EmailSender { void send(String email,String code); }
