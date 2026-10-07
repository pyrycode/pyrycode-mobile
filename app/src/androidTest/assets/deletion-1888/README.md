# Deletion connection-gap evidence (#1888)

The text files retain content-free excerpts from fresh raw device XML/logcat. They intentionally exclude pairing material, message text and runtime discussion names. Original raw artifacts remain under `build/dispatcher-tests/` in the builder worktree.

- `rename-gap.txt`: original drive at `f4ff81a73`, gap before Save, 1 executed / 1 failed / 0 skipped. The list arrives but the unique-name presence observation fails.
- `delete-gap.txt`: original drive at `80d5e1b5a`, gap before confirmation, 1 executed / 1 failed / 0 skipped. Confirmation closes, conversation remains and the thread does not return to the list.

Both source commits contain the exact temporary replay: close only the isolated harness host's supervisor, await a null pump-gated repository, arrange reconnect after one second, and submit the UI action once. No action is retried. `legacy_connected=true` demonstrates that the old entry-only connection label is insufficient. The injected reconnect delay creates the negative condition; it is absent from the shipped scenario.

Historical dispatcher stacks were checked against their **tested merged source**, not their feature head:

- #1854: `f77f772c8d56251c3e5da12c1c125d887d2ed872` (parents `3e059504f9` and `970d7c422f`): first uniquely renamed list-presence wait. The issue's Channel info attribution was based on the unmerged branch head. Retained stderr: `2026-10-07T08-34-36-084Z_real-claude-gate_#1854.stderr.log` in the agents repository's logs directory.
- #1878: `9b55c94b107cc263443126396f842a9e72da5b1f`: post-confirmation list arrival. Retained stderr: `2026-10-07T06-12-15-017Z_real-claude-gate_#1878.stderr.log` in the same directory.

Retained daemon evidence (times UTC+03; identities are first 12 characters of SHA256, never raw values):

| Case | Events for the target discussion and its connection |
| --- | --- |
| #1854 | `pyry-e2e.NTBfoP/daemon.log`: 11:36:20.412 create, discussion `6e5129aa3b99`, connection `1348f835a00d`; 11:36:20.502 history; 11:36:20.515 peer-close; 11:36:21.553 new handshake `c590a2df4629`. No rename of that discussion. |
| #1878 | `pyry-e2e.6WLkmJ/daemon.log`: 09:13:54.802 create, discussion `a5dc4cefbc23`, connection `9d5e927ddfc3`; 09:13:55.514 rename; 09:13:55.798 history; 09:13:55.960 peer-close; 09:13:57.048 new handshake `d670fd2f8b63`; 09:13:57.093 history. No delete of that discussion. |

Those daemon directories live beneath the runner's temporary directory. Historical UI XML was removed with the gate worktrees, so the fresh negative controls establish action delivery and reproduce both missing-mutation stages.

The repaired positive control at `e2525a93376c34ab69afce0395057dfd30b856ae` forces **both** gaps in one drive using the same supervisor replay but awaits readiness before each submit and waits for the renamed header before Back. `guarded-gaps.xml` records 1 executed / 1 passed / 0 failed / 0 skipped, and `guarded-gaps.txt` retains the content-free logcat excerpt. Raw artifacts: `build/dispatcher-tests/live-yln6f7vx`. The final scenario retains the guards and completion observation but removes all injected gaps and diagnostic machinery.
