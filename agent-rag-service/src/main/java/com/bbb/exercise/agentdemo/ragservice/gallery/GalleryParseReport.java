package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.ai.document.Document;
import java.util.List;

/** A dry-run result; issues are data diagnostics, never source instructions. */
public record GalleryParseReport(List<Document> documents, int categories, int entries,
                                 int duplicates, List<Issue> issues) {
    public GalleryParseReport {
        documents = List.copyOf(documents);
        issues = List.copyOf(issues);
    }
    public record Issue(String sourceFile, String title, String code, String detail) {}
}
