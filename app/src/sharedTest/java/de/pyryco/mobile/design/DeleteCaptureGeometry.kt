package de.pyryco.mobile.design

import androidx.compose.ui.geometry.Rect
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals

/** Screen-coordinate viewport; overlapping bars and cutouts reserve each edge only once. */
internal fun usableDialogViewport(
    screen: Rect,
    effectiveInsets: WindowInsetsCompat,
): Rect {
    val clearance = effectiveInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    return Rect(screen.left + clearance.left, screen.top + clearance.top, screen.right - clearance.right, screen.bottom - clearance.bottom)
}

internal fun assertDeleteCentered(
    surface: Rect,
    viewport: Rect,
    density: Float,
) {
    assertEquals("Delete center: surface=$surface usableViewport=$viewport", viewport.center.y, surface.center.y, 2f * density)
}
