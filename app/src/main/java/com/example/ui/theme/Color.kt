package com.example.ui.theme

import androidx.compose.ui.graphics.Color

object RoadColors {
  val Primary = Color(0xFF0A64D8)
  val PrimaryContainerLight = Color(0xFFDCEBFF)
  val PrimaryContainerDark = Color(0xFF133A73)
  val PinPurple = Color(0xFF7B3FE4)

  val StatusOpen = Color(0xFF1E8E3E)
  val StatusOpenBgLight = Color(0xFFE6F4EA)
  val StatusOpenBgDark = Color(0xFF123820)

  val StatusUnverified = Color(0xFFB26A00)
  val StatusUnverifiedBgLight = Color(0xFFFEF3C7)
  val StatusUnverifiedBgDark = Color(0xFF3D2807)

  val StatusBlocked = Color(0xFFC5221F)
  val StatusBlockedBgLight = Color(0xFFFCE8E6)
  val StatusBlockedBgDark = Color(0xFF471312)

  val PinPalette = listOf(
    Color(0xFFF28C28), // Orange
    Color(0xFF2F6FE4), // Blue
    Color(0xFF0F9D9A), // Teal
    Color(0xFF34A853), // Green
  )

  fun pinColorForIndex(featureIndex: Int): Color {
    val idx = (featureIndex - 1).coerceAtLeast(0) % PinPalette.size
    return PinPalette[idx]
  }

  // Light Mode Surfaces (cool gray-blue #EEF2F8 92%, high-contrast ink)
  val LightBackground = Color(0xFFEDEFF3)
  val LightCardSurface = Color(0xEBEEF2F8)
  val LightCardElevated = Color(0xD9FFFFFF)
  val LightSegmentedTrack = Color(0xFFDDE3EC)
  val LightInnerCardBorder = Color(0xFFD5DCE8)
  val LightTextPrimary = Color(0xFF0B1220)
  val LightTextSecondary = Color(0xFF3C4656)
  val LightDivider = Color(0xFFD5DCE8)

  // Night-Driving Dark Mode Surfaces (94% card opacity)
  val DarkBackground = Color(0xFF060B14)
  val DarkCardSurface = Color(0xF00F172A)
  val DarkCardElevated = Color(0xFF1E293B)
  val DarkTextPrimary = Color(0xFFF8FAFC)
  val DarkTextSecondary = Color(0xFFCBD5E1)
  val DarkDivider = Color(0xFF334155)
}
