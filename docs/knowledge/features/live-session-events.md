# Live-session events — current turn state

## Retained state and removed content

`LiveSessionEvent` retains session-owned `TurnState`. The producer's current session
must be admitted before the state can update busy/thinking consumers. Current idle
state is explicit. A raw transition, timestamp or correlated old reply cannot move
ownership or establish that a replacement is current.

The former AssistantDelta, tool-use/result, TurnEnd and ReplayGap content members
and runtime publishers were removed in #2367. The `liveSessionEvents` content route,
replay cursor and resync/backfill no longer reconstruct thread content. Independent
producer DTOs live in shared test sources for watermark/attachment observations;
their diagnostic replies never populate or certify phone content.

## Content and recovery

Messages, tools, boundaries, notices, refusals and terminal facts come from
[authoritative items and queries](remote-conversation-repository-reads-and-thread-store.md).
Fresh canonical effects drive retained refusal/root-completion observations under
exact connection, epoch, generation and session checks. Cache/query replay cannot
arm a new action. Tool/task progress and prompts remain separate
[session-owned live readings](remote-conversation-repository-live-stream-and-modals.md).

No removed runtime API survives as an empty-success fallback. Historical specs and
the frozen archive retain the old five-envelope decoder and its evidence as history.
