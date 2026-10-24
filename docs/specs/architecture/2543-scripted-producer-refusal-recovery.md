# #2543 — Extract the retained producer recovery

Source: #2494 / PR #2531 at 78e25757935229e975a7cab0c23b2504f237c19c. Base e2bd0712c.

## Scope
Only retryable producer-query refusal recovery, its causal tests and bounded native diagnostic. Keep accepted-send ownership, final markers, partial-store rollback and original deadlines unchanged. No phone final-read or durable-gap-cleanup extraction in this slice.

## Plan before extraction
1. Copy exact retained ProducerThreadObservation/SecondClientPeer changes and their tests.
2. Keep the native producer diagnostic consumer with its helper.
3. Verify diff, source provenance and composition identity; partial-tree compile/tests and normal independent acceptance remain required.
4. Preserve every historical failed execution, source/binary limit and #2494's final 2/2/native/configured acceptance.

## Verification boundary
Existing full-candidate evidence is retained, not a fresh standalone-slice execution. No acceptance or historical cause is declared by extracting these bytes.
