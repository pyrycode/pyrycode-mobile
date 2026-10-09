# Inherited rename-test gate failure (#2014)

The outstanding #1900 verifier finding reproduced on both the PR and current main in separate worktrees. The exact method ran once on each tree, failed once at `discussion rename: await dialog field`, and had zero errors/skips; both Gradle commands exited 1. `evidence.json` records each tested revision, command, counts and original XML SHA-256. The raw XML contains only the static timeout stack, with empty stdout/stderr.

The test, discussion-rename helper, RenameDialog and Gradle configuration have identical Git blobs on both trees. This confirms an inherited failure, not its cause. The earlier verifier baseline passed; these fresh failures do not prove the fixture always fails. No passing rename result is claimed and no retry, ignore, deadline change or source repair ships in #1900. Open #2014 owns the diagnosis and fix and is linked as #1900's blocker. Resume focused counted verification after it lands.

The retained host-isolation controlled red/green and focused live results in the parent directory remain separate evidence. Full dispatcher live acceptance remains pending.
