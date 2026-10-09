package com.bbb.exercise.agentdemo.runtime.client;
/** Server-selected mode rules; untrusted evidence is supplied only as user data. */
public final class ModelPrompts {
    private ModelPrompts() {}
    public static String forMode(String mode,boolean fallback) {
        return com.bbb.exercise.agentdemo.api.model.ModelPrompts.forMode(mode,fallback);
    }
}
