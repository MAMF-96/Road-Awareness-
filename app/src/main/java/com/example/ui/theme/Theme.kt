package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.PanelStyle
import com.example.data.model.ThemePreference

data class RoadExtendedColors(
  val isDark: Boolean,
  val isMapDark: Boolean = isDark,
  val cardSurface: Color,
  val cardElevated: Color,
  val divider: Color,
  val openText: Color,
  val openBg: Color,
  val unverifiedText: Color,
  val unverifiedBg: Color,
  val blockedText: Color,
  val blockedBg: Color,
  val schematicSkyTop: Color,
  val schematicSkyBottom: Color,
  val schematicRoadFill: Color,
  val schematicRoadCasing: Color,
)

val LocalRoadColors =
  staticCompositionLocalOf {
    RoadExtendedColors(
      isDark = true,
      isMapDark = true,
      cardSurface = RoadColors.DarkCardSurface,
      cardElevated = RoadColors.DarkCardElevated,
      divider = RoadColors.DarkDivider,
      openText = Color(0xFF6FE393),
      openBg = RoadColors.StatusOpenBgDark,
      unverifiedText = Color(0xFFFFC14D),
      unverifiedBg = RoadColors.StatusUnverifiedBgDark,
      blockedText = Color(0xFFFF7A76),
      blockedBg = RoadColors.StatusBlockedBgDark,
      schematicSkyTop = Color(0xFF132238),
      schematicSkyBottom = Color(0xFF0B1320),
      schematicRoadFill = Color(0xFF334155),
      schematicRoadCasing = Color(0xFF64748B),
    )
  }

@Composable
fun resolveIsMapDark(themePreference: ThemePreference): Boolean =
  when (themePreference) {
    ThemePreference.AUTO -> isSystemInDarkTheme()
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
  }

private val LightScheme =
  lightColorScheme(
    primary = RoadColors.Primary,
    onPrimary = Color.White,
    primaryContainer = RoadColors.PrimaryContainerLight,
    onPrimaryContainer = Color(0xFF002B66),
    background = RoadColors.LightBackground,
    onBackground = RoadColors.LightTextPrimary,
    surface = RoadColors.LightCardSurface,
    onSurface = RoadColors.LightTextPrimary,
    surfaceVariant = RoadColors.LightCardElevated,
    onSurfaceVariant = RoadColors.LightTextSecondary,
    outline = RoadColors.LightDivider,
  )

private val DarkScheme =
  darkColorScheme(
    primary = Color(0xFF5FA3FF),
    onPrimary = Color(0xFF001C42),
    primaryContainer = RoadColors.PrimaryContainerDark,
    onPrimaryContainer = Color(0xFFDCEBFF),
    background = RoadColors.DarkBackground,
    onBackground = RoadColors.DarkTextPrimary,
    surface = RoadColors.DarkCardSurface,
    onSurface = RoadColors.DarkTextPrimary,
    surfaceVariant = RoadColors.DarkCardElevated,
    onSurfaceVariant = RoadColors.DarkTextSecondary,
    outline = RoadColors.DarkDivider,
  )

private val RoadShapes =
  Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
  )

@Composable
fun RoadAwarenessTheme(
  themePreference: ThemePreference = ThemePreference.AUTO,
  panelStyle: PanelStyle = PanelStyle.ALWAYS_DARK,
  content: @Composable () -> Unit,
) {
  val isMapDark = resolveIsMapDark(themePreference)
  val useDark =
    when (panelStyle) {
      PanelStyle.ALWAYS_DARK -> true
      PanelStyle.FOLLOW_MAP -> isMapDark
    }

  val extended =
    if (useDark) {
      RoadExtendedColors(
        isDark = true,
        isMapDark = isMapDark,
        cardSurface = RoadColors.DarkCardSurface,
        cardElevated = RoadColors.DarkCardElevated,
        divider = RoadColors.DarkDivider,
        openText = Color(0xFF6FE393),
        openBg = RoadColors.StatusOpenBgDark,
        unverifiedText = Color(0xFFFFC14D),
        unverifiedBg = RoadColors.StatusUnverifiedBgDark,
        blockedText = Color(0xFFFF7A76),
        blockedBg = RoadColors.StatusBlockedBgDark,
        schematicSkyTop = Color(0xFF132238),
        schematicSkyBottom = Color(0xFF0B1320),
        schematicRoadFill = Color(0xFF334155),
        schematicRoadCasing = Color(0xFF64748B),
      )
    } else {
      RoadExtendedColors(
        isDark = false,
        isMapDark = isMapDark,
        cardSurface = RoadColors.LightCardSurface,
        cardElevated = RoadColors.LightCardElevated,
        divider = RoadColors.LightDivider,
        openText = RoadColors.StatusOpen,
        openBg = RoadColors.StatusOpenBgLight,
        unverifiedText = RoadColors.StatusUnverified,
        unverifiedBg = RoadColors.StatusUnverifiedBgLight,
        blockedText = RoadColors.StatusBlocked,
        blockedBg = RoadColors.StatusBlockedBgLight,
        schematicSkyTop = Color(0xFFDCEBFF),
        schematicSkyBottom = Color(0xFFF3F7FC),
        schematicRoadFill = Color(0xFF616A78),
        schematicRoadCasing = Color(0xFFFFFFFF),
      )
    }

  CompositionLocalProvider(LocalRoadColors provides extended) {
    MaterialTheme(
      colorScheme = if (useDark) DarkScheme else LightScheme,
      typography = RoadTypography,
      shapes = RoadShapes,
      content = content,
    )
  }
}
