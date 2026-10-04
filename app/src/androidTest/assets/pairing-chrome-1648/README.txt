#1648 pairing header hardware evidence, captured 2026-10-04.

Command (ANDROID_HOME points to the installed SDK):
./gradlew :app:pixel8Api35DebugAndroidTest --rerun '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.PairingHeaderCaptureTest,de.pyryco.mobile.ui.onboarding.ScannerFrameTest' -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain

Exit 0; api35-green.xml records 4 executed, 0 failed, 0 errors, 0 skipped.
PairingHeaderCaptureTest captures the assembled MainActivity at 412x892, density 1,
static dark, hardwareAccelerated=true, syntheticBars=false, real 24px status/navigation
bars. The per-frame .txt files retain these measurements. It uses pointer taps to
open scanner/pair-code, return to scanner, and return from denied to Welcome; it
asserts 48dp Back targets, title offsets, rule offsets and absence on denied.
ScannerFrameTest covers full, compact and enlarged-text scanner layouts in both themes.

Figma references fetched with get_design_context/get_screenshot on 2026-10-04:
533:2147 (pair-code) and 13:2 (scanner), file g2HIq2UyPhslEoHRokQmHG.
Header comparisons place Figma at left and hardware pixels at right after excluding
only the actual 24px status bar. No image rescaling. The title, glyph, inset rule,
dark gradient and sharp foreground match; the row uses #1646's Default shadow and
the backdrop uses its progressive Haze effect. The screen backgrounds are smooth,
so these captures do not independently quantify the blur radius; #1646 supplies
shared-effect underlap proof. Existing scanner atmospheric approximation and form
label/body styling remain outside this ticket. Denied keeps its existing title,
colors, narrower Back inset and no divider.
