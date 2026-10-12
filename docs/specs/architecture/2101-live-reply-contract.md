# Reliability investigation: background-agent reply contract

This is a bounded test repair under shared reliability investigation #2101. It does
not claim acceptance of the whole investigation or change production behaviour.

## Evidence and change

The permission-answer deadlock is repaired separately in backend PR 3279. After
that repair, the background-agent scenario exposed test assumptions: a capitalised
reply failed its token check, an interleaved child tool split main text across rows,
and the child's late reply used a different turn identity from its launch.

Use the recorded reply turn and parent agent identity. Require nonblank, settled
assistant text with complete contiguous fragments. Follow the current repository
when the phone reconnects. The prompt leaves ordinary reply wording open while
retaining the commands that hold and release the task. Navigation accepts several
paragraphs and still proves visibility, collapse, tool ownership and list ordering.

Controlled reply and multi-paragraph screen regressions fail before their repairs
and pass afterward. The focused live scenario passed once with no retry against
backend 9c9cd5a25. Its first passing capture used the effort-test worktree plus this
repair. Validate the clean main-based branch separately before merging.

Private evidence lives in the Mobile agents evidence folder
`background-owned-reply-20261012`. It contains raw testcase XML, phone and daemon
logs, and hashes. Do not publish those raw logs.

## Validation and remaining work

Run the complete debug unit suite, lint, debug build, Android-test compilation,
formatting and repository checks. Execute the named live scenario on the final
main-based branch. Keep both Mobile dispatchers paused. The broader suite, other
wording-dependent scenarios, Stop investigation and effort repair remain separate
work within the reliability investigation.

## Attributed reply scenario follow-up

The integrated check passed all three effort scenarios, but the related attributed
reply scenario failed while locating a prescribed main-reply phrase. Its child
ownership and collapse checks had already passed. Private evidence is retained as
`mobile-effort-recovery-20261012` in the Mobile agents evidence directory.

Give the completed Markdown body a test tag derived from its existing message ID.
The tag carries no text and changes no layout, interaction or rendering. The live
test chooses both child and main replies through recorded turn and parent identity,
then locates their actual rendered bodies by ID. Requests leave wording open.
User, streaming and other-message bodies cannot satisfy the completed-reply tag.

Two screen regressions failed without the identity tag and pass with it. They cover
wording and Markdown changes, wrong IDs, user messages and unfinished replies.
Existing message rendering checks also pass. Run full debug verification and both
background-agent scenarios before handoff. No full-suite recovery is claimed.

## Finished-reply Copy follow-through

The first complete recovery baseline timed out before the Copy action. Private phone logs
show the captured connection closing and a replacement receiving the reply. Daemon history
contains the exact requested words and a successful turn end. The test still observed the
closed repository for its full deadline. This occurrence is not caused by wording variation.

Move the completion wait to the existing reconnect-aware reader. The fresh chat accepts a
nonblank completed main assistant reply, excluding user, child and streaming rows. The prompt
also stops prescribing wording. Locate the completed body through its existing identity tag
and derive the selected word from its actual rendered text layout. Markdown punctuation is
not treated as displayed text. Retain the real long press, Android Copy menu, unrelated
clipboard baseline and exact selected-word clipboard assertion. The controlled fixture keeps
its fixed reply, while both callers follow the current connection.

## Remaining wording audit

The agent and Copy repairs do not complete migration of the live suite. Shared ping
checks still prescribe a token across many scenarios. Offline catch-up, queue ordering and
the channel-instructions test also use fixed prose as reply identity.
Migrate each through its actual turn and message before relaxing the prompt. In particular,
a prior completed message must not satisfy a follow-up or reconnect check.

Keep exact copied words, sent payloads, file bytes, protocol fields and the mid-turn
marker where those values are the feature being exercised. Fixed Markdown layout
specimens belong in the scripted rendering suite. The shared verification guide now
states this rule explicitly rather than recommending prescribed live reply tokens.

## Post-Stop reply follow-through

The background-task Stop scenario now leaves the launch acknowledgment and later
reply wording open. Before sending the later message, it records completed turn IDs.
It then requires a new completed turn, that turn's settled main reply through the
current connection, and exactly one visible body with the recorded message ID.
The held task, real Stop action, completion observation and cleanup are unchanged.
The existing three reply-completeness and ownership tests pass, as does Android-test
compilation. The fresh combined live gate executed and passed this method.

## Combined acceptance evidence

Mobile d92a2f4e8d7950db249d34a59dfed3d394c9db98 with backend
9c9cd5a25c337666bdbed47c29202e695a172733 passed all 65 curated live tests and
all 22 scripted tests on 2026-10-12. Both runs had zero failures, errors, skips or
retries. The combined branch includes this PR, effort PR 2111, Send now PR 2134
and a private experimental native Copy tap. All four live methods named by this
PR ran and passed. The Copy tap remains under causal investigation; this is
integration evidence, not a claim that the original Copy flake is repaired.

Raw XML, phone logs and hashes are retained privately in the Mobile agents
evidence folders `mobile-combined-live-20261012` and
`mobile-combined-scripted-20261012`. Both Mobile dispatchers remain paused.

## Security review

**Verdict:** PASS

**Findings:**

- Trust boundaries: test-only reply selection reads decoded repository messages.
  Role, turn and parent identity are checked before acceptance. Text is not parsed
  as a command or used as authority. Missing fragments cannot satisfy the check.
- Tokens: no credential generation, storage or lookup changes. The existing
  managed launcher supplies the isolated test login. No token enters the new
  helper or its diagnostics.
- Files and storage: no production storage changes. Evidence stays in private
  operator storage. Reply text cannot select a file path.
- Android attack surface: no manifest, exported component, intent, provider or
  WebView changes. The added completed-body test tag contains the existing opaque
  message ID. It is inert identity data, never a command, URL or authority. Existing
  agent test tags already carry the corresponding grouping IDs. The body tag is
  applied only to the finished assistant renderer. User and streaming bodies do
  not receive it. No reply text or credential is added to the semantics.
- Cryptography: the existing encrypted phone connection remains unchanged. The
  test does not replace the handshake or bypass authentication.
- Network: existing launcher, relay, timeouts and cancellation remain in use.
  Following the active repository does not resend commands or create retry loops.
- Errors and logs: new diagnostics report roles, Boolean identity matches and
  streaming state. They exclude message text, commands, credentials and raw IDs.
  Raw capture retention stays private. The changes compile into test source sets.
- Concurrency: waits remain bounded and cancel with the test. The existing
  reconnect-aware read cancels the old repository subscription when replaced.
  The pure reply predicate has no coroutine or mutable shared state.
- Threat model: this repair adds no production boundary. Dropped fragments and
  wrong ownership fail the checks. A failed connection cannot pass through a
  default empty reply. Existing Noise, Keystore and renderer protections remain
  responsible for relay, storage and hostile-frame threats.

**Reviewer:** Codex, self-review using the builder security-review procedure.
**Date:** 2026-10-12
