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

- [ ] Write/run failing owner isolation, optimistic concurrency, tombstone, payload size and metadata sanitization tests.
- [ ] Implement bounded index/detail reads and compare-and-set writes, preserving stable conversation/request IDs.
- [ ] Implement initial merge, explicit deletion tombstones, serialized debounced writes and account epoch cancellation. Surface sync failures without losing local data.
- [ ] Run backend and frontend recovery tests.

## Root task 2: uploaded source image preview recovery

Files: existing media `ImageAssetService` and controller; frontend `src/lib/attachmentPreviews.ts` and hook integration.

- [ ] Write/run failing owner/READY and expired-upload-token preview tests.
- [ ] Issue fresh short-lived GET URLs only for owned READY assets. Upload authorization expiry is distinct from viewing previously uploaded assets.
- [ ] Restore previews after reload and handle expiry without persisting signed URLs.
- [ ] Run media/security and frontend preview tests.

## Agent deliverables and integration

- [ ] Agent 1 provides regression tests, isolated commits and exact remaining limitations.
- [ ] Agent 2 clones and parses every referenced category, records upstream revision/license and parser report, provides live VectorStore configuration and semantic search/chat examples.
- [ ] Review commits, resolve shared dependency/config/API interfaces, compile and run relevant/full regression suites.
- [ ] Document configuration, migration/backfill/import commands, test evidence and unverified external integrations. Never claim a real remote import from mock tests.
