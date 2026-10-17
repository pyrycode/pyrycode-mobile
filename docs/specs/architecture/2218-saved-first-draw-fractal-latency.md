# #2218 — saved-thread first-draw misses after managed-image repair

## Files read

- `SavedThreadFirstDrawDeviceTest`: `measureSavedThreads`, `repository` (measured `io`/`default` workers), `openAndMeasure`, `Probe`. The timer starts in `Probe` before cache, repository and ViewModel construction; `drawn` is the frame-commit callback.
- `FileConversationCache`: `readThread`, `readHistoryPosition`, `readDecodedThread` (the `thread_cache_restored` read/decode/validate stages and the lazy `thread_cache_history_restored` metadata/proofs stages), `matchesBytes`.
- `CachingConversationRepository`: `observeThreadSnapshot` (first empty snapshot publishes the cache list directly, #2153), `readHistoryPosition`.
- `ThreadViewModel`: `historySeed` (calls `readHistoryPosition` at construction, so it usually takes the cache mutex first and performs the row decode), `threadContent`, `paceThreadContent`.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md` and `conversation-cache-layout.md` (#1949 shared decode, #2026 decoder settings). Lesson carried: optional history work must not hold the first rows, and decode/proof contracts stay unchanged.
- Retained #2177 evidence bundle `0ad15d3e…` (`repaired-image-class-20261017/`): raw class XML, four phone logs, `fresh-openings.jsonl`, `owner-comparison.json`, `host-guest-observation.jsonl`, preparation `inputs.json` and `prepare-hardware.ini`.
- Host launcher on build-hp-1: `$ANDROID_HOME/emulator/emulator` is the #2051 HP renderer wrapper, which execs `emulator.hp-native` with `-gpu swangle`; `app/src/androidTest/assets/scanner-2051/hp-evidence.txt` records it as an HP-host rendering workaround.

## Context

#2177 / PR #2203 reached valid readiness for every opening on Linux/build-fractal-2 but failed both positive methods (4 executed / 2 passed / 2 failed). The fragmented first opens drew at 1314, 1032 and 1026 ms. This ticket localizes that cost and repairs it on whichever side owns it. No decision record is needed: the outcome is an investigation and a runner-side blocker, with no new Mobile design.

## Design

The investigation compares the failing run with two others: a same-host passing run on fractal-2 with the same runner, and a fresh focused class run on build-hp-1. Results go into checked, content-free summaries under `app/src/androidTest/assets/saved-first-draw-2218/`. No production or test source changes.

Localized costs, from the retained failing logs (cumulative restore/snapshot/content/frame milliseconds):

- Restore dominates. In each failing first open, the cold row decode in `readDecodedThread` took 624–789 ms of 710–935 ms. Reading the bytes took 5–14 ms and validation 33–64 ms. The same bytes decoded in 395 ms in the passing held-newest first open of the same process, so the decode varies about twofold on identical work.
- Content to committed frame took 210–329 ms in the failing first opens and 146 ms in the passing one. Even the 20-row ordinary fixture took 138–232 ms from content to frame, against roughly 40–60 ms in the #2202 build-hp-1 runs, so the frame path itself is slow on that device, independent of row count.
- The lazy history decode (1069–1465 ms of metadata plus 626–929 ms of proofs) starts when `historySeed` releases the cache mutex, and so overlaps the first draw. Each failing first open had two app garbage collections overlapping content-to-frame, and one or two overlapping restore. The passing first open had none in its frame window. Reopens with no history decode still took 144–258 ms from content to frame. This overlap is a measured contributor, but not demonstrated as the cause.

Environment comparison on build-fractal-2 with the same runner (agents `9aad4b5d`), where all eight measurement-source hashes match:

| Run | Generation / launcher | Counts | Fragmented first draws |
| --- | --- | --- | --- |
| Owner `fractal-prepared` (passing) | `07867931…` / `emulator`, `auto-no-window`, lavapipe | 4/4/0/0/0 | 653, 582, 816, 707 ms |
| #2177 failing | `151a9dbd…` / `emulator.hp-native`, `-gpu swangle`, swiftshader | 4/2/2/0/0 | 1026, 619, 1314, 1032 ms |

Only the launcher-identity input differs between those two generations. The failing run also recorded up to 16.17% guest steal, against 0–3.88% in the earlier fractal reference run. That pattern fits SwiftShader's host-side rasterization competing with the emulator's virtual CPUs on fractal-2, which would slow guest decode and frame commit together. The #2051 wrapper is an HP-host workaround for an emulator crash; fractal-2 had used the native launcher until this generation.

Causal reading: the measurements place the regression in the fractal-2 execution environment, namely the HP SwANGLE launcher now resolved there. Mobile's restore path is unchanged and passes on the same host with the native launcher. This is a two-run comparison, not a repeated A/B, so it localizes but does not prove the renderer. Confirming it needs launcher-controlled repetitions on fractal-2, which only the runner owner can schedule. Any Mobile change here would be chosen without demonstrated cause, which the ticket forbids. Deferring the history seed or rewriting decode remains a possible later Mobile ticket if the native A/B clears the launcher.

Routing: file the runner/deployment repair in `pyrycode/pyrycode-mobile-agents`: verify or restore fractal-2's native launcher and run a launcher-controlled A/B of this class there. Link it as a native blocker of #2218. #2177 consumes the result.

## State and concurrency model

Unchanged. No scope, flow, dispatcher or cancellation path changes.

## State transitions and identity reuse

None: no production or test logic changes.

## Error handling

Unchanged.

## Testing strategy

- One supported focused class on build-hp-1 (Linux, the ticket's operating system): `python3 scripts/android-test-gate.py ui --tests 'de.pyryco.mobile.ui.conversations.thread.SavedThreadFirstDrawDeviceTest'` at unchanged main `10666ec4` with agents `c8edc37e`. This is a host comparison with identical measurement source. It does not attribute or clear the fractal-2 miss.
- No unit test: no changed logic. Fixtures, the 1000 ms bound, the 1100 ms projection hold, the 3000 ms slow control, readiness windows, cold timed construction and the exact frame endpoint all stay as they are.
- Raw XML, four phone logs and a manifest go under `$AGENTS_REPO_PATH/logs/evidence/2218/`. Checked summaries go under `app/src/androidTest/assets/saved-first-draw-2218/`.
- Full configured UI and scripted-all gates remain dispatcher-owned and pending.

## Results

The build-hp-1 focused class used generation `151a9dbd…`, the same as the failing run, with the same wrapper and SwiftShader, and its source hashes match. It recorded 4/4/0/0/0, exit 0, XML timestamp 2026-10-17T09:30:11:

- Fragmented first draws were 555, 433, 407 and 420 ms, with cold decode at 249–291 ms and history metadata at 362–522 ms.
- Ordinary draws were 129–241 ms.
- The slow control rejected the bound at 3494 ms.
- All 17 readiness barriers passed, across 85 windows, with maximum acquire/read of 14/35 ms.

Identical source and generation therefore pass on build-hp-1 with at least 445 ms of headroom. The same cold decode takes 1.4 to 3.2 times longer on fractal-2, which fits the localization above.

## Open Questions

- Is the fractal-2 SwANGLE launcher intended? This is answered by the native blocker, not by Mobile. Resolved on #2222: yes, see Revisions.

## Revisions

### 2026-10-17: blocker #2222 resolved by operator decision and #2228

- **What changed.** The operator answered #2222 directly and closed it. The SwANGLE wrapper is intended on both build-hp-1 and build-fractal-2, so the mount stays and no native-launcher A/B runs. The operator attributes the remaining difference to Fractal's slower processor under software rendering. PR #2228, which the operator made outside the pipeline and which is merged into this branch, changes `SavedThreadFirstDrawDeviceTest`. It keeps #1949's 1000 ms bound on hardware renderers and applies 2000 ms when the guest `GL_RENDERER` string contains SwiftShader. The renderer is read once through a 1x1 EGL pbuffer before any timing. The optional-projection twin holds projection for the applied bound plus 100 ms, so 2100 ms under SwiftShader. The 3000 ms slow control exceeds both bounds.
- **What drove it.** The operator's decision on #2222 rescoped the goal: the class must pass under SwANGLE on both hosts, and the operator allowed either a faster draw or a timing budget justified for software rendering. That decision supersedes the ticket's "1000 ms bound, 1100 ms projection hold, no threshold relaxation" wording, but only under a SwiftShader renderer. Everything else in that criterion stands unchanged: readiness windows, cold timed construction, the exact frame endpoint, both fixtures, every row, marker and request assertion, and no warming, retries or skips.
- **New contract for #2218.** This ticket still changes no Mobile production or test source. The localization above remains its cause analysis, and the Mobile-side candidates it names stay unimplemented, because no measurement shows a Mobile defect. Fresh focused acceptance runs on Linux/build-hp-1 at the merged candidate, which includes #2228. That run is also the first device run of #2228's renderer detection, so it checks the logged `event=first_draw_bound` renderer and bound against the failure messages and phases. Its checked summary goes beside the earlier ones under `app/src/androidTest/assets/saved-first-draw-2218/`, and the raw evidence goes under `$AGENTS_REPO_PATH/logs/evidence/2218/`. build-fractal-2 is not run from this ticket, which is claimed to build-hp-1. The dispatcher's configured UI gate stays pending, and a Fractal result comes only from that gate, never from this run.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`. Add the localized costs above (decode share, content-to-frame on the ordinary fixture, history and garbage-collection overlap), the same-host launcher comparison, counted results for all three runs, and the attribution limits. Keep readiness evidence separate from elapsed-draw evidence, and keep closed #2202's historical disposition.
