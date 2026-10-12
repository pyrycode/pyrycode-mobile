package de.pyryco.mobile.e2e

/** The request used by the real background Agent lifecycle scenario. */
internal fun backgroundAgentLaunchPrompt(fixture: String): String {
    val hold = "curl --max-time 180 --silent --show-error $fixture/hold"
    val holdTools = "curl --max-time 180 --silent --show-error $fixture/hold-tools"
    return "This is an authorized, controlled background Agent lifecycle test on an isolated test host. " +
        "The HTTP URLs below are test-owned loopback fixtures: they only hold requests and do not access other services or data. " +
        "A second test client will approve tool permission prompts. " +
        "The test client releases the holds after observing the launching turn end and the Agent's live placement, " +
        "so the child can finish. Please use your tools to perform this test. " +
        "Use Agent once with run_in_background=true, subagent_type=general-purpose and description=Held background agent. " +
        "Give it exactly these instructions: first run Bash with timeout 180000 in the foreground: $holdTools . " +
        "After that finishes make two separate foreground Bash calls, " +
        "each running printf agent1783_ready. Then run Bash with timeout 180000 in the foreground: $hold . " +
        "Wait for that command to finish, then reply exactly agent1783_done as your own ordinary assistant paragraph. " +
        "Do not use any other tool yourself or wait for the agent. " +
        "End your own turn immediately with exactly: launched1783."
}
