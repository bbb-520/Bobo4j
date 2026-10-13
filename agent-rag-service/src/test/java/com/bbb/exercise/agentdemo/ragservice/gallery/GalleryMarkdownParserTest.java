package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GalleryMarkdownParserTest {
    private final GalleryMarkdownParser parser = new GalleryMarkdownParser();
    @TempDir Path root;

    @Test void parsesOnlyPromptBodyAndRetainsNestedHeadingsAndAttribution() {
        var report = parser.parse("photography", "gallery-photography.md", """
                # Photography
                Routing instructions must not enter the prompt.
                ### No. 67 · Natural portrait
                - Image: `docs/photo.png`
                <img src="../../../docs/photo.png" width="420"/>
                - Metadata: Photography · `portrait` · `1024x1536` · Author: Ada · Source: [Article](https://example.org/source)
                ```text
                Create a portrait.
                # Realism Guidelines
                ## Basic mindset
                Preserve freckles.
                ![preview](https://example.org/image.png)
                ```
                [Back to gallery](gallery.md)
                """, "abc123");
        assertThat(report.documents()).hasSize(1);
        var doc = report.documents().getFirst();
        assertThat(doc.getText()).isEqualTo("Create a portrait.\n# Realism Guidelines\n## Basic mindset\nPreserve freckles.");
        assertThat(doc.getMetadata()).containsEntry("size", "1024x1536").containsEntry("author", "Ada")
                .containsEntry("category", "photography").containsEntry("source_commit", "abc123");
        assertThat(doc.getMetadata().get("image_urls").toString()).contains("docs/photo.png", "../../../docs/photo.png");
        assertThat(report.issues()).anyMatch(i -> i.code().equals("MISSING_FIELD") && i.detail().contains("quality"));
    }

    @Test void emitsSeparateAdaptationBlocksAndReportsDuplicatesAndMissingPrompts() {
        var report = parser.parse("isometric", "gallery-isometric.md", """
                # Isometric
                ### No. 54 · Cafe
                - Metadata: Isometric · `square` · `1024x1024` · Curated
                ```text
                A tiny cafe.
                ```
                - Output metadata: `gpt-image-2.5` · `high` · `2048x2048`
                - Adapted edit prompt: Curated
                ```text
                A tiny cafe at night.
                ```
                ### No. 55 · Duplicate
                ```text
                A tiny cafe.
                ```
                ### No. 56 · Empty
                [Navigation](gallery.md)
                """, "abc");
        assertThat(report.entries()).isEqualTo(3);
        assertThat(report.documents()).hasSize(2);
        assertThat(report.documents().get(1).getMetadata()).containsEntry("quality", "high").containsEntry("size", "2048x2048");
        assertThat(report.duplicates()).isEqualTo(1);
        assertThat(report.issues()).anyMatch(i -> i.code().equals("NO_PROMPT"));
        assertThat(parser.parse("isometric", "other-file.md", "## Other title\n```text\nA tiny cafe.\n```", "new").documents().getFirst().getId())
                .isEqualTo(report.documents().getFirst().getId());
    }

    @Test void supportsExplicitUnfencedPromptAndMetadataVariantsWithoutNavigation() {
        var report = parser.parse("watercolor", "gallery-watercolor.md", """
                # Watercolor
                ## Mountain
                **Size:** 1536x1024
                **Quality:** high
                **Tags:** landscape, paint
                ### Prompt
                Paint a mountain.
                Keep soft edges.
                ![image](https://example.org/mountain.png)
                ### Notes
                Some curator commentary.
                """, "abc");
        assertThat(report.documents()).hasSize(1);
        assertThat(report.documents().getFirst().getText()).isEqualTo("Paint a mountain.\nKeep soft edges.");
        assertThat(report.documents().getFirst().getMetadata()).containsEntry("quality", "high").containsEntry("tags", List.of("landscape", "paint"));
    }

    @Test void discoversOnlyLocalLinkedCategoriesAndRejectsEscapingPaths() throws Exception {
        Files.createDirectories(root.resolve("skills/gpt-image/references"));
        Path refs = root.resolve("skills/gpt-image/references");
        Files.writeString(refs.resolve("gallery.md"), "[Art](gallery-art.md)\n[Missing](gallery-missing.md)\n[Unsafe](../../../../outside.md)\n");
        Files.writeString(refs.resolve("gallery-art.md"), "# Art\n### No. 1 · Picture\n```text\nDraw a tree.\n```\n");
        var report = parser.parseRepository(root, "abc");
        assertThat(report.categories()).isEqualTo(1);
        assertThat(report.documents()).hasSize(1);
        assertThat(report.issues()).anyMatch(i -> i.code().equals("MISSING_SOURCE"));
    }

    @Test void rejectsUnclosedFenceInsteadOfSilentlyIndexingTruncatedPrompt() {
        var report=parser.parse("art","gallery-art.md","# Art\n### No. 1 · Broken\n```text\nA truncated prompt.","abc");
        assertThat(report.documents()).isEmpty();
        assertThat(report.issues()).anyMatch(i -> i.code().equals("UNCLOSED_FENCE"));
    }
}
