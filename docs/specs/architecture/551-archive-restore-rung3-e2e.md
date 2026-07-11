# #551 — rung-3 real-claude e2e: archive a conversation → gone from list, then restore → back in list

**Ticket:** [#551](https://github.com/pyrycode/pyrycode-mobile/issues/551) · **Size:** S · **Not** `security-sensitive`

Round-trip twin of #554 (delete e2e). Zero production code: one new always-on `@Test` in
`InteractiveStreamE2ETest.kt`, ~6 companion constants, one edit to the `scripts/e2e-emulator.sh` LIVE
`class#method` list (quartet → quintet), and a scenario entry in `docs/e2e-interactive-stream.md`.

## Design source

N/A — test-only ticket. It exercises already-shipped, already-reviewed UI (the #549 archive/unarchive wire,
#556 archive-from-thread, #557 restore-from-Archive-screen, the `ThreadOverflowMenu` "Archive" item, the
`ArchivedDiscussionsScreen` / `ArchiveRow`, `RenameDialog`) and adds **no production surface**, so there is
nothing to design and the visual-fidelity check is intentionally skipped. The ticket body carries no
`## Figma` section, which is correct here (not a PO gap): this is a test of existing UI, not new UI work.
(Same shape #541 / #554 / #566 established.)

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — **the clone target.**
  - `interactiveTurn_deleteConversation_removesFromListAndClosesThread` (lines 514–602) — the **closest
    twin**. Steps 1–6 (land → `awaitConnected` → create discussion → rename to unique → back-to-list presence
    check → re-enter thread) are reused **verbatim** here — archive/restore diverges only from step 7 onward.
    Read its KDoc (463–513) for the rename-to-unique / genuine-inversion discipline this scenario also follows.
  - `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (302–377) — the **back-to-list**
    navigation idiom (`CD_BACK` → wait `CD_NEW_DISCUSSION`) reused for the two return hops after restore.
  - The `companion object` (621–710) — reuse `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `CD_BACK`,
    `CD_MORE_ACTIONS`, `RENAME_ITEM`, `RENAME_SAVE`, `awaitConnected()`, `LIST_TIMEOUT_MS`,
    `CONNECT_TIMEOUT_MS`, `THREAD_TIMEOUT_MS`. **Do NOT redefine `CONVERSATION_NAME_PREFIX`** (line 702, `"e2e554-"`,
    owned by the delete scenario) — the companion is shared; add a distinct `ARCHIVE_NAME_PREFIX`
    (see § Companion constants). No `PING`/`pingNodeCount` — this scenario spends **no** claude turn (§ Cost).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:71–77` — the **"Archive"**
  item (`thread_overflow_archive` = "Archive", **gated on `mutationsSupported`** → present in relay mode after
  PR #572) fires `ThreadEvent.Archive`. It is a **plain overflow item** — the archive tap is the whole action.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:653–663,813–818` — the
  archive handling. **`ThreadEvent.Archive → sendArchive()`** (813–818, also closes the Channel-Info sheet if
  open — a no-op from the overflow). `sendArchive` (653–663): `repository.archive(conversationId)` then a
  **success-only** `ThreadNavigation.PopBack`; failure emits on `archiveErrors` and stays on the thread.
  **There is NO confirmation dialog** (contrast #554 delete's "Delete conversation?" confirm) — so the sheet /
  dialog / "Delete"-collision gotchas of #554 **do not apply here**.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:104–107` — the channel-list
  top-bar settings `IconButton` (`cd_open_settings` = "Open settings") fires `ChannelListEvent.SettingsTapped`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:302–321,418–480,498–508` — the nav wiring:
  `SettingsTapped → navigate(Routes.SETTINGS)` (308–309); Settings `onOpenArchivedDiscussions →
  navigate(Routes.ARCHIVED_DISCUSSIONS)` (459); the `ARCHIVED_DISCUSSIONS` composable (463–480) wires
  `RestoreRequested`/`TabSelected`/`BackTapped`; `Routes` (498–508). Each screen's back nav is `popBackStack()`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:75,84–93,245–254` — `onBack` back-icon
  (`cd_back` = "Back", 84–93) pops to the channel list; the **"Archived discussions"** `SettingsRow`
  (`archived_discussions_settings_row`, 245–254) fires `onOpenArchivedDiscussions`. This row's headline text
  doubles as the Settings-screen arrival marker for the return-nav gate.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:61,91–120` — **default tab
  is `ArchiveTab.Discussions`** (line 61, `MutableStateFlow(ArchiveTab.Discussions)`) → a renamed **discussion**
  surfaces there with **no tab tap** needed. **Load-bearing for the one gotcha:** `RestoreRequested` runs
  `viewModelScope.launch { repository.unarchive(id); _effects.send(RestoreSucceeded) }` (93–115) — the restore
  coroutine is scoped to **this screen's ViewModel** (see § Gotchas).
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:48–61,66–74,104–154` — the
  `RestoreSucceeded` effect shows a snackbar `restored_snackbar` = "Restored <name>" (48–61); top bar
  `archived_title` = "Archived" + `cd_back` back icon (66–74); the two tabs (Channels / Discussions, 104–123)
  and the per-item `ArchiveRow` (137–154).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ArchiveRow.kt:33–84` — **each row renders
  ONLY the display name** (`Text`, 52–58) plus a restore `IconButton` whose content description is
  `cd_restore_archive` = **"Restore <displayName>"** (72–82). This is why a runtime-unique rename is a hard
  prerequisite (an auto-named discussion renders as the non-unique fallback "Untitled discussion").
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — context only:
  `archive` / `unarchive` are **request/reply** (reply reuses `conversation_updated`, #549); `mutationsSupported
  = true` (~:1457, PR #572). Archive folds the conversation out of the active projection and into the Archived
  projection; unarchive reverses it. **Both projection updates happen on the repository's own demux loop, not
  the ViewModel's coroutine** — relevant to the § Gotchas cancellation reasoning. Not edited.
- `app/src/main/res/values/strings.xml` — the six load-bearing strings (values inlined in § Companion
  constants): `thread_overflow_archive`, `cd_open_settings`, `archived_discussions_settings_row`,
  `archived_title`, `restored_snackbar`, plus the reused `cd_back` / `thread_overflow_rename` / `rename_dialog_save`.
- `scripts/e2e-emulator.sh:9–10,55,454–463,480` — the LIVE curation block. Line 463 is the comma-separated
  `class#method` `TEST_TARGET` (a **quartet** today) to append to; lines 9–10, 55, 454–455, 460–461, 480 carry
  the "quartet / 3 turns" prose to refresh to a quintet.
- `docs/e2e-interactive-stream.md` — **read-only while writing this test** (the developer edits it as a
  deliverable). Mirror how #541 / #554 / #566 were wired in.
- Memory lesson `androidtest-not-compiled-by-mandatory-gates` — `./gradlew test` / `lint` / `assembleDebug`
  do **not** compile `androidTest`. The build gate for this ticket is `./gradlew compileDebugAndroidTestKotlin`.

## Context

Org policy (2026-07-08): every operator-facing happy-path flow ships with a real-claude e2e that runs in the
pre-ship gate. This is the archive/restore round-trip the #531 Definition-of-done note asked PO to file
separately. All prerequisites have landed: **#549** shipped the archive/unarchive data path (PR #553),
**#556** the archive-from-thread surfacing (PR #558), **#557** the restore-from-Archive-screen surfacing
(PR #559), and PR #572 flipped `RemoteConversationRepository.mutationsSupported = true`, so the "Archive"
overflow item (which lives in the `mutationsSupported`-gated block) is reachable on the live relay path.
Archive and restore are conversation-scoped (both keyed by `conversation_id`, reply reuses
`conversation_updated`), so they are in the clean-buildable camp with #541 / #554 / #566 — they carry none of
the orthogonal session-scoped blockers that re-park #545 (live `currentSessionId=""`) or #562 (workspace-chip
`!hasMessages` catch-22). Sibling e2es #541 (PR #573) and #554 (PR #574) already shipped through the pipeline
as proof the family is buildable. This is a **test-only** ticket.

## The scenario

Seed a conversation with a **runtime-unique, list-visible name** (via Rename), confirm it is present in the
active list, drive the real **Archive** flow from the thread and confirm the first durable post-condition (the
name is **gone** from the active list), then drive the real **Restore** flow (Settings → Archived discussions →
the Archived screen's restore affordance) and confirm the second durable post-condition (the name is **back**
in the active list). The round-trip closes: the same unique token flips **out of** and then **back into** the
same surface (the channel list), so each assertion is a genuine inversion of the other — never a match-everything.

## Design

### Durable identity: **Rename** to a runtime-unique name (unchanged from #554)

The Archived screen renders **only the display name** (`ArchiveRow.kt:52–58`), and a freshly-created discussion
is auto-named server-side → it renders as the non-unique fallback "Untitled discussion", which is un-findable on
the Archived screen and makes an absence/presence check a match-everything. So the seeded conversation is
renamed to a runtime-unique, list-visible token — `ARCHIVE_NAME_PREFIX + System.currentTimeMillis()`, e.g.
`"e2e551-1720800000000"` — **before** archiving. Rationale is identical to #554 (simpler than "Save as channel",
no accumulating dedicated-workspace folder, visibility guaranteed because a just-created discussion is #1 in
`observeConversations(Discussions)` sorted by `lastUsedAt` → always inside the visible recents). The unique token
also keeps repeated LIVE gate runs clean (no collision with prior-run leftovers) and makes both the archive
**absence** and the restore **presence** assertions genuine inversions.

### The round-trip: archive **out**, restore **back** (the divergence from #554)

#554 asserts one direction (delete → absent, thread closed). #551 asserts the list flip in **both** directions.
Two structural differences from the delete twin:

1. **Archive is immediate — no confirmation, no sheet.** The "Archive" item sits directly in the thread overflow
   menu (`ThreadOverflowMenu.kt:71–77`) and fires `ThreadEvent.Archive → sendArchive → repository.archive →
   success-only PopBack`. There is **no** "Delete conversation?" dialog, **no** Channel-Info sheet in the path,
   and therefore **none** of #554's "Delete"-collision / sheet-behind-dialog gotchas. The archive tap is a single
   `onNodeWithText("Archive")` in the open overflow (exact match; "Archive" is unique in the menu and absent from
   the list it pops back to).
2. **Restore requires navigation to a second screen.** Unlike delete (which completes in the thread), restore
   lives on the Archived screen reached via **channel list → Settings → "Archived discussions"**. After restoring,
   the test navigates **back** (two `CD_BACK` hops: Archived → Settings → channel list) to confirm re-appearance
   in the active list. The Archived screen opens on the **Discussions** tab by default and the seeded item is a
   discussion, so **no tab tap** is needed.

### The `@Test` — `interactiveTurn_archiveRestore_roundTripsListMembership`

One always-on (not `@Ignore`d) method. Steps as tolerant-assertion scenario bullets — **not** code to paste.
Steps 1–6 are the #554 delete twin verbatim (create + rename-to-unique + presence + re-enter); the archive/restore
divergence begins at step 7.

1. **Land on the channel list** — `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` present.
2. **`awaitConnected()`** — the relay session must be Open before the rename/archive/restore round-trips.
3. **Create a discussion** — tap `CD_NEW_DISCUSSION` → `waitUntil(THREAD_TIMEOUT_MS)` for `CD_SEND_MESSAGE`.
   A plain discussion suffices — "Rename" and "Archive" are both gated on `mutationsSupported = true`, reachable on it.
4. **Rename to the unique name** — overflow (`CD_MORE_ACTIONS`) → `waitUntil` `RENAME_ITEM` → tap; `waitUntil` for
   the dialog's `hasSetTextAction() and isFocused()` field, `performTextReplacement(uniqueName)` (the field is
   pre-filled + selected — **replace**, not `performTextInput`; #554 § Gotchas), tap `RENAME_SAVE`. Identical to
   #554 step 4.
5. **Back to list → presence check #1 (AC-1)** — tap `CD_BACK`; `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION`;
   then `waitUntil` `onAllNodesWithText(uniqueName, substring = true)` non-empty and `onFirst().assertIsDisplayed()`.
   The genuine presence observation on the surface where absence is later asserted (step 8).
6. **Re-enter the thread** — `onAllNodesWithText(uniqueName, substring = true).onFirst().performClick()`;
   `waitUntil(THREAD_TIMEOUT_MS)` for `CD_SEND_MESSAGE`. (Archive is driven "from the thread"; a 2nd presence
   observation — it can only succeed if the name is on the list.)
7. **Archive (immediate)** — open the overflow (`CD_MORE_ACTIONS`), `waitUntil` for `ARCHIVE_ITEM`, tap it
   (`onNodeWithText(ARCHIVE_ITEM).performClick()`). No confirm dialog: `sendArchive → repository.archive →
   PopBack`.
8. **Absence check (AC-1)** — after `PopBack`: `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` (the list
   marker → the thread has popped back), then `onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)`
   (**gone from the active list** — a genuine inversion of step 5). Tolerant; never a delta count or timing.
9. **Navigate to the Archived screen** — tap the settings button (`hasContentDescription(CD_OPEN_SETTINGS)`);
   `waitUntil` for the Settings marker `ARCHIVED_ROW` ("Archived discussions"); tap it
   (`onNodeWithText(ARCHIVED_ROW).performClick()`); `waitUntil(LIST_TIMEOUT_MS)` for `ARCHIVED_TITLE` ("Archived",
   the Archived-screen top bar). Default tab is Discussions → the seeded discussion is on it, no tab tap.
10. **Restore** — `waitUntil(LIST_TIMEOUT_MS)` for the restore affordance keyed on the unique name:
    `onAllNodes(hasContentDescription(uniqueName, substring = true))` non-empty (the "Restore <uniqueName>"
    `IconButton`; the row's name is a `Text` node, so only the restore button matches a *content-description*
    search) — this is a presence observation on the Archived screen — then `onFirst().performClick()` →
    `RestoreRequested → repository.unarchive`.
11. **Restore-completed guard (the one gotcha — see § Gotchas)** — `waitUntil(THREAD_TIMEOUT_MS)` for
    `onAllNodesWithText(RESTORED_SNACKBAR, substring = true)` non-empty (the "Restored <name>" success snackbar).
    This proves `repository.unarchive` returned (the daemon acked) **before** the return-nav tears down the
    Archived screen's ViewModel — closing the restore-coroutine cancellation race.
12. **Navigate back to the list** — tap `CD_BACK` (Archived → Settings); `waitUntil` for `ARCHIVED_ROW` (Settings
    marker); tap `CD_BACK` (Settings → channel list); `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION`.
13. **Presence check #2 (AC-2 — round-trip closes)** — `waitUntil(LIST_TIMEOUT_MS)` for
    `onAllNodesWithText(uniqueName, substring = true)` non-empty; `onFirst().assertIsDisplayed()`. The
    re-appearance is attributable to the restore (asserted absent in step 8), on the same surface, same unique token.

**Always-on, not `@Ignore`d.** Both post-conditions are **durable** structural facts (a conversation is in the
active list or not) — no transient like #482's spinner — so the scenario belongs in the always-on gate, matching
#481's tool-name row, #541's delimiter, and #554's delete inversion.

**No negative control needed.** The unique token's presence is observed on the list twice before archive (step 5
assert + step 6 tap) and its restore reappearance mirrors its archive disappearance; there is no match-everything
to disprove (same reasoning #482 / #554 document).

### Gotchas

**The restore-coroutine cancellation race (the one real gotcha).** `RestoreRequested` handling is
`viewModelScope.launch { repository.unarchive(id); _effects.send(RestoreSucceeded) }`
(`ArchivedDiscussionsViewModel.kt:93–115`) — the coroutine is scoped to the **Archived screen's** ViewModel.
If the test tapped restore (step 10) and immediately navigated `CD_BACK` (step 12), `popBackStack` would clear
that ViewModel and cancel `viewModelScope`; a launched-but-not-yet-started restore coroutine would be cancelled
**before `repository.unarchive` ever sent the request**, and the conversation would never be restored → step 13's
presence check would flake to a timeout. **Step 11's `waitUntil` for the "Restored" success snackbar closes this
race**: the snackbar can only render after `unarchive` returned and `RestoreSucceeded` was sent, so once it is
observed the round-trip has fully completed and navigating away is safe.

- `RESTORED_SNACKBAR` = `"Restored"` (prefix of `restored_snackbar` = "Restored %1$s") is a clean guard:
  `onAllNodesWithText` searches **text** semantics only, and "Restored" appears in **no** other on-screen string
  (the row subtitle is "Archived <time>"; the restore button's content-description is "Restore …", not
  "Restored") — so a non-empty match can only be the success snackbar.
- **First-run fallback** (rung 3 permits selector tuning): if the Short-duration snackbar proves hard to catch on
  the emulator, an equivalent completion signal is the seeded row **leaving** the Archived Discussions tab — but
  that must be gated so the simultaneously-shown snackbar (which also carries the unique name) does not confound
  a text-absence check. Prefer the snackbar-presence guard; record any tuning as a finding, do not weaken step 13.

**Not a gotcha here (unlike #554):** there is **no** confirm dialog, **no** Channel-Info sheet in the archive
path, and therefore **no** "Delete"-collision / sheet-behind-dialog disambiguation. Archive is one overflow tap.

**Back-nav disambiguation.** Both the Archived screen and Settings use `cd_back` = "Back" for their nav icons.
The `waitUntil(ARCHIVED_ROW)` gate between the two `CD_BACK` taps (step 12) waits for Compose idle → the Archived
screen is fully torn down and only Settings' single "Back" node exists before the second tap. Never `onFirst()`
across two transiently-coexisting "Back" nodes.

### Companion constants to add

Add to the shared `companion object`. **`CONVERSATION_NAME_PREFIX` already exists** (#554, `"e2e554-"`) — use a
**distinct** name here to avoid a redeclaration clash. Reuse `RENAME_ITEM` / `RENAME_SAVE` / `CD_*` / timeouts
already present.

```kotlin
const val ARCHIVE_ITEM = "Archive"                   // R.string.thread_overflow_archive — overflow item (mutationsSupported-gated), IMMEDIATE — no confirm
const val CD_OPEN_SETTINGS = "Open settings"         // R.string.cd_open_settings — channel-list top-bar settings button
const val ARCHIVED_ROW = "Archived discussions"      // R.string.archived_discussions_settings_row — Settings row → Archived screen (also the Settings-screen return-nav marker)
const val ARCHIVED_TITLE = "Archived"                // R.string.archived_title — Archived-screen top-bar arrival anchor
const val RESTORED_SNACKBAR = "Restored"             // prefix of R.string.restored_snackbar ("Restored %1$s") — restore-completion guard (§ Gotchas)
const val ARCHIVE_NAME_PREFIX = "e2e551-"            // runtime-unique rename target (+ System.currentTimeMillis()); distinct from #554's CONVERSATION_NAME_PREFIX
```

Keep the `// Keep in sync with res/values/strings.xml` comment convention already used in the companion.

### Cost — zero claude turns (same as #554)

Create-discussion, rename, archive, and restore are **daemon round-trips, not claude turns**, and the durable
identity is the typed name (no live session content needed to identify it) — so this scenario sends **no** ping
and spends **no** claude turn (there is no `PING_PROMPT`). It still rides the real rung-3 stack (real relay +
daemon) and belongs in the LIVE gate: it catches a broken `archive` / `unarchive` / `rename` wire against the
production relay. The LIVE gate goes from a **quartet (4 methods / 3 turns)** to a **quintet (5 methods / still 3
turns)** — archive/restore adds a method, not a turn (same "spends none" note #554 makes for delete).

## State + concurrency model

None new. The test drives the shipped MVI surface through Compose/Espresso synchronization (`waitUntil` +
`assertIsDisplayed` / `assertCountEquals`) and reads the relay connection via the existing `awaitConnected()`
`ConnectionStateSource` helper. No coroutine, dispatcher, or `StateFlow` is introduced. The archive path
(`sendArchive → repository.archive → PopBack`, #556) and the restore path (`RestoreRequested → repository.unarchive
→ observeConversations re-emit`, #557) are the canonical shipped flows — the e2e proves them against a real daemon,
exercising nothing the Fake synthesizes.

## Error handling

Test-side only: every wait is `waitUntil(<generous timeout>)`, which throws `ComposeTimeoutException` on a
genuinely stuck flow — the correct red signal (a down/stale relay, a broken archive/unarchive/rename wire, or a
list re-projection regression all surface this way). No new production error paths. Real-claude variance is not a
factor (no claude turn); the daemon round-trips are fast, so `THREAD_TIMEOUT_MS`/`LIST_TIMEOUT_MS = 30_000L` are
ample. All assertions are tolerant (substring, presence/absence, generous timeouts) per the
`docs/e2e-interactive-stream.md` Constraints.

## Testing strategy

Instrumented (`androidTest`), run by `scripts/e2e-emulator.sh` on a headless emulator against a host `pyry` +
relay (rung 3), and by the `LIVE=1` pre-ship gate (`scripts/e2e-preship-gate.sh`) against the production relay.
**Not** covered by `./gradlew test` (unit). Developer verification:

- **Compiles:** `./gradlew compileDebugAndroidTestKotlin` — the only gate that compiles `androidTest`
  (`test`/`lint`/`assembleDebug` skip it). All matchers this scenario needs — `hasContentDescription` (with
  `substring = true`), `onAllNodesWithText`, `onNodeWithText`, `performTextReplacement`, `isFocused`, the `and`
  member, `assertCountEquals`, `onFirst` — are **already imported/used** by the existing tests, so **no new
  imports are expected**. Let the compile gate confirm the final set.
- **Script sanity:** `bash -n scripts/e2e-emulator.sh`, and eyeball that the comma-separated `class` arg is a
  single unbroken shell token.
- **Green in the gate (AC-4):** `bash scripts/e2e-preship-gate.sh` — an operator-run, real-stack check that now
  executes five curated methods. That green LIVE run is the operator's pre-ship confirmation, not something the
  developer's turn budget must reproduce headlessly.

## `scripts/e2e-emulator.sh` change (LIVE quartet → quintet)

Append the new method to the LIVE `TEST_TARGET` comma-separated `class#method` list (line 463), so the gate runs
a **quintet**: ping + create-workspace-folder + new-session + delete + **archive-restore**. Contract (shape, not
a paste):

```sh
# scripts/e2e-emulator.sh, LIVE branch (line ~463) — append the 5th method:
TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread,\
${TEST_CLASS}#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace,\
${TEST_CLASS}#interactiveTurn_newSession_rendersSessionBoundaryDelimiter,\
${TEST_CLASS}#interactiveTurn_deleteConversation_removesFromListAndClosesThread,\
${TEST_CLASS}#interactiveTurn_archiveRestore_roundTripsListMembership"
```

The #481 tool-use test stays LIVE-excluded for cost — this is **additive**. Refresh the "quartet / 3 turns" prose
to "quintet / 5 scenarios, **still 3 turns** (archive/restore spends none — create/rename/archive/restore are
daemon round-trips)": header lines 9–10, usage line 55, run-block comments 454–455 and 460–461, and the PASS log
line 480. Grep to find every spot so none drifts:

```bash
grep -n 'quartet\|3 turns\|4 method\|delete = 4\|new-session + delete\|delete-conversation flow' \
  scripts/e2e-emulator.sh docs/e2e-interactive-stream.md
```

## Doc note (`docs/e2e-interactive-stream.md`)

Mirror how #541 / #554 / #566 were wired in: add an archive-restore scenario to the rung-3 scenario list
(§ "What rung 3 is made of"), the ladder entry, the LIVE-mode "What it runs" / pre-ship gate sections, and update
the Cost lines and any Coverage follow-ups from a quartet to a quintet. Key facts to state: the load-bearing
signals are the **two durable** post-conditions (unique name **absent** from the active list after archive — a
genuine inversion — and **present again** after restore, closing the round-trip); always-on (durable); the
identity is a **rename** to a runtime-unique name; archive is **immediate** (no confirm) while restore navigates
Settings → Archived screen; and total real-claude cost is **zero** turns, so the LIVE gate stays at 3 turns across
5 curated methods.

## Open questions / first-run assumptions (grounded, unverified end to end)

- **The daemon honours `archive` and `unarchive`, and the active/Archived projections re-emit accordingly.** This
  is the #549 / #556 / #557 contract (reply reuses `conversation_updated`), but unverified end to end against a
  real daemon. If archive does not converge the active list, step 8's `assertCountEquals(0)` fails; if unarchive
  does not re-add it, step 13's presence check fails — that is the drift Layer 3 exists to surface. Do **not**
  weaken the assertions; record the finding.
- **The restore success snackbar renders and is catchable (§ Gotchas).** The Short-duration snackbar is the
  completion guard. If it proves un-catchable on the emulator, fall back to the archived-row-departs signal
  (gated against the snackbar's own text) — do not remove the completion guard, or step 12's nav can cancel an
  unstarted restore coroutine.
- **The two `CD_BACK` hops return to the channel list.** The thread/settings/archived were all entered by forward
  navigation, so `popBackStack` twice returns to the list. Confirm on first run that the list — keyed on
  `CD_NEW_DISCUSSION` — is what renders after the second Back, not some other back-stack entry.
- **The seeded discussion appears under the Archived screen's default Discussions tab.** Rename keeps it a
  discussion (`isPromoted = false`), and the default tab is Discussions, so no tab tap is expected. If a renamed
  discussion is instead surfaced under Channels, add a tab tap on `archived_tab_discussions` ("Discussions (N)").

## Acceptance criteria (developer deliverable)

- [ ] New always-on `@Test` in `InteractiveStreamE2ETest` (rung 3) seeds a conversation with a runtime-unique,
      list-visible name (via **Rename**), confirms it **present** in the active list, then drives the real Archive
      flow from the thread (overflow → "Archive", immediate — no confirm) and asserts it is **absent** from the
      active list (`assertCountEquals(0)`, a genuine inversion of the presence check).
- [ ] The same scenario then drives the real Restore flow (settings button → "Archived discussions" → the Archived
      screen's restore affordance keyed on the unique name) and asserts the conversation is **present in the active
      list again** (round-trip closes). Both list assertions key on the runtime-unique identity; never a transient
      or a timing/delta count.
- [ ] A restore-completion guard (the "Restored" success snackbar) is awaited **before** navigating back from the
      Archived screen, so the `ArchivedDiscussionsViewModel`-scoped restore coroutine is not cancelled mid-flight;
      the rename uses `performTextReplacement` on the pre-filled field.
- [ ] The scenario is added to the LIVE curated `class#method` list in `scripts/e2e-emulator.sh` (quartet →
      quintet) and documented in `docs/e2e-interactive-stream.md`, mirroring how #541 / #554 / #566 were wired in;
      the LIVE gate stays at 3 real claude turns (archive/restore spends none).
- [ ] `./gradlew compileDebugAndroidTestKotlin` is green (androidTest is not compiled by the mandatory gates).
