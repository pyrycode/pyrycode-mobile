# Cancel stale Go to agent navigation

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: marker callback, pending `goToAgent`, list effect and list input modifiers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadListFollow.kt`: `FollowNewestEnd` and `ThreadListViewport`; compensated geometry is not reader intent.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `OlderHistoryGesture` requires touch provenance for history demand; navigation cancellation must also accept accessibility input.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadViewport.kt`: destination lifecycle ownership.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: existing scroll-only and expansion assertions remain intact.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentViewportDeviceTest.kt`: explicit Android-visible wrappers select shared probes in the routine UI gate.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-subagent-tool-rows.md`: the fold cannot create a marker without a loaded root; navigation never expands a run.
- `docs/knowledge/features/development-verification-gates.md`: shared JVM screen tests and Android-visible device selection.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: dark thread, translucent header and composer, blue message surfaces, primary text and inverse-primary dividers. Existing Material 3 geometry, assets and theme tokens remain unchanged; only marker navigation lifetime changes.

## Context

A tap can outlive its root disappearing from loaded rows. A later root arrival must respect newer reader intent. #1955 is merged in this checkout; its relocation and compensation rules remain unchanged. No in-flight numeric feature branch overlaps the planned existing files. This is one navigation contract, approximately 350–450 written lines including plan and probes, two new internal types, one existing screen consumer, four criteria and no new error branches. No decision record is needed.

## Design

Add a small UI-local `ThreadAgentNavigation` owner with an identity-bearing request and the currently executing navigation job. A fresh marker tap replaces the request even for the same agent. `ThreadScreen` remembers the owner per conversation and keeps its existing row lookup effect, delegating the actual scroll to that owner. A successful scroll consumes only the request it executed.

Attach a non-consuming nested-scroll observer to the message region, enclosing both list and empty-thread scrollables. Nonzero vertical `UserInput` cancels the request and its active job synchronously; this includes accessibility scrolling. Programmatic `scrollToItem`, layout changes and viewport compensation do not emit reader input. Keep the history-demand gesture unchanged.

Observe the destination lifecycle with `DisposableEffect`: loss of resumed ownership cancels navigation, and disposal cancels it too. The request is never saveable. Conversation changes create a fresh owner and retire the old one. Run expansion remains independently owned by existing saveable state.

## State and concurrency model

All navigation state and callbacks run on the Compose main dispatcher. `LaunchedEffect` owns each scroll job. Recomposition caused by rows or prompt count cancels the effect, retaining an unresolved request for a new lookup; reader movement, replacement and departure explicitly clear it and cancel the recorded job. Identity checks prevent an old completion from consuming a newer tap. No new ViewModel, flow, dispatcher or background job is introduced.

## State transitions and identity reuse

Shared production-screen probes in `AgentNavigationScreenTest`, also explicitly selected by `AgentNavigationDeviceTest`:

| Event | Probe |
| --- | --- |
| Tap loaded marker, then root disappears; unchanged waiting and root rearrival | `rootReturnsWhileWaiting_navigatesOnce` |
| Gesture while unresolved, then root rearrival | `gestureWhileWaiting_cancelsNavigation` |
| Accessibility scroll while unresolved, then root rearrival | `accessibilityScrollWhileWaiting_cancelsNavigation` |
| Reader input interrupts a scroll mutation still waiting to execute | `readerInterruptsInProgressNavigation_doesNotRetry` |
| Destination leaves resumed state and returns while still composed | `departureAndReturn_doesNotReviveRequest` |
| Unmount/remount or saved-state recreation | `remount_doesNotReviveRequest` |
| Conversation switches away and back, reusing root id | `conversationSwitch_doesNotReviveRequest` |
| Another marker tap replaces an unresolved request | `freshTap_replacesUnresolvedRequest` |
| A new tap after cancellation | `freshTapAfterReaderCancellation_navigates` |

Existing `BackgroundAgentBlocksScreenTest` covers navigation before/after task completion and collapsed/expanded owned runs.

## Error handling

Cancellation is expected control flow. Static structured event codes report request, replacement, reader input, departure and completion without logging agent ids or daemon text. No I/O or UI error surface is added.

## Testing strategy

Write shared screen regressions first and observe their cancellation failure on current production behavior. They invoke the rendered marker's actual click action and then remove its loaded root in the same UI turn, before the navigation effect can settle. Capture the real `LazyListState` through the existing composition observer. Assert waiting arrival reaches the root, cancelled arrival retains stationary reader position, and a successful request cannot replay. Exercise actual pointer and semantics scrolling, lifecycle departure, remount and conversation replacement.

The device file only wraps shared probes so the routine UI gate selects them, as required by the ticket; it contains no separate device-only implementation. Run the shared class and existing marker/expansion tests on JVM, the wrapper class on Android with named-method XML counts, and the relevant `background-agent` scripted scenario. Preserve the rung-3 `InteractiveStreamE2ETest#interactiveTurn_backgroundAgent_followsBottomUntilFinished` and rung-4 `DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` unchanged. Fresh full live and scripted results, including these methods' counts, are pending dispatcher-owned gates.

## Open Questions

None.

## Revisions

2026-10-09: The production-screen interruption probe holds the real list at `UserInput` priority while the marker effect attempts its mutation. Mutator rejection cancels the inner scroll while the effect remains active; consume that request explicitly, while retaining intent when a rows-change cancels the effect itself. Observe nested input at the message-region parent so cancellation also applies if root removal leaves an empty scrollable. The focused scripted selector is `background-agent`, which directly runs the preserved rung-4 method.

2026-10-09: Strengthened `remount_doesNotReviveRequest` with `StateRestorationTester` recreation after a second pending tap, alongside its ordinary departure/re-entry check. The pending request must remain absent even when saveable list and run state are restored.
