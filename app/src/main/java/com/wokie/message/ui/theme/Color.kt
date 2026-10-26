package com.wokie.message.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Wokie brand palette.
 *
 * The brand colour is "signal cyan" — a calm, technical cyan derived from the
 * original neon accent (#00F3FF), re-toned to meet Material 3 contrast roles in
 * both schemes. Supporting semantics:
 *  - tertiary / [WokieColors.success] — the "secure / verified" green,
 *  - [WokieColors.warning] — amber for experimental features and expiries.
 */

// ---- Light scheme ----
val WokiePrimaryLight = Color(0xFF00687A)
val WokieOnPrimaryLight = Color(0xFFFFFFFF)
val WokiePrimaryContainerLight = Color(0xFFA9E9F7)
val WokieOnPrimaryContainerLight = Color(0xFF001F26)
val WokieSecondaryLight = Color(0xFF4B6268)
val WokieOnSecondaryLight = Color(0xFFFFFFFF)
val WokieSecondaryContainerLight = Color(0xFFCDE7EC)
val WokieOnSecondaryContainerLight = Color(0xFF051F24)
val WokieTertiaryLight = Color(0xFF006C35)
val WokieOnTertiaryLight = Color(0xFFFFFFFF)
val WokieTertiaryContainerLight = Color(0xFFA9F4C0)
val WokieOnTertiaryContainerLight = Color(0xFF00210E)
val WokieErrorLight = Color(0xFFBA1A1A)
val WokieOnErrorLight = Color(0xFFFFFFFF)
val WokieErrorContainerLight = Color(0xFFFFDAD6)
val WokieOnErrorContainerLight = Color(0xFF410002)
val WokieBackgroundLight = Color(0xFFF7FAFB)
val WokieOnBackgroundLight = Color(0xFF191C1D)
val WokieSurfaceLight = Color(0xFFFFFFFF)
val WokieOnSurfaceLight = Color(0xFF191C1D)
val WokieSurfaceVariantLight = Color(0xFFDCE5E8)
val WokieOnSurfaceVariantLight = Color(0xFF3F484B)
val WokieOutlineLight = Color(0xFF6F797C)
val WokieOutlineVariantLight = Color(0xFFBEC9CC)

// ---- Dark scheme (first-class: Wokie is primarily used in the field, at night) ----
val WokiePrimaryDark = Color(0xFF45D7E8)
val WokieOnPrimaryDark = Color(0xFF00363D)
val WokiePrimaryContainerDark = Color(0xFF0E4A54)
val WokieOnPrimaryContainerDark = Color(0xFFB6EEF7)
val WokieSecondaryDark = Color(0xFFB0CBD1)
val WokieOnSecondaryDark = Color(0xFF1B343A)
val WokieSecondaryContainerDark = Color(0xFF334B51)
val WokieOnSecondaryContainerDark = Color(0xFFCDE7EC)
val WokieTertiaryDark = Color(0xFF55E39A)
val WokieOnTertiaryDark = Color(0xFF00391F)
val WokieTertiaryContainerDark = Color(0xFF0B4D2C)
val WokieOnTertiaryContainerDark = Color(0xFFB9F6D3)
val WokieErrorDark = Color(0xFFFFB4AB)
val WokieOnErrorDark = Color(0xFF690005)
val WokieErrorContainerDark = Color(0xFF93000A)
val WokieOnErrorContainerDark = Color(0xFFFFDAD6)
val WokieBackgroundDark = Color(0xFF0B0F11)
val WokieOnBackgroundDark = Color(0xFFDFE4E6)
val WokieSurfaceDark = Color(0xFF11181B)
val WokieOnSurfaceDark = Color(0xFFDFE4E6)
val WokieSurfaceVariantDark = Color(0xFF1D2629)
val WokieOnSurfaceVariantDark = Color(0xFFBEC9CC)
val WokieOutlineDark = Color(0xFF889397)
val WokieOutlineVariantDark = Color(0xFF3F484B)

/**
 * Semantic colours that don't map onto a Material role. Provided through
 * [LocalWokieColors] by [WokieTheme] so call sites stay scheme-aware.
 */
data class WokieColors(
    val success: Color,
    val warning: Color
)

val WokieColorsLight = WokieColors(
    success = Color(0xFF0B8043),
    warning = Color(0xFFB26A00)
)

val WokieColorsDark = WokieColors(
    success = Color(0xFF4CE08A),
    warning = Color(0xFFFFC94D)
)
