# Two-host Archive rename regression evidence (#1992)

## Historical observation

Mobile `730064e531`, merged with main `5d339ab241`: the initial host-A rename timed out at the final new-name wait in `renameOpenThread`, before archive assertions. Retained original gate XML reports **65 executed, 3 failed, 0 errors, 0 skipped**. The same-tree rerun reports **3 executed, 2 failed, 0 errors, 0 skipped**, with this method passed. Other failures are outside this ticket.

Runner logs: `/Users/juhanailmoniemi/WorkSpace/Projects/pyrycode-mobile-agents/logs/2026-10-08T21-05-46-206Z_real-claude-gate_#1968.log` and `.stderr.log`, with corresponding `real-claude-gate-rerun` files. Daemon evidence: `/private/var/folders/k0/gc07w9ws319b07n0plnw6y8r0000gn/T/pyry-e2e.6txisD/daemon.log`.

Content-free event correlation for A's created conversation `e1d67fd8-5c5a-42f7-a1d6-d2ac698dc071` (2026-10-09, +03:00):

- 00:07:08.098: `create_conversation.created`.
- 00:07:08.660: `v2.peer_close.teardown`.
- 00:07:09.616: `v2.handshake.accept`.
- 00:07:38.794: `delete_conversation.deleted` during cleanup.
- No `rename_conversation.renamed` exists for this conversation.

The original phone logcat is unavailable. These events support a connection-gap hypothesis but do not establish the instant of the original Save or exclude another cause. The post-run destroyed-Activity focus record does not establish focus during the action.

## Fresh deterministic reproductions

Base revision `1abc1f751` (plan committed atop `ba5724b08019030e677e372570e281ed3a742d84`), with uncommitted test-only source snapshots retained here and identified by `SHA256SUMS`. These are controlled reproductions, not claims that the historical phone state was observed.

- `delayed-dialog-red.xml`: **1 executed, 1 failed, 0 errors, 0 skipped**. The original discussion-arm drive selects a focused underlying composer before the delayed Rename dialog exists, then fails to find Save. Exact extracted driver and fixture are retained as `delayed-dialog-red-*.kt.txt`. Command: `./gradlew testDebugUnitTest --tests de.pyryco.mobile.e2e.DiscussionRenameTest --console=plain`, exit 1.
- `connection-gap-red.xml`: **1 executed, 1 failed, 0 errors, 0 skipped**. The dialog-targeted driver without its pre-Save owner wait submits once through the real `StableConversationRepository` while A's delegate is absent. Another host is ready. The unchanged title observation times out with original Compose cause and `submitted=1, rejected=1, owner ready=false, other ready=true`. Exact driver and fixture are retained as `connection-gap-red-*.kt.txt`. Command: `./gradlew testDebugUnitTest --tests de.pyryco.mobile.e2e.DiscussionRenameTest.owningHostGapDoesNotLoseRenameWhenAnotherHostIsReady --console=plain`, exit 1.
- `regressions-green.xml`: **5 executed/passed, 0 failed, 0 errors, 0 skipped**, with the repaired driver. Covers delayed appearance, owning-host reconnect, matching field without confirmed completion, reopen, and failure of diagnostic collection.
- `existing-dialog-green.xml`: existing `RenameDialogTest`, **14 executed/passed, 0 failed, 0 errors, 0 skipped**. The two green classes ran together using `testDebugUnitTest --tests de.pyryco.mobile.e2e.DiscussionRenameTest --tests de.pyryco.mobile.ui.conversations.components.RenameDialogTest --console=plain`, exit 0. Green tested base is `1abc1f751` plus the implementation committed with this record.

The diagnosed reproducible defect is test synchronization: list/create completion and another host's Connected state do not guarantee A is live at Save. The repair waits for A's current authenticated repository immediately before exactly one Save, targets the labelled modal field, and requires dialog dismissal followed by the confirmed title. No retry, ignore, removed assertion, or deadline increase is used. A later mid-flight disconnect still fails with its original cause and fixed-stage diagnostics.

## Live acceptance

Unchanged fresh baseline: mobile `ba5724b08019030e677e372570e281ed3a742d84`, daemon `55f1f1839c72ebd140679ddcb3c1db3d2d30c0d3`, Claude 2.1.280, `pixel2Api33Atd`; **1 executed/passed, 0 failed, 0 errors, 0 skipped**, exit 0. Artifacts: `build/dispatcher-tests/live-orc1emdh`, including counted XML and method logcat. This is not acceptance of the repair.

Pending dispatcher after verification: fresh full live gate, explicit pass for `InteractiveStreamE2ETest#interactiveTurn_twoHostsArchive_staysPerHost`, tested mobile/daemon revisions, retained artifact location, and executed/failed/error/skipped counts. The original A archive/restore UI flow, both B ID-set comparisons, guaranteed partial-setup cleanup and curated-suite membership remain unchanged. Documentation stage records these results in `docs/e2e-interactive-stream.md` under “Live mode (rung 3: live relay)” and “Verification status”.
