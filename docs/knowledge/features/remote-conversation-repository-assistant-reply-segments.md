# Remote conversation repository — assistant content identity

## Daemon-built assistant items

Runtime assistant segment reconstruction, `(turnId, seq)` joins, minted renderer-id
ledgers and raw turn-end settlement were removed in #2367. Assistant text, parent,
session, identity, revision and order come from admitted daemon items. The renderer
retains markdown reveal, tool grouping and Agent pinning without reconstructing
source history. See [store application](remote-conversation-repository-reads-and-thread-store.md#item-application-and-explicit-clears)
and [Agent presentation](thread-screen-subagent-tool-rows.md).

Serialized segment definitions needed by the dormant storage backend remain for
\#2364; their presence grants no runtime reader or fallback. Literal presentation
fixtures can still test layout without invoking a removed reducer.

## Replacement observation fixtures

A double modelling complete replacement snapshots must publish complete canonical
snapshots with the actual source connection owner. An append-preserving store can
retain an omitted duplicate and make replacement/duplicate-rejection assertions
observe stale content. The four `ReplySuggestionReadsTest` methods retain exact
text, duplicate, session, replacement and newer-clear checks against complete
snapshots; fixing only a removed API override does not establish this proof.
