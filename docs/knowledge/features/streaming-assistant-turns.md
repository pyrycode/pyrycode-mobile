# Streaming assistant turns — supplied revisions in the thread

## Content path

The daemon supplies assistant item identity, revision, text, active/status, session,
parent and order. `AuthoritativeThreadRoute`/queries apply complete updates to the
host-owned store; the destination projects those facts through its negotiated route.
The existing bubble retains markdown reveal, growing-content behavior and settled
presentation without a visual redesign.

`ThreadFold`, raw `assistant_delta` accumulation, raw turn-end finalization,
segment joining and inferred ordering were removed in #2367. The ViewModel no longer
merges a raw live-event stream with legacy snapshots. Missing or conflicting bases
require authoritative repair; timestamps, sequence fragments and queue/roster
observations cannot reconstruct missing daemon facts.

## Presentation, read proof and following

Complete authoritative presentation retains worker scheduling and display-frame
pacing. Pending/skipped values cannot grant sight. Current owner/generation/revision,
shown metadata, viewport and reveal evidence remain required for read qualification.
Composer, prompts and configuration stay independent of content pacing. Scrolling,
follow-newest, tool grouping and Agent pinning retain their existing behavior.
See [ViewModel state](thread-screen-how-it-works-state.md) and
[rendering](thread-screen-how-it-works-list-and-status-row.md).

## Coverage

The independently counted stream methods retain growing-reply and queued-follow-up
continuity. They use supplied facts and actual device/message identity rather than
raw reduction for fixtures. Both execute through managed `scripted stream` and
`scripted-all` with zero real Claude turns. See
[counted acceptance](../../e2e-interactive-stream.md#authoritative-runtime-removal-2367).
Historical raw-stream algorithm evidence remains in past specs and the frozen archive.
