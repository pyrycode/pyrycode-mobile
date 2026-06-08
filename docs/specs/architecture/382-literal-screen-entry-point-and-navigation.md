# #382 — Literal-screen entry-point action + navigation destination

**Size:** S · **Security-sensitive:** yes · **Figma:** 16-8 (Conversation Thread overflow / status area)
**Split from #379** (itself split from #372 → #378/#379, then #379 → #381/#382). Consumes **#381** (merged, PR #383): the `LiteralScreenSurface` render surface + the `viewModel { LiteralScreenViewModel(get(), get()) }` Koin registration. This slice wires the **entry point** (a thread-overflow action) and the **navigation destination** that obtains that VM and renders that surface. It adds no wire types and touches neither `ThreadViewModel` nor the repository.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread (node `16:8`) renders a standard Material 3 `TopAppBar` (back arrow, title, **`more_vert` overflow** at `16:16`/`16:17`) over the message list, status row, and composer. **The entry point is that existing overflow** — this slice adds one new `DropdownMenuItem` to the existing `ThreadOverflowMenu` dropdown; no new visual component. The dropdown is not drawn in its expanded state in the locked frame, so the new row reuses the established M3 dropdown-row pattern (text-only `DropdownMenuItem`, `Schemes/surface` container, `Schemes/on-surface` text) and its exact label/placement is **design-owed** (provisional copy below). The render surface the action opens has no Figma frame — see #381 (design-owed).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:339-367` — the **thread `composable` destination** to extend (`onShowLiteralScreen` wiring) and the exact mirror for the new destination. **Extract:** the `composable(route, arguments = listOf(navArgument("conversationId") { type = NavType.StringType })) { … }` shape, the `koinViewModel<ThreadViewModel>()` + `collectAsStateWithLifecycle()` idiom, and `navController.popBackStack()`. The new `literal_screen/{conversationId}` destination is a near-verbatim copy of this block.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:368-401, 452-461` — the **`onOpenAbout` precedent** (a pure navigation callback, `onOpenAbout = { navController.navigate(Routes.ABOUT) }`) and the `private object Routes` table. **Extract:** the pattern this slice mirrors for `onShowLiteralScreen`, and the `Routes` insertion point for `LITERAL_SCREEN`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` (whole file, 81 lines) — **the menu to extend.** Add one unconditional `DropdownMenuItem` + a new `onShowLiteralScreen: () -> Unit` param. Note every item's `onClick` does `onDismiss()` then dispatches; the new item does `onDismiss()` then `onShowLiteralScreen()`. The unconditional items (not inside `if (isPromoted)` / `if (!isPromoted)`) are the "always available" region.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt:21-69` — the **pass-through layer.** Add `onShowLiteralScreen` to the signature; forward it to `ThreadOverflowMenu`. The only caller is `ThreadScreen`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:64-97` — the **top of the callback chain.** Add `onShowLiteralScreen: () -> Unit = {}` (defaulted, matching the other optional callbacks like `onOverflowEvent`/`onTitleClick`); pass it into `ThreadTopAppBar`. The default keeps `ThreadScreenOverflowTest`/`ThreadScreenChannelInfoTest`/previews compiling unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurface.kt:56-69` — the **surface this destination renders.** Public contract `LiteralScreenSurface(state, onEvent, onBack, modifier)`; it fires `LiteralScreenEvent.Request` once on entry via `LaunchedEffect(Unit)` — so a fresh destination per open re-fetches automatically. Context only; do not modify.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenViewModel.kt:60-66` — confirms the VM reads `savedStateHandle.get<String>("conversationId")`. **The route arg name MUST be exactly `conversationId`** so the destination's back-stack-entry `SavedStateHandle` seeds it. Same mechanism as `ThreadViewModel` (proven on `main`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — confirm `viewModel { LiteralScreenViewModel(get(), get()) }` is already registered (#381). No DI change in this slice; `koinViewModel<LiteralScreenViewModel>()` resolves against it.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt` (whole file, 215 lines) — **the test idiom + the exact-displayed assertions to update.** Both `*_menu_items_render_*` tests must add the new item; 8 `ThreadOverflowMenu(...)` call sites gain `onShowLiteralScreen = …`; add a new tap test. Copy the `string(resId)` helper + `createComposeRule` shape.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt:51-61` — `tapping_overflow_icon_renders_all_five_menu_items` renders the menu through `ThreadScreen`; update it for the sixth item (rename it accordingly). `ThreadScreen`'s defaulted `onShowLiteralScreen` means its `setContent` need not change.
- `app/src/main/res/values/strings.xml:54-59` — the `thread_overflow_*` key convention + insertion point for the new label. (The `literal_screen_*` strings at 70-77 already exist from #381 — do not duplicate.)
- Memory `ktlint-filename-rule-single-class` — no new single-public-type files are added here (all edits are to existing files + one new test class named after its class), so the filename rule is satisfied by construction.

## Context

"Show the literal screen" is the parser-independent floor of the Phase-2 degrade strategy (pyrycode#596, ADR 025 § Safe degradation). #378 merged the `LiteralScreenViewModel`; #381 merged the `LiteralScreenSurface` + DI registration. Both are reachable from nowhere — this slice supplies the **only path to them**: an always-available action on the Conversation Thread that opens a dedicated literal-screen destination for the current conversation.

"Surface this prominently on a stall" is a separate ticket (#373, blocked on a stall signal that does not yet exist on the wire). Here the action is **always manually available**, independent of connection / parse / promotion state.

## Design

**No new production source file.** Four existing `.kt` files change; one string is added; two test files are updated; one new test file is added.

Production source (`.kt`, non-test) — 4 files, all modifications:
- `ui/conversations/thread/ThreadOverflowMenu.kt` — new param + one unconditional `DropdownMenuItem`
- `ui/conversations/thread/ThreadTopAppBar.kt` — new param, forwarded
- `ui/conversations/thread/ThreadScreen.kt` — new defaulted param, forwarded
- `MainActivity.kt` — new route constant, new destination, `onShowLiteralScreen` wiring, two imports

Resources / tests:
- `res/values/strings.xml` — one new string
- `androidTest/…/ThreadOverflowMenuTest.kt` — updated (call sites + count assertions + new tap test)
- `androidTest/…/ThreadScreenOverflowTest.kt` — updated (the "five items" assertion)
- **New:** `androidTest/…/LiteralScreenNavigationTest.kt` — route-contract test

### Chosen shape: a pure navigation callback (not a `ThreadEvent`)

The action is wired as a dedicated `onShowLiteralScreen: () -> Unit` threaded `ThreadScreen → ThreadTopAppBar → ThreadOverflowMenu`, **mirroring Settings' `onOpenAbout`** (`MainActivity.kt:400`). Rationale (the architect's call, per the ticket):

- It is **pure navigation** with no VM state change — the conversationId is already in hand at the destination layer. Routing it through `ThreadEvent` would force either a new `ThreadNavigation.ToLiteralScreen` emitted by `ThreadViewModel` (couples a view-only action to the VM) or an interception `when` block over `onOverflowEvent` in `MainActivity` (more code). Neither earns its keep.
- It **avoids rippling a new variant through the `ThreadEvent` sealed type** and the overflow tests' event assertions — exactly the churn the ticket flags.
- It keeps `ThreadViewModel`, `ThreadEvent`, and the repository untouched.

### `ThreadOverflowMenu.kt`

Add a required `onShowLiteralScreen: () -> Unit` parameter (the existing `onDismiss`/`onEvent` callbacks are required too — keep the contract honest; a navigation action must not silently default to a no-op). Add **one unconditional `DropdownMenuItem`** — placed as the **first item, above the `if (!isPromoted)` Save-as-channel block** so it sits in the always-shown region (AC#1: never gated on promotion). Behaviour mirrors the existing items: `onClick = { onDismiss(); onShowLiteralScreen() }`, label `stringResource(R.string.thread_overflow_show_literal_screen)`. Placement/label are design-owed (the expanded dropdown isn't in the frame).

### `ThreadTopAppBar.kt`

Add `onShowLiteralScreen: () -> Unit` to the signature (required; only caller is `ThreadScreen`); forward it into the `ThreadOverflowMenu(...)` call.

### `ThreadScreen.kt`

Add `onShowLiteralScreen: () -> Unit = {}` (defaulted, matching `onOverflowEvent`/`onTitleClick`/etc.); pass it to `ThreadTopAppBar`. The default means the existing `ThreadScreen` callers (two overflow/channel-info tests, four `@Preview`s) compile unchanged; only `MainActivity` supplies the real callback.

### `MainActivity.kt`

Two changes plus a route constant and imports:

1. **Thread destination** (`composable(route = Routes.CONVERSATION_THREAD, …)`, currently `MainActivity.kt:339-367`): capture the back-stack entry in the trailing lambda (`{ backStackEntry -> … }`) and read `val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()`. Add to the `ThreadScreen(...)` call:

   ```kotlin
   onShowLiteralScreen = { navController.navigate("literal_screen/$conversationId") },
   ```

   (Per the ticket's "read `conversationId` from the thread's back-stack entry." `state.conversationId` from the collected `ThreadUiState` is an equivalent source if preferred — both yield the same id; the back-stack-entry read is timing-independent.)

2. **New literal-screen destination** — a near-verbatim mirror of the thread destination, signature only:

   ```kotlin
   composable(
       route = Routes.LITERAL_SCREEN, // "literal_screen/{conversationId}"
       arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
   ) {
       val vm = koinViewModel<LiteralScreenViewModel>()   // scoped to THIS back-stack entry
       val state by vm.state.collectAsStateWithLifecycle()
       LiteralScreenSurface(state = state, onEvent = vm::onEvent, onBack = { navController.popBackStack() })
   }
   ```

   **Security-load-bearing (AC#3):** `koinViewModel<LiteralScreenViewModel>()` is called **inside this destination's composable lambda**, so it binds to `LocalViewModelStoreOwner.current` — the destination's `NavBackStackEntry`. Each `navigate("literal_screen/<id>")` pushes a **new** back-stack entry (a distinct `ViewModelStoreOwner` with its own `SavedStateHandle` seeded from that entry's `conversationId` arg) ⇒ a **fresh `LiteralScreenViewModel` per open**, and `LiteralScreenSurface`'s `LaunchedEffect(Unit) { Request }` re-fetches. This is the same mechanism the merged thread destination already uses for `ThreadViewModel`. The VM must **never** be obtained above the destination (activity/NavHost scope) or registered as a Koin `single` — either would let one conversation's screen text bleed into the next. See Security review › Trust boundaries / Concurrency.

3. **`Routes`**: add `const val LITERAL_SCREEN = "literal_screen/{conversationId}"`. **Imports:** `…ui.conversations.thread.LiteralScreenSurface`, `…ui.conversations.thread.LiteralScreenViewModel`.

### `strings.xml`

| Key | Purpose | Provisional value |
|---|---|---|
| `thread_overflow_show_literal_screen` | Overflow item label | `Show the literal screen` |

Copy is design-owed (label may change with the owed Figma dropdown); keep the key.

## State + concurrency model

- This slice introduces **no new state holder, StateFlow, or coroutine.** The overflow→navigate path is a synchronous callback; the destination collects the existing VM's `state` via `collectAsStateWithLifecycle()` (Main-thread, lifecycle-aware) exactly like every other destination.
- The literal-screen VM is **per-destination-back-stack-entry scoped** (above). Its `viewModelScope` is cancelled when the entry is popped (back/dispose) — the in-flight snapshot read is cancelled on screen exit, unchanged from #378. No app/activity-scoped work is launched.
- No hot/cold-flow change; no `MutableStateFlow` mutation; no `update {}`/mutex concerns added.

## Error handling

This slice surfaces no new failure modes. Snapshot fetch errors are owned by `LiteralScreenViewModel` (maps repository exceptions → `LiteralScreenError`) and rendered by `LiteralScreenSurface` (#381) — both already on `main`. The action itself cannot fail: it is always offered (AC#1), and tapping it always navigates (a fresh destination + a fresh VM that issues `Request`). If the snapshot read fails — including the always-possible disconnected case — the surface shows the calm per-reason error + retry that #381 specified; nothing here intercepts or maps it.

## Testing strategy

Instrumented Compose / navigation tests (`./gradlew connectedAndroidTest` locally; CI gate `./gradlew test` + `lint` + `spotlessCheck`, AC#4). All use `createComposeRule` + the `string(resId)` helper, matching the sibling thread tests. Developer writes bodies in the project idiom.

**`ThreadOverflowMenuTest.kt` (update):**
- All 8 existing `ThreadOverflowMenu(...)` call sites gain `onShowLiteralScreen = {}` (or a recording lambda where exercised) — mechanical, required-param compile fix.
- Both `*_menu_items_render_in_documented_order_when_expanded` tests (promoted **and** discussion/unpromoted): add `onNodeWithText(string(R.string.thread_overflow_show_literal_screen)).assertIsDisplayed()`. **Asserting it in both proves "always available regardless of promotion" (AC#1).**
- New: `tapping_show_literal_screen_dismisses_then_invokes_callback` — record `onDismiss` + a `onShowLiteralScreen = { log.add("show") }`; tap the item; assert order `["dismiss", "show"]` and that no `ThreadEvent` was dispatched (the action bypasses `onEvent`).

**`ThreadScreenOverflowTest.kt` (update):**
- `tapping_overflow_icon_renders_all_five_menu_items` → rename to `…six_menu_items` (or drop the count from the name); add the new item's `assertIsDisplayed()` after opening the menu. `ThreadScreen`'s defaulted `onShowLiteralScreen` keeps `setContent` unchanged.

**`LiteralScreenNavigationTest.kt` (new) — route-contract (AC#2/#3 routing half):**
- Build a minimal two-destination `NavHost` (`start` + `literal_screen/{conversationId}` with the `StringType` `navArgument`, stub content) hosting a `rememberNavController()` captured via `lateinit`. **No new dependency** — uses `navigation-compose` (already present) under `createComposeRule`; deliberately avoids `androidx.navigation:navigation-testing`/`TestNavHostController`, which is not in the catalog.
- Scenario: `runOnUiThread { navController.navigate("literal_screen/conv-42") }` → `waitForIdle()` → assert `currentBackStackEntry?.destination?.route == "literal_screen/{conversationId}"` **and** `currentBackStackEntry?.arguments?.getString("conversationId") == "conv-42"`.
- This pins the **route template + arg-name contract** the VM's `SavedStateHandle.get<String>("conversationId")` depends on (a `StringType` arg named `conversationId` survives `navigate`). It tests a copy of the route string, not `PyryNavHost` directly — consistent with the repo's altitude (no `MainActivity` nav tests exist; `PyryNavHost` is `private` + Koin-bound). See Open questions.

What is **not** unit-tested, by design: the `MainActivity` glue line (`onShowLiteralScreen = { navigate("literal_screen/$conversationId") }`) and the per-entry VM-freshness framework guarantee — both are correctness-by-construction (call-site placement) and covered by code review, the same way #381 handled `FLAG_SECURE`'s no-op-under-test.

## Open questions

- **Menu placement / label.** "Show the literal screen" as the first (top) overflow item is a provisional choice; the owed Figma dropdown (the expanded `16:16` menu isn't drawn) may reorder or relabel it. The always-available (promotion-independent) placement is the locked invariant; pixel/label fidelity is design-owed (#381 precedent).
- **Route-template duplication.** The nav test hard-codes `"literal_screen/{conversationId}"`, a copy of `Routes.LITERAL_SCREEN`. This matches the existing inline-route idiom (`"conversation_thread/$id"` is already built inline, separate from `Routes.CONVERSATION_THREAD`). A shared `internal` route builder would let the test guard `MainActivity` directly, but introducing one is adjacent-refactor scope creep — deferred. If a future ticket consolidates route building, fold this in.
- **`conversationId` source for the navigate call.** Spec uses the thread back-stack entry arg (per the ticket). `state.conversationId` is equivalent and already collected; the developer may use either. No functional difference.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. This slice moves no data across a trust boundary. The only datum it handles is the **non-sensitive** `conversationId` (an app-internal id already crossing the thread route), passed verbatim into the new route. The **sensitive** snapshot text never enters this slice — it is fetched inside `LiteralScreenViewModel` and rendered by `LiteralScreenSurface`, both reviewed in #378/#381. The boundary that matters here is the **VM-instance boundary**: a fresh `LiteralScreenViewModel` per destination back-stack entry, enforced by calling `koinViewModel()` inside the destination composable (binds to the entry's `ViewModelStoreOwner`). This is documented in the Design and is the load-bearing AC#3 invariant.
- **[Tokens, secrets, credentials]** N/A — no token/secret/credential is generated, stored, compared, or logged. The `conversationId` is not a secret.
- **[File / storage operations]** N/A — nothing is written to disk. No `rememberSaveable`/`SavedStateHandle` of sensitive data is introduced here (the route arg `conversationId` is non-sensitive and is the only thing in the back-stack entry's saved state; the snapshot text's no-persist rule lives in #381's surface and VM, untouched). No path is constructed from any input.
- **[Inter-process / Android attack surface]** No MUST FIX. The new route is an **internal** Compose-NavHost destination — **not** an exported `Activity`, intent-filter, deep link, content provider, `PendingIntent`, or WebView. No third-party app can reach `literal_screen/{conversationId}`; navigation originates only from the in-app overflow tap. No `android:exported`/manifest change. Attack surface is unchanged.
- **[Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparison introduced.
- **[Network & I/O]** N/A in this slice — it issues no network call. The snapshot request runs over the existing Noise/WS transport, owned by the VM/repository (timeouts, frame caps, TLS upstream); this slice only triggers it indirectly via the surface's existing `LaunchedEffect`.
- **[Error messages, logs, telemetry]** No MUST FIX. This slice adds **no `Log.*`/`println`**. It builds no error string and interpolates no sensitive value into any UI/log text — the only interpolation is `"literal_screen/$conversationId"` (a non-sensitive id into a route). The redacted-`toString`/no-log guarantees for the snapshot text are #378/#381's and are not weakened here.
- **[Concurrency]** No MUST FIX — and the AC#3 cross-conversation-bleed threat lands squarely here. It is closed by **two fabrics** (belt-and-suspenders): (a) the **deterministic framework guarantee** that each `navigate(...)` to the parameterized route creates a distinct `NavBackStackEntry`/`ViewModelStore`, so `koinViewModel()` inside the destination yields a fresh VM with a fresh per-entry `SavedStateHandle` (same proven mechanism as `ThreadViewModel`); and (b) the **spec-mandated call-site placement** — the VM is obtained *only* inside the destination composable, never hoisted to activity/NavHost scope and never registered as a Koin `single`. The fresh VM begins in `Loading` and re-fetches via the surface's `LaunchedEffect(Unit)`, so no prior conversation's `text` is reachable. No new coroutine/scope/shared-mutable-state is introduced; VM `viewModelScope` cancels on entry pop.
- **[Threat model alignment]** Addressed. The mobile-specific leakage threats for the sensitive surface (screenshot/recording/cast via `FLAG_SECURE`, clipboard via no-`SelectionContainer`, instance-state persistence via no-`rememberSaveable`, log/crash capture via no-logging + redacted `toString`) are all owned by #381's surface and remain in force — this slice neither adds nor weakens any. The threat this slice **owns** is **cross-conversation text bleed via VM scope**, closed above. **Accessibility-service / overlay eavesdropping** of on-screen text remains an app-wide platform residual not addressable per-view (no per-destination defense beyond `FLAG_SECURE`); OUT OF SCOPE, app-wide, no ticket — unchanged from #381.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
