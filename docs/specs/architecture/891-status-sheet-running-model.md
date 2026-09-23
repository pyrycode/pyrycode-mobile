# Show the model Claude is running in the Status sheet (#891)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeAnnouncedModel`, `observeSessionFacts`, `AnnouncedModel`, `SessionFacts` — #890's two per-conversation readings (`null` until a frame arrives; cleared on the conversation's session transition and across connections). Their KDoc makes the strings inert-text-only and forbids keying behaviour on them.
- `app/src/main/java/de/pyryco/mobile/data/repository/AnnouncedModelProjection.kt`, `SessionFactsProjection.kt` → `observe` — each frame replaces the entry; `distinctUntilChanged` per conversation. Why no VM-side staleness logic is needed.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeAnnouncedModel` / `observeSessionFacts` — `switchToLive(null)`: a reconnect or host switch heads the flow with `null`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfigFlow` (a five-arm `combine`, already at the typed ceiling), `runConfig`, `forLiveSession`, `String.inert()` — where the reading joins the run configuration and the one inert path this ticket must reuse.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig` (`savedModel`, `selectedModel`, `modelLabel`, `permissionMode`) — the surface both render sites read.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet`, `StatusSheetContent`, `SectionHeader`, `UnavailableNote`, `Caption` — the sheet's section and row style the new section reuses.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `StatusSheet(...)` call.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/BannerNoticeRow.kt` → `BannerNoticeRow` — the client-owned italic " (truncated)" mark appended to daemon text; the truncation idiom reused here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `permissionModeLabel` — reads only `ThreadRunConfig.permissionMode`; must stay untouched by the claimed posture.
- `docs/specs/architecture/889-applied-effort-footer.md` — nearest analogue (same projection, same sheet, same security posture).
- Tests: `ThreadViewModelAppliedEffortTest` (`ScriptedRepo` delegating to `FakeConversationRepository`, `collectedVm`) — the VM harness to mirror; androidTest `StatusSheetTest` (`setSheet`); `e2e/InteractiveStreamE2ETest` (`interactiveTurn_pingPrompt_streamsPingReplyIntoThread`, `createChat`, `awaitConnected`, `awaitDisplayedPingReply`).

No in-flight `origin/feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100

The "Run configuration" bottom sheet: a title row, then `labelLarge` / `onSurfaceVariant` section headers ("Model", "Effort", …) over `bodyLarge` / `onSurface` primary rows with `bodySmall` / `onSurfaceVariant` secondary lines. The node has no running-model row; this ticket adds a "Running model" section directly beneath the Model section using the existing `SectionHeader`, a `bodyLarge` primary line (the model) and a `bodySmall` secondary line (the build), and the existing `UnavailableNote` for the unavailable state. No new tokens.

## Context

The footer and the sheet's Model section show the *selected* model (pending tap or saved `SessionSettings.model`, `""` = inherited default). #890 now exposes what claude *announced* (`model_announced`) and claude's build (`session_facts.claude_code_version`). This makes the difference visible in the Status sheet only; the footer is unchanged. No ADR warranted.

## Design

### State (ThreadUiState.kt)

- `data class ThreadReportedText(val text: String, val truncated: Boolean)` — one claude-reported value already made inert; `truncated` = the daemon cut it **or** the client's inert bound cut it.
- `data class ThreadRunningModel(val model: ThreadReportedText? = null, val build: ThreadReportedText? = null)` — `model == null` is the unavailable state; `build == null` means not reported (empty) and hides the build line.
- `ThreadRunConfig.running: ThreadRunningModel = ThreadRunningModel()`. Independent of `settingsAvailable`, `savedModel`, `selectedModel`: nothing derives the running model from the selection and nothing falls back to it.

### ViewModel (ThreadViewModel.kt)

- `internal fun reportedText(raw: String, truncated: Boolean): ThreadReportedText?` — applies `inert()`; returns `null` when the inert text is empty (an all-control-character value is unavailable, not a blank row); `truncated` ORs in "inert cut characters beyond the ISO-control strip" (i.e. the 128-char bound fired).
- A private `runningModel: Flow<ThreadRunningModel>` = `combine(repository.observeAnnouncedModel(id), repository.observeSessionFacts(id))`:
  - `model` = `announced?.let { reportedText(it.model, it.truncated) }`
  - `build` = `facts?.let { reportedText(it.claudeCodeVersion, "claude_code_version" in it.truncatedFields.orEmpty()) }`
  - `SessionFacts.permissionMode` is never read.
- `runConfigFlow` keeps its five-arm `combine` and adds `.combine(runningModel) { config, running -> config.copy(running = running) }` — no sixth arm, no change to `runConfig(...)`.
- `forLiveSession` unchanged: the repository itself clears both readings on the conversation's `session_transition`, so there is no settings-re-read lag to mask.

### UI

- `StatusSheet` / `StatusSheetContent` gain `running: ThreadRunningModel = ThreadRunningModel()`. Beneath `ModelSection`: `SectionHeader(stringResource(status_sheet_running_model))` then a private `RunningModelSection(running)`:
  - `model != null` → `Text` (`bodyLarge`, `onSurface`) built as an `AnnotatedString`: the inert text plus, when truncated, the client-owned italic `status_sheet_running_truncated` suffix (" (truncated)") — visible, and read by TalkBack because it is text. **No `maxLines`/ellipsis**: `inert()`'s 128-char bound already caps the layout, and an ellipsis would clip exactly the suffix that says the value was cut. Carries `testTag(RUNNING_MODEL_TEST_TAG)` (a static constant) for the live scenario.
  - `model == null` → `UnavailableNote(stringResource(status_sheet_running_model_unavailable))` ("Not announced yet").
  - `build != null` → `Caption`-style `bodySmall` line `status_sheet_running_build` ("Claude Code %1$s") with the same truncated suffix rule.
- `ThreadScreen` passes `running = state.runConfig.running`.
- `strings.xml`: `status_sheet_running_model` "Running model", `status_sheet_running_model_unavailable` "Not announced yet", `status_sheet_running_build` "Claude Code %1$s", `status_sheet_running_truncated` " (truncated)".

## State + concurrency model

No new jobs or scopes. Both readings are cold flows collected inside the existing `state` `stateIn(viewModelScope, WhileSubscribed(5_000))`. Each `#890` flow emits `null` immediately (default `flowOf(null)` for the fake; `switchToLive(null)` for the stable repository), so the added `combine` never stalls the state. The VM is per `conversationId` (from `SavedStateHandle`), so another conversation's reading is never subscribed.

## Error handling

No new failure modes. Missing reading, cleared reading, and an inert-empty value all render the explicit "Not announced yet" state — never the selected or saved model.

## Testing strategy

- **Unit, ViewModel** (new `ThreadViewModelRunningModelTest`, `ScriptedRepo` shape with per-conversation `MutableStateFlow` maps for both readings):
  - announced differs from the selected model — saved `"sonnet"` and saved `""` (inherited default): `running.model.text` is the announced value; `modelLabel` / `selectedModel` are unchanged.
  - unavailable: no announcement → `running.model == null`; an announcement then cleared (map entry removed) → back to `null`, never the saved model.
  - truncation: `truncated = true` → flagged; a >128-char value → flagged by the inert bound; `truncated_fields = ["claude_code_version"]` → build flagged; control characters stripped; empty build → `build == null`.
  - conversation switch: readings exist for conversation A only; a VM for conversation B shows `null` model and build.
  - claimed posture: facts `permissionMode = "bypassPermissions"` with settings `permissionMode = "default"` → `runConfig.permissionMode` and `permissionModeLabel` unchanged; no `setSessionSettings` call.
- **Compose** (`StatusSheetTest`, focused managed-device run): announced model and build render under "Running model"; unavailable note renders when `model == null`; a truncated model shows " (truncated)" in its text (the semantics TalkBack reads).
- **Rung 3** (`InteractiveStreamE2ETest.interactiveTurn_pingPrompt_statusSheetShowsRunningModel`): send the ping turn, await the reply, tap the footer's status icon (`cd_thread_status_expand`), wait for the `RUNNING_MODEL_TEST_TAG` node and assert its text is non-empty and not the unavailable string. No model name hard-coded. Run by the dispatcher via `python3 scripts/android-test-gate.py live`. No rung-4 twin: whether scripted `fakeclaude` emits a `system/init` model line is not established here; left out rather than guessed.

## Open questions

- Does the rung-3 test's `testTag` need the unmerged tree? The text node sits in a plain `Column` with no merging parent, so the default tree should suffice. Resolve during implementation; record in Revisions if it changes.

## Documentation handoff

The ticket has no Documentation handoff section. Suggested for the documentation stage (pending): `docs/knowledge/features/status-sheet.md` — the new Running model section (announced model + build, unavailable state, truncation mark, claimed posture not rendered); `docs/e2e-interactive-stream.md` coverage list — the new rung-3 scenario.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — `AnnouncedModel.model` and `SessionFacts.claudeCodeVersion` are claude-authored and unsanitized upstream. Their only route to Compose is `reportedText`, which calls the existing `String.inert()` (ISO-control strip + 128-char bound) inside the ViewModel, so `StatusSheet` still only receives already-inert text and imports nothing from `data/`. The values reach `Text` only: not `MarkdownText`, a `testTag` (the tag is a static constant), a `contentDescription`/`stateDescription`, a URL, a filename or a log. The build string is a *format argument* to `status_sheet_running_build`, never the format string. SHOULD FIX (handled in Phase B): a unit test that a hostile value (ESC/newline, >128 chars, all-control) renders inert, is flagged truncated when the bound fires, and collapses to unavailable when nothing printable is left.
- [Trust boundaries / truncation honesty] Found and addressed in the Design: a `maxLines` ellipsis would hide the " (truncated)" mark on a long value, presenting cut text as whole. The design drops `maxLines` and relies on the inert bound for layout.
- [Trust boundaries / claimed posture] No findings — `SessionFacts.permissionMode` is never read by the VM, so it cannot reach `ThreadRunConfig.permissionMode`, `permissionModeLabel`, the footer's permission button, `pendingPermission` or any write. A unit test pins this.
- [Trust boundaries / behaviour keying] No findings — neither reading feeds `selectedModel`, `onModelSelected`'s same-value guard, `setSessionSettings`, or any branch other than null/empty checks.
- [Tokens] No findings — none touched.
- [File / storage] No findings — nothing persisted; the values are not in `rememberSaveable` or `SavedStateHandle`.
- [Android surface] No findings — no intents, deep links, WebViews, providers.
- [Crypto] No findings — no primitives touched.
- [Network & I/O] No findings — no new frames or sends; decode bounds are #890's (`ModelAnnouncedPayloadDto`, `SessionFactsPayloadDto`).
- [Logs] No findings — no new log lines.
- [Concurrency] No findings — two more cold flows inside the existing `state` `stateIn(viewModelScope)`; cancellation is the existing `WhileSubscribed`. Cross-conversation leakage is prevented by the per-`conversationId` projection lookup and the per-conversation VM; cross-host leakage by `StableConversationRepository`'s `switchToLive(null)`.
- [Threat model] Hostile daemon/claude: a lying model or build string changes one inert label and grants nothing. Unicode bidi/format characters (not ISO-control) pass `inert()` and could visually reorder the label — OUT OF SCOPE: the same gap applies to every run-config label today, and a hostile producer can already state any model name outright; a repo-wide `inert()` hardening would be its own ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
