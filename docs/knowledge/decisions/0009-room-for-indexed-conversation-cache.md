# ADR 0009 — SQLite through Room for indexed conversation storage

**Status:** Accepted (Juhana approved 2026-10-10; legacy backend delivered by
[#2162](https://github.com/pyrycode/pyrycode-mobile/issues/2162)).

## Context

The file cache decodes a complete legacy thread document to read its newest rows. Saved
history includes unsigned spans, overlapping gap evidence, per-row producing claims, proofs,
fragment bindings and opaque cursor/walk anchors. Bounding only returned rows would still
allow decoding all older evidence. Replacements must preserve committed content and evidence
atomically, exact case-sensitive ownership and held order independently of timestamps.

## Decision

Use SQLite through Room for indexed conversation storage. The initial shared
`ConversationCacheDatabase` uses Room 2.8.4 with KSP 2.3.6 and exported version-1 schema.
Construct it at the Android `di/` boundary under app-private `noBackupFilesDir`, so the fixed
database file and all SQLite sidecars are excluded from backup. Do not use destructive upgrades.

Keep legacy rows/evidence in explicitly legacy tables, with one canonical payload per held
ordinal, independently indexed evidence cells and scalar-only headers. Use fixed-width decimal
text for exact unsigned ordering through `ULong.MAX_VALUE`. Reuse canonical `MobileJson` file
codecs and proof inputs. Full operations remain available alongside distinct bounded page
projections and exact owner/snapshot-bound local continuations. Room transactions commit each
mutation and provide coherent reads across instances.

## Rationale

Indexed selection bounds row payload and applicable evidence decoding without changing the
`HistoryCoverage` consumer contract. Separate evidence indexes permit metadata-only reads and
avoid a header containing every proof. Transactions preserve the last committed representation
on expected failure or cancellation. Keeping Android construction outside the data contract
retains portable schema/domain semantics. The no-backup directory covers journal/WAL/SHM files
without relying on individual sidecar backup rules.

## Alternatives considered

- Continue whole-document file reads: preserves compatibility but still decodes all legacy rows.
- Store one JSON document or every proof in a Room header: moves the file representation into
  SQLite without providing bounded payload/evidence decoding.
- Widen page evidence to full intersecting span endpoints: older cursors within one continuous
  span then grow page work. Select the claim window and necessary adjacent/gap/unknown anchors.
- Treat legacy rows as authoritative facts: rejected because legacy coverage cannot certify an
  authoritative reconnect checkpoint. Authoritative storage remains a separate family.

## Consequences

Schema migrations must preserve data instead of silently rebuilding it. The existing plaintext,
app-private cache threat model remains unchanged; this choice adds no network service, paging
library, background full-history download or second save coordinator. Partial evidence needs
projection-aware validation and must never certify omitted history. Continuations are local
storage positions, distinct from daemon cursors and invalidated by replacement.

The initial backend is independently callable, with real disk/restart and read/decode probes.
It is not yet application-bound: #2161 owns file import/binding, #2163 authoritative storage,
and #2068 reader activation/owned partial writes. Conversation metadata, read positions,
settings and attachment bytes keep their existing storage.

## Related

- [Layout](../features/conversation-cache-layout.md#indexed-legacy-room-layout-2162)
- [Contract](../features/conversation-cache-contract.md#indexed-legacy-operations-and-pages-2162)
- [Disk probes and retained file regressions](../features/conversation-cache-testing.md#testing)
- [Architecture and security review](../../specs/architecture/2162-indexed-legacy-room.md)
- [Implementation and review](https://github.com/pyrycode/pyrycode-mobile/pull/2164)
