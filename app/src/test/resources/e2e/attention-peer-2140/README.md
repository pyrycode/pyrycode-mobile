# Attention peer-opening investigation (#2140)

The independent fresh defect is **relay admission refusal before Noise**, not an attention/read assertion failure. In the standard same-process question, permission and attention selection, the permission predecessor's peer received HTTP 503 on three dials. Matching relay logs report `conn_cap_reached` on `/v1/client` at the same retry cadence. Existing backoff later obtained capacity. All three methods passed, including attention's complete original read/permission proof; attention settled on its first dial in this run.

The owning bounded-capacity repair is [pyrycode-relay #194](https://github.com/pyrycode/pyrycode-relay/issues/194), a native blocker of Mobile #2140. Relay v0.1.4 defaults to 20 shared daemon/client connections; the active machine has 256 MiB and no explicit cap override. Mobile changes only four static stage log calls. No retry, deadline, cleanup or assertion was changed. This is investigation evidence, not a completed repair.

## Counted evidence

| Run | Executed | Passed | Failed | Errors | Skipped | Attention |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Original full #1896 gate | 65 | 62 | 3 | 0 | 0 | Failed at peer opening |
| Original unchanged subset rerun | 3 | 3 | 0 | 0 | 0 | Passed |
| Builder isolated baseline | 1 | 1 | 0 | 0 | 0 | Passed |
| Builder immediate predecessor comparison | 2 | 2 | 0 | 0 | 0 | Passed |
| Builder diagnostic predecessor comparison | 3 | 3 | 0 | 0 | 0 | Passed |

`evidence.json` maps the reports to Mobile and daemon revisions and source-report hashes. The adjacent XML preserves suite/method outcomes while excluding raw logs, exception text, prompts and payloads. Focused unit checks (`RedialingLinkTest`, `PeerWaitTest`, `OffscreenPeerReadinessTest`) passed 22/22; lint, debug assembly and instrumentation compilation passed.

The original answer-daemon log records phone pairing and two chat creations, but no accepted second handshake during the failed opening interval. The original daemon already contains pyrycode #3214's capacity repair. Original phone logs are unavailable, so **historical attribution remains unknown**. Neither passing reruns nor the independently observed relay refusal identify the original cause conclusively.

## Private provenance and pending validation

Raw originals, fresh XML/phone logs, correlated daemon captures and the relay cap log are retained with owner-only permissions and verified SHA-256 manifests at the Mac agents volume's `logs/evidence/2140/`, in `historical/`, `baseline-isolated/`, `baseline-predecessor/` and `diagnostic-predecessors/`. The first isolated baseline daemon capture and historical per-run binary hashes are unavailable. Public machine metadata is allowlisted; credential homes and pairing files were not copied.

After the owning repair, rerun the exact attention method and predecessors with correlated private captures. The ticket still requires fresh full dispatcher live acceptance after verifier, with method and suite counts plus revision/hash provenance. Documentation updates to the phone-read and peer-read sections of `docs/e2e-interactive-stream.md` remain with the documentation stage.
