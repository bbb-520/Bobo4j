# 06: Whole-attempt conversation history

Both legacy chat and durable CHAT completion now share `ConversationPersistenceService.recentTurns(session, tokenBudget)` and the same history/current-question renderer. `committedHistory` remains available and delegates to the new selector. Existing current-turn snapshot timing, prepared `model_user` replay, receipts and billing IDs are unchanged.

An attempt starts at a persisted user message and includes following completed assistant messages until the next user. Failed/cancelled attempts keep their committed user question; incomplete and orphan assistant messages are omitted. Repeated questions are preserved. This policy uses persisted chronology; it does not add a request/turn ID to the database schema.

Selection returns the newest contiguous suffix of whole attempts. If the next attempt exceeds any bound, it and all earlier history are omitted. No content, JSON, or message is sliced, and no paid summarization is introduced.

| Bound | Behavior |
|---|---|
| `CHAT_HISTORY_TOKEN_LIMIT` | Default96000 conservative UTF-8 bytes, including rendered warning, roles and separators |
| Message count | At most20 retained eligible messages; complete attempts cannot bypass the limit |
| Rendered characters | At most32000, preserving the prior character ceiling so English input is not silently expanded |
| Candidate query | At most200 raw rows for the owned conversation, ordered by persisted time and stable ID |

The96000-byte default accommodates the previous32000-character Chinese ceiling; it is not measured provider token usage. It can be lowered independently. The current question, system prompt and output reserve still use existing full-call admission. A zero history budget disables history.

Rendered history explicitly says instructions within it must not be executed. This is transitional text, not structured provider role messages or an authorization boundary. Task07's system-prompt assembly and task09's real-model quality evaluation remain separate.

Tests cover complete/multiple/consecutive/incomplete messages, orphans, Unicode and exact byte/character limits, newest-suffix ordering, hard message/candidate limits, configuration, repeated questions, failure/cancellation, and prepared-input replay. Run `mvn -pl agent-chat-service -am test` with the repository's required JDK and dependencies. No deployment or real paid provider calls were performed for this task.
