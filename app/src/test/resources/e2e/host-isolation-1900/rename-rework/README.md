# Rename-test gate evidence before and after #2014

The outstanding #1900 verifier finding reproduced on both the PR and current main in separate worktrees. The exact method ran once on each tree, failed once at `discussion rename: await dialog field`, and had zero errors/skips; both Gradle commands exited 1. `evidence.json` records each tested revision, command, counts and original XML SHA-256. The raw XML contains only the static timeout stack, with empty stdout/stderr.

The test, discussion-rename helper, RenameDialog and Gradle configuration had identical Git blobs on both trees. This confirmed an inherited failure, not its cause. The earlier verifier baseline passed; these failures do not prove the fixture always failed. These retained reports are historical baseline evidence, not current acceptance.

#2014 subsequently merged through PR #2025 at `f3186e5a5ed013540cf74ff410e9e36dcd75b775`. Fresh #1900 verification at mobile `6ccb18ea3c4160a95e21221a01c3f86787ad30c1` executed/passed the exact method **1/1** and the full class **5/5**, with **0 failures/errors/skips**, both exit **0**. The unchanged commands were `./gradlew testDebugUnitTest --tests '<selector>' --rerun --console=plain`, selecting `de.pyryco.mobile.e2e.DiscussionRenameTest.delayedDialogDoesNotReplaceComposer` and `de.pyryco.mobile.e2e.DiscussionRenameTest` respectively. `--rerun` forces fresh test execution without recompiling unchanged inputs.

[post-2014.json](post-2014.json) retains commands, exit results, tested revision, source-state notes, named-method results and raw XML hashes. [post-2014-exact.xml](post-2014-exact.xml) and [post-2014-class.xml](post-2014-class.xml) are verbatim Gradle reports with empty stdout/stderr. The class report includes `delayedDialogDoesNotReplaceComposer`. #1900 changes no rename source or deadlines; the merged blocker owns its fixture repair and [controlled proof](../../discussion-rename-2014/README.md).

The retained host-isolation controlled red/green and focused live results in the parent directory remain separate evidence. Full dispatcher live acceptance remains pending.
