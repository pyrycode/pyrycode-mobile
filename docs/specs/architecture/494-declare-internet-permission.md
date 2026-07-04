# Spec: Declare the INTERNET permission explicitly (#494)

**Size:** XS · **Security-sensitive:** no · **UI-visible:** no

## Files to read first

- `app/src/main/AndroidManifest.xml:5` — the existing `<uses-permission android:name="android.permission.CAMERA" />` anchor. The INTERNET declaration goes adjacent to this. Lines 6–8 are the camera `uses-feature` + its explanatory comment (unrelated to this change — do not disturb their grouping).
- `app/src/debug/AndroidManifest.xml` — debug-only overlay that sets `networkSecurityConfig` (cleartext to local relay for e2e). It declares **no** permissions and inherits `main`'s permission set, so the single `main` declaration also covers debug builds. Read it only to confirm you don't need a second edit here — you don't.

No Kotlin files are in scope. Verified: the only permission check in code is `MainActivity.kt:206` (`checkSelfPermission(..., Manifest.permission.CAMERA)`). INTERNET is a *normal* (install-time) permission — no runtime request API, no `checkSelfPermission`, no code coupling.

## Context

`app/src/main/AndroidManifest.xml` declares no `INTERNET` permission. The app reaches the network today only because a transitive dependency's manifest declares INTERNET and the Android manifest merger folds it into the merged manifest. That is a hidden dependency: the committed "paste the pairing code instead" onboarding fallback (`AndroidManifest.xml:6–7`) makes it viable to swap or drop the QR-scanner library, and if the dropped library is the one carrying INTERNET, **all** networking — including the Phase 4 Noise_IK relay transport — breaks at runtime with no compile-time signal.

This change makes the app the explicit owner of the INTERNET declaration. **No runtime behaviour change:** the merged manifest already grants INTERNET today; this only moves ownership of the declaration into our own manifest.

Filed from the Cross-Repo Code Review 2026-07-03.

## Design

Single-line addition to `app/src/main/AndroidManifest.xml`: add

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**Placement (recommended):** on the line immediately **before** the existing CAMERA `uses-permission` (i.e. new line 5, pushing CAMERA to line 6). Rationale: this groups the two `<uses-permission>` elements contiguously at the top of the manifest and keeps the CAMERA permission + its explanatory comment + the camera `uses-feature` as an unbroken trio. Placing it after CAMERA (between the permission and its comment) also satisfies "alongside," but splits the camera grouping — prefer before.

INTERNET is a `normal`-protection-level permission: granted automatically at install, no runtime consent prompt, no `requestPermissions` call. There is nothing to wire in Kotlin — do **not** add any permission-check code.

- No new types, no DI changes, no ViewModel/Compose surface.
- No `data/` or `data/network/` changes — `OkHttpRelayTransport` and the Noise stack already assume network access; this only formalizes the declaration they depend on.

## State + concurrency model

N/A — no runtime code, no flows, no coroutines.

## Error handling

N/A — no runtime surface. A `normal` permission cannot fail at runtime; the only failure mode is a malformed manifest, caught deterministically by `assembleDebug`.

## Design source

N/A — manifest permission declaration; no visual surface. The visual-fidelity check is intentionally not applicable (there is no `## Figma` section in the ticket body, correctly).

## Testing strategy

No unit or instrumented tests. Verification is manifest-merge + build, matching the ACs:

- **AC1 — declaration present:** `app/src/main/AndroidManifest.xml` contains `<uses-permission android:name="android.permission.INTERNET" />` adjacent to the CAMERA declaration.
- **AC2 — merged manifest grants INTERNET exactly once:** after `./gradlew assembleDebug`, confirm the merged manifest dedupes the app-owned and transitive declarations into a single entry:

  ```bash
  grep -c 'android.permission.INTERNET' \
    app/build/intermediates/merged_manifests/debug/AndroidManifest.xml
  ```

  Expected output: `1`. (The merger collapses the app-owned declaration and the transitive one into one `<uses-permission>` node.)
- **AC3 — build succeeds:** `./gradlew assembleDebug` exits 0.

`./gradlew lint` and `spotlessCheck` are unaffected (no Kotlin changes) but remain part of the standard gate set the developer runs before commit.

## Open questions

None.
