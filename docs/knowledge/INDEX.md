# Knowledge Base Index

Start here for mobile development. Read the owning topic before changing code.
Use [CATALOG.md](CATALOG.md) when you need the legacy per-ticket inventory or a
broader document search. The frozen archive under `codebase/` is historical.

## Product and UI

- [Shared typography](features/shared-typography.md): Figma text roles, emphasis, visual evidence and font-scale limit.
- [Navigation](features/navigation.md): routes, pairing state and the single activity.
- [Welcome](features/welcome-screen.md): first-run entry point.
- [Pairing](features/scanner-screen.md): camera scanner, [pair with code](features/paste-code-dialog.md), [fingerprint confirmation](features/pairing-confirm-gate.md), and [paired-server storage](features/paired-server-store.md).
- [Channels and discussions](features/channel-list-screen.md): list surfaces, promotion and archive flows.
- [Conversation thread](features/thread-screen.md): messages, live turns, status rows and thread actions.
- [Markdown reader](features/markdown-reader-screen.md): in-app reader for a `.md`/`.markdown` attachment, opened from the thread's file rows instead of another app; also opened live, fetched fresh on every tap and never cached, from a workspace-path link in an assistant reply.
- [Slash-command type-ahead](features/slash-command-type-ahead.md): composer suggestions from the conversation's published slash-command menu, filtering, completion and dismissal.
- [Banner notice row](features/banner-notice-row.md): claude's `banner` frame surfaced as an inert thread row, live and on history reload.
- [Model refusal row](features/model-refusal-row.md): a model refusal or fallback explained in the thread, live and on history reload.
- [Stopped-turn row](features/stopped-turn-row.md): a turn that failed or stopped early leaves a "Stopped: …" row in the thread, live and on history reload, surviving after the status-area turn-outcome arm clears.
- [Settings](features/settings-screen.md): notifications-only modal and persisted push control.
- [Shared mobile modal](features/mobile-modal.md): caller-controlled editing shell, theme mapping, focus and IME behavior.
- [Host editor](features/host-editor.md): the shared Edit host state machine (`ui/host/HostEditor.kt`) used by the channel list.
- [System prompt editor](features/system-prompt-editor.md): the shared channel system-prompt editing state (`ui/conversations/components/SystemPromptEditor.kt`), mounted by `ThreadViewModel` for Channel info's System prompt section.

## Data and transport

- [Data model](features/data-model.md): domain entities and portable data-layer rules.
- [Conversation cache](features/conversation-cache.md): app-private, host-keyed storage that lets loaded conversation content survive a restart.
- [Conversation repository](features/conversation-repository.md): repository contract and observable thread state.
- [Remote repository](features/remote-conversation-repository.md): relay-backed reads, writes and live events.
- [Mobile protocol](features/mobile-protocol-v2-wire-layer.md): versioned envelope and payload handling.
- [Noise session](features/noise-ik-session.md): the encrypted phone-to-daemon session.
- [Relay transport](features/relay-ws-transport.md): WebSocket framing and connection lifecycle.
- [Reconnect supervision](features/relay-reconnect-supervisor.md): reconnect, retry and handoff behavior.
- [Lifecycle driver](features/lifecycle-connection-driver.md): foreground and background connection ownership, and the push-wake background window.
- [Push messaging service](features/push-messaging-service.md): FCM token capture and the push trust boundary; the daemon (v0.23.0+) and relay send the wake, and #955 is the live proof it reaches a backgrounded phone.
- [Device key storage](features/device-static-keystore.md): Android Keystore wrapping and key continuity.

## Decisions

- [Decision records](decisions/0001-kotlinx-datetime-for-data-layer.md): numbered architectural choices from data timestamps through transport and key storage, and why a release-kept diagnostic trail is a separate facility from the debug-only relay log.

## Verification and history

- [Development verification](features/development-verification.md): Kotlin, Compose, Gradle, emulator and real-evidence checks.
- [Hands-on device checks](features/hands-on-device-checks.md): picking a USB phone or emulator by hand, and pairing it without typing the code.
- [Interactive stream e2e](../e2e-interactive-stream.md): manual and scripted emulator coverage, with its current evidence limits.
- [Document catalog](CATALOG.md): the pre-migration knowledge index preserved byte-for-byte, plus current shared-topic pointers.
- [Frozen ticket archive](codebase/): per-ticket implementation notes, closed to new writes.

## Recording knowledge

Builders and verifiers record discoveries on the ticket or PR. The documentation
stage owns evergreen topic documents and this map. Update a topic before adding a
new document. Workflow lessons belong in the agent or dispatcher repository.
Claude local memory is disabled for this project and is not a second knowledge
source.
