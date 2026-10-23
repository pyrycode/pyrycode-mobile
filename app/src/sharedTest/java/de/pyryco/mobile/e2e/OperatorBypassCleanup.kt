package de.pyryco.mobile.e2e

/** Scenario seam shared by native replay and its controlled cleanup regression. */
internal inline fun <T> withOperatorBypassCleanup(
    closePeer: () -> Unit,
    beforeHostRemoval: () -> Unit,
    removeHost: () -> Unit,
    scenario: () -> T,
): T = withPermissionIsolationCleanup(closePeer, beforeHostRemoval, removeHost, scenario)
