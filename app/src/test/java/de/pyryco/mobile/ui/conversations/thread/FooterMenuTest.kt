package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        permissionMode: String = "",
        pendingPermission: String? = null,
        sessionId: String = "s1",
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
        sessionId = sessionId,
        permissionMode = permissionMode,
        pendingPermission = pendingPermission,
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

    // ---- #650: the permission control ----------------------------------------------------------

    @Test
    fun permission_withoutAConfirmedMode_isHiddenAndOffersNothing() {
        assertNull(permissionModeLabel(config(permissionMode = "")))
        assertNull(footerMenu(FooterControl.Permission, config(permissionMode = "")))
        assertFalse(footerControlEnabled(FooterControl.Permission, config(permissionMode = "")))
    }

    @Test
    fun permission_labelsEachConfirmedMode() {
        val labels =
            mapOf(
                "default" to "Manual approval",
                "acceptEdits" to "Auto-approve edits",
                "auto" to "Auto approval",
                "plan" to "Plan",
                "dontAsk" to "Approved actions only",
                "bypassPermissions" to "Bypass approvals",
            )
        labels.forEach { (wire, label) -> assertEquals(label, permissionModeLabel(config(permissionMode = wire))) }
    }

    @Test
    fun permission_offersAutoApprovalOnlyWhenTheSelectedRowSupportsIt() {
        val without = footerMenu(FooterControl.Permission, config(permissionMode = "plan"))
        assertEquals(
            listOf("default", "acceptEdits", "plan", "dontAsk", "bypassPermissions"),
            without?.options?.map { it.value },
        )
        assertEquals("plan", without?.selectedValue)

        val autoOpus = opus.copy(supportsAutoMode = true)
        val with = footerMenu(FooterControl.Permission, config(choices = listOf(autoOpus), permissionMode = "plan"))
        assertEquals(
            listOf("default", "acceptEdits", "auto", "plan", "dontAsk", "bypassPermissions"),
            with?.options?.map { it.value },
        )
        assertEquals("Auto approval", with?.options?.get(2)?.label)
    }

    @Test
    fun permission_anUnrecognisedModeIsInertText_selectsNothing_andIsNeverAChoice() {
        val runConfig = config(permissionMode = "future\u001B[31mMode")

        assertEquals("future[31mMode", permissionModeLabel(runConfig))
        val menu = footerMenu(FooterControl.Permission, runConfig)
        assertTrue(menu?.options.orEmpty().none { it.value == runConfig.permissionMode })
        assertTrue(menu?.options.orEmpty().none { it.value == menu?.selectedValue })
    }

    @Test
    fun permission_opensOnlyWithASessionAndNoOutstandingPermissionWrite() {
        assertTrue(footerControlEnabled(FooterControl.Permission, config(permissionMode = "plan")))
        assertFalse(footerControlEnabled(FooterControl.Permission, config(permissionMode = "plan", sessionId = "")))
        assertFalse(footerControlEnabled(FooterControl.Permission, config(permissionMode = "plan", pendingPermission = "default")))
        // Model and effort gating is unchanged: a pending model tap does not block the permission control,
        // and a pending permission write does not block the model control.
        assertTrue(footerControlEnabled(FooterControl.Permission, config(permissionMode = "plan", pendingModel = "haiku")))
        assertTrue(footerControlEnabled(FooterControl.Model, config(permissionMode = "plan", pendingPermission = "default")))
    }
}
