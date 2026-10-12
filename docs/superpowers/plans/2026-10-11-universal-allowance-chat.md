# Universal Allowance and Chat Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deploy the approved universal free allowance and text/image/document chat to boboo.xin.

**Architecture:** Reuse the wallet and durable execution infrastructure. Frontend uploads owned assets and documents; internal chat resolves image references, while mixed document/image execution first obtains image evidence and then invokes RAG. Preserve stable idempotency keys and actual usage metering.

**Tech Stack:** Java 21, Spring WebFlux, JDBC/MySQL, React/TypeScript, Node tests, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-11-universal-allowance-chat-design.md` (approved by user: 按此实现并部署).

## Global Constraints

- Preserve all existing wallet balances, remaining allowances, user changes, database data and OSS assets.
- Free allowance 1,000,000 Token; no-token image debit uses ceil(imageMicros * 1000 / ratePer1kMicros), currently 100,000 per image.
- Maximum 8 attachments/message, including maximum 3 images; image 20 MiB/document 100 MiB.
- Default chat, explicit single-source-image creation, automatic description/overview for attachment-only messages.
- Existing 64,000 execution Token hard budget, ownership checks, unknown-use reconciliation, stop/recovery protections remain.
- No new libraries, no commits requested, no restoration of user-deleted deployment example files.

## Review Focus

- Signing the same OSS object twice must not change billing idempotency identity (Task 2).
- Partial free allowance for per-image pricing must charge only residual cash and refund exactly once (Task 1).
- A second text question must retain uploaded image/document context after refresh (Task 4).
- Switching accounts during upload must not submit or render the prior account's attachments (Task 4).
- Crashing between image evidence and document QA must reuse the evidence receipt and budget (Task 3).

### Task 1: Universal wallet allowance

Files: `agent-auth-service/.../billing/BillingService.java`, `TokenAllowance.java`, `TokenAllowanceBillingTest.java`, `agent-api/.../billing/BillingContracts.java` if needed for explicit free allowance reporting.

Interfaces: existing reserve/settle/refund signatures remain. Usage may append `freeTokensUsed` as an additive JSON property; metering `IMAGE_TOKEN` denotes image-price conversion, never actual token fields.

- [x] Add and run failing H2 tests for IMAGE with zero cash, measured usage, unmeasured image conversion, partial allowance, exact-once refund, legacy image trial and debt protection; cover EMBEDDING/RERANK/EVALUATION allowance.
- [x] Implement integer conversion, universal admission and per-image allowance settlement.
- [x] Run billing tests plus affected auth suite; inspect all regressions.

### Task 2: Owned multiple-image model inputs

Files: `agent-api/.../model/ModelContracts.java`, `TokenBudgetEstimator.java`; `agent-runtime/.../client/ModelCallClient.java`; `agent-chat-service/.../chat/InternalCompletionService.java`, tests.

Interfaces: append `List<ChatAttachmentRequest> attachments` to `CompletionRequest`, preserving five- and six-argument constructors. Add `VisionImage(String assetId,String url)` and a vision overload accepting `List<VisionImage>`, `Long tokenBudget`, events. Preserve existing one-URL overload.

- [x] Add and run failing protocol tests proving multiple image content blocks, image-count budget, stable asset identity despite URL refresh, owned-asset lookup and idempotent replay.
- [x] Resolve assets via signed InternalServiceClient.mediaAsset for current owner; reject invalid/unready/unowned references before model dispatch.
- [x] Route CHAT requests with attachments to VISION, include history, forward normal answer events, and preserve committed receipt behavior.
- [x] Run runtime and chat suites.

### Task 3: Durable image and mixed document execution

Files: `agent-orchestrator-service/.../execution/ExecutionStore.java`, `ExecutionWorker.java`, their tests.

Interfaces: append `List<ChatAttachmentRequest> attachments` to `ExecutionStore.Create`, preserving old six-argument constructor. JSON field `attachments` contains asset ID and file metadata only. Normal CHAT completion body forwards attachments. DOCUMENT_QA with attachments obtains a stable `:vision` step, then feeds its answer as quoted untrusted evidence into normal `:document` step.

- [x] Add and run failing persistence/validation tests: null old attachment field, maximum three, duplicate/invalid references, request replay change conflict.
- [x] Add and run worker tests: CHAT forwards owned references; mixed input calls vision before RAG; retry reuses successful vision step and charges budget once.
- [x] Implement attachment normalization and mixed execution through existing step/lease/usage machinery; never commit preliminary image evidence as final document answer.
- [x] Run orchestrator suite.

### Task 4: Unified chat input and context

Frontend root: `D:/Desktop/Desktop/projectPractice/AgentWebDemo`.
Files: `src/components/Composer.tsx`, `ChatArea.tsx`, `MessageItem.tsx`, `App.tsx`, `hooks/useConversations.ts`, `api/executions.ts`, attachment helpers, `types.ts`, account/onboarding copy, regression scripts.

Interfaces: `onSend(q:string,files?:File[],kind?:'image'|'chat'):Promise<boolean>`; hook may retain legacy single-File callers. ExecutionRequest appends `attachments?: ImageAttachment[]`. Conversation retains `imageAttachments?: ImageAttachment[]`; message document attachment references are separate from image attachments.

- [x] Add and run failing tests for five required input forms, multiple/mixed attachments, default chat mode, limits, attachment-only prompts, document readiness/failed upload, scoped cancellation and context follow-up.
- [x] Implement selection/previews/error validation; upload images and documents, observe document readiness, create document scope, then submit durable execution. Persist uploaded references and progress/error state without Blob URLs.
- [x] Preserve explicit single-image creation and existing document manager; keep signed-in guards, IME/Enter behavior, wallet state and stop/refresh recovery.
- [x] Update allowance admission/copy to include image conversion and distinguish measured/converted usage.
- [x] Run all existing frontend scripts, typecheck and production build.

### Task 5: Integration, deployment and operations guide

Files: update `项目配置与运维指南.md`; ignored `.migration` scripts, package/build outputs; new server release folder and image tags.

- [x] Review combined changes and run affected backend reactor suites, noting the known missing-example contract test separately if still present.
- [x] Verify packaged jars/frontend bundle; create new immutable release artifacts and current Compose backup.
- [x] Upload SHA-verified jars and web bundle; build server images using existing local base images; update affected services and check health without deleting volumes.
- [x] Verify public page/version, unauthenticated API ownership boundaries and healthy services. Use a controlled temporary account only if a billable end-to-end test is possible without changing existing users' money or quota; clean temporary test data.
- [x] Document configuration locations, conversion formula, supported inputs, tested scope and rollback paths; return deployed result concisely.
