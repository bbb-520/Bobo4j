package com.bbb.exercise.agentdemo.ragservice.gallery;

/** Only locally authored, credential-free configuration diagnostics may reach HTTP clients. */
public final class GalleryNotReadyException extends IllegalStateException {
    public GalleryNotReadyException(String message) { super(message); }
}
