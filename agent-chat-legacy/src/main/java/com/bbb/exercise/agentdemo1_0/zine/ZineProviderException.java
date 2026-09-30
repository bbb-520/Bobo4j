package com.bbb.exercise.agentdemo1_0.zine;

/** Safe, user-facing failure from the configured image provider. */
public class ZineProviderException extends RuntimeException {

    public ZineProviderException(String message) {
        super(message);
    }

    public ZineProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
