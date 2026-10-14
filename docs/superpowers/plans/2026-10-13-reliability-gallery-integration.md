# Reliability and prompt gallery integration plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Follow the verification gates below.

**Goal:** Fix confirmed recovery, billing and routing defects, replace fixed tool/lexical retrieval limitations, and provide a runnable semantic prompt gallery.

**Architecture:** Two independent implementation agents work from backend commit 66e7f58. Existing dispatch receipts and owner boundaries remain authoritative. The gallery has a separate collection, explicit administrative import, environment configuration and no automatic paid startup work. Browser conversation snapshots supplement model history; they never become trusted billing or execution records.

**Tech stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, MySQL, Milvus, React/TypeScript.

**Scope:** User approved confirmed bugs plus tool registration, visual vector retrieval and actual rate/circuit controls. Production capacity/security changes and real payment acceptance remain separate. This implementation does not deploy production.

## Ownership

- Agent 1: existing backend modules, frontend routing/storage safeguards, refunds, model propagation, grounded answer parsing, tools and resilience.
- Agent 2: independent gallery clone; new RAG gallery package, dependencies, tests, fixtures and usage documentation.
- Root: owner-scoped conversation snapshot API, source preview rehydration, integration and final verification. Root owns chat migration V4; Agent 1 starts new chat migrations at V5.

## Review focus

- Unknown paid calls must not be retried or silently billed as zero.
- Concurrent clients must not overwrite newer snapshots or resurrect deleted conversations.
- Identity changes must cancel in-flight sync/preview callbacks.
- Gallery metadata is untrusted data; filters must use typed expressions and import must stay administrative.
- Vector model/dimension changes must not mix incompatible embeddings.

## Root task 1: durable browser conversation snapshots

Files: `agent-chat-service/.../conversation/sync/ConversationSnapshotService.java`, `ConversationSnapshotController.java`, migration `V4__conversation_snapshots.sql`; frontend `src/lib/conversationSync.ts` and integration in `useConversations.ts`.

- [x] Write/run failing owner isolation, optimistic concurrency, tombstone, payload size and metadata sanitization tests.
- [x] Implement bounded index/detail reads and compare-and-set writes, preserving stable conversation/request IDs.
- [x] Implement initial merge, explicit deletion tombstones, serialized debounced writes and account epoch cancellation. Surface sync failures without losing local data.
- [x] Run backend and frontend recovery tests.

## Root task 2: uploaded source image preview recovery

Files: existing media `ImageAssetService` and controller; frontend `src/lib/attachmentPreviews.ts` and hook integration.

- [x] Write/run failing owner/READY and expired-upload-token preview tests.
- [x] Issue fresh short-lived GET URLs only for owned READY assets. Upload authorization expiry is distinct from viewing previously uploaded assets.
- [x] Restore previews after reload and handle expiry without persisting signed URLs.
- [x] Run media/security and frontend preview tests.

## Agent deliverables and integration

- [x] Agent 1 provides regression tests, isolated commits and exact remaining limitations.
- [x] Agent 2 clones and parses every referenced category, records upstream revision/license and parser report, provides live VectorStore configuration and semantic search/chat examples.
- [x] Review commits, resolve shared dependency/config/API interfaces, compile and run relevant/full regression suites.
- [x] Document configuration, migration/backfill/import commands, test evidence and unverified external integrations. Never claim a real remote import from mock tests.

## Final evidence

- Full clean backend verify succeeded; final scoped RAG verify succeeded after trailing metadata and historical grounding receipt regressions.
- Latest reports: 557 tests, 552 passed, 5 environment skips, zero failures/errors; no baseline exclusions.
- Frontend: all 16 test scripts and production build succeeded.
- Real local Milvus test succeeded and cleaned its temporary collection. Full pinned catalog: 31 categories, 163 entries, 164 prompts, zero duplicates, 327 missing-field notices.
- Cross-review reproduced then fixed stopped text recovery creating a missing execution, future-revision tombstone resurrection, new-turn-only storage quota failure, and prompt metadata after fences.
- Historical production test receipts identified the grounding format issue; repaired without another model call or weakened verdict validation. Production paid acceptance, semantic OpenAI import and deployment were not performed.
