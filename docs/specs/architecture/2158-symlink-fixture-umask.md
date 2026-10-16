## Files read

- `scripts/test_e2e_fault_evidence.py`: `FaultEvidenceTest.test_destination_symlinks_are_rejected_without_writing_or_chmod` creates the outside-directory fixture and asserts refusal without writes or permission changes.
- `docs/knowledge/features/development-verification-gates.md`: use focused checks and retain nonzero execution counts.

## Change

Explicitly chmod the outside directory to `0755` immediately after creation, before installing the symlink. `mkdir(mode=0o755)` alone is filtered by the inherited umask. Preserve both destination cases, their `RetentionError` expectations, the empty-directory assertion and the final `0755` assertion. No production behavior, exported types, call sites or state changes.

## Testing strategy

Reproduce the existing method's permission assertion failure under `umask 077` before editing. Run the repaired method under `077` and `022`, then its entire test module and complete `scripts/test_*.py` unittest discovery under `077`, reporting executed counts. Run required lint, assemble, formatting and final pre-verification gates. No device or real-Claude scenario applies to this test-fixture-only change.
