package com.wokie.message.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Scheme-aware semantic colours (success / warning) for status UI. */
val LocalWokieColors = staticCompositionLocalOf { WokieColorsDark }

/**
 * Spacing tokens — the only paddings/margins new UI code should use, so every
 * screen breathes the same way. Based on a 4dp grid.
 */
object WokieSpacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 16.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp

    /** Horizontal inset for top-level screens. */
    val ScreenHorizontal = 16.dp
}

val WokieShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val WokieDarkColorScheme = darkColorScheme(
    primary = WokiePrimaryDark,
    onPrimary = WokieOnPrimaryDark,
    primaryContainer = WokiePrimaryContainerDark,
    onPrimaryContainer = WokieOnPrimaryContainerDark,
    secondary = WokieSecondaryDark,
    onSecondary = WokieOnSecondaryDark,
    secondaryContainer = WokieSecondaryContainerDark,
    onSecondaryContainer = WokieOnSecondaryContainerDark,
    tertiary = WokieTertiaryDark,
    onTertiary = WokieOnTertiaryDark,
    tertiaryContainer = WokieTertiaryContainerDark,
    onTertiaryContainer = WokieOnTertiaryContainerDark,
    error = WokieErrorDark,
    onError = WokieOnErrorDark,
    errorContainer = WokieErrorContainerDark,
    onErrorContainer = WokieOnErrorContainerDark,
    background = WokieBackgroundDark,
    onBackground = WokieOnBackgroundDark,
    surface = WokieSurfaceDark,
    onSurface = WokieOnSurfaceDark,
    surfaceVariant = WokieSurfaceVariantDark,
    onSurfaceVariant = WokieOnSurfaceVariantDark,
    outline = WokieOutlineDark,
    outlineVariant = WokieOutlineVariantDark
)

private val WokieLightColorScheme = lightColorScheme(
    primary = WokiePrimaryLight,
    onPrimary = WokieOnPrimaryLight,
    primaryContainer = WokiePrimaryContainerLight,
    onPrimaryContainer = WokieOnPrimaryContainerLight,
    secondary = WokieSecondaryLight,
    onSecondary = WokieOnSecondaryLight,
    secondaryContainer = WokieSecondaryContainerLight,
    onSecondaryContainer = WokieOnSecondaryContainerLight,
    tertiary = WokieTertiaryLight,
    onTertiary = WokieOnTertiaryLight,
    tertiaryContainer = WokieTertiaryContainerLight,
    onTertiaryContainer = WokieOnTertiaryContainerLight,
    error = WokieErrorLight,
    onError = WokieOnErrorLight,
    errorContainer = WokieErrorContainerLight,
    onErrorContainer = WokieOnErrorContainerLight,
    background = WokieBackgroundLight,
    onBackground = WokieOnBackgroundLight,
    surface = WokieSurfaceLight,
    onSurface = WokieOnSurfaceLight,
    surfaceVariant = WokieSurfaceVariantLight,
    onSurfaceVariant = WokieOnSurfaceVariantLight,
    outline = WokieOutlineLight,
    outlineVariant = WokieOutlineVariantLight
)

/**
 * Root theme for Wokie. Follows the system dark/light setting; the dark
 * scheme is the first-class citizen (field/night use) and the light scheme is
 * a full brand scheme, not an inversion afterthought.
 */
@Composable
fun WokieTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) WokieDarkColorScheme else WokieLightColorScheme
    val wokieColors = if (darkTheme) WokieColorsDark else WokieColorsLight
    CompositionLocalProvider(LocalWokieColors provides wokieColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WokieTypography,
            shapes = WokieShapes,
            content = content
        )
    }
}
