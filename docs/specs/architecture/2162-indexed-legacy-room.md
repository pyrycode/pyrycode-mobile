# Indexed legacy Room storage (#2162)

## Files read

- `data/cache/ConversationCache.kt`: full replacement, exclusions, cap and failure contract.
- `data/cache/FileConversationCache.kt`: canonical row codecs, uniqueness validation and history retention.
- `data/repository/HistoryCoverage.kt`: unsigned claims, fragment bindings and certification invalidation.
- `docs/knowledge/features/conversation-cache-contract.md`: optional-history isolation and exact ownership.
- `docs/knowledge/features/conversation-cache-testing.md`: fresh-instance persistence and existing regression families.
- `ui/conversations/thread/FragmentedHistoryFixture.kt`: unchanged 18000-span fixture used by bounded probes.
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `build.gradle.kts`: Kotlin/AGP toolchain and Robolectric admission.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: existing security model; no wire change.

## Context

File thread reads decode the complete document. Add an independently callable legacy store for
#2161 and #2068, without binding it in the application or converting legacy facts into authoritative
items. Room setup also serves #2163. A decision record for the approved Room choice belongs to
the documentation stage. No in-flight remote feature branch overlaps the planned files.

## Design

Use stable Room 2.8.4 and KSP 2.3.6. `ConversationCacheDatabase` has nested internal schema
records and DAO. The platform factory in `di/` opens a fixed database name beneath
`Context.noBackupFilesDir`; journal/WAL/SHM files consequently share that backup-excluded root.
Export the initial schema beneath `app/src/main/roomSchemas`. No destructive migration fallback.

`LegacyRoomStorage` supplies callable full thread/history reads, whole-thread replacement,
history replacement/clear and scoped removal. It does not implement metadata/settings storage
or add a second save coordinator. Each database query includes exact host and conversation keys
with SQLite's binary comparison. All tables here are explicitly legacy-family tables.

Persist one canonical payload per held ordinal. Reuse the file-private codecs through narrow
internal encode/decode helpers in the existing file; do not change proof inputs or file readers.
Writes apply cache policy and validate uniqueness before replacing committed rows.

Index history as individual evidence cells: spans/gaps with unsigned interval columns; each
row order, proof, alias, offset, length and hash separately; each producing entry separately;
cursor/walk anchors separately. Unsigned SQL ordering uses fixed-width decimal text, preserving
the entire ULong range. A small history header holds only scalar flags/cursors, never proofs.
Reconstruct full metadata using MobileJson and the existing HistoryCoverage validation/retention.
Metadata-only reads decode no row payloads. Invalid optional metadata yields no history while
rows remain readable; reads never repair storage.

`LegacyThreadPage` is a distinct result carrying exact owner, selected rows, partial JSON evidence,
and an opaque local `Continuation`. Its evidence is explicitly a projection, never a full
HistoryCoverage or a certification of omitted content. Newest/before reads select at most N
payloads ordered by held ordinal, plus one ordinal-only continuation probe. Tokens bind exact
owner, legacy family and committed snapshot generation; replacements invalidate old tokens.
Selected ordinal indexes fetch only their row/delta claims. Intersecting span/gap queries and
nearest adjacent edge/cursor probes preserve relevant gaps and opaque cursor evidence. Scalar
unknown/incomplete/completeness flags are copied, never inferred from local exhaustion.

## State and concurrency model

Caller-owned suspend work runs on the injected IO dispatcher. Room transactions serialize
mutations across instances and provide coherent read snapshots. No new scope, job, hot flow or
lifecycle owner. Cancellation propagates and rolls back unfinished mutations. Full writes may
materialize the full bounded snapshot; page reads must never do so.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Restart over the same disk database | `roundTripInvariant_allKindsAndFieldsSurviveRestart` |
| Newest then repeated before reads, misleading/equal timestamps | `heldOrderInvariant_backwardTraversalVisitsEachRowOnce` |
| Same id under another case-sensitive owner | `ownershipInvariant_tokensAndRemovalStayScoped` |
| Replacement/replay under the same owner | `replacementInvariant_oldTokensCannotReadNewSnapshot` |
| Row replacement changes bound content or trims | `committedEvidenceInvariant_changedContentAndTrimInvalidateClaims` |
| Invalid optional evidence | `committedEvidenceInvariant_invalidHistoryLeavesRowsReadable` |
| Failed mutation or cancelled transaction | `atomicityInvariant_failureAndCancellationKeepCommittedSnapshot` |
| Sparse spans and unknown cursor walk, fragmented 18000-row fixture | `boundedEvidenceInvariant_fragmentedFixtureDoesNotDecodeOutsideWindow` |
| Unsigned extremes and aliased fragments | `unsignedAndFragmentInvariant_exactEvidenceSurvivesRestart` |

## Error handling

Expected SQLite, IO, security and invalid-data errors produce graceful empty/null reads and
causeless ConversationCacheException mutation failures. Cancellation and programming errors
propagate. Diagnostics contain static operation/status/failure codes and counts only. Page,
token and schema result strings redact content, cursors and ownership. There is no UI change.

## Testing strategy

Write failing Robolectric disk-storage probes first, then implement. Reopen actual databases
between persistence assertions. Measure row payloads read/decoded, ordinal probes and evidence
cells read/decoded at the actual query/codec boundaries. Compare a fixed window before and after
adding older history, and exhaustively traverse the unchanged fragmented fixture. Cover empty
and singleton pages, duplicates, reverse held ordering, owner reuse, unsigned extremes, stale
bindings, rollback, trim and removal. Retain file-codec/restore/hash/unsigned/history regressions.
Run focused `testDebugUnitTest` through normal Gradle admission and report XML executed/failed/
skipped counts. No emulator or real-Claude scenario: this is unbound data-layer work.

## Open Questions

None. Forecast: about 1400 written lines (narrow codec seam, schema/queries/backend, tests,
build setup and plan); at most five exported declarations, no consumer signature migration,
five acceptance criteria, fewer than ten classified reject branches.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed by canonical row validation and content-bound history
  validation before transaction commit; malformed optional metadata is independently isolated.
- Tokens/secrets: no credentials are stored or generated. Local tokens are not bearer credentials.
- Files/storage: fixed app-private no-backup location, including sidecars; owner strings are bound
  SQL values, never paths. Plain SQLite retains the approved existing plaintext-cache threat model;
  encryption against rooted app-private readers is outside this cache migration.
- Android attack surface: factory adds no exported component, provider, intent or WebView.
- Cryptography: reuse MobileJson proof inputs and existing SHA-256; no Noise/key changes.
- Network/IO: no network work; row cap and bounded query/decode contract limit page materialization.
- Errors/logs: SHOULD FIX implemented with redacted result strings, static diagnostics and
  causeless classified mutation exceptions; test hostile content/owner/cursor values.
- Concurrency: one database transaction per snapshot/mutation; cancellation propagates; fresh
  instances cannot observe mixed rows/evidence. Snapshot-bound continuations reject owner reuse.
- Threat model: malicious relay behavior and token theft remain with existing Noise/Keystore
  boundaries; hostile cached content stays inert text; screenshot/accessibility leakage remains
  with existing render surfaces. No new network or UI surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-14

## Revisions

- 2026-10-14: expose page evidence through redacted `LegacyPageEvidence` with typed unsigned
  fields and explicit saved high-water/unknown-edge values. A raw JSON result could expose opaque
  cursors through its string representation; the wrapper keeps partial evidence distinct from
  full HistoryCoverage and retains global incomplete flags when a page holds only signed-range rows.
- 2026-10-14: interval queries seek the indexed predecessor before their bounded range, and
  cursor queries select nearest candidates for intersecting gaps even outside that range. Invalid
  optional evidence also clears the page's saved cursor/completeness fields. Focused probes cover
  these refinements and both legacy and coverage-bearing 100000-row trims.
