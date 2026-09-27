# Sidebar pairing toolbar (#1186)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` — `ChannelListTopBar`, `ChannelListBarEntry`, `ConversationTree`, `treeSection`: fixed chrome and the two existing groups.
- `app/src/main/res/values/strings.xml` — `channel_list_empty`, toolbar and section descriptions: static accessible guidance.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `PyryNavHost`: existing Settings, selected-host Archive, scanner and code-pairing routes and cancellation.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — `setTree`, `assertBarDrawn`: event, folding and list fixtures.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListColoursTest.kt` — `show`, `draw`, `assertTwoRules`: light/dark rendering and retained separator proof.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsNavigationTest.kt` — `start`: production navigation graph with isolated stores and inert transport.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/ArchiveNavigationTest.kt` — `theChannelListsArchiveEntryOpensTheSelectedHostsArchive`: retained Archive destination proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — `pairHostByCode`: selector and connection/return waits.
- `docs/knowledge/features/channel-list-screen.md`, `channel-list-screen-how-it-works.md`, `channel-list-screen-tree-and-controls.md` — current tree, inset ownership, local dark panel and host-qualified controls.
- `docs/knowledge/features/navigation.md` — Manual pairing entry and return: scanner stays below code entry on cancellation.
- `docs/knowledge/features/development-verification.md` — Where a screen test goes and Compose evidence: shared Robolectric tests and native rendering.

Codegraph context returned unrelated archive symbols; targeted searches found the bar entry and E2E helper, but the bar callee query was empty. File reads supplied the missing context.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Read the screen and top-bar design context and viewed the Sidebar `133:259` screenshot. The toolbar is a titleless row, with Settings and Archive at the left and a plus at the right, above a thin rule. The panel has 20dp gutters, a 24dp top inset, a 28dp button wrapper (4dp before the 24dp glyph), a 16dp wrapper-to-rule gap and 24dp rule-to-content spacing; controls use primary and the dark rule uses inversePrimary at 60%.

Use the existing Material Settings/Archive icons and styling per the ticket; the plus uses the existing Material Add glyph vocabulary. This preserves the established Material approximation of Figma's FontAwesome artwork. The host-first hierarchy pictured below the bar belongs to #1187.

## Change

Move the existing `PairHostTapped` emission to one `ChannelListBarEntry` at the right of the fixed `ChannelListTopBar`. Keep Settings and Archive together on the left. Remove only global `TreeSectionHeader` emissions from `treeSection`; retain both groups, their separator, fold keys, host/workspace actions and row targeting. Leave the reusable header component intact.

Keep 48dp non-overlapping targets and 24dp glyphs. The glyph top is 28dp: target top 16dp plus 12dp inner slack. Targets end at 64dp; 4dp more puts the 1dp divider at 68dp, matching the reference. Keep 52dp between left-control centres (4dp target gap). Place outer glyph edges at the 20dp gutters. Set divider-to-first-row spacing to 24dp, with zero extra first-host or list-top padding. Retain the inter-group separator's 28dp air on both sides now that there is no header slack to account for. Preserve the existing Scaffold inset ownership and theme colors.

Add static `cd_pair_another_host` = “Pair another host”; empty guidance says “To pair a host, tap Pair another host at the top right.” This remains true while snapshots are pending. No new state, flow, coroutine, route, I/O, failure mode or dependency. Existing pairing lifecycle/error logging remains in its current owners; moving a stateless event emitter adds no new lifecycle to log.

## Testing strategy

- RED then GREEN in `ChannelListScreenTest`: one pairing control, no global titles or old pairing names, all three events on empty/populated draws, persistent controls after scrolling to the final chat, 48dp non-overlapping targets, 24dp glyphs and horizontal gutters.
- Extend native-render `ChannelListColoursTest` to measure the 1dp rule and 24dp first-row gap in both themes, preserving fill/separator checks and saving temporary renders for visual comparison.
- Extend `SettingsNavigationTest` using its existing production graph: toolbar → scanner → paste → code, Cancel/Back → scanner → invoking list; Archive does nothing without a selected host and Settings still opens. Existing `ArchiveNavigationTest` proves the selected-host route.
- Update only the selector in `InteractiveStreamE2ETest.pairHostByCode`; preserve its paste, confirmation, connection and return waits. Selector maintenance creates no new live-Claude acceptance requirement, per the ticket.
- Run affected shared classes via `testDebugUnitTest`, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`. Dispatcher owns full-suite and live acceptance.

## Scope and dependencies

One deliverable: revised sidebar pairing entry. Estimate approximately 350 written lines including this plan, tests and selector maintenance, consistent with #737's 327 additions. One production Kotlin file plus strings; zero new exported types, zero changed signatures or consumer migrations, three acceptance criteria, zero new error branches. All six size limits pass. Refreshed remote feature branches; no overlaps with intended files were found. No dependency on #1187.

## Documentation handoff

No explicit documentation-only acceptance criterion or handoff was supplied. Pending documentation stage: update `docs/knowledge/features/channel-list-screen-how-it-works.md` § “The list's own top bar”, `channel-list-screen.md` § “What it does”, `channel-list-screen-tree-and-controls.md` § “Add controls”, and `navigation.md` § “Manual pairing entry and return” to describe the single toolbar entry, removed global headers, revised spacing and empty guidance.

## Open questions

None.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `PairHostTapped` remains a parameterless app event. `PyryNavHost` still enters the scanner and `parsePairingPayload` plus fingerprint confirmation remain between untrusted QR/code input and persistence. No shortcut to save or connect is introduced.
- **Tokens:** no credentials enter toolbar state, labels, route arguments or test artifacts. Existing pairing stores and token lifecycle are unchanged.
- **File/storage:** no new runtime file access, paths, cache keys, backup behavior or persistence. Test renders contain only synthetic fixtures.
- **Android surface:** no manifest, exported component, intent, deep-link, pending-intent or WebView changes; the new control navigates only through the existing internal route.
- **Cryptography:** no key, nonce, primitive or handshake changes; the existing Noise and Keystore ownership stays outside the toolbar.
- **Network/I/O:** toolbar action does not dial. Existing parse/confirm/persist/connect gates retain their ownership; no transport settings or limits change.
- **Errors/logs:** new copy is static and carries no host data or secrets. Existing pairing failure and lifecycle logs remain owned by the original flow; no new telemetry.
- **Concurrency:** stateless callback relocation introduces no jobs or mutable state. Code cancellation retains the original view-model and back-stack behavior, covered through production navigation.
- **Threat model:** no new daemon text rendering or relay trust. Malicious relay behavior, disk token theft, overlay/keyboard interception and pairing-screen screenshot policy stay with existing transport/pairing implementations; this ticket neither changes nor claims new protection for them.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27
