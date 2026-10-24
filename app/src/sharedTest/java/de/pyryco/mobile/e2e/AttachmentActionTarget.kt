package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule

/** The physical target used for both attachment open and save in the native scenarios. */
internal fun ComposeTestRule.attachmentActionTarget(matcher: SemanticsMatcher): SemanticsNodeInteraction = onNode(matcher)
