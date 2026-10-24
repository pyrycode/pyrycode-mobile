# Replay cursor — retired runtime route

`ReplayCursor`, event high-water recording, hello `last_event_id` advertisement,
resync processing and backfill were removed in #2367. Production reconnect uses
[authoritative queries](remote-conversation-repository-reads-and-thread-store.md)
with certified epoch/checkpoint and exact host/conversation ownership. Applied live
progress is not a resumable checkpoint. Hosts lacking required thread support get
daemon update-required treatment.

Historical replay evidence and frozen per-ticket notes retain their original
meaning and results. Independent test-producer observations never restore or
certify phone content. See [current recovery proof](../../e2e-interactive-stream.md#authoritative-runtime-removal-2367).
