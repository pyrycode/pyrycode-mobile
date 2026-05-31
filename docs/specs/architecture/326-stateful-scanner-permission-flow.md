# Spec: stateful ScannerScreen + camera-permission flow (#326)

Phase 4 / pairing. Turn the Phase-0 stateless `ScannerScreen` stub into a stateful screen:
a `ScannerViewModel` + sealed `ScannerUiState` (permission-requesting / ready-to-scan / denied /
error), with the **camera-permission flow** wired (request on entry; granted → locked viewport;
denied → the existing `ScannerDeniedScreen`). **No live camera in this slice** — `ReadyToScan`
renders the existing locked viewport unchanged. The CameraX preview + ML Kit decode that fills
`ReadyToScan` is the immediately-following sibling slice and consumes this state machine.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:50-168` — the current stateless
  stub body (TopAppBar + viewport `Box` + paste `TextButton`). This whole body becomes the
  `ReadyToScan` / `PermissionRequesting` rendering, extracted behind a `when(state)`. The private
  `Reticle`/`Corner`/`HintCard` composables (`:170-268`) and the two previews (`:270-290`) are reused.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt:33-86` — the existing
  `#61` denied screen. **Reuse as-is** for the `Denied` state; do not invent a new screen. Note its
  signature: `(onOpenSettings, onPasteCode, modifier)`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:128-149` — current `composable(Routes.SCANNER)`
  block: the stub `onTap` does `pairedServerStore.save(STUB_PAIRED_SERVER)` → navigate to
  `channel_list`, swallowing `PairedServerStoreException`. This block is where the VM, the permission
  launcher, and the entry-request `LaunchedEffect` get wired. `STUB_PAIRED_SERVER` (`:308-314`) and
  the stub-pair handler are preserved verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:27-59,154-158` —
  the house pattern for `sealed interface XxxUiState` + `MutableStateFlow` + `asStateFlow()` exposure
  + private `companion object`. `ScannerViewModel` follows this shape (minus the flow plumbing — it's
  a pure synchronous mapper, like the `pendingWorkspacePicker` MutableStateFlow on `:60`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:62-71` — Koin `viewModel { … }` registrations.
  Add `viewModel { ScannerViewModel() }` (no constructor args).
- `app/src/main/AndroidManifest.xml:4-6` — `<application>` block; add `<uses-permission>` /
  `<uses-feature>` above it.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` (whole, 56 lines) —
  the 3 existing Compose tests call `ScannerScreen(onTap = {})`; they must pass `state =
  ScannerUiState.ReadyToScan` after the signature change. New denied/requesting tests slot in here.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` — the unit
  test idiom (plain JUnit, no `runTest` needed for a synchronous VM). `ScannerViewModelTest` follows it.
- `gradle/libs.versions.toml` + `app/build.gradle.kts:82` — `androidx.activity.compose` is already a
  dependency. **No new dependency is needed** — use the platform
  `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`, not accompanist.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

Locked dark scanner viewport: a `Column` with a transparent `TopAppBar` ("Pair with pyrycode" +
back arrow) over a rounded-24dp `Box` (`surfaceContainerLowest`, two radial gradients in
`primary`@0.12 / `tertiary`@0.06, horizontal atmosphere stripes at `onSurface`@0.04) holding a
centered 248dp `primary` corner-reticle with a glowing scan line and a bottom hint card
(`scrim`@0.72 with a monospace `pyry pair` span), plus a "Trouble scanning? Paste the pairing code
instead" `TextButton`. **This visual is already built and locked by #60/#121 — reproduce it
unchanged by reusing the existing composable body; this slice only wraps it in a `when(state)`.**
The `Denied` state reuses the existing `ScannerDeniedScreen` (#61, Figma node `32:2`).

## Context

`ScannerScreen` today is a stateless stub: `ScannerScreen(onTap, modifier)` where every interactive
element fires `onTap`, and the route persists a throwaway `PairedServer` + navigates. This slice
introduces the MVI scaffolding (ViewModel + sealed `UiState`) and the runtime camera-permission flow
so the screen behaves correctly as a stateful screen, *before* the live camera lands. The camera
engine (CameraX preview + ML Kit barcode analyzer) is the next slice and fills the `ReadyToScan`
state; the error state defined here is the seam it will drive on camera-bind / decode failure.

## Design

### Package / files

All new UI-layer code lives in `ui/onboarding/` (camera is an Android UI concern — kept out of
`data/`, per CLAUDE.md). No `data/` changes.

| File | Change |
|------|--------|
| `ui/onboarding/ScannerViewModel.kt` | **New** — `ScannerUiState`, `ScannerEvent`, `ScannerViewModel`. |
| `ui/onboarding/ScannerScreen.kt` | **Modify** — signature now `(state, onTap, onOpenSettings, onPasteCode, modifier)`; body becomes a `when(state)` dispatch; extract the current viewport body into a private `ScannerViewport`; add a private `ScannerErrorContent`; update both previews. |
| `MainActivity.kt` | **Modify** — `composable(Routes.SCANNER)` block: obtain VM, collect state, wire the permission launcher + entry-request `LaunchedEffect`, render the stateless `ScannerScreen`. |
| `di/AppModule.kt` | **Modify** — add `viewModel { ScannerViewModel() }`. |
| `AndroidManifest.xml` | **Modify** — declare `CAMERA` permission + optional camera feature. |

### State shape — `ScannerViewModel.kt`

A pure synchronous state machine — no `viewModelScope`, no flows, no Android types (this is what
makes the transitions unit-testable, AC5). Mirrors the `MutableStateFlow` + `asStateFlow()` idiom
from `ChannelListViewModel`.

```kotlin
sealed interface ScannerUiState {
    data object PermissionRequesting : ScannerUiState   // initial; request in flight / being checked
    data object ReadyToScan : ScannerUiState            // granted → locked viewport (camera lands next slice)
    data object Denied : ScannerUiState                 // → existing ScannerDeniedScreen (#61)
    data class Error(val message: String) : ScannerUiState
}

sealed interface ScannerEvent {
    data object PermissionGranted : ScannerEvent
    data object PermissionDenied : ScannerEvent
    data class CameraError(val message: String) : ScannerEvent   // seam: no live producer this slice
}
```

`ScannerViewModel : ViewModel()` exposes:
- `val state: StateFlow<ScannerUiState>` — backed by a private `MutableStateFlow(PermissionRequesting)`,
  exposed via `asStateFlow()`. **Single source of state.**
- `fun onEvent(event: ScannerEvent)` — exhaustive `when` mapping: `PermissionGranted → ReadyToScan`,
  `PermissionDenied → Denied`, `CameraError(m) → Error(m)`. One-liner per branch; no logging, no
  side effects.

> The `CameraError` event has **no live producer in this slice** (the permission contract only yields
> granted/denied). It exists so the `Error` state is reachable and its transition unit-testable now,
> and so the camera-engine slice has a defined entry point. Defining it is justified by AC5's
> "the `UiState` transitions are unit-testable" — an unreachable state can't have its transition tested.

### Composable contract — `ScannerScreen.kt` (stateless renderer)

The composable becomes a thin `when(state)` renderer (AC1). It stays **stateless** and keyed purely
on `ScannerUiState` so the Compose tests can drive each state directly (AC5) without touching real
runtime permissions:

```kotlin
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onTap: () -> Unit,            // viewport tap + "paste" affordance → existing stub-pair + navigate
    onOpenSettings: () -> Unit,   // denied screen → app settings
    onPasteCode: () -> Unit,      // denied/error screen "paste code" → existing stub-pair + navigate
    modifier: Modifier = Modifier,
)
```

Dispatch:
- `PermissionRequesting`, `ReadyToScan` → `ScannerViewport(onTap, modifier)` — the **existing** locked
  viewport body, extracted verbatim into a private composable. Both render identically now; the camera
  slice diverges them (live preview in `ReadyToScan`, shell in `PermissionRequesting`). Rendering the
  viewport behind the system permission dialog matches standard camera-permission UX.
- `Denied` → `ScannerDeniedScreen(onOpenSettings, onPasteCode, modifier)` — existing #61 screen, reused.
- `Error` → private `ScannerErrorContent(message, onPasteCode, modifier)` — a minimal centered surface
  (message text in `onSurfaceVariant` + a "Paste the pairing code instead" `TextButton` so onboarding
  still completes). No new illustration; no Figma exists for this state — keep it ≤ ~18 lines.

Refactor mechanics: the current `ScannerScreen` body (`:60-167`) moves wholesale into
`private fun ScannerViewport(onTap, modifier)`; `Reticle`/`Corner`/`HintCard` are untouched. **No
visual change to the viewport** (AC3) — this is a pure extraction. Update both `@Preview`s to pass
`state = ScannerUiState.ReadyToScan`.

### Permission wiring — `MainActivity.kt` `composable(Routes.SCANNER)`

The route owns the VM + Android permission API (consistent with the existing "route owns VM, screen
is stateless" pattern used by every other destination). The VM stays Android-free.

Wiring (contract, not full body):
1. `val vm = koinViewModel<ScannerViewModel>()`; `val state by vm.state.collectAsStateWithLifecycle()`.
2. Permission launcher: `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`
   whose callback does `vm.onEvent(if (granted) PermissionGranted else PermissionDenied)`.
3. **Request once on entry**, guarded so config changes don't re-prompt: a
   `rememberSaveable { mutableStateOf(false) }` "requested" flag. `LaunchedEffect(Unit)`: if
   `ContextCompat.checkSelfPermission(context, CAMERA) == PERMISSION_GRANTED` → `onEvent(PermissionGranted)`
   immediately (no dialog); else if not yet requested → set the flag and `launcher.launch(CAMERA)`.
4. Factor the existing stub-pair-and-navigate logic (`pairedServerStore.save(STUB_PAIRED_SERVER)` →
   navigate to `CHANNEL_LIST` with the existing `popUpTo`/`launchSingleTop`, `try/catch` on
   `PairedServerStoreException` → `Log.w`) into one local lambda, **unchanged** (AC4).
5. Render `ScannerScreen(state = state, onTap = stubPairAndNavigate, onOpenSettings = openAppSettings,
   onPasteCode = stubPairAndNavigate)`.
6. `openAppSettings`: fire an `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")`
   via `context.startActivity` so the user can grant permission manually.

`Routes.SCANNER` stays a **single nav destination** — `Denied` is an in-route state render, not a new
route (AC2: "no new denied screen is invented"). No new `Routes` entry.

### Manifest

Add above `<application>`:
- `<uses-permission android:name="android.permission.CAMERA" />`
- `<uses-feature android:name="android.hardware.camera.any" android:required="false" />` — camera not
  *required* (the paste fallback completes onboarding without it), so the app stays installable on
  camera-less devices.

### Koin

`AppModule.kt`: add `viewModel { ScannerViewModel() }` to the `viewModel { … }` block. No-arg ctor.

## State + concurrency model

- **Single `StateFlow<ScannerUiState>`** per the VM; no parallel mutable state. Backed by a plain
  `MutableStateFlow` (no `stateIn`/`viewModelScope` — the mapping is synchronous, like
  `ChannelListViewModel.pendingWorkspacePicker`).
- No coroutines in the VM. The only async edge is the Android permission callback, which lives in the
  composable and feeds the VM via `onEvent`.
- The VM survives configuration changes (ViewModel scope) → resolved state (`ReadyToScan`/`Denied`)
  is retained across rotation. The `rememberSaveable` "requested" flag prevents a re-prompt on
  rotation while `Denied`.
- Collection via `collectAsStateWithLifecycle()` in the route (matches every other destination).
- No shutdown/cancellation concerns this slice (no camera resource, no long-running job).

## Error handling

| Failure mode | Where | Surface |
|--------------|-------|---------|
| Permission denied (incl. "don't ask again") | launcher callback → `onEvent(PermissionDenied)` | `Denied` → `ScannerDeniedScreen`; "Open settings" handles permanent denial, "Paste code" completes onboarding. Don't distinguish transient vs permanent denial this slice (would need `shouldShowRequestPermissionRationale` + the Activity — unneeded; "Open settings" covers both). |
| Stub-pair persist fails (`PairedServerStoreException`) | existing route `try/catch` | **Unchanged** — `Log.w` + stay on screen so the user can re-tap. NOT routed through the VM `Error` state (preserving #295 behavior; `PairedServer` handling is out of scope per the ticket). |
| Camera bind / decode failure | `Error` state exists; **no live producer this slice** | The camera-engine slice drives `onEvent(CameraError(...))`; renders `ScannerErrorContent`. |

## Testing strategy

**Unit — `ScannerViewModelTest.kt`** (`./gradlew test`, plain JUnit, no `runTest` — synchronous VM):
- initial `state.value == PermissionRequesting`.
- `onEvent(PermissionGranted)` ⇒ `ReadyToScan`.
- `onEvent(PermissionDenied)` ⇒ `Denied`.
- `onEvent(CameraError("boom"))` ⇒ `Error("boom")`.

**Instrumented — `ScannerScreenTest.kt`** (`./gradlew connectedAndroidTest`, `ComposeTestRule`):
- Update the 3 existing tests to pass `state = ScannerUiState.ReadyToScan` (+ the 3 new lambdas);
  their assertions ("Pair with pyrycode", "pyry pair", "Trouble scanning?") still hold → proves
  AC3 no-regression.
- `ReadyToScan` renders the viewport (assert reticle/hint present, e.g. "pyry pair").
- `Denied` renders the #61 screen (assert "Camera permission required" exists) → AC2 denied route,
  Compose-tested.
- `PermissionRequesting` renders the viewport shell (assert "Pair with pyrycode").
- `Error("…")` renders the message + a clickable "Paste the pairing code instead".

The permission launcher → event bridging (route glue in `MainActivity`) is thin and not separately
tested — AC5 only requires VM transitions (unit) and the denied/viewport renders (Compose), both
covered above. The denied/connecting screens keep their existing tests untouched.

## Open questions

- **Re-check permission on resume.** If the user taps "Open settings", grants, and returns, this
  slice leaves them in `Denied` until they re-enter the scanner (no `LifecycleResumeEffect` re-check).
  Deferred deliberately (Simplicity First; not in the AC). The camera-engine slice owns CameraX
  lifecycle handling and is the natural home for an on-resume re-check — flag it there.
- **`CameraError` event naming.** Kept descriptive; the developer may rename to a neutral
  `ErrorOccurred(message)` if preferred. The contract (an event carrying a message → `Error(message)`)
  is what matters.
