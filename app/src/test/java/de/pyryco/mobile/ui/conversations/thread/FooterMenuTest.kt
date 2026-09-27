package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.SessionCapabilities
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
        appliedEffort: EffectiveEffort = EffectiveEffort.Unavailable,
        capabilities: SessionCapabilities? = null,
        inheritedChoice: ThreadModelChoice? = null,
    ) = ThreadRunConfig(
        choices = choices,
        inheritedChoice = inheritedChoice,
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
        appliedEffort = appliedEffort,
        capabilities = capabilities,
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

    // ---- #972: no model override reads the inherited-default row's levels -----------------------

    private val inherited =
        ThreadModelChoice(
            value = "default",
            label = "Default",
            detail = "",
            effortChoices = listOf(ThreadEffortChoice(value = "low", label = "low"), ThreadEffortChoice(value = "high", label = "high")),
        )

    @Test
    fun effort_withNoModelOverride_offersTheDefaultRowsLevels() {
        val config = config(choices = listOf(opus), inheritedChoice = inherited, savedModel = "", savedEffort = "")

        assertEquals(inherited.effortChoices, config.effortChoices)
        assertTrue(footerControlEnabled(FooterControl.Effort, config))
        assertFalse(footerControlEnabled(FooterControl.Effort, config.copy(sessionId = "")))
    }

    @Test
    fun effort_withNoModelOverrideAndNoDefaultRow_offersNothing() {
        val config = config(savedModel = "", savedEffort = "")

        assertTrue(config.effortChoices.isEmpty())
        assertFalse(footerControlEnabled(FooterControl.Effort, config))
    }

    @Test
    fun effort_withNoModelOverride_leavesTheModelSelectionUnwidened() {
        val config = config(choices = listOf(opus), inheritedChoice = inherited, savedModel = "", savedEffort = "")

        assertNull(config.selectedChoice)
        assertEquals(UNAVAILABLE_MODEL_LABEL, config.modelLabel)
        assertEquals("", footerMenu(FooterControl.Model, config)?.selectedValue)
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

    // ---- #1111: the session's capability list ---------------------------------------------------

    @Test
    fun effort_withACapabilityList_offersOnlyLevelsBothTheRowAndTheListName() {
        val runConfig = config(capabilities = caps(effortLevels = listOf("low", "max")))

        assertEquals(listOf(OptionsOverlayOption("max", "max")), footerMenu(FooterControl.Effort, runConfig)?.options)
        assertEquals(listOf("max"), runConfig.effortChoices.map { it.value })
    }

    @Test
    fun effort_withACapabilityListNamingNoneOfTheRowsLevels_offersNothing() {
        assertNull(footerMenu(FooterControl.Effort, config(capabilities = caps(effortLevels = emptyList()))))
    }

    @Test
    fun permission_withACapabilityList_offersListedModesPlusBypass() {
        val menu =
            footerMenu(
                FooterControl.Permission,
                config(permissionMode = "default", capabilities = caps(permissionModes = listOf("default", "plan"))),
            )

        assertEquals(listOf("default", "plan", "bypassPermissions"), menu?.options?.map { it.value })
    }

    @Test
    fun permission_withACapabilityList_autoStillNeedsTheRowsSupport() {
        val listed = caps(permissionModes = listOf("default", "auto"))
        val autoOpus = opus.copy(supportsAutoMode = true)

        assertEquals(
            listOf("default", "bypassPermissions"),
            footerMenu(FooterControl.Permission, config(permissionMode = "default", capabilities = listed))?.options?.map { it.value },
        )
        assertEquals(
            listOf("default", "auto", "bypassPermissions"),
            footerMenu(FooterControl.Permission, config(choices = listOf(autoOpus), permissionMode = "default", capabilities = listed))
                ?.options
                ?.map { it.value },
        )
        // A row that supports auto does not bring it back when the list leaves it out.
        assertEquals(
            listOf("default", "bypassPermissions"),
            footerMenu(FooterControl.Permission, config(choices = listOf(autoOpus), permissionMode = "default", capabilities = caps()))
                ?.options
                ?.map { it.value },
        )
    }

    private fun caps(
        effortLevels: List<String> = listOf("high", "max"),
        permissionModes: List<String> = listOf("default"),
    ) = SessionCapabilities(effortLevels = effortLevels, permissionModes = permissionModes)

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

    // ---- #889: the effort display takes Claude's applied reading, falling back to the saved choice ----

    @Test
    fun effort_appliedValueWins_overADifferentSavedChoice() {
        val runConfig = config(savedEffort = "high", appliedEffort = EffectiveEffort.Applied("max"))

        assertEquals("max", runConfig.effortLabel)
        assertEquals("max", footerMenu(FooterControl.Effort, runConfig)?.selectedValue)
        assertNull(runConfig.effortNote)
    }

    @Test
    fun effort_appliedValueWins_overAnEmptySavedChoice() {
        val runConfig = config(savedEffort = "", appliedEffort = EffectiveEffort.Applied("high"))

        assertEquals("high", runConfig.effortLabel)
        assertEquals("high", runConfig.selectedEffort)
        assertNull(runConfig.effortNote)
    }

    @Test
    fun effort_aPendingTapOutranksTheAppliedValue() {
        val runConfig = config(pendingEffort = "high", appliedEffort = EffectiveEffort.Applied("max"))

        assertEquals("high", runConfig.effortLabel)
        assertEquals("high", footerMenu(FooterControl.Effort, runConfig)?.selectedValue)
        assertNull("the pending state description covers it", runConfig.effortNote)
    }

    @Test
    fun effort_unavailableReading_fallsBackToTheSavedChoiceAndSaysSo() {
        val runConfig = config(savedEffort = "high", appliedEffort = EffectiveEffort.Unavailable)

        assertEquals("high", runConfig.effortLabel)
        assertEquals("high", footerMenu(FooterControl.Effort, runConfig)?.selectedValue)
        assertEquals(EffortNote.SelectedRunningUnavailable, runConfig.effortNote)
    }

    @Test
    fun effort_unavailableReadingWithoutASavedChoice_readsEffortWithNothingSelected() {
        val runConfig = config(savedEffort = "", appliedEffort = EffectiveEffort.Unavailable)

        assertEquals("Effort", runConfig.effortLabel)
        assertEquals("", footerMenu(FooterControl.Effort, runConfig)?.selectedValue)
        assertEquals(EffortNote.DefaultRunningUnavailable, runConfig.effortNote)
    }

    @Test
    fun effort_emptyAppliedReading_isTreatedAsUnavailable() {
        val withChoice = config(savedEffort = "max", appliedEffort = EffectiveEffort.Applied(""))
        assertEquals("max", withChoice.effortLabel)
        assertEquals(EffortNote.SelectedRunningUnavailable, withChoice.effortNote)

        val without = config(savedEffort = "", appliedEffort = EffectiveEffort.Applied(""))
        assertEquals("Effort", without.effortLabel)
        assertEquals("", without.selectedEffort)
        assertEquals(EffortNote.DefaultRunningUnavailable, without.effortNote)
    }

    @Test
    fun effort_explicitNull_clearsTheSelectionEvenWithASavedChoice() {
        listOf("high", "").forEach { saved ->
            val runConfig = config(savedEffort = saved, appliedEffort = EffectiveEffort.NotReported)

            assertEquals("Effort", runConfig.effortLabel)
            assertEquals("", footerMenu(FooterControl.Effort, runConfig)?.selectedValue)
            assertEquals(EffortNote.NotReported, runConfig.effortNote)
        }
    }

    @Test
    fun effort_appliedValueOutsideThePublishedLevels_isTheLabelButSelectsNoOption() {
        val runConfig = config(appliedEffort = EffectiveEffort.Applied("xhigh"))
        val menu = footerMenu(FooterControl.Effort, runConfig)

        assertEquals("xhigh", runConfig.effortLabel)
        assertEquals(listOf("high", "max"), menu?.options?.map { it.value })
        assertTrue(menu?.options.orEmpty().none { it.value == menu?.selectedValue })
    }

    @Test
    fun effort_hostileAppliedValue_rendersInert() {
        val runConfig = config(appliedEffort = EffectiveEffort.Applied("hi\u001B[31mgh\n" + "x".repeat(400)))

        assertFalse(runConfig.effortLabel.any { it.isISOControl() })
        assertTrue(runConfig.effortLabel.length <= 128)
    }

    @Test
    fun effort_withNoPublishedLevels_isReadOnlyWhateverTheReading() {
        val runConfig = config(savedModel = "haiku", appliedEffort = EffectiveEffort.Applied("high"))

        assertEquals("high", runConfig.effortLabel)
        assertNull(footerMenu(FooterControl.Effort, runConfig))
        assertFalse(footerControlEnabled(FooterControl.Effort, runConfig))
    }

    @Test
    fun effort_withoutAnyReading_staysUnknownWithNoNote() {
        val runConfig = ThreadRunConfig()

        assertEquals(UNKNOWN_RUN_CONFIG_LABEL, runConfig.effortLabel)
        assertNull(runConfig.effortNote)
    }

    // #884: the Actions menu is the client-owned rows in order, and never a radio choice. #678 added the
    // background-tasks row last, with the live count in its label.
    @Test
    fun actions_listsTheRowsInOrder_withNothingSelected() {
        val menu = footerMenu(FooterControl.Actions, config())

        assertEquals(
            listOf("Reset session", "Compact session", "Knowledge capture", "Background tasks (0)"),
            menu?.options?.map { it.label },
        )
        assertEquals(listOf("reset", "compact", "knowledge-capture", "background-tasks"), menu?.options?.map { it.value })
        assertTrue(menu?.options.orEmpty().all { it.enabled })
        assertEquals("", menu?.selectedValue)
        assertEquals(0, menu?.notListed)
        assertTrue(menu?.actions == true)
    }

    // #884: Reset session sits under the overflow item's own mutationsSupported gate.
    @Test
    fun actions_withoutMutations_omitsResetSession() {
        val menu = footerMenu(FooterControl.Actions, config(), mutationsSupported = false)

        assertEquals(listOf("Compact session", "Knowledge capture", "Background tasks (0)"), menu?.options?.map { it.label })
    }

    // #884: a command the published menu proves absent is greyed out, and the other rows are not.
    @Test
    fun actions_absentCommand_isDisabled_andTheOthersStayEnabled() {
        val menu = footerMenu(FooterControl.Actions, config(), absentActions = setOf(ComposerAction.CompactSession))

        assertEquals(listOf(true, false, true, true), menu?.options?.map { it.enabled })
    }

    // #678: the background-tasks row carries the live count, and stays enabled when every command is absent.
    @Test
    fun actions_backgroundTasksRow_carriesTheCount_andStaysEnabled() {
        val menu =
            footerMenu(
                FooterControl.Actions,
                config(),
                absentActions = setOf(ComposerAction.CompactSession, ComposerAction.KnowledgeCapture),
                backgroundTaskCount = 3,
            )
        val row = menu?.options?.single { it.value == "background-tasks" }

        assertEquals("Background tasks (3)", row?.label)
        assertTrue(row?.enabled == true)
    }

    // #884: a command send needs no session to address and no idle run configuration.
    @Test
    fun actions_isEnabled_withoutASession_andWhileAWriteIsPending() {
        assertTrue(footerControlEnabled(FooterControl.Actions, config(sessionId = "")))
        assertTrue(footerControlEnabled(FooterControl.Actions, config(pendingModel = "haiku", pendingPermission = "plan")))
        assertTrue(footerControlEnabled(FooterControl.Actions, ThreadRunConfig()))
    }

    // #884: the pre-existing controls are untouched by the Actions inputs.
    @Test
    fun otherControls_ignoreTheActionsInputs() {
        assertEquals(
            footerMenu(FooterControl.Model, config()),
            footerMenu(FooterControl.Model, config(), mutationsSupported = false, absentActions = ComposerAction.entries.toSet()),
        )
        assertTrue(footerMenu(FooterControl.Model, config())?.actions == false)
    }
}
