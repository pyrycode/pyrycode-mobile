# #2544 — Extract retained current-connection phone proof

Source #2494 / PR #2531 at 78e25757935229e975a7cab0c23b2504f237c19c; stacked on #2543.

## Plan before extraction
1. Extract phone proof helper, current-owner native consumers and passive diagnostics with their regressions.
2. Preserve same-connection reset fences, withheld-current-final regression and malformed diagnostic forwarding control.
3. Keep the producer slice earlier; leave cleanup and full integration with #2494 and peer repair with #2542.
4. Prove original source tree composes exactly; standalone compilation/tests and normal independent acceptance remain pending.

## Evidence boundary
Reused full-candidate evidence is not a fresh standalone run. Keep all original failed/native/history/identity limits. No test assertion, deadline, security invariant or final #2494 acceptance is relaxed.
