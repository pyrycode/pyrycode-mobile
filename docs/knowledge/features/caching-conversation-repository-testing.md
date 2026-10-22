# Caching conversation repository — testing

## Supported persistence proofs

`AuthoritativeThreadCacheTest` and the authoritative owned-upsert/window suites retain
saved items, epoch, checkpoint, coverage and retirement fences across fresh storage
instances. Held-I/O and worker probes distinguish admitted work, actual commit,
successful baseline publication and cleanup. Restore/reset/delete probes use exact
host/conversation, runtime generation and incarnation, including reused epoch text.
A successful return or in-memory count alone cannot establish durable fencing.
See [concurrency](caching-conversation-repository-concurrency.md).

Observation cancellation joins its writer and final-flush work. Failed writes must
leave the prior baseline retryable; deletion must prevent delayed/final writes from
recreating content. Bounded windows preserve parent/placement witnesses without
certifying unloaded history. Older hydration belongs to the active observation,
with cancellation and cache lifetime checked before forwarding demand.

Dedicated raw merge/coverage/alias/coalescing tests were retired with their runtime
in #2367. Dormant backend serialization tests remain for #2364. Surviving mixed
presentation tests use supplied facts or literal rows and retain viewport, grouping,
attachment, ownership and privacy assertions.

`DurableGapProof` retains saved certification, actual recovery receipts, reset/page
checks and exact rendered order in both deterministic and live offline/reconnect
callers. [Counted acceptance](../../e2e-interactive-stream.md#authoritative-runtime-removal-2367)
distinguishes scripted/replay passes from genuine provider observations.
Attachment retrieval and daemon-delete refusal tests remain separate from content
certification; non-content observables pass through without restoring live prompts.
