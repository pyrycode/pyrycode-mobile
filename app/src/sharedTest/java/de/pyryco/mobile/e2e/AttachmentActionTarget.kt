package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Reveal the physical open/save target between thread chrome before the caller's single gesture. */
internal fun ComposeTestRule.attachmentActionTarget(
    matcher: SemanticsMatcher,
    evidence: (String) -> Unit = {},
): SemanticsNodeInteraction = questionAnswerTarget(matcher, evidence = evidence)
