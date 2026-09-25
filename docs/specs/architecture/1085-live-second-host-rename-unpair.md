# #1085 — rung 3: pair, rename and unpair a second host without disturbing the first

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` (#847) — the two-daemon drive this scenario mirrors: camera grant, `pairHostByCode`, B removed from `PairedServerCollectionStore` in `finally`.
  - `pairHostByCode`, `hostLabel`, `awaitChannelRow`, `channelRow`, `openRow`, `assertHostHoldsConversation`, `hostConversationIds`, `awaitConnected`, `twoHostArg` — reused as they stand.
  - companion `HOST_B_NAME`, `ARG_SERVER_ID_B`, `ARG_PAIR_CODE_B`, `ARG_COLLISION_*`, `CD_BACK`, `EDIT_CHANNEL_OK`; instance field `modalCancel`.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `EditHostModal`, `EDIT_HOST_NAME_FIELD_TAG` — the name field's test handle, the in-place unpair confirmation, and that the shell's Cancel on the confirmation declines back to the editor rather than closing it.
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorController.submitName`, `requestUnpair`, `declineUnpair`, `confirmUnpair` — rename writes `setDisplayName`; confirm calls `pairedServers.remove` and closes the editor; decline writes nothing.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → the host row, `treeHostEditTestTag` — the per-host Edit control, content description `cd_tree_host_edit`.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `reconcile`, `connectionFor` — bundles are keyed by the stored record (not the display name), so neither B's rename nor B's removal may replace A's bundle; removal closes only B's; selection falls back to the last saved host.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` — footer labels "Cancel" / "OK".
- `app/src/main/res/values/strings.xml` → `cd_tree_host_edit`, `edit_host_title`, `edit_host_unpair`, `edit_host_unpair_confirm_title`.
- `scripts/e2e-emulator.sh` § LIVE `TEST_TARGET` list; `scripts/android-test-gate.py` → `LIVE_MINIMUM`; `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list` (the floor must equal the list's method count).
- `docs/specs/architecture/847-e2e-two-host-colliding-conversation-id.md` — harness shape of host B.

In-flight overlaps: #1017, #1051, #1053, #1059 also append scenarios to `InteractiveStreamE2ETest.kt` (and #1017 to both scripts). All additive; a later merge may touch those files.

## Design source

N/A — test and script only; no UI changes. The drive uses existing Edit host modal controls (#744, #745).

## Context

Rename (#744) and unpair (#745) of a host are proven only against fakes (`EditHostModalTest`, `HostEditor` unit tests). #847 pairs host B through the real pair-by-code flow but never manages it. This ticket adds one rung-3 scenario on the same two-daemon harness. Zero real-claude turns: pairing, rename and unpair are phone-local or daemon round-trips. Split from #676.

## Design

One new `@Test` on `InteractiveStreamE2ETest`:
`interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched()`.

Drive, in order:

1. **Baseline for A.** `awaitChannelList`, `awaitConnected`. Capture `labelA = hostLabel(serverIdA)`, `bundleA = registry.connectionFor(serverIdA)`, `idsA = hostConversationIds(serverIdA)`, and `nameA =` the name A's live repository currently holds for the seeded collision id (read, not `ARG_COLLISION_NAME_A`, because #847 renames it and JUnit's method order is by name hash).
2. **Pair B.** Grant CAMERA, `pairHostByCode(ARG_PAIR_CODE_B)`, `awaitChannelRow(nameB)` (B's seeded conversation, never renamed by any scenario), assert `hostLabel(serverIdB) == HOST_B_NAME`.
3. **Rename B (AC-1).** Tap `treeHostEditTestTag(serverIdB)`; wait for the `edit_host_title` text; `performTextReplacement` on `EDIT_HOST_NAME_FIELD_TAG` with a run-unique name (`HOST_RENAME_PREFIX + millis`); tap OK. Wait until B's Edit control's content description names the new name and none names `HOST_B_NAME`. Assert the store's display name for B is the new name, `nameB` still drawn, and A unchanged (helper `assertFirstHostUntouched`, below).
4. **Decline unpair (AC-2).** Reopen B's editor, tap `edit_host_unpair`, wait for `edit_host_unpair_confirm_title`, tap Cancel. Wait for the editor title back (the confirmation's Cancel returns to the editor, per `EditHostModal`), then Cancel again to close it. Assert B still in the store, `connectionFor(serverIdB)` non-null, B's row still drawn under the new name.
5. **Confirm unpair (AC-2).** Reopen, Unpair host, OK on the confirmation. Wait until no node carries B's Edit control description and no `nameB` row is drawn (B's section gone). Assert `loadById(serverIdB) == null` and `connectionFor(serverIdB) == null`. `awaitConnected` (the selection falls back to A). Assert A unchanged, then `openRow(nameA)` and back: one of A's conversations still opens.
6. **`finally`:** `PairedServerCollectionStore.remove(serverIdB)` on the current graph, as #847 does, so a red run never leaves B paired or selected. `remove` of an absent id is the no-op #847 already relies on.

New private helpers (kept next to the #847 helpers):

- `heldConversationName(serverId, conversationId): String` — the name `serverId`'s live repository holds for the id; `assertHostHoldsConversation` delegates to it rather than duplicating the read.
- `openHostEditor(serverId)` — tap the host's Edit control, wait for the editor title.
- `hostEditDescription(label): String` — `cd_tree_host_edit` formatted with `label`.
- `assertFirstHostUntouched(serverIdA, labelA, bundleA, idsA, nameA)` — the stored label is still `labelA` and A's Edit control still carries it; `connectionFor(serverIdA)` is the **same instance** as `bundleA` (the registry never closed or rebuilt A) and its repository is live; A's live conversation-id set equals `idsA`; A's `nameA` row is drawn.

Companion additions: `HOST_RENAME_PREFIX = "e2e1085-host-"` (no substring shared with other scenarios' names or `HOST_B_NAME`) and `EDIT_HOST_OK = "OK"`, the shell footer's hardcoded submit label, matched with a click action. Strings for title / Unpair / confirmation title come from resources.

Never log the pair code: the scenario only passes it to `pairHostByCode`, which types it into the field.

### Scripts

- `scripts/e2e-emulator.sh`: append the method to the LIVE `TEST_TARGET` list with a `# #1085` comment (zero turns; list grows by one method). Default rung 3 runs the whole class, so it is included there without change.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM += 1` with a `# #1085` comment.

## State + concurrency model

Test-only. Store and registry reads use `runBlocking` + `withTimeout` as the surrounding helpers do. All waits are `composeTestRule.waitUntil` with the class's existing timeouts (`THREAD_TIMEOUT_MS` for modal steps, `LIST_TIMEOUT_MS` for list redraws, `PAIR_TIMEOUT_MS` inside `pairHostByCode`).

## Error handling

A missing instrumentation arg fails through `twoHostArg` naming the script. Every wait failing names its step through the assertion message where the helper allows. `finally` always removes B.

## Testing strategy

- The scenario itself is the proof; it runs on rung 3 (whole class) and in `LIVE=1`. It is a live two-daemon scenario, so it cannot run in a focused managed-device run (it needs the host daemons `scripts/e2e-emulator.sh` starts); the dispatcher's live gate runs it after verifier.
- Builder checks: `./gradlew compileDebugAndroidTestKotlin`, `./gradlew assembleDebug`, `./gradlew lint`, `spotlessApply`, and `python3 -m unittest scripts/test_android_test_gate.py` (its `test_live_floor_matches_the_curated_list` proves the floor equals the list).
- No rung-4 twin: the scripted harness has one daemon and no second host.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`: add the scenario to the LIVE inventory in § Live mode (rung 3, live relay) and § Pre-ship gate.
- `docs/knowledge/features/paste-code-dialog.md` and `docs/knowledge/features/scanner-screen.md`: point the lines that say second-host management belongs to #676 at this scenario; say that real camera QR capture stays unproven live.

## Open questions

- Does the editor's name field accept `performTextReplacement` while the dialog is in its own window? `EditHostModalTest` drives it the same way under Robolectric; confirm by compiling and via the live gate.
