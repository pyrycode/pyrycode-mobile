# #1009 — Update-required treatment on the host row

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` → `RelayLinkStatus.UpdateRequired(minClientVersion)` — the terminal state #1008 added; its KDoc says render as text only, never log.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` → `validMinClientVersion` / `MIN_CLIENT_VERSION_SHAPE` — the version is already `[0-9]{1,6}` ×3, at most 20 characters, before it reaches the UI.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `isDisconnected`, `ConnectionLegPair`, `LegDot`, `ConversationStatusDot`, `TreeRowControl`, `treeHostReconnectTestTag`, the preview matrix — every seam this ticket touches.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → `RelayLinkStatus.toLegVisual` — shared with the Settings status line; **not changed** (the outboard relay dot keeps it).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent` (`TreeHostReconnectTapped`, `TreeHostRePairTapped`), `treeSection`'s `onReconnectTapped` switch — #842's precedent for re-aiming the plug.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`'s exhaustive `ChannelListEvent` handler — where #842's re-pair navigation lives; the new event must be handled there.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → `uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` — the existing pattern for opening an app-authored URL.
- `app/src/sharedTest/.../components/ConversationTreeRowsTest.kt` and `app/src/sharedTest/.../list/ChannelListScreenTest.kt` → the #840/#842 host-row tests the new tests sit beside.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` — the tree control conventions (serverId-only events, bounded names in descriptions).

No in-flight feature branch touches these files (checked 2026-09-25).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=581-3520 (version known `581-3521`, version unknown `581-4085`)

The host row's server glyph and name turn red as for a disconnected host; a download icon (arrow into a tray) sits in the plug's slot inboard of the two leg dots; the inboard host dot is the idle ring (no fill, `primary` 1dp ring) while the outboard relay dot keeps its current mapping. Under the row, aligned with the host name, a `bodySmall` / `onSurfaceVariant` caption reads "Update Pyrycode to version 1.4.0 or newer to use this host." or, with no version, "Update Pyrycode to use this host." Pencil and plus stay drawn (the frame hides them only because they are hover-only on desktop).

## Context

Since #1008 a host that rejects the app as too old parks in `RelayLinkStatus.UpdateRequired`. The tree currently treats it as generic disconnected: the plug retries, which cannot succeed. This ticket gives that host the update treatment and points its control at the Play Store. No ADR warranted.

## Design

**`TreeHostRow`** (no signature change):

- `val update = connectionStatus.relay as? RelayLinkStatus.UpdateRequired`.
- The row becomes a `Column` carrying the caller's `modifier`: `FoldableTreeRow` (unchanged args) then, when `update != null`, a caption `Text`. Caption: `bodySmall`, `onSurfaceVariant`, start padding = `HostRowIndent + TreeGlyphSize + TreeGlyphGap` (aligns with the name), end `TreeRowEndPadding`, bottom 6dp (the frame's `pb-6`). Text = `stringResource(R.string.tree_host_update_required_version, v)` when `update.minClientVersion` is non-null, else `R.string.tree_host_update_required`. Because the caption lives in the host row's own lazy item, folding the host (which drops only the workspace items) leaves it visible.
- The inboard control slot: `update != null` → `TreeRowControl(icon = Icons.Filled.Download, contentDescription = stringResource(R.string.cd_tree_host_update, bounded), onClick = onReconnectTapped, testTag = treeHostUpdateTestTag(serverId))`; otherwise the existing `disconnected` plug branch unchanged. The update control never carries the reconnect tag, so "no plug" is directly assertable.
- `ConnectionLegPair(status, hostIdle = update != null)`: when `hostIdle`, the inboard dot is a new private `IdleLegDot` (size `TreeDotSize`, transparent fill, `TreeDotRingWidth` `primary` ring — the `ConversationStatusDot` idle drawing — with `contentDescription = stringResource(R.string.cd_tree_host_leg_idle)`, "Pyrycode: idle"); the outboard relay `LegDot` is untouched.
- `isDisconnected` keeps `UpdateRequired → true` (red accent unchanged).
- New `fun treeHostUpdateTestTag(serverId: String): String = "tree-host-update:${boundedTagId(serverId)}"`.

**`ChannelListScreen`**:

- New `ChannelListEvent.TreeHostUpdateTapped` — a `data object`: opening the store needs no host, so it carries nothing (in particular never the version).
- `treeSection`'s `onReconnectTapped` switch becomes a `when` on `host.connectionStatus.relay`: `PairingRejected → TreeHostRePairTapped(id)`, `is UpdateRequired → TreeHostUpdateTapped`, else `TreeHostReconnectTapped(id)`.
- `internal const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=de.pyryco.mobile"` — app-authored literal (not `BuildConfig.APPLICATION_ID`, whose debug suffix would name a listing that does not exist).

**`MainActivity`**: `val uriHandler = LocalUriHandler.current` in the channel-list destination; `ChannelListEvent.TreeHostUpdateTapped -> uriHandler.openUri(PLAY_STORE_URL)`. No `vm.reconnectHost` call on this path.

**Strings** (`strings.xml`): `tree_host_update_required_version` ("Update Pyrycode to version %1$s or newer to use this host."), `tree_host_update_required` ("Update Pyrycode to use this host."), `cd_tree_host_update` ("Update Pyrycode to use %1$s" — host name only, never the version), `cd_tree_host_leg_idle` ("Pyrycode: idle").

Update the preview matrix with one version-known and one version-unknown update-required host.

## State + concurrency model

None added. Stateless rendering of the existing `ConnectionStatus`; the store open is a synchronous platform call on the click.

## Error handling

`LocalUriHandler.openUri` throws only when no activity handles an `https` view intent; the existing `SessionBoundaryDelimiter` and `ThreadOverflowMenu` call it unguarded, so this follows the precedent (no observed failure to defend against).

## Testing strategy

Robolectric screen tests in `app/src/sharedTest`:

- `ConversationTreeRowsTest`:
  - update-required with version: caption text equals the formatted version string; the update control (by `treeHostUpdateTestTag`) is described by `cd_tree_host_update` with the host name; no reconnect-tagged node; a tap fires `onReconnectTapped` once and not the fold; no node's content description contains the version.
  - update-required with `null`: caption equals `tree_host_update_required`.
  - folded (`expanded = false`): caption still shown.
  - the idle dot: a node described "Pyrycode: idle" exists; for an `Offline` host it does not and the pyrycode leg description does.
  - the existing `hostRow_connectedConnectingOrIdle_drawsNoReconnectControl` extended to assert no update control either.
- `ChannelListScreenTest`: an update-required host beside a rejected-pairing and an offline host; tapping each control yields `[TreeHostUpdateTapped, TreeHostRePairTapped("…"), TreeHostReconnectTapped("…")]`; the update host's channels stay listed.
- Unit: `PLAY_STORE_URL` equals the exact store URL (JVM test under `app/src/test`).

Not operator-facing live flow against the daemon (a static render of a terminal state plus an external link), so no rung-3 scenario.

## Open questions

None.

## Documentation handoff

The ticket names no documentation requirement. Pending for the documentation stage: fold the update-required host treatment into `docs/knowledge/features/channel-list-screen-tree-and-controls.md`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the one daemon-authored value is `UpdateRequired.minClientVersion`, validated once at `validMinClientVersion` (three 1–6 digit parts, ≤ 20 chars) before the supervisor stores it. `TreeHostRow` formats it only into the caption's `Text` via a `%1$s` string resource; it reaches no `contentDescription` (`cd_tree_host_update` takes the bounded host name only), no event (`TreeHostUpdateTapped` is a `data object`), no URL (`PLAY_STORE_URL` is a constant), no test tag and no log. Tests assert no content description contains the version.
- [Tokens] No findings — none touched.
- [File / storage] No findings — nothing read or written.
- [Inter-process] No findings — `openUri` of a constant `https://play.google.com` URL; no new exported component, intent filter or extras. The host's server id and name never enter the intent.
- [Crypto] No findings — none touched.
- [Network & I/O] No findings — the update control does not call `reconnectHost`, so tapping it cannot trigger a dial loop against a host that already refused the build.
- [Logs] No findings — no log line added; the version is never logged.
- [Concurrency] No findings — no coroutine added.
- [Threat model] Hostile daemon frame: bounded by #1008's shape check; even a bypass renders as plain text in one `Text`. A hostile daemon cannot choose the store URL. Play in-app update API is out of scope per the ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

## Revisions

- **2026-09-25, rationale only.** The Design section gave a debug `applicationIdSuffix` as the reason `PLAY_STORE_URL` is not built from `BuildConfig.APPLICATION_ID`. `app/build.gradle.kts` sets no suffix. The constant stays a literal because it is an app-authored value pinned to the published listing, not derived from build configuration. The contract is unchanged, and the KDoc on `PLAY_STORE_URL` now says this.
