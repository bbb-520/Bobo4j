# Prompt Gallery RAG Implementation Plan

> **For agentic workers:** Execute inline using superpowers:executing-plans; no additional agents, as explicitly requested.

**Goal:** Import the referenced prompt catalog into a separate persistent vector collection and expose authenticated search and optional prompt adaptation.
**Architecture:** Parse only catalog-linked Markdown data into Spring AI Documents; a dedicated Milvus v2 VectorStore embeds body text and upserts stable IDs. Opt-in configuration creates models lazily and uses existing beans when supplied; authenticated REST uses existing document routes.
**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Milvus SDK 2.6.4, JUnit 5.
**Spec:** Parent task dispatch (2026-10-13), reflected in docs/prompt-gallery.md.

## Global Constraints
- No changes to original workspace or publish-backend; isolated codex/prompt-gallery branch from 66e7f581.
- No upstream skill/script execution or image downloads; preserve upstream MIT attribution and pinned commit.
- No automatic import, credentials in source, or paid test calls. Java tests require escalated execution.
- Reuse /api/documents/prompt-gallery/** and browser session/CSRF authentication. All paid operations additionally require ADMIN/SUPER_ADMIN plus model:manage. Search uses POST with CSRF.

## Review Focus
- Headings and code fences inside prompt text must survive without splitting entries.
- Multiple prompt blocks within one entry must be separate complete Documents.
- Unsafe category strings must remain AST literal values when converted to Milvus filters.
- Duplicate imports must upsert the same stable IDs without polluting user RAG collections.
- Disabled or keyless gallery must not break existing startup or make network calls.

### Task 1: Markdown catalog parser
Files: gallery/GalleryMarkdownParser.java, GalleryParseReport.java, GallerySource.java; tests GalleryMarkdownParserTest.java.
Interface: parseRepository(Path root,String commit) -> GalleryParseReport; parse(String category,String source,String markdown,String commit) -> GalleryParseReport.
- [x] Write tests for real source variants, multiple fences, nested prompt headings, clean content, missing fields and duplicate IDs.
- [x] Run targeted tests and observe missing implementation failure.
- [x] Implement parser and source manifest with confined local category links.
- [x] Verify fixture suite and real 31-category/163-entry catalog parsing; commit.

### Task 2: Persistent embedding and search
Files: gallery/MilvusGalleryVectorStore.java, PromptGalleryService.java, GalleryProperties.java; tests MilvusGalleryVectorStoreTest.java, PromptGalleryServiceTest.java.
Interface: VectorStore.add(List<Document>) idempotent upsert; similaritySearch(SearchRequest); searchPrompts(String,int,String) -> List<Document>.
- [x] Write failing contract tests for embedding body only, schema isolation, safe filter, real SDK upsert/search requests, and validation.
- [x] Implement lazy Milvus schema/client initialization, explicit dimensions and JSON metadata arrays; validate supplied collection prefix.
- [x] Implement explicit import and bounded search with Filter.Expression; verify contract tests; commit.

### Task 3: Opt-in models, ChatClient and REST
Files: gallery/GalleryConfiguration.java, GalleryPromptAssistant.java, GalleryController.java, GalleryAccess.java, application-gallery.yml; corresponding tests.
- [x] Write failing tests for disabled startup, missing keys, reused beans, authorization and context injection boundary.
- [x] Implement opt-in OpenAI fallback, optional rewrite/search/context/ChatClient, admin import/dry-run and authenticated search.
- [x] Document environment configuration, import source provenance, examples, model-space rebuild and billing semantics.
- [x] Run module/dependency tests, full reactor tests as appropriate and inspect diff; commit final result.

## Execution record
- Ruling: use custom Milvus v2 VectorStore because official 2.0.1 MilvusVectorStore uses insert, not upsert; retain existing SDK version and isolate metadata schema.
- Ruling: inherited explicit implementation authorization supersedes skill handoff confirmation. Root performs final independent review/integration.

## Verification record (2026-10-13)
- Parser RED: missing implementation; GREEN: 5 fixture tests.
- Vector/search RED: missing implementations; GREEN: 12 parser/store/search tests.
- Configuration/ChatClient/access RED: missing implementations, then exact 2.0.1 API adaptation; GREEN: 25 tests including provenance, full source coverage and real Milvus integration.
- REST RED: existing global advice returned 500 instead of actionable 503; GREEN: prioritized gallery-specific safe diagnostics. Final POST route and admin boundaries passed 3 REST tests in full regression.
- Full RAG/dependency suite initially blocked by pre-existing DeploymentAssetsContractTest.deploymentAssetsRemainPresentAtRepositoryRoot (missing tracked config/model-routing.env.example). No gallery files caused this failure.
- Full RAG tests: 84 tests, 0 failures/errors, 2 environment-gated skips; includes all 28 gallery tests and real local Milvus with deterministic embedding. Runtime dependency: 44 tests, 0 failures/errors. Common remaining contracts also verified, with only exact baseline method excluded.
- Actual upstream coverage: 31 categories, 163 numbered entries, 164 prompts, 0 duplicates, 327 declared missing-field issues. No broken/no-prompt category entries.
- Ruling: no additional agents; root supplies independent final review. Do not claim real OpenAI embeddings/import or chat generation: no API key configured and no paid call performed.
- Ruling: root requested admin cost restriction instead of user wallet integration for this standalone gallery. All paid POST routes require existing browser session/CSRF plus ADMIN/SUPER_ADMIN and model:manage, with process concurrency/rate limits; ordinary-user paid endpoints remain unavailable.
- Ruling: repeated stable-ID upserts give row idempotence, not cost idempotence; docs explicitly describe re-embedding cost and new-collection strategy for changed corpus/model.
- Final independent-review fixes: existing business ChatClient with default tools/advisors must not be inherited; zero cosine vectors rejected. Both tests were observed RED (2 failures) before implementation, then GREEN in the full suite.
- Final exact-exclusion verification: mvn -ntp -pl agent-rag-service -am test -Dtest=*,!DeploymentAssetsContractTest#deploymentAssetsRemainPresentAtRepositoryRoot -Dsurefire.failIfNoSpecifiedTests=false -Dgallery.catalog=<pinned-checkout>, with PROMPT_GALLERY_MILVUS_INTEGRATION=true: RAG 86 tests / 0 failures / 0 errors / 2 gated skips; gallery 30 tests all passed; runtime 44 passed; common 11 / 0 failures / 1 gated skip. Real Milvus test passed and temporary collection cleaned.
