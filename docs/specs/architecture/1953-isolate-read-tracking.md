# Isolate thread read tracking from scroll-frame composition

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen`, `ThreadRowContent`, `ThreadMessageList` and `recordReadVersion`; the list host currently reads three snapshot maps during composition.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadViewport.kt`: `ThreadReadViewport` and `qualifiesReadEdge`; preserve lifecycle, obscuring-surface and measured chrome qualification.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageBubble`, `MessageContainer` and `StreamingAssistantBody`; exact-version reveal and inner readable-column edge are distinct signals.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt`: `ToolCallRowContent`; retain the tool surface edge and its existing spacing semantics.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadViewportTest.kt`: foreground, offscreen, overlay and tall-row assertions remain intact.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadViewportDeviceTest.kt`: explicitly selects shared probes for the UI gate.
- `docs/knowledge/features/thread-screen.md`: Foreground read checkpoints (#1912) requires exact reveal, measured content edge, resumed destination and unobscured content; background-agent evidence remains attached to its rendered row.
- `docs/knowledge/features/message-bubble.md` and `docs/knowledge/features/tool-call-row.md`: measurement must not change bubble or tool geometry.
- `docs/knowledge/features/development-verification-gates.md`: shared screen tests run on Robolectric and require an Android-visible wrapper for the default UI gate.
- `scripts/android-test-gate.py`: UI gate selects device-only wrappers; scripted `ping` preserves its existing daemon-confirmed checkpoint.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: a reverse chronological message viewport under a translucent header and above the composer/status area, with inset message bubbles, tool surfaces and session rules. Existing Material 3 scheme and typography tokens, decorations, assets and all geometry remain unchanged; this ticket changes bookkeeping only, as the issue requests.

## Context

Every positioned row currently updates full-version maps that the list host reads in composition. Moving edges invalidate that host and recording versions scans accumulated row identities. Replace this UI-local lifetime history with one current candidate while retaining #1912's content-presentation contract. No repository, protocol or documentation contract changes and no decision record are needed.

The deliverable is one read-tracking isolation fix with regression coverage. Forecast: roughly 650 written lines including tests and plan, four production files, one new internal holder, one updated internal consumer and no new reject branches; all sizing limits hold. Codegraph returned no callers, so repository searches confirmed the viewport's sole consumer and the edge callback forwarding chain. Defaulted nullable callbacks preserve existing consumers without migration.

In-flight overlap: #1766 changes streaming rendering in `MessageBubble`; its diff changes a different block and supplies no dependency. Keep edits local to callback plumbing and measurement modifiers.

## Design

- Replace `laidOutVersions`, `trailingEdges`, `revealedVersions` and `recordReadVersion` with an internal `ThreadReadCandidate` holding one immutable exact rendered row and list key plus snapshot layout, edge and reveal fields.
- Remember a fresh candidate for each change of delivered row/version/key. A queued newest row has no candidate. Removing and reintroducing even an equal version creates fresh bookkeeping. Old callbacks close over only the retired holder and cannot qualify the active holder.
- Keep viewport bounds as snapshot state, but pass the state object to the viewport observer without reading its value during host composition.
- Pass the candidate object into `ThreadReadViewport`; read its fields and measured viewport inside `snapshotFlow`, alongside list membership, evidence, chrome, lifecycle and visibility. No frame-driven state read occurs in host composition.
- Only the candidate receives read-layout and content-edge callbacks. Nullable edge callbacks omit `onGloballyPositioned` entirely on other bubbles and tools. Message edges retain the inner readable column; tool edges retain `ToolCallRow`'s existing measurement. Other delivered content retains its row measurement.
- Preserve newest-row selection excluding invisible info banners, queued-newest blocking, background-agent evidence projection, nonvisual-tail advancement, tall-row edge qualification and monotonic checkpoint suppression.
- Add an internal optional composition-local observer invoked by a `SideEffect` at the production scope constructing the list and read observer. It supplies the remembered list state to tests and counts host compositions without a test wrapper or snapshot write.

## State and concurrency model

The holder and measured viewport are UI-local snapshot state confined to Compose's UI thread. `snapshotFlow` observes them in the existing `LaunchedEffect`, cancelled when the list leaves composition, its conversation changes or its lifecycle owner changes. Lifecycle collection and current state/event forwarding retain their existing behavior. No new jobs, dispatchers, repository flows or socket ownership are introduced.

## Error handling

No new I/O or error branches. Missing candidate, layout, edge, reveal, visible membership, measured chrome, foreground lifecycle or unobscured viewport continues to suppress presentation. An unchanged checkpoint emits nothing.

## Testing strategy

- First add a shared regression using 60 fixed rows and the production host observer. Wait for initial layout/checkpoint and reveal to settle, scroll a nonzero pixel distance while the newest row is still visible, assert changed position and unchanged host count, then visit older rows and assert the same. Run it red with the original maps before implementing.
- Preserve every existing `ThreadReadViewportTest` assertion. Add replacement-version coverage and focused holder tests proving stale layout/edge/reveal callbacks cannot qualify a replacement or a removed/reintroduced version and bookkeeping is constant-size.
- Add message/tool edge coverage measuring the supplied edge against actual content geometry, including optional metadata/spacing, and candidate replacement while offscreen or obscured.
- Redeclare the scroll probe in the Android wrapper alongside existing foreground methods so the default UI gate selects it. This is a required device execution of shared coverage, not new device-only logic.
- Run focused unit/shared tests, the focused viewport device class, and scripted `ping`; record selected methods and executed/failure/skip counts from fresh XML. Dispatcher owns full UI/scripted sets. The existing live checkpoint scenario remains the real-Claude coverage; no new operator flow is introduced.
- Run lint, assembleDebug, Android test Kotlin compilation and forced Spotless. After final main merge and push, run whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` with the PR body.

## Open Questions

None. Exact row identity, edge meaning, reveal readiness and gate selection are supplied by #1912 and this ticket.
