package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What each composer-footer control offers (#808), proven on plain [ThreadRunConfig] values. A control
 * that offers nothing yields `null`, and the footer disables it rather than opening an empty overlay.
 */
class FooterMenuTest {
    private val opus =
        ThreadModelChoice(
            value = "opus[1m]",
            label = "Opus 4.7",
            detail = "",
            effortChoices =
                listOf(
                    ThreadEffortChoice(value = "high", label = "high"),
                    ThreadEffortChoice(value = "max", label = "max"),
                ),
        )
    private val haiku = ThreadModelChoice(value = "haiku", label = "Haiku", detail = "", effortChoices = emptyList())

    private fun config(
        choices: List<ThreadModelChoice> = listOf(opus, haiku),
        menuAvailable: Boolean = true,
        savedModel: String = "opus[1m]",
        savedEffort: String = "high",
        pendingModel: String? = null,
        pendingEffort: String? = null,
        droppedModels: Int = 0,
        hiddenChoices: Int = 0,
    ) = ThreadRunConfig(
        choices = choices,
        menuAvailable = menuAvailable,
        droppedModels = droppedModels,
        hiddenChoices = hiddenChoices,
        settingsAvailable = true,
        savedModel = savedModel,
        savedEffort = savedEffort,
        pendingModel = pendingModel,
        pendingEffort = pendingEffort,
        sessionId = "s1",
    )

    @Test
    fun model_withNoPublishedMenu_offersNothing() {
        assertNull(footerMenu(FooterControl.Model, config(choices = emptyList(), menuAvailable = false)))
    }

    @Test
    fun model_withAnEmptyPublishedMenu_offersNothing() {
        assertNull(footerMenu(FooterControl.Model, config(choices = emptyList(), menuAvailable = true)))
    }

    @Test
    fun model_listsThePublishedRowsInDaemonOrderWithVerbatimValues() {
        val menu = footerMenu(FooterControl.Model, config())

        assertEquals(
            listOf(OptionsOverlayOption("opus[1m]", "Opus 4.7"), OptionsOverlayOption("haiku", "Haiku")),
            menu?.options,
        )
        assertEquals("opus[1m]", menu?.selectedValue)
        assertEquals(0, menu?.notListed)
    }

    @Test
    fun model_notListed_sumsTheProducersCutAndTheRenderCapWithoutRecountingRows() {
        // Two rows shown; the producer reports 40 cut and this client hid 3. Neither is derived from
        // choices.size, so the overlay can say the list is a subset.
        val menu = footerMenu(FooterControl.Model, config(droppedModels = 40, hiddenChoices = 3))

        assertEquals(43, menu?.notListed)
    }

    @Test
    fun model_selectedValueFollowsThePendingTap() {
        val menu = footerMenu(FooterControl.Model, config(pendingModel = "haiku"))

        assertEquals("haiku", menu?.selectedValue)
    }

    @Test
    fun effort_listsOnlyTheSelectedRowsLevels() {
        val menu = footerMenu(FooterControl.Effort, config())

        assertEquals(
            listOf(OptionsOverlayOption("high", "high"), OptionsOverlayOption("max", "max")),
            menu?.options,
        )
        assertEquals("high", menu?.selectedValue)
        assertEquals(0, menu?.notListed)
    }

    @Test
    fun effort_forARowPublishingNoLevels_offersNothing() {
        assertNull(footerMenu(FooterControl.Effort, config(savedModel = "haiku")))
    }

    @Test
    fun effort_whenNoPublishedRowIsSelected_offersNothing() {
        assertNull(footerMenu(FooterControl.Effort, config(savedModel = "sonnet")))
    }

    @Test
    fun effort_selectedValueFollowsThePendingTap() {
        val menu = footerMenu(FooterControl.Effort, config(pendingEffort = "max"))

        assertEquals("max", menu?.selectedValue)
    }
}
