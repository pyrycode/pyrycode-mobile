# #1223 — Remembered model for new chats

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `createChat`, `submitAddWorkspace`, `onHostRowTapped` — the two chat creation paths and the existing-row boundary.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `rememberedModel` — persistent, app-wide raw choice from #1222.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createDiscussion`, `observeModelMenu`, `setSessionSettings`, `ModelMenuRow` — creation and destination vocabulary contracts.
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `observe` — a fresh conversation subscription requests its menu once.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `forAgent`, `onModelSelected` — agent filtering and acknowledged choices; thread opening must not recall this model.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Repo`, `fixture` — host-qualified creation and preference fixtures.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `publishedMenu`, `freshSettings`, `pickFooterOption`, `sendFromPhone` — live relay scenario seams.
- `docs/knowledge/features/channel-list-viewmodel.md` § What it does — creation ownership and host qualification.
- `docs/knowledge/features/development-verification.md` § Emulator and real evidence — live gate and evidence boundary.
- `../verifier-2694/docs/protocol-mobile.md` § `create_conversation` — upstream optional model and session settings contract; no new wire verb.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694

The dark Run configuration modal shows a vertically arranged Model radio group above Effort, running model, and context window, with Sonnet selected in the reference. This ticket changes the initial model of a newly created chat and reuses the existing Material 3 modal; it adds no visual component.

## Context

#1222 persists the last acknowledged raw model choice, but new chats still start with the daemon's inherited choice. An existing chat's saved setting, including `""`, must remain authoritative. The target host and the created conversation's agent determine the valid model vocabulary. `create_conversation` accepts an optional model upstream, but the phone cannot know the new conversation's agent and published menu with certainty before creation. The client therefore creates with its inherited default, validates against that created conversation's menu, then writes its session setting before navigating to its thread. This keeps the first message behind the acknowledged write without changing the repository contract.

The in-flight #1193 branch touches `HostChannelListViewModelTest` and `InteractiveStreamE2ETest` in separate test sections; it does not provide a type or rewrite a block this design needs. Edits here will be local and additive.

## Design

- Add one private suspending helper in `ChannelListViewModel` called by both `createChat` and `submitAddWorkspace` after `createDiscussion` returns and before navigation. It reads `AppPreferences.rememberedModel` once for each creation, so a process restart reads the persisted value. An absent, empty, or inherited-default sentinel choice leaves the created session unchanged.
- Subscribe to `repository.observeModelMenu(conversation.id)` for a bounded period. Match the raw value exactly against a row whose `agent` equals `conversation.agent` and whose `value` was not truncated. A missing menu, missing row, or unavailable vocabulary leaves the inherited setting. Never infer support from a display name, another host's menu, or another agent's row.
- For a match and a nonempty `conversation.currentSessionId`, await `setSessionSettings(sessionId, model = value)` before sending navigation. A failed write retains the inherited setting and still opens the new chat. Existing-row taps, reopens, and thread construction never call this helper.
- Log static outcomes only; do not log the remembered value, menu text, conversation id, or daemon error.

## State and concurrency model

Both creation actions already launch in `viewModelScope` on Main and resolve a host-qualified repository. The helper stays in each existing job, performs cold `Flow.first` reads, and bounds the menu wait. Cancellation propagates to the job and does not navigate; ordinary preference/menu/write failures fall back and navigate. The existing create-state compare-and-set and dismissal rules remain in force. No new hot state or background job is introduced.

## Error handling

Preference I/O failure, unavailable menu, timeout, truncated value, agent mismatch, empty session id, and rejected settings write all preserve the daemon's inherited default. The user sees the existing created chat rather than an error banner because creation succeeded. Only `createDiscussion` failure uses the existing creation failure state. The error text never reaches UI or logs.

## Testing strategy

- Focused `HostChannelListViewModelTest` cases cover both chat creation paths, persisted choice read, same-host/same-agent exact validation, absent or unavailable menus, unsupported values, no write on existing-row tap, and navigation only after the model write settles. Use the existing in-memory repository and DataStore fakes.
- Add a rung-3 `InteractiveStreamE2ETest`: choose a published nondefault model in an existing conversation through the phone, create a new chat through the Chats control, send its first message and await a real Claude reply, then assert the new session's saved model and the original conversation's unchanged saved model. The dispatcher runs `python3 scripts/android-test-gate.py live` after verification; this builder does not claim that live result.
- Run the focused unit class, Android-test Kotlin compile, lint, and debug assemble. No new screen visual behavior needs a Compose screen test or a deterministic twin: the selection source and real first turn are the rung-3 contract.

## Open questions

- Does the created conversation always carry a usable session id? If not, retain the inherited default rather than guessing a session.

## Documentation handoff

Pending documentation stage: describe new-chat model selection and the existing-conversation boundary in `docs/knowledge/features/channel-list-viewmodel.md`; record the live scenario in `docs/e2e-interactive-stream.md`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The remembered raw value is untrusted input at the `ChannelListViewModel` helper. Exact comparison with the created conversation's agent-filtered, untruncated `ModelMenuRow.value` is required before any `setSessionSettings` call. A menu from another host or agent cannot authorize a write.
- [Tokens and storage] No token is created or changed. `AppPreferences.rememberedModel` uses the existing app-private DataStore and holds a model name, not a credential.
- [Files and Android surface] The change adds no path operation, exported component, intent, push handling, provider, or WebView.
- [Crypto and network] The existing Noise-wrapped repository sends the current settings verb; no key, frame limit, TLS policy, or reconnect behavior changes. `../verifier-2694/docs/protocol-mobile.md` remains the wire source of truth.
- [Logs] Static outcome codes only; never the raw model, daemon-authored menu strings, ids, message text, or error bodies.
- [Concurrency] The existing `viewModelScope` job owns menu collection and settings write. Timeout bounds missing menu; cancellation propagates. Navigation waits for the settings acknowledgement or explicit fallback.
- [Threat model] A malicious relay cannot see the Noise payload; an unavailable or hostile daemon menu cannot cause an unvalidated model write. Screenshot, accessibility, and device compromise risks are unchanged and outside this ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27
