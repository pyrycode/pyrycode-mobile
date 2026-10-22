package de.pyryco.mobile.ui.conversations.thread

internal fun deferredLargeSnapshot() =
    authoritativeSnapshot(
        authoritativeAgent(1),
        authoritativeItem(
            2,
            "tool_call",
            parent = 1,
            content =
                """
                {"name":"Read","input":{"file_path":"closed-file","body":"hidden-input"},
                "result":{"result_summary":"hidden-output"}}
                """.trimIndent(),
        ),
        *(3L..8333L).map { authoritativeItem(it, parent = 1) }.toTypedArray(),
    ).copy(checkpoint = 9000, completedVersion = 9000)

internal fun deferredNestedSnapshot() =
    authoritativeSnapshot(
        authoritativeAgent(1),
        authoritativeItem(2, "tool_call", parent = 1, content = """{"name":"Read","input":{"file_path":"outer-two"}}"""),
        authoritativeAgent(3, parent = 2),
        authoritativeItem(4, "tool_call", parent = 1, content = """{"name":"Read","input":{"file_path":"outer-four"}}"""),
        authoritativeItem(
            5,
            "tool_call",
            parent = 3,
            content =
                """
                {"name":"Read","input":{"file_path":"inner-five"},"result":{"result_summary":"inner-output"}}
                """.trimIndent(),
        ),
        authoritativeItem(6, parent = 3),
    )
