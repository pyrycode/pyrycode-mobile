# #1998: collision-host thread mutation menu readiness

## Files read
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, `openRow`, `renameOpenThread`; seven rename callers share the menu drive.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: constructor mutation snapshot, `hostAvailable`, `state`; the snapshot survives connection changes.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `mutationsSupported` denies while its live delegate is absent and re-reads each call.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `ThreadDestinationFactory.repository` and `thread` bind the stable facade and availability to the captured owning host.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt`: mutation support gates Rename/Edit but leaves Channel info and Background tasks present.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt`: existing menu render and click coverage.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: existing mutation capability assertions and `makeVm` test dispatcher injection.
- `docs/knowledge/features/stable-conversation-repository.md`: connection-scoped repositories disappear during churn, even though a destination's facade survives.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: Application/Koin survives methods; failure-listener focus after teardown cannot attribute input focus at the tap.
- `docs/e2e-interactive-stream.md`: live gate ownership and retained scenario contract.

## Context
The historical run tested mobile `2d48240a001595e52aad792a9d9ed6551a2f4821`, merged main `ba5724b080`, against daemon `a39c72739eb2e811708a67b08906614a4316b834`. Its retained stderr establishes a 30-second Rename/Edit menu-arrival timeout, before editing or writing a name. The full run executed 65 with one failure; its focused same-tree rerun executed one and passed. The original artifacts are gone, and post-cleanup launcher focus is not tap-time focus evidence.

Code inspection identifies a controllable local defect: constructing a thread while its owning stable facade has no live repository freezes mutation capability at false. A later connected projection cannot show Edit/Rename. A regression will distinguish this proven condition from attribution of the historical occurrence, which remains an inference without the missing tap-time/menu evidence. No daemon or wire change and no decision record are needed.

Overlaps are additive/local: #1968 changes thread content/state pacing, while this ticket changes capability projection; #1682, #1689, #1690, #1691, #1693, #1695, #1725, #1766, #1879, #1888, #1900, #1973 and #1992 touch other live scenarios. #1992's discussion editor repair is downstream of menu arrival and is independent.

## Design
Remove the construction-only capability cache. Preserve the initial facade reading, then project mutation support from the existing owning-host availability seam in `state`: unavailable is false, available reads the current repository capability. No constructor, repository interface, exported type or navigation contract changes. A real non-supporting repository remains denied.

Add a shared Compose regression mounting a real `ThreadViewModel`, `StableConversationRepository` and `ThreadScreen`. Hold its owning repository absent at construction, attach a supporting seeded repository only after the composer has rendered, tap More actions once, and require Edit and its event. A second host with the same conversation ID provides a negative control: connecting it cannot enable the disconnected owner's menu. Add unit transition coverage beside the existing capability tests.

Add content-free diagnostics scoped to the collision scenario's open/rename step, recording owning repository presence/capability, menu-common-row visibility and active Activity focus/lifecycle while the failure is still inside the test. Diagnostic collection failures must not mask the original exception. The shared `renameOpenThread` drive, assertions, deadlines and seven callers stay intact.

## State and concurrency model
Reuse `hostAvailable`, derived from the destination-bound `repositoryAvailable` flow, collected eagerly in `viewModelScope`; the existing state projection remains WhileSubscribed. No new job, dispatcher, mutable identity or scope is introduced. Background socket closure publishes unavailable; reconnection publishes available and re-reads the facade. Screen disposal uses the existing ViewModel cleanup.

## State transitions and identity reuse
| Event | Regression |
| --- | --- |
| Thread constructed while owner absent, then connected | shared `owningHostArrivalEnablesEditAfterComposerWasAlreadyDrawn`; unit `mutationCapabilityFollowsOwningRepositoryAcrossReconnect` |
| Disconnect and reconnect the same owner/conversation | unit `mutationCapabilityFollowsOwningRepositoryAcrossReconnect` |
| Reconnect to a non-supporting delegate | unit `mutationCapabilityFollowsOwningRepositoryAcrossReconnect` |
| Other host connects with colliding conversation ID | shared `otherHostArrivalDoesNotEnableOwnersEdit` |
| Rebuild app graph over saved pairings; each host link cycles | existing live `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, unchanged checks and B cleanup |
| Initial/steady supporting and non-supporting fake | existing capability unit tests |

## Error handling
No new product failure outcome. Mutation rows remain hidden while the owner is unavailable. Scenario diagnostics retain the original failure object, adding only bounded, content-free context; diagnostic failures are also retained without replacing it. No retries, ignores or timeout changes.

## Testing strategy
Write and run the controlled failing shared/unit regressions before production repair; retain counted red/green XML under `app/src/androidTest/assets/collision-menu-1998/` with revision provenance. Run the focused new shared class, focused capability unit methods, existing `ThreadScreenOverflowTest`, `ThreadOverflowMenuTest`, `ThreadScreenConnectionGateTest`, and `StableConversationRepositoryTest`. Run the new shared class on the managed device since this ticket concerns real Android menu input, plus compile Android tests, lint, assemble and forced Spotless.

The existing live method keeps two real pairings, host rows/threads, A-only rename, both link cycles, graph rebuild and guaranteed B removal, and stays curated. The ticket assigns the fresh full live gate to the dispatcher; no focused rerun is claimed as that proof. Required later evidence: named passing testcase, mobile/daemon revisions, artifact path and executed/failed/error/skipped counts. No new device-only test or scripted stream fixture is needed: the menu capability condition can be controlled with the production ViewModel and facade without a daemon.

## Documentation handoff
Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “Live mode (rung 3, live relay)” and “Verification status”, with the established cause, historical attribution limits and revision-linked counted regression/live evidence. The dispatcher supplies full live evidence after verification.

## Open Questions
The controlled regression must confirm that the menu itself opens but the stale capability hides mutation rows. If it does not, revise the diagnosis before repair. Historical attribution cannot be proven by the retained stack alone.
