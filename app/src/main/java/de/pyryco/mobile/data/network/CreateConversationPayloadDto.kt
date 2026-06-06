package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `create_conversation` request payload (#347): the phone→binary request that
 * creates a new conversation. **Encode-only** — the phone sends it; the server replies with a
 * `conversation_created` envelope carrying the bare created conversation object, decoded through the
 * #318 [ConversationResponseDto] boundary. Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/conversations_write.go` `CreateConversationPayload` (#274),
 * whose three fields are `*bool`/`*string`/`*string` (`is_promoted`, `name`, `cwd`) — all optional
 * pointers, none with `omitempty`. Field declaration order matches the Go struct (skipping the
 * unmodeled `name`).
 *
 *  - [isPromoted] is always sent as `false`: the mobile app's `createDiscussion` only ever creates
 *    **unpromoted** discussions. `encodeDefaults=true` emits it even though it equals the Kotlin
 *    default, removing any ambiguity about the server's default-when-absent. Promotion is the
 *    separate `promote_conversation` flow (#348).
 *  - [cwd] carries the caller's `workspace` verbatim, or `null`. A non-null value pins the
 *    conversation's cwd; `null` requests a **server-assigned scratch cwd** — under [MobileJson]
 *    (`explicitNulls=false`) a null [cwd] is **omitted** from the JSON (not `"cwd":null`), which the
 *    server decodes identically to an absent key (Go leaves the `*string` nil), so the server
 *    assigns the scratch cwd (#274 sanctions filling server-side defaults when the field is absent).
 *
 * **`name` is intentionally not modeled.** Discussions are server-auto-named, so the create flow
 * never sends a `name`; an absent key decodes to a nil pointer server-side and the
 * `conversation_created` reply comes back with `name: null`. This is an encode-only DTO — model only
 * what is sent (the #346 / [SendMessagePayloadDto] discipline), not the full decode surface (#318's
 * job). Do NOT "add `name` for completeness".
 */
@Serializable
data class CreateConversationPayloadDto(
    @SerialName("is_promoted") val isPromoted: Boolean = false,
    val cwd: String? = null,
)
