# #1546 — Launcher icon shows the snowflake on the dark glow

## Files read

- `app/src/main/res/drawable/ic_launcher_background.xml`: the placeholder flat `@color/ic_launcher_background` fill. It gets redrawn.
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: the placeholder white P. It gets redrawn.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`: the adaptive icons. The monochrome layer already points at `ic_launcher_foreground`, so these stay as they are.
- `app/src/main/res/values/colors.xml`: `ic_launcher_background` (#FF32628D) and `splash_mark` (#FF7AB8E8, the glacier blue the splash overview says this ticket shares).
- `app/src/main/res/drawable/ic_splash_logo.xml`, `docs/knowledge/features/splash-screen.md`: how #1545 placed the same mark, and the lesson that launcher and splash drawables resolve outside the app theme, so they take colour resources and never `?attr/...`.
- `app/src/sharedTest/java/de/pyryco/mobile/SplashResourcesTest.kt`: `launcherIconBackgroundIsUnchanged` pins the old #FF32628D and has to follow the redesign.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=703-5001 (background `703:5002`, foreground `703:5003`, circle preview `703:5009`)

Two 108 dp adaptive icon layers, after the macOS icon `504:2189`. The background is Schemes/Surface dark #101418 under an elliptical radial glow. Figma exports the glow as `radialGradient r=10` with `gradientTransform="matrix(7.9313 -1.6594 1.6791 9.2402 54 43.594)"`, #134A74 at offset 0.11058 fading to `rgba(0,51,85,0)` at 0.62303, so it is centred horizontally and about 35 % down the 72 dp visible area. The foreground is the pyrycode mark, 47.25 x 54 dp, #7AB8E8, centred: x 30.375 to 77.625, y 27 to 81 on the 108 dp canvas.

## Change

- `ic_launcher_foreground` draws the path Figma exports for `703:5003` verbatim. That path is already placed and scaled on the 108 dp canvas, so it needs no group transform, unlike `ic_splash_logo`. It is filled `@color/splash_mark`, the glacier blue the splash already names, so the brand colour stays one resource.
- `ic_launcher_background` fills the canvas with `@color/ic_launcher_background`, which is retuned from #FF32628D to #FF101418 (`backgroundDark`). Over that it draws the glow as a square path filled with an inline `aapt:attr` radial gradient: centre (0, 0), radius 10, items #FF134A74 at 0.11058 and #00335500 at 0.62303. The square sits inside one `<group>` with `translateX=54`, `translateY=43.594`, `rotation=-5.932`, `scaleX=8.092` and `scaleY=9.401`. That is the singular value decomposition of Figma's matrix, M = R(-5.932°) · diag(8.092, 9.401) · R(-5.070°). The right-hand rotation turns a circular gradient about its own centre and changes nothing, so the group's scale-then-rotate order reproduces the ellipse exactly.
- The monochrome layer keeps pointing at the foreground. Themed icons use only its alpha, so the snowflake shows (AC 2).

Nothing else changes. The manifest, the mipmaps and the splash already reference these names.

## Testing strategy

- `SplashResourcesTest.launcherIconBackgroundIsUnchanged` becomes `launcherIconBackgroundIsTheDarkSchemeBackground`, which pins `R.color.ic_launcher_background` to `backgroundDark`.
- A new shared test, `LauncherIconResourcesTest`, inflates `R.mipmap.ic_launcher` and `ic_launcher_round` as `AdaptiveIconDrawable` under Robolectric. It asserts both have a monochrome layer and that the layered vectors inflate, gradient included.
- Visual AC 1 is a capture of the home screen on the managed emulator, compared with the `703:5009` render at 1:1 and recorded in the PR. No instrumented test is added: as `splash-screen.md` records for the splash, the launcher draws the icon outside the app process, where Compose tests cannot reach it.

## Documentation handoff

- `docs/knowledge/features/splash-screen.md`, "Colors" and "Resource contract": `ic_launcher_background` is now #FF101418, not #FF32628D, and the launcher foreground shares `splash_mark`. Pending for the documentation stage.
