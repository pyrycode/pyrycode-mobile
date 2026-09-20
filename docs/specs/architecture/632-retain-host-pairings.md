# Retain encrypted host pairings (#632)

## Context and scope

Pairing another host currently overwrites the one credential record. Retain an
ordered collection under the existing encrypted preference, keeping the four-field
`PairedServer` constructor and all connection consumers compatible. This is one
storage deliverable; routing belongs to #633/#634 and editing UI to #642. ADR 0006
already covers the encryption mechanism; no new ADR or dependency is required.

Size check: approximately 650–700 written lines including this plan, two production
files, two new exported types, zero consumer updates, five acceptance criteria,
and at most nine storage/decode rejection branches. #294 added 340 store/test lines
plus a 178-line plan; this change reuses its crypto helpers and instrumented fixture.
Remote feature branches were refreshed and checked: no overlapping files.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` — `PairedServerStore`, `PairedServer`: compatibility surface and redaction.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStore.kt` — `load`, `save`, `wrap`, `getWrapKey`: existing ciphertext envelope and atomic persistence.
- `app/src/androidTest/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStoreTest.kt` — existing real-Keystore round-trip, corruption and key-loss assertions.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystoreDeviceStaticKeyStore.kt` — `loadOrCreate`: separate key alias and preferences that must survive pairing removal.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` — `RelayLog`: debug-only injectable diagnostic sink.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt` — `create`, `reloadDeviceStaticKey`: unchanged latest-pairing consumers.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` — `connect`: compatibility read before transport construction.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `MainActivity`: paired-state and Settings compatibility reads.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingConfirmation.kt` — `confirmPairingAndConnect`: save must fail before connection on persistence errors.
- `docs/knowledge/features/paired-server-store.md` — “How custody works”, “Edge cases & limits”: preserve byte-faithfulness, dedicated alias and graceful reads.
- `docs/knowledge/features/development-verification.md` — focused device evidence, cancellation and JVM logging constraints.
- `docs/knowledge/decisions/0006-keystore-wrap-at-rest-device-static-key.md` — Keystore-generated GCM IV and accepted in-process plaintext residual.
- `../../pyrycode/docs/protocol-mobile.md` — “Pairing flow”, “Security model”: authoritative wire contract, unchanged here.
- `../../pyrycode-desktop/src/main/pairedServerStore.ts` — `MultiPairedServerStore`, `createPairedServerStore`: ordered collection and additive interface precedent.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — existing serialization, DataStore and managed API 33 test dependencies.

Codegraph context found the store; impact/callees returned only declarations or an
unrelated symbol. Source search confirmed five legacy production loads, one save,
five JVM store doubles and the instrumented E2E save. None need changes.

## Design

Keep `PairedServerStore.load()` and `save(record)` signatures. Add
`PairedServerCollectionStore : PairedServerStore` in the same contract file with
`list(): List<PairedServerEntry>`, `loadById(serverId): PairedServerEntry?`,
`setDisplayName(serverId, displayName: String?)`, and `remove(serverId)` suspend
methods. The concrete Keystore store implements this richer interface. Existing
DI and legacy test doubles continue using the base; new routing can adopt the
collection interface separately.

`PairedServerEntry(record: PairedServer, displayName: String? = null)` separates
local metadata from credentials. Exact String equality of `record.serverId` is
the only identity rule. Names are stored verbatim, nullable and never identifying.
Unknown-id rename/removal is a no-op. Save preserves the matching entry's name,
removes its prior position and appends the replacement. List order is oldest-save
first; load returns the last record. Rename preserves order, remove filters one id.

The private serializable envelope has version 1 and an ordered entry list. Reject
unsupported versions and duplicate ids on decode. Recognize the existing bare
`PairedServer` JSON as one unnamed entry without writing. Successful mutations
write the envelope, including an encrypted empty collection on final removal.
Keep the existing preference key, wrap alias, AES-GCM framing and crypto helpers.
No plaintext ids or names become preference keys, paths, or Keystore aliases.
Record, entry and private envelope string output redact every field.

## State and concurrency

Read, decrypt, transform, encrypt and preference replacement happen inside one
`DataStore.edit` transaction. DataStore serializes across store instances sharing
it and commits atomically, so overlapping mutations cannot lose successful saves.
There is no cache, active pointer, extra mutex, job, flow or ViewModel. Each suspend
operation uses an injectable IO dispatcher and remains owned by its caller.
Background connection closure adds no persistence work; cancellation propagates.

## Error handling

Reads return empty/null on missing, corrupt or undecryptable storage and expected
IO/Keystore failures. They never create a wrap key or write migration state.
Mutations decode existing storage strictly: an unreadable existing blob or storage/
Keystore failure throws `PairedServerStoreException` and does not replace it. This
prevents a transient read failure from erasing retained hosts. A failure after
transformation likewise leaves the old persisted bytes and ordering intact.
No raw exception cause is attached, because providers/parsers can include input
in messages. Classify IO, security, provider and malformed-data failures using
static codes. Unrelated bugs and coroutine cancellation propagate unchanged.
Debug-only `RelayLog` events record operation/outcome codes, never field values,
names, plaintext, ciphertext or raw exception messages.

## Testing strategy

First run a new redaction regression against the existing store, then implement.
Expand `KeystorePairedServerStoreTest` on the managed API 33 device to prove:

- Real file close/reopen with two ids sharing relay/name; all credentials round-trip,
  re-pair updates one id and preserves its name, and latest selection survives restart.
- Targeted set/clear/remove, no-op unknown ids, removal fallback, final empty state,
  unrelated preferences and actual device static key continuity.
- Concurrent saves through distinct store instances sharing the DataStore.
- Independently encrypted legacy JSON reads without writes, migrates on mutation,
  and cannot resurrect after removal and repeated reopen.
- Failure injected after transaction transformation preserves persisted bytes and
  selection for save, rename, migration and removal; cancellation propagates.
- Missing keys, malformed/authentication-failing ciphertext, exception/string/log
  redaction and no plaintext credentials or names in the stored blob.

Run the affected device class fresh, inspect executed counts/XML, then Spotless,
lint, assembleDebug and compileDebugAndroidTestKotlin. This storage-only ticket
adds no operator-facing flow, Compose visual change or real-Claude scenario.

## Open questions

None. The additive interface avoids a fake/consumer cascade; unknown-id metadata
updates are no-ops, and mutation read failures fail closed as specified above.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/paired-server-store.md`
to describe collection identity, local names, migration, failure behavior and the
temporary latest-saved compatibility accessors, replacing its single-record/overwrite
claims (particularly “The contract”, “How custody works”, “Data flow”, and “Edge
cases & limits”). No shared documentation is edited by this builder.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `decodeEntries` authenticates ciphertext before parsing;
  legacy and versioned shapes are explicit. Credentials remain unvalidated storage
  values; the existing pairing parser owns wire validation. No new UI text surface.
- **Tokens:** retain the dedicated uid-scoped Keystore AES key and encrypted app-private
  preference. Re-pair replaces one credential; local removal is not daemon revocation.
- **Storage:** fixed preference key/alias; ids cannot traverse paths. One DataStore
  transaction preserves old data on failure. Restored ciphertext without its key
  reads gracefully; backup policy and rollback freshness remain ADR 0006 residuals.
- **Android IPC:** no exported components, intents, providers or WebViews introduced.
- **Cryptography:** unchanged AES-256-GCM with fresh platform-generated IVs; no new
  primitives, token comparisons, Noise changes or cross-purpose key reuse.
- **Network/I/O:** no network calls added. Existing parser/transport owns URL and
  frame validation; connection construction and lifecycle stay with #633/#634.
- **Errors/logging:** resolved design hazard: raw parser/provider causes can expose
  credentials, so wrap failures without those causes. Redact all model fields and
  capture static diagnostics in tests, including malformed-JSON exceptions.
- **Concurrency:** transaction encloses the entire read-modify-write and key lookup;
  no cached selection can advance after a failed write. Cancellation is not wrapped.
- **Threat alignment:** ciphertext theft lacks the Keystore key; same-uid execution
  can still unwrap, as accepted by ADR 0006. Malicious relay/daemon protections stay
  in the existing Noise/protocol layers; UI disclosure is outside this storage
  deliverable and belongs to #642 when names are rendered.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-20
