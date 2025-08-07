package apps.visnkmr.batu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/*
 NOTE:
 This file name Theme.kt and package apps.visnkmr.batu.ui.theme collides at build time
 with an existing Theme.kt under io.github.visnkmr.tvcalendar.ui.theme (as seen in error output),
 because both compile to the same JVM class name ThemeKt when packages are equal after refactor.
 To resolve, we keep this file but ensure no duplicate file exists in a different path/package
 that still declares package apps.visnkmr.batu.ui.theme. Remove or rename the older tvcalendar Theme/Type files.
*/

// Light scheme colors
private val primaryLight = Color(0xFF1967D2)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFDCE7FB)
private val onPrimaryContainerLight = Color(0xFF001B3A)
private val secondaryLight = Color(0xFF006C47)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFF9BEFBF)
private val onSecondaryContainerLight = Color(0xFF002113)
private val tertiaryLight = Color(0xFF7A5620)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFFDDB6)
private val onTertiaryContainerLight = Color(0xFF2A1800)
private val errorLight = Color(0xFFB3261E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF9DEDC)
private val onErrorContainerLight = Color(0xFF410E0B)
private val backgroundLight = Color(0xFFFFFBFE)
private val onBackgroundLight = Color(0xFF1C1B1F)
private val surfaceLight = Color(0xFFFFFBFE)
private val onSurfaceLight = Color(0xFF1C1B1F)
private val surfaceVariantLight = Color(0xFFE7E0EC)
private val onSurfaceVariantLight = Color(0xFF49454F)
private val outlineLight = Color(0xFF79747E)
private val outlineVariantLight = Color(0xFFCAC4D0)
private val scrimLight = Color(0x66000000)
private val inverseSurfaceLight = Color(0xFF313033)
private val inverseOnSurfaceLight = Color(0xFFF4EFF4)
private val inversePrimaryLight = Color(0xFFAAC7FF)
private val surfaceDimLight = Color(0xFFDED8E1)
private val surfaceBrightLight = Color(0xFFFFFBFE)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF7F2FA)
private val surfaceContainerLight = Color(0xFFF3EDF7)
private val surfaceContainerHighLight = Color(0xFFECE6F0)
private val surfaceContainerHighestLight = Color(0xFFE6E0E9)

// Dark scheme colors
private val primaryDark = Color(0xFFAAC7FF)
private val onPrimaryDark = Color(0xFF003063)
private val primaryContainerDark = Color(0xFF004A9B)
private val onPrimaryContainerDark = Color(0xFFDCE7FB)
private val secondaryDark = Color(0xFF6DD58C)
private val onSecondaryDark = Color(0xFF00391F)
private val secondaryContainerDark = Color(0xFF00522F)
private val onSecondaryContainerDark = Color(0xFF9BEFBF)
private val tertiaryDark = Color(0xFFEFB86A)
private val onTertiaryDark = Color(0xFF452B00)
private val tertiaryContainerDark = Color(0xFF5E3F10)
private val onTertiaryContainerDark = Color(0xFFFFDDB6)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF8C1D18)
private val onErrorContainerDark = Color(0xFFF2B8B5)
private val backgroundDark = Color(0xFF1C1B1F)
private val onBackgroundDark = Color(0xFFE6E1E5)
private val surfaceDark = Color(0xFF1C1B1F)
private val onSurfaceDark = Color(0xFFE6E1E5)
private val surfaceVariantDark = Color(0xFF49454F)
private val onSurfaceVariantDark = Color(0xFFCAC4D0)
private val outlineDark = Color(0xFF938F99)
private val outlineVariantDark = Color(0xFF49454F)
private val scrimDark = Color(0x66000000)
private val inverseSurfaceDark = Color(0xFFE6E1E5)
private val inverseOnSurfaceDark = Color(0xFF313033)
private val inversePrimaryDark = Color(0xFF1967D2)
private val surfaceDimDark = Color(0xFF141218)
private val surfaceBrightDark = Color(0xFF2B2930)
private val surfaceContainerLowestDark = Color(0xFF0F0D13)
private val surfaceContainerLowDark = Color(0xFF1D1B20)
private val surfaceContainerDark = Color(0xFF211F26)
private val surfaceContainerHighDark = Color(0xFF2B2930)
private val surfaceContainerHighestDark = Color(0xFF36343B)

private val lightScheme =
  lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
  )

private val darkScheme =
  darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
  )

@Immutable
data class CustomColors(
  val appTitleGradientColors: List<Color> = listOf(),
  val userBubbleBgColor: Color = Color.Transparent,
  val agentBubbleBgColor: Color = Color.Transparent,
  val linkColor: Color = Color.Transparent,
  val successColor: Color = Color.Transparent,
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

private val lightCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1)),
    agentBubbleBgColor = Color(0xFFe9eef6),
    userBubbleBgColor = Color(0xFF32628D),
    linkColor = Color(0xFF32628D),
    successColor = Color(0xff3d860b),
  )

private val darkCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1)),
    agentBubbleBgColor = Color(0xFF1b1c1d),
    userBubbleBgColor = Color(0xFF1f3760),
    linkColor = Color(0xFF9DCAFC),
    successColor = Color(0xFFA1CE83),
  )

val MaterialTheme.customColors: CustomColors
  @Composable @ReadOnlyComposable get() = LocalCustomColors.current

@Composable
fun StatusBarColorController(useDarkTheme: Boolean) {
  val view = LocalView.current
  val currentWindow = (view.context as? Activity)?.window

  if (currentWindow != null) {
    SideEffect {
      WindowCompat.setDecorFitsSystemWindows(currentWindow, false)
      val controller = WindowCompat.getInsetsController(currentWindow, view)
      controller.isAppearanceLightStatusBars = !useDarkTheme
    }
  }
}

@Composable
fun BatuGalleryTheme(
  useDarkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  StatusBarColorController(useDarkTheme = useDarkTheme)

  val colorScheme = if (useDarkTheme) darkScheme else lightScheme
  val customColorsPalette = if (useDarkTheme) darkCustomColors else lightCustomColors

  CompositionLocalProvider(LocalCustomColors provides customColorsPalette) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = apps.visnkmr.batu.ui.theme.BatuTypography,
      content = content
    )
  }
}
