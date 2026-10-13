package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class GalleryCatalogTest {
    @Test @EnabledIfSystemProperty(named="gallery.catalog",matches=".+")
    void parsesEveryPinnedUpstreamCategoryWithoutAnyModelCall() throws Exception {
        var report=new GalleryMarkdownParser().parseRepository(Path.of(System.getProperty("gallery.catalog")),GallerySource.COMMIT);
        assertThat(report.categories()).isEqualTo(31);assertThat(report.entries()).isEqualTo(163);
        assertThat(report.documents()).hasSize(164);
        assertThat(report.issues()).noneMatch(i->java.util.Set.of("NO_PROMPT","UNCLOSED_FENCE","MISSING_SOURCE").contains(i.code()));
        assertThat(report.documents()).allSatisfy(doc->{
            assertThat(doc.getText()).doesNotContain("<img src=","- Metadata:","Load this file only when");
            assertThat(doc.getMetadata()).containsKeys("category","source_file","title","size","quality","tags","image_urls","source_commit");
        });
        System.out.printf("Gallery catalog: categories=%d entries=%d prompts=%d duplicates=%d missing-fields=%d%n",report.categories(),report.entries(),report.documents().size(),report.duplicates(),report.issues().size());
    }
}
