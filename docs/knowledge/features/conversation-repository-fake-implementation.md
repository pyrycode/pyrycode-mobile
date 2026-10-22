# ConversationRepository — demo implementation

## Phase 1 implementation — `FakeConversationRepository`

The in-memory demo remains selectable with `-PuseRelayRepository=false` and the
non-relay DI module. It uses a demo-owned negotiated route and complete authored
facts. It does not decode raw frames, run history reconstruction or expose removed
`observeMessages`/`requestHistory` APIs. Restart loses its in-memory state.

Demo content and workspace chips still reach the production destination through
that route. A fake without the negotiated provider cannot test supported thread
rendering merely by retaining old message lists. For component-only scenes, use
literal presentation rows. Replacement-observation doubles must publish complete
canonical snapshots with their actual source owner; append-preserving fixtures can
retain omitted duplicates and invalidate the proof.

Metadata filtering, channel/discussion mutations, session operations and prompt
editing remain demo behavior. Network capability/ownership and durable recovery
must be tested against the real repository/cache; the demo does not prove them.

## Host system prompt demo state (#1774)

The fake owns one in-memory `HostSystemPromptReading` per instance, initially
current/default both empty. The empty default is demo data; never copy the daemon's
shipped reset instructions into mobile. Read returns the held pair. A write checks
`SystemPromptLimit` before atomically replacing that pair and returns the exact
acknowledgement it wrote, preserving empty and whitespace. Oversized input returns
a failure without changing the held value. Concurrent writes may supersede each
other, but each acknowledgement identifies its own write.

Host state is independent of the nullable, conversation-keyed channel prompt map
and never changes conversations or sessions. `HostSystemPromptFacadesTest` checks
per-instance isolation, byte boundaries and host/channel independence. See [the
host contract](conversation-repository-conventions.md#host-system-prompt-contract-1774).
