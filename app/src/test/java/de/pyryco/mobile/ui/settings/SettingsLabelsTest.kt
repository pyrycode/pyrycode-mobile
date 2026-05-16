package de.pyryco.mobile.ui.settings

import de.pyryco.mobile.data.preferences.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLabelsTest {
    @Test
    fun systemLabel_isSystem() {
        assertEquals("System", ThemeMode.SYSTEM.label())
    }

    @Test
    fun lightLabel_isLight() {
        assertEquals("Light", ThemeMode.LIGHT.label())
    }

    @Test
    fun darkLabel_isDark() {
        assertEquals("Dark", ThemeMode.DARK.label())
    }
}
