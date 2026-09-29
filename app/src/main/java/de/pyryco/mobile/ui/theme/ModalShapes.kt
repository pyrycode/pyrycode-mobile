package de.pyryco.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val ModalControlShape = RoundedCornerShape(6.dp)

/** The fixed dark modal's 6 dp field and action corner from the design system. */
val Shapes.modalControl: Shape
    get() = ModalControlShape
