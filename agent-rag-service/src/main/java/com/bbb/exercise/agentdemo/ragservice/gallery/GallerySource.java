package com.bbb.exercise.agentdemo.ragservice.gallery;

import java.io.IOException;
import java.nio.file.*;

/** Pinned provenance, not executable instructions. */
public final class GallerySource {
    public static final String REPOSITORY = "https://github.com/wuyoscar/GPT-Image2-Skill";
    public static final String COMMIT = "9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f";
    public static final String LICENSE = "MIT";
    public static final String ATTRIBUTION = "Copyright (c) 2026 Wuyoscar";
    private GallerySource() {}
    /** Read Git metadata as files; never run upstream scripts, hooks or Git commands. */
    public static void verifyCommit(Path repository,String expected) throws IOException {
        if(expected==null || !expected.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("PROMPT_GALLERY_SOURCE_COMMIT must be the full 40-character source commit");
        Path git=repository.toRealPath().resolve(".git");
        if(!Files.isDirectory(git)) throw new IOException("Configured source must be a local Git clone with a .git directory");
        String head=Files.readString(git.resolve("HEAD")).strip();String actual=head;
        if(head.startsWith("ref: ")) {
            String ref=head.substring(5);Path file=git.resolve(ref).normalize();
            if(!file.startsWith(git) || !ref.matches("refs/[A-Za-z0-9_./-]+")) throw new IOException("Invalid source checkout reference");
            if(Files.isRegularFile(file)) actual=Files.readString(file).strip();
            else if(Files.isRegularFile(git.resolve("packed-refs"))) {
                actual=Files.readAllLines(git.resolve("packed-refs")).stream().filter(line->line.endsWith(" "+ref))
                        .map(line->line.split(" ",2)[0]).findFirst().orElse("");
            }
        }
        if(!expected.equals(actual)) throw new IllegalArgumentException("Configured checkout does not match PROMPT_GALLERY_SOURCE_COMMIT; check out the pinned commit or explicitly update the source commit");
    }
}
