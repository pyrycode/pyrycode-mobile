# Session boundaries — supplied content and live ownership

The runtime `session_transition` decoder/fold and raw row reconstruction were removed
in #2367. Session delimiters are rendered from admitted authoritative items with
supplied identity/order; missing session metadata cannot be inferred from raw events.
The [delimiter component](session-boundary-delimiter.md) remains unchanged.

Current live-session ownership is a separate concern. Authoritative conversation
summaries or ordered producing-session family clears reconcile replacement through
`SessionLiveAdmission`. Fresh readings, timestamps and correlated old replies cannot
move that binding. See [live state and retirement](remote-conversation-repository-live-stream-and-modals.md).
