# Connection status indicator

`ConnectionBanner` was retired by [the thread notice placement](../../specs/architecture/1283-thread-connection-and-notice-states.md). The current `ConnectionStatusIndicator` in `ui/conversations/components/ConnectionStatusIndicator.kt` renders [Connecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740) and [Reconnecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4657) as muted body-small text in the composer status row. Connected and Offline render no text there.

[Offline Retry](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910) is a separate action in [Thread top overlay](thread-top-overlay.md); rejected pairing instead offers Re-pair. See [Thread screen status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement) for precedence and retry wiring. The [connection state](connection-state.md) model and source contract are unchanged.
