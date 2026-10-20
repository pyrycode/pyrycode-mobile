# Exact Agent ownership on nested tool rows

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `renderRow` and `ThreadRowContent` provide an existing ancestor without changing geometry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` supplies the scoped Agent identity and parent ownership.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageBubble` passes tool modifiers to the outlined tool surface.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt`: `ToolCallRowContent` owns `TOOL_ROW_TAG`; placing both tags there cannot establish ancestry.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadFixtures.kt`: reuse admitted snapshot and Agent fixtures.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadScreenTest.kt`: mirror mounted authoritative presentation and tool fixtures.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md`: ownership and visual nesting are independent; collapse keeps the Agent root visible.
- `docs/knowledge/features/thread-screen-testing-background-agent-viewport.md`: authoritative snapshot settlement must retain identity.
- `docs/knowledge/features/development-verification-gates.md`: shared tests also compile for native execution.

## Design source

N/A: semantics-only repair, explicitly confirmed by the refiner. No visual change or Figma contract is requested.

## Change

Tag the existing `ThreadRowContent` ancestor of an owned tool child with `background-agent-child:<agentBlockId>`, excluding the Agent root itself. Keep the assistant-child modifier, nesting padding, lazy keys, outlined `TOOL_ROW_TAG`, projection and expansion state unchanged. This is one independently useful behaviour with its regression, estimated at approximately 240 written lines across production, shared tests and this plan; no exported production API or consumer migration. No new state, concurrency or failure branch is introduced. Remote numeric feature branches have no overlap; PR #1285's `feature/1283-notice-placement` also edits `ThreadScreen` but only connection/status placement, so no dependency is needed.

## Testing strategy

Add `AuthoritativeAgentOwnershipTest` under `sharedTest`, using real `projectAuthoritativeThread` output and mounted `ThreadScreen`. Retain the exact unmerged `hasTestTag(TOOL_ROW_TAG) and hasAnyAncestor(hasTestTag("background-agent-child:<exact Agent message ID>"))` matcher, further selecting the tool by its content. Prove running and completed ownership, collapsed child absence and expansion, full composition removal/reopen, and exclusion of foreground tools and another Agent's tool. Exercise collapse enabled and disabled. First observe a failure with unchanged production, then repair and run focused JVM and native checks. Run existing authoritative screen, background block, prose and nesting coverage, lint, assembly, instrumented Kotlin compilation and formatting, followed by the required final pre-verification gate.

All execution is on macOS. The ticket's Linux-only combination with PR #2337's two deterministic methods is outside this run's verification scope under the current operating-system policy; no second-OS task is requested and no Linux pass is claimed. Genuine captures remain with #2246 and full gates remain dispatcher-owned.

## Revisions

2026-10-20: Native red proof executed both methods and failed both on the exact owner matcher. The first repaired run passed the uncollapsed lifecycle/reopen method; its collapse method exposed an incorrect new test expectation that every child lazy key disappears. `ThreadRow.listKey` deliberately reuses the first child's key for the collapsed header. Preserve that header key, require the sibling key absent and all owned outlined tools absent, then require both tool keys and exact owners on expansion. Include an indirectly parented tool to cover the existing parent-chain contract. Production design is unchanged.
