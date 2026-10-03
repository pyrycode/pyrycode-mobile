# #1519 — Usage-limit pill uses desktop's client-owned copy

## Files read

- `ui/conversations/components/UsageLimitIndicator.kt` — `usageLimitLabel` (the composable being rewritten),
  `usageLimitIsWarning` (variant, unchanged), `usageLimitStatusLabel` and `usageLimitSpentPercent` (lose their
  only caller, removed), `formatUsageLimitReset` (range guard kept, output shape changes).
- `ui/conversations/thread/ThreadTopOverlay.kt` — `ThreadTopOverlay` calls `usageLimitLabel(usage, agent)`;
  `agent` has no other use there.
- `ui/conversations/thread/ThreadScreen.kt` — the single `ThreadTopOverlay` call site passes `agent = state.agent`.
- `ui/conversations/components/NoticePill.kt` — `NoticePillPreviewContent` carries the old copy. `NoticePill`
  itself already hugs its text at the end and wraps (`maxLines = Int.MAX_VALUE`), so no layout change.
- `data/repository/ConversationRepository.kt` — the `UsageLimitReading` KDoc: SECURITY paragraph, `status` and
  `limitType` params.
- `res/values/strings.xml` — `thread_usage_limit_*`.
- Tests: `UsageLimitIndicatorFormatTest` (unit), `ThreadTopOverlayTest` and `ScriptedUsageLimitTest`
  (sharedTest), `ThreadDesignCaptureTest` (`threadStatusFramesAt412By892`, `compactAt320By700`; the reading is
  already `allowed_warning` / `seven_day` / `0`, so no code change there).
- pyrycode-desktop `src/renderer/src/screens/conversation/usageLimitNotice.ts` — the rule ported:
  `EXHAUSTED_STATUS`, `WINDOW_COPY`, `formatResetInstant`.
- pyrycode `internal/streamsup/parser.go` `maxRateLimitField` (256 bytes) — the daemon's field cap, for the
  security review.
- `docs/knowledge/features/development-verification-compose-evidence.md` and
  `app/src/androidTest/assets/design-1220/README.md` — recapture on `pixel8Api35` with
  `requireRealSystemBars=true`, compare with `scripts/design-compare.py`, replace only the recaptured states'
  files and note the recapture in `thread/index.md`.

No in-flight branch touches these files (checked against `origin/feature/*` at `5cc9d820`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-3139

The Top overlay's Default pill (`primaryContainer` / `onPrimaryContainer`, `bodySmall`, 6dp radius, 8/4 padding,
8dp gap before the X) reads "Nearly at usage limit - 7-day window" on one line, 238×24, right-aligned above the
Error "Pairing error - Re-pair" pill with the 12dp gap. Only the copy changes; the pill component is untouched.
`568:3139` is the reference for the no-reset case.

## Context

The #1432 audit found the usage pill reading "Claude reports usage-limit status: allowed_warning · 94% spent",
two lines at 412dp and three at 320dp/150 %. On 2026-10-02 Juhana decided to port desktop's rule (#1321, #1604):
the pill's text is client-owned copy picked by exact-equality lookup and claude's strings never appear in it.
This supersedes, for this pill, the "no behaviour branches on `limit_type`" rule and #804's "the label never
branches on `status`". The documentation stage should record that in `usage-limit-state.md`; no new decision
record is needed beyond that, since the decision is recorded on the ticket and mirrors desktop.

## Change

`UsageLimitIndicator.kt`:

- `usageLimitText(reading, now: Instant, timeZone: TimeZone, resources: Resources): String` — new, non-composable,
  the whole copy rule:
  - lead: `thread_usage_limit_reached` when `reading.status == "rejected"`, else `thread_usage_limit_nearly`;
  - window: `when (reading.limitType)` `"five_hour"` → `thread_usage_limit_five_hour`, `"seven_day"` →
    `thread_usage_limit_seven_day`, anything else → nothing;
  - reset: from `formatUsageLimitReset`; `thread_usage_limit_resets` (", resets %1$s") for a same-day reset,
    `thread_usage_limit_resets_on` (", resets %1$s at %2$s") otherwise; nothing when it declines.
  Kotlin `String ==` is exact: no trim, case fold, prefix or substring test.
- `usageLimitLabel(reading): String` — the composable keeps its name, drops `agent`, remembers `now` per reading
  as today, and delegates to `usageLimitText` with `TimeZone.currentSystemDefault()` and `LocalResources.current`.
- `formatUsageLimitReset(resetsAt, now, timeZone): UsageLimitReset?` — the same range guard (`0`, past, beyond
  31 days decline), now returning `internal data class UsageLimitReset(val date: String?, val time: String)`:
  `time` is `HH:MM`, `date` is `DD.MM.YYYY` or `null` on the same local date. Zero-padded from the
  `LocalDateTime` fields, not a locale formatter (desktop's `formatMessageTime` discipline). The `Locale`
  parameter goes.
- Removed: `usageLimitStatusLabel`, `usageLimitSpentPercent`, `MAX_STATUS_CHARS`, `STATUS_FIELD`, `ELLIPSIS`.
  `usageLimitIsWarning` and `WARNING_STATUS` stay as they are. The file KDoc is rewritten to state the new rule.

`ThreadTopOverlay.kt` drops its `agent` parameter and the KDoc's "(#1115)" sentence; `ThreadScreen.kt` drops the
one `agent = state.agent` argument. Nothing else in either file moves.

`strings.xml`: `thread_usage_limit_label`, `_label_no_status` and `_spent` are replaced by `_reached`, `_nearly`,
`_five_hour`, `_seven_day`, `_resets_on`; `_resets` keeps its name with the new comma copy. The comment above
them states the client-owned rule.

`NoticePill.kt`: the preview's usage pill reads "Nearly at usage limit - 7-day window".

`ConversationRepository.kt`: the `UsageLimitReading` KDoc's SECURITY paragraph and `status` / `limitType` params
say the pill's copy branches on exact `status == "rejected"` and on the two known `limit_type` values, that both
are lookup keys only and never rendered, and that this supersedes "no behaviour branches on any field" for the
pill's copy. No code in that file changes.

`truncatedFields` and `utilization` are no longer read by the label. A cut status or limit type cannot equal a
lookup key by accident (the daemon's cap is 256 bytes), so it simply misses and takes the fallback arm.

## Testing strategy

- `app/src/sharedTest/.../components/UsageLimitTextTest.kt` (new, Robolectric for real string resources), on
  `usageLimitText` with a fixed `now` in UTC: the exact text for `rejected` / `allowed_warning` / unknown status
  × `five_hour` / `seven_day` / unknown / empty limit type, and for a reset later today, on another day and `0`
  (AC 1). A hostile-values test: statuses `<b>x</b>`, `rejected `, `Rejected`, `REJECTED`, `rejected_new`, and
  limit types `<b>x</b>`, `seven_day `, `SEVEN_DAY`, `five_hour\n`, `constructor`; asserts the text never contains
  the input and that each near miss takes the fallback lead or omits the window (AC 2).
- `UsageLimitIndicatorFormatTest` (unit): drops the status and percent tests; keeps every range-guard test;
  the two format tests assert `UsageLimitReset(null, "15:30")` and `UsageLimitReset("29.09.2026", "08:30")`, plus
  one past local midnight in a non-UTC zone to pin the local-date comparison.
- `ThreadTopOverlayTest`: `label(...)` becomes the exact new copy; the agent test becomes "reads the same for a
  Codex conversation"; the variant and dismissal tests keep their assertions (AC 3).
- `ScriptedUsageLimitTest`: the expected label is built from the new resources; the spent figure goes from the
  expectations (AC 3).
- `ThreadDesignCaptureTest` on `pixel8Api35` (device-only: real pixels): `threadStatusFramesAt412By892` and
  `compactAt320By700`, then `scripts/design-compare.py` for `task-count-pill` against `figma-568-3139.png` and
  `compact-notices` against `figma-646-4707.png`; only those states' files and their `index.md` entries are
  updated (AC 4). If the device is unavailable, the PR says so and names AC 4 as unverified.
- No rung-3 scenario: the reading is account state claude emits on its own and cannot be produced on demand, and
  this ticket changes copy only on an existing surface whose scripted rung-2 coverage is updated above.

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/usage-limit-state.md`, in the section that owns
  rendering or security — the pill's copy branches on exact `status == "rejected"` and on the two known
  `limit_type` values, superseding the "no behaviour branches on `limit_type`" rule for this pill, and neither
  string is ever rendered. The matching `UsageLimitReading` KDoc lands with this change.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. `status` and `limitType` are claude-authored and enter UI code only through
  `UsageLimitReading`. The one function that reads them for copy is `usageLimitText`, which uses them solely as
  `==` / `when` keys selecting string-resource ids; no branch appends either value, so markup, bidi controls or
  newlines in them cannot reach the `Text`. The hostile-values test pins that. `usageLimitIsWarning` remains the
  only other reader and picks the variant only.
- [Trust boundaries] No findings on near misses. A family test (prefix, trim, case fold) would pull
  `rejected_something_new` into "Usage limit reached", the overclaim the two-lead design avoids; exact equality
  is pinned by the near-miss cases. A daemon cut cannot produce an exact key: `maxRateLimitField` is 256 bytes
  and both keys are under 10.
- [Trust boundaries] SHOULD FIX. `resetsAt` is claude's unvalidated number. The range guard in
  `formatUsageLimitReset` must run before any `Instant` or `LocalDateTime` is built, as today, and the existing
  `0`, negative, past, year-40000 and `Long.MAX_VALUE` tests must keep passing against the new return type.
- [Tokens] No findings: nothing on this path touches tokens or credentials.
- [Files and storage] No findings: nothing is persisted. The dismissal key `UsageLimitDismissals.Key` is unchanged
  and in memory; neither string becomes a file name, cache key or preference key.
- [Android attack surface] No findings: no component, intent, deep link or WebView is involved; the text is drawn
  by a Compose `Text`.
- [Cryptography] No findings: none used.
- [Network and I/O] No findings: no wire or codec change; `MobileWireCodec` is untouched.
- [Errors and logs] No findings. Nothing is logged on this path today and the change adds no log call; the
  status/limit-type pair discloses the account's quota posture and must stay out of logs.
- [Concurrency] No findings: a pure function called during composition; `now` is remembered per reading as before.
- [Threat model] Hostile daemon frame: handled — any `status`/`limit_type` renders one of a fixed set of client
  strings. Malicious relay: cannot alter frames inside Noise; a dropped or delayed frame only changes which
  reading shows. Token theft and UI-side leakage: out of scope for a copy change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03
