# Shared image-intent routing

Image-context requests in the matching frontend use `POST /api/chat/image-intent` before submitting vision or image work. The authenticated JSON request is `{ "question": "..." }`, limited to 4000 Unicode code points. The response is `{ "intent": "GENERATE|REMIX|DESCRIBE|WAIT_FOR_INSTRUCTION" }`.

GENERATE creates a new image; REMIX edits the selected reference; DESCRIBE continues image Q&A. Uncertain, malformed, or failed classification stops dispatch. Classification uses the existing billed CLASSIFY runtime with a 16-token output cap and no automatic fallback/retry. The subsequent media/vision endpoints enforce asset ownership.

Frontend companion: [AgentWebDemo image-intent branch](https://github.com/bbb-520/AgentWebDemo/tree/codex/image-intent-routing). It retains uploaded references, fences stop/account changes, reclassifies explicit retries after reload, and reuses durable image request IDs.

Local verification: frontend160 affected tests passed; development backend168 passed+1 infrastructure skip; this public backend's7 endpoint tests passed. TypeScript and frontend production build passed. No deployment or real paid provider call was performed; real classification quality remains an outstanding evaluation task.
