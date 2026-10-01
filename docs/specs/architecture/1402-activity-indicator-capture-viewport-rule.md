# #1402 — Activity indicator capture: set the viewport before the activity launches

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadActivityIndicatorCaptureTest.kt` → `setViewport`, `restoreViewport`, `compactLargeTextKeepsOutcomeInTheStatusArea` — the only file that changes.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalCaptureTest.kt` → its `viewport` `TestRule` at `order = 0` around the compose rule at `order = 1` — the pattern to mirror.

## Design source

N/A — test-infrastructure change; no rendered output changes.

## Change

`createComposeRule()` launches its `ComponentActivity` when the rule is applied, which is before `@Before`. So today both tests resize the display after launch: `setViewport` in `@Before`, and the compact test a second time in its body. A resize under a running activity reconfigures or refocuses it, and the launcher can briefly hold focus — the observed "No compose hierarchies found" with focus on `EmptyHomeActivity`.

Replace `@Before setViewport` / `@After restoreViewport` and the in-body `wm size 320x692` with a `@get:Rule(order = 0)` `TestRule` that reads the saved size and density overrides, applies density 160 and the test's final size, waits for idle, evaluates the statement, and restores both in `finally`. The compose rule moves to `@get:Rule(order = 1)`, so its activity starts only after the final size is set and is gone before the restore. The per-test size comes from a private runtime annotation on the test method (`@Viewport("320x692")` on the compact test); without one the rule applies 412x892. An annotation, rather than matching the method name, keeps the size next to the test it belongs to. Assertions, capture names, sizes and the `capture` / `showThread` helpers stay as they are.

## Testing strategy

The class is its own proof: device-only (real `wm size` / density on the managed device), so Robolectric cannot stand in. Run the class five times on the managed API 33 device with the focused command (`:app:pixel2Api33AtdDebugAndroidTest --rerun` with `class=…ThreadActivityIndicatorCaptureTest`) and record each XML's `tests="2" failures="0"` on the PR, per the acceptance criteria.
