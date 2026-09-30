package com.bbb.exercise.agentdemo1_0.generation;

import java.util.List;

/** Structured, user-visible understanding of an image before generation. */
public record SceneCard(String summary,
                        List<String> subjects,
                        String palette,
                        String composition,
                        String mood,
                        String sourceAssetId) {
    public SceneCard {
        summary = clean(summary);
        palette = clean(palette);
        composition = clean(composition);
        mood = clean(mood);
        sourceAssetId = clean(sourceAssetId);
        subjects = subjects == null ? List.of() : subjects.stream()
                .map(SceneCard::clean).filter(value -> !value.isBlank()).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
