# Prompt Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for integration, and dispatching-parallel-agents for the two independent data/API tasks. Steps use checkbox syntax.

**Goal:** Deliver the approved visual prompt library with complete copyable gallery and private owner memory, then publish and deploy.
**Architecture:** Static immutable gallery exported with the existing Java Markdown parser; a bounded read-only owner API for visual memories; a lazy full-screen React browser with split detail modal that preserves the underlying composer.
**Tech Stack:** Java21, Spring Boot4/WebFlux/JDBC, React18/TypeScript/Vite, existing PillNav, Node24 tests, Playwright Chromium.
**Spec:** docs/superpowers/specs/2026-10-14-prompt-library-design.md

## Global Constraints

- Gallery source commit9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f,164prompts/31categories; original full content, stable ID, real metadata and MIT attribution.
- No model/reindex/vector import calls or Token charges for browsing/copying; private memory must filter both tenant/user and never enter public catalog.
- Keep current chat/draft/files/execution alive under the library overlay; new CSS scoped, preserve user's index.css/main.tsx/theme.css changes.
- Public catalog browser24items; memory APIlimit1..24 default24, q≤160, owner-bound updated_at/id cursor, no-store responses.
- Secrets stay protected; deploy only chat service and frontend because other runtime code/SQL schema are unchanged.

## Shared contracts

Gallery JSON: `{source:{repository,commit,license,attribution},categories:[{id,label,keywords:string[]}],prompts:[{id,title,category,content,size,quality,tags:string[],sourceFile,sourceRepository,sourceCommit,author,attribution,sourceAttribution,variant,contentHash}]}`. Optional metadata may be blank, never invented. Catalog is src/data/prompt-gallery.json and loaded only when opening gallery.

Memory GET `/api/settings/visual-memory/prompts?q=&limit=24&cursor=` -> `{items:[{id:string,title:string,content:string,category:'visual-memory',source:'visual-memory',updatedAt:string,subjects:string[],palette:string,composition:string,mood:string,sourceAssetId:string}],nextCursor:string|null}`; memoryID is prefixed `memory:` and not an authorization primitive. Search is bounded parameterized read-only SQL. Existing chat:use policy and resolver stay authoritative.

## Review Focus

- Account switch while request/detail open must not reveal the previous owner's content, including one-frame stale state.
- Gallery-open/close, hash back/forward and login must preserve unsent text/files and active executions; no background generation.
- Copy exact whitespace/long JSON and handle denied clipboard; modal focus trap/Esc/focus return and nested scroll.
- Corrupt structured_json/unknown gallery fields/bad cover URLs must fail safely without exposing raw credentials or crossing owners.
- Cursor ties, concurrent insert/deletion and q-filter changes must not skip/duplicate unrelated owners; catalog completeness and no remote image/model calls.

### Task1: Complete immutable gallery catalog (independent agent)
Files: ops/prompt-library/export-catalog helper+instructions/tests; frontend/src/data/prompt-gallery.json and public/prompt-library/third-party license. Existing GalleryMarkdownParser reused unchanged.
- [x] Write failing contract/export tests, observe RED; build no Spring context/network.
- [x] Export164/31 exact entries with sourceCommit/hash/attribution and Chinese category labels; validate duplicate and full-body retention; GREEN.
- [x] Return exact files, tests and export command. No git/push/server actions.

### Task2: Private visual-memory browsing (independent agent)
Files: chatservice/memory/VisualPromptService.java, VisualPromptController.java, focused H2/security tests. No changes outside chat module.
- [x] RED tests for owner/tenant/inactive exclusion,24+1keyset/search/invalidparams/zeroModelCalls and real security-chain auth.
- [x] Implement DTO contract/read-only SQL and no-store; repair corrupted metadata conservatively without inventing content; GREEN.
- [x] Run chat reactor verify in isolated backend snapshot, no Maven install. Return exact files/evidence.

### Task3: Gallery UI and preserved-chat navigation (root)
Files: src/components/prompts/{PromptLibrary,PromptDetail,PromptCover,prompt-library.css}, src/lib/promptLibrary.ts, src/api/visualPrompts.ts, App.tsx, ChatArea.tsx, StartPage.tsx, tests/e2e; original style files untouched.
- [x] RED gallery/filter/copy/modal/private-scope tests and App draft/files continuity test.
- [x] Implement lazy overlay/PillNav,24card batches,31translatedcategories,illustrative local SVG covers with stable-ID override JSON, split modal/exact copy/manual fallback and mobile/dark CSS.
- [x] Wire owner API abort/fences and empty/retry state, keep account-scoped detail inaccessible after switch.
- [x] Focused GREEN, full frontend scripts/build/real Chromium incl390px+desktop screenshots and visual critique.

### Task4: Integration, review and release (root)
- [x] Review two agent deliverables, integrate selected owned files after baseline checks. Run backend fullverify + final frontend suite/build/Chromium.
- [x] Fresh whole-change review once; grade findings, one RED→GREENfix pass for Important/Critical; ledger minor limitations.
- [ ] README/config replacement instructions, source/asset/license checks, commit/push both existing codex branches, preserve external styles.
- [ ] Backup current chat/web image/config, verify artifact/source hashes/tmp; drain and guarded two-service rollout, rollback on failed acceptance, keep maintenance on rollback failure.
- [ ] Live owned read-only memory acceptance including cross-owner/no-cost and public gallery asset/version/full-catalog checks; disable fixtures, retain audit.
- [ ] Sync original selected source with backups, final report and deferred OSS state unchanged.
