package com.bbb.exercise.agentdemo1_0.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Password hashing; the raw password is never persisted or logged. */
@Component
public class PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String hash(String raw) { return encoder.encode(raw); }
    public boolean matches(String raw, String encoded) { return encoder.matches(raw, encoded); }
}
