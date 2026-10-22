# Caching conversation repository — reconnect ownership

## Authoritative store continuity

The coordinator retains the host-owned authoritative store across connection
replacement. New connections recreate queries/decoders over held facts and certified
checkpoints. The cache wrapper restores/upserts supplied identities while preserving
newer live revisions and both sides of an interrupted merge. Disconnect does not
replace held content with an empty legacy snapshot.

The fixed raw-row merge base and disconnect rebase algorithm were removed in #2367.
See [restore/write ownership](caching-conversation-repository.md) and
[generation, retirement and final flush](caching-conversation-repository-concurrency.md).
