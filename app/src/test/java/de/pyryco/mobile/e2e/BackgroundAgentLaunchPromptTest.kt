package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAgentLaunchPromptTest {
    @Test
    fun controlledFixturePurposeAndReleaseOwnershipPrecedeExecutableInstructions() {
        val prompt = backgroundAgentLaunchPrompt("http://127.0.0.1:12345")
        val explanation = prompt.substringBefore("Use Agent once")
        for (context in listOf(
            "controlled background Agent lifecycle test",
            "isolated test host",
            "test-owned loopback fixtures",
            "only hold requests",
            "second test client will approve",
            "test client releases the holds",
        )) {
            assertTrue("missing controlled-test context: $context", context in explanation)
        }
    }

    @Test
    fun framingPreservesTheExactLaunchAndLateChildInstructionContract() {
        val fixture = "http://127.0.0.1:12345"
        val originalInstructions =
            "Use Agent once with run_in_background=true, subagent_type=general-purpose and description=Held background agent. " +
                "Give it exactly these instructions: first run Bash with timeout 180000 in the foreground: " +
                "curl --max-time 180 --silent --show-error $fixture/hold-tools . " +
                "After that finishes make two separate foreground Bash calls, " +
                "each running printf agent1783_ready. Then run Bash with timeout 180000 in the foreground: " +
                "curl --max-time 180 --silent --show-error $fixture/hold . " +
                "Wait for that command to finish, then reply exactly agent1783_done as your own ordinary assistant paragraph. " +
                "Do not use any other tool yourself or wait for the agent. " +
                "End your own turn immediately with exactly: launched1783."
        val prompt = backgroundAgentLaunchPrompt(fixture)
        assertEquals(originalInstructions, "Use Agent once" + prompt.substringAfter("Use Agent once"))
    }

    @Test
    fun bothHoldsUseTheCurrentIsolatedFixture() {
        for (port in listOf(12345, 54321)) {
            val fixture = "http://127.0.0.1:$port"
            val urls = Regex("http://[^ ]+").findAll(backgroundAgentLaunchPrompt(fixture)).map { it.value }.toList()
            assertEquals(listOf("$fixture/hold-tools", "$fixture/hold"), urls)
        }
    }
}
