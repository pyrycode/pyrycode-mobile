package de.pyryco.mobile.data.network

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codec tests for the v2 `recent_workspaces_list` reply payload (#565). JUnit4, mirroring
 * CreateWorkspaceFolderPayloadsTest.kt. The reply DTO is **decode-only** (binary → phone), exercised
 * through `MobileJson.decodeFromJsonElement`. Wire SSOT: server `internal/protocol/workspace.go`
 * `RecentWorkspacesListPayload{Workspaces}` / `RecentWorkspace{Path, LastUsedAt}` (pyrycode#888).
 */
class RecentWorkspacesPayloadsTest {
    // AC #5: the canonical reply decodes to two rows whose `path`s match, in wire order. The daemon's
    // `last_used_at` field is tolerated by `ignoreUnknownKeys` and is absent from the DTO (it is
    // intentionally not modeled — the recency subtitle is out of scope).
    @Test
    fun reply_decodesPathsInWireOrder_droppingLastUsedAt() {
        val element =
            MobileJson.parseToJsonElement(
                """
                {"workspaces":[
                  {"path":"/Users/x/pyry-workspace/alpha","last_used_at":"2026-05-08T09:12:00Z"},
                  {"path":"/Users/x/pyry-workspace/beta","last_used_at":"2026-05-07T18:04:30Z"}
                ]}
                """.trimIndent(),
            )

        val dto = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(element)

        assertEquals(
            listOf("/Users/x/pyry-workspace/alpha", "/Users/x/pyry-workspace/beta"),
            dto.workspaces.map { it.path },
        )
    }

    // AC #5: an empty registry marshals as `{"workspaces":[]}` (never null) and decodes to an empty list.
    @Test
    fun reply_emptyRegistry_decodesToEmptyList() {
        val element = MobileJson.parseToJsonElement("""{"workspaces":[]}""")

        val dto = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(element)

        assertEquals(emptyList<RecentWorkspaceDto>(), dto.workspaces)
    }

    // Forward-compat proof for `ignoreUnknownKeys`: an extra unknown top-level field and an extra
    // unknown row field still decode, consuming only `path`.
    @Test
    fun reply_withUnknownFields_stillDecodes() {
        val element =
            MobileJson.parseToJsonElement(
                """{"workspaces":[{"path":"/a/proj","last_used_at":"2026-05-08T09:12:00Z","pinned":true}],"total":1}""",
            )

        val dto = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(element)

        assertEquals(listOf("/a/proj"), dto.workspaces.map { it.path })
    }
}
