package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class PromptGalleryService {
    private final VectorStore vectorStore;
    private final GalleryMarkdownParser parser;
    private final GalleryProperties properties;
    public PromptGalleryService(VectorStore vectorStore, GalleryMarkdownParser parser, GalleryProperties properties) {
        this.vectorStore=vectorStore;this.parser=parser;this.properties=properties;
    }
    public List<Document> searchPrompts(String query,int topK,String category) {
        if(query==null || query.isBlank() || query.length()>2000) throw new IllegalArgumentException("query must contain 1..2000 characters");
        if(topK<1 || topK>20) throw new IllegalArgumentException("topK must be 1..20");
        if(category!=null && category.length()>128) throw new IllegalArgumentException("category must be <=128 characters");
        var request=SearchRequest.builder().query(query.strip()).topK(topK).similarityThresholdAll();
        if(category!=null && !category.isBlank()) request.filterExpression(new FilterExpressionBuilder().eq("category",category.strip()).build());
        return vectorStore.similaritySearch(request.build());
    }
    /** Read only the server-configured checkout; HTTP callers cannot supply arbitrary paths. */
    public GalleryParseReport previewImport() throws IOException {
        if(properties.getSourceRoot()==null || properties.getSourceRoot().isBlank())
            throw new IllegalArgumentException("Set PROMPT_GALLERY_SOURCE_ROOT to the pinned local GPT-Image2-Skill checkout");
        Path root=Path.of(properties.getSourceRoot());GallerySource.verifyCommit(root,properties.getSourceCommit());
        return parser.parseRepository(root,properties.getSourceCommit());
    }
    /** Explicit invocation only. Stable category/content IDs and Milvus upsert make repeated imports idempotent. */
    public synchronized ImportResult importConfiguredSource(boolean dryRun) throws IOException {
        var report=previewImport();int written=0;
        if(!dryRun) for(int i=0;i<report.documents().size();i+=16) {
            var batch=report.documents().subList(i,Math.min(i+16,report.documents().size()));vectorStore.add(batch);written+=batch.size();
        }
        return new ImportResult(dryRun,report.categories(),report.entries(),report.documents().size(),written,report.duplicates(),properties.getSourceCommit(),report.issues());
    }
    public record ImportResult(boolean dryRun,int categories,int entries,int parsedPrompts,int upserted,int duplicates,String sourceCommit,List<GalleryParseReport.Issue> issues) {}
}
