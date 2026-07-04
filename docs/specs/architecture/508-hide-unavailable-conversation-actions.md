# 508 — Hide unavailable conversation actions (overflow menu + Channel Info sheet)

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/508
**Size:** S · **Security-sensitive:** no · **Blocked by:** #507 (SHIPPED — `mutationsSupported` is on `main`)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:13-91` — the dropdown; items to gate are New session / Rename / Change workspace / Archive (lines 46-73). Show literal screen, Save as channel, Channel Info, Install memory plugin stay.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt:23-72` — the pass-through that hosts the overflow; already forwards `isPromoted`. Add a sibling `mutationsSupported` param, same shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:135-145` — `ThreadTopAppBar(...)` call site; passes `isPromoted = state.isPromoted`. Add `mutationsSupported = state.mutationsSupported`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:326-342` — `ChannelInfoSheet(...)` call site (inside `if (state.channelInfoOpen)`). Add `mutationsSupported = state.mutationsSupported`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt:60-124` — `ChannelInfoSheet` + `ChannelInfoSheetContent`; the `SectionHeader("Actions")` + `ActionsGrid(...)` at lines 113-119 are what gets hidden.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:82-104,139-143,225-267` — proves `ThreadUiState.mutationsSupported` (line 104) is populated in both the combine (256) and the `initialValue` (265) from the facade snapshot (143). **No ViewModel change in this ticket** — read only.
- `app/src/androidTest/.../thread/ThreadOverflowMenuTest.kt:35-84` — component-level test idiom (drives `ThreadOverflowMenu` directly with `isPromoted`); extend with a `mutationsSupported = false` case.
- `app/src/androidTest/.../thread/ThreadScreenChannelInfoTest.kt:37-81` — screen-level sheet idiom (drives `ThreadScreen` with `channelInfoOpen = true`); extend with a `mutationsSupported = false` case.
- `CLAUDE.md` § Conventions — stateless composables, `(state, onEvent)`; and the `androidTest` gate note (below).
- `docs/knowledge` / memory lesson **[[androidtest-not-compiled-by-mandatory-gates]]** — the two surface tests are instrumented and are **not** compiled by `test`/`lint`/`assembleDebug`; compile them with `./gradlew compileDebugAndroidTestKotlin`.

## Design source

**Figma — Conversation Thread (overflow surface):** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8
**Figma — Channel Info Sheet (action grid + delete):** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48

The thread frame is an M3 `TopAppBar` (back arrow · title · `more_vert` overflow) over the message list, status row, and composer; the overflow **dropdown itself is not drawn** — it is the closed state. The Channel Info frame is an M3 `ModalBottomSheet`: drag handle, title + close, an **About** section of label/value rows, a **Memory** row, then the load-bearing **Actions** section — two `FilledTonalButton` rows (Rename / Change workspace, then Archive / Delete) — and a monospace Channel-ID footer. Both frames show the **fake-mode** surface with every action present; the relay-mode treatment is the resolved design decision below (**hide**, not disable), which Figma does not depict.

## Context

On the path to flipping `USE_RELAY_REPOSITORY`, the relay repository cannot perform conversation mutations (`archive`/`unarchive`/`rename`/`startNewSession`/`changeWorkspace` throw `UnsupportedOperationException`; `delete` inherits a throwing default). Sibling ticket #490 stops the crash at the call site (a guard); **this ticket stops presenting the impossible actions at all** — complementary, not a substitute. Both action surfaces (thread overflow menu, Channel Info sheet) must gate on the single capability signal #507 introduced, `state.mutationsSupported`, and on nothing else (no `USE_RELAY_REPOSITORY` / instance-type / relay-vs-fake check inside the composables). In fake mode (the default) `mutationsSupported` is `true`, so the surfaces are unchanged.

## Design

Purely additive UI threading of one already-existing `Boolean`. No new types, no ViewModel change, no string resources (the gated entries reuse existing labels; the sheet cells are hardcoded strings today). Treatment = **hide** on both surfaces (AC#5).

### Threading

The signal already lives on `ThreadUiState.mutationsSupported` (fail-safe-deny `false` when no connection is live — see #507's facade getter and the VM snapshot at `ThreadViewModel.kt:143`). It is forwarded, never re-derived:

```
ThreadScreen (reads state.mutationsSupported)
 ├─ ThreadTopAppBar(mutationsSupported = …) ─▶ ThreadOverflowMenu(mutationsSupported = …)   // overflow surface
 └─ ChannelInfoSheet(mutationsSupported = …) ─▶ ChannelInfoSheetContent(mutationsSupported = …)  // sheet surface
```

**Gated-param default = `true`.** Add `mutationsSupported: Boolean = true` to `ThreadOverflowMenu`, `ThreadTopAppBar`, `ChannelInfoSheet`, and `ChannelInfoSheetContent`. The default is an ergonomic seam for previews/tests only — it mirrors `ThreadUiState.mutationsSupported = true` and keeps the 10 existing `ThreadOverflowMenu(...)` test call sites and the 2 `ChannelInfoSheetContent` previews compiling **without edits** (avoids a fan-out cascade). The real fail-safe-deny is not weakened by this default: every production path threads the VM-captured value (`repository.mutationsSupported` through the `StableConversationRepository` facade, which returns `false` while no connection is live), so the default is never what reaches the UI in relay mode. **Do not "harden" these into required params** — that would cascade ~12 test/preview edits for no correctness gain. Leave the existing `isPromoted` param exactly as it is (required); only `mutationsSupported` is defaulted.

### Overflow menu (`ThreadOverflowMenu`)

The four mutation entries (New session `:46`, Rename `:53`, Change workspace `:60`, Archive `:67`) are contiguous — wrap them in a single `if (mutationsSupported) { … }` block. Everything else is untouched:

| Item | Gate | Rationale |
|------|------|-----------|
| Show literal screen | always | pure navigation, no repo call |
| Save as channel | `!isPromoted` (unchanged) | `promote` **is** implemented in remote — out of scope |
| New session / Rename / Change workspace / Archive | **`mutationsSupported`** (new) | throw / no-op-but-misleading in relay mode |
| Channel Info | always | read-only sheet opener; must remain (AC#2) |
| Install memory plugin | `isPromoted` (unchanged) | opens a docs URL, no repo call |

Contract: `fun ThreadOverflowMenu(expanded, isPromoted, mutationsSupported: Boolean = true, onDismiss, onEvent, onShowLiteralScreen, modifier)`. `New session` is a no-op today (`onOverflowEvent` maps `ThreadEvent.NewSession -> Unit`, never calls `startNewSession`) — it does not currently throw, but it is still impossible/misleading in relay mode, so it is gated with the rest.

### Channel Info sheet (`ChannelInfoSheetContent`)

Wrap `SectionHeader("Actions")` **and** `ActionsGrid(...)` (lines 113-119) together in `if (mutationsSupported) { … }`. The About and Memory sections and the footer stay — the sheet remains viewable as read-only info (AC#2). Hiding the whole section (not disabling the four cells) reads cleaner than a header floating over inert cells, since all four cells are unsupported.

Contract: add `mutationsSupported: Boolean = true` to both `ChannelInfoSheet` (forwarded verbatim) and `ChannelInfoSheetContent` (the gate site). **Keep `mutationsSupported` off `ChannelInfoUiModel`** — the model is pure display content; a capability flag is a separate concern, and adding it there would drag `toChannelInfoUiModel()` (`ThreadScreen.kt:544`) and `ThreadScreenMapperTest` into scope for no benefit. Param, not model field.

## State + concurrency model

None changed. `mutationsSupported` is a snapshot captured once at VM construction (the mode is a static Koin fake-vs-relay swap, never a runtime toggle — see `ThreadViewModel.kt:139-143`). It flows through the existing single `StateFlow<ThreadUiState>`. This ticket adds no flow, no `viewModelScope` job, no `remember`ed state — the two gates are plain `if` expressions inside already-stateless composables. Stability is preserved: `Boolean` is a stable type, so the added params do not introduce recomposition regressions.

## Error handling

Not applicable at this layer — no IO, parse, or network path. The gate is a client-side capability read. The relevant failure mode (invoking a throwing mutation in relay mode) is defended by #490 at the call site; this ticket removes the affordance that would let a user reach it, so the two together mean the throw is neither reachable via UI nor crashing if reached. The `false`-when-no-connection fail-safe is inherited from #507's facade and needs nothing here.

## Testing strategy

Both surface tests are **instrumented (`androidTest`)** — not covered by the mandatory `test`/`lint`/`assembleDebug` gates. Compile with `./gradlew compileDebugAndroidTestKotlin` before signalling done (see [[androidtest-not-compiled-by-mandatory-gates]]). Members `onNodeWithText` / `assertIsDisplayed` / `assertDoesNotExist` need no extra import.

Extend the two existing test files rather than adding new ones:

- **`ThreadOverflowMenuTest`** (component-level, drives `ThreadOverflowMenu` directly):
  - New case — `mutationsSupported = false, isPromoted = true`: assert New session / Rename / Change workspace / Archive each `assertDoesNotExist()`; assert Show literal screen, Channel Info, Install memory plugin each `assertIsDisplayed()` (they survive the gate).
  - The two existing render-order tests (`:36`, `:62`) already assert the supported case (all four present) — they exercise the defaulted `mutationsSupported = true` path unchanged; no edit needed.

- **`ThreadScreenChannelInfoTest`** (screen-level, drives `ThreadScreen` with `channelInfoOpen = true`):
  - New case — a `channelInfoState().copy(mutationsSupported = false)`: assert `onNodeWithText("Actions").assertDoesNotExist()` and each of "Rename" / "Change workspace" / "Archive" / "Delete" `assertDoesNotExist()`; assert "About" and the workspace path `assertIsDisplayed()` (read-only info remains). The sheet cell labels are literal strings in `ChannelInfoSheet.kt`, and the menu is closed in this test, so those texts appear only in the sheet.
  - Existing tests (`:70`-`:159`) use the state default `mutationsSupported = true` and continue to see the Actions section — no edit needed.

Write scenarios in the project's existing `composeTestRule` idiom (see the two files above); do not pre-write assertions from this spec verbatim.

## Open questions

None blocking. One note for the developer: if a future `ThreadTopAppBar` or `ChannelInfoSheet` caller appears outside `ThreadScreen`, it must pass `mutationsSupported` explicitly rather than rely on the `= true` default — the default is a preview/test convenience, not the intended production value. Flag it in the KDoc if you think it will bite; otherwise the single production caller (`ThreadScreen`, which passes `state.mutationsSupported`) is unambiguous.
