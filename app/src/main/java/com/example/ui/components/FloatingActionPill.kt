package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.LocalGlassConfig
import com.example.ui.theme.RoadColors

/**
 * Floating bottom glass pill: strictly 3 Material3 buttons (Search, Recenter, Settings).
 */
@Composable
fun FloatingActionPill(
  showLabels: Boolean,
  isFollowingUser: Boolean,
  onSearchClick: () -> Unit,
  onRecenterClick: () -> Unit,
  onSettingsClick: () -> Unit,
  modifier: Modifier = Modifier,
  buttonSize: Dp = 48.dp,
) {
  val minSize = maxOf(buttonSize, 48.dp)
  GlassSurface(
    modifier = modifier.testTag("bottom_action_pill"),
    shape = CircleShape,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PillActionButton(
        icon = Icons.Default.Search,
        label = stringResource(R.string.action_search),
        showLabel = showLabels,
        active = false,
        buttonSize = minSize,
        testTag = "pill_search_button",
        onClick = onSearchClick,
      )
      PillActionButton(
        icon = Icons.Default.MyLocation,
        label = stringResource(R.string.action_recenter),
        showLabel = showLabels,
        active = isFollowingUser,
        buttonSize = minSize,
        testTag = "pill_recenter_button",
        onClick = onRecenterClick,
      )
      PillActionButton(
        icon = Icons.Default.Settings,
        label = stringResource(R.string.action_settings),
        showLabel = showLabels,
        active = false,
        buttonSize = minSize,
        testTag = "pill_settings_button",
        onClick = onSettingsClick,
      )
    }
  }
}

/**
 * Floating Pins button with count badge at map's edge (visible only when pinCount > 0).
 */
@Composable
fun FloatingPinsButton(
  pinCount: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (pinCount <= 0) return
  val glass = LocalGlassConfig.current
  val interactionSource = rememberPressHapticInteractionSource()
  val isPressed by interactionSource.collectIsPressedAsState()
  val pressTint =
    if (isPressed) {
      if (glass.isDark) Color.White.copy(alpha = 0.16f) else Color(0x180B1220)
    } else {
      Color.Transparent
    }

  Box(modifier = modifier) {
    Button(
      onClick = {
        TouchTelemetry.recordControlHandled("floating_pins_button")
        onClick()
      },
      shape = CircleShape,
      interactionSource = interactionSource,
      colors = ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = RoadColors.PinPurple,
      ),
      elevation = null,
      contentPadding = PaddingValues(0.dp),
      modifier =
        Modifier
          .defaultMinSize(minWidth = 52.dp, minHeight = 52.dp)
          .size(52.dp)
          .testTag("floating_pins_button"),
    ) {
      GlassSurface(
        shape = CircleShape,
        tintOverlay = pressTint,
        modifier = Modifier.fillMaxSize().innerPressScale(interactionSource),
      ) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Place,
            contentDescription = stringResource(R.string.floating_pins_desc),
            tint = RoadColors.PinPurple,
            modifier = Modifier.size(26.dp),
          )
        }
      }
    }

    Box(
      modifier =
        Modifier
          .align(Alignment.TopEnd)
          .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
          .clip(CircleShape)
          .background(RoadColors.PinPurple)
          .border(1.5.dp, Color.White, CircleShape)
          .padding(horizontal = 5.dp, vertical = 1.dp)
          .testTag("floating_pins_badge"),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "$pinCount",
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun PillActionButton(
  icon: ImageVector,
  label: String,
  showLabel: Boolean,
  active: Boolean,
  buttonSize: Dp,
  testTag: String,
  onClick: () -> Unit,
) {
  val glass = LocalGlassConfig.current
  val interactionSource = rememberPressHapticInteractionSource()
  val isPressed by interactionSource.collectIsPressedAsState()

  val bg =
    when {
      active && isPressed ->
        if (glass.isDark) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 1f)
        else RoadColors.Primary.copy(alpha = 0.88f)
      active ->
        if (glass.isDark) MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (glass.reduceTransparency) 1f else 0.88f)
        else RoadColors.Primary
      isPressed -> if (glass.isDark) Color.White.copy(alpha = 0.18f) else Color(0x1A0B1220)
      else -> Color.Transparent
    }
  val borderColor =
    if (active) {
      if (glass.isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color(0x4D0A64D8)
    } else {
      Color.Transparent
    }
  val fg =
    if (active) {
      if (glass.isDark) MaterialTheme.colorScheme.primary else Color.White
    } else {
      MaterialTheme.colorScheme.onSurface
    }

  Button(
    onClick = {
      TouchTelemetry.recordControlHandled(testTag)
      onClick()
    },
    shape = CircleShape,
    interactionSource = interactionSource,
    colors = ButtonDefaults.buttonColors(
      containerColor = Color.Transparent,
      contentColor = fg,
    ),
    elevation = null,
    contentPadding = PaddingValues(0.dp),
    modifier =
      Modifier
        .defaultMinSize(minWidth = buttonSize, minHeight = buttonSize)
        .height(buttonSize)
        .widthIn(min = buttonSize)
        .testTag(testTag),
  ) {
    Row(
      modifier =
        Modifier
          .innerPressScale(interactionSource)
          .height(buttonSize)
          .widthIn(min = buttonSize)
          .clip(CircleShape)
          .background(bg, CircleShape)
          .then(
            if (borderColor != Color.Transparent) Modifier.border(BorderStroke(1.dp, borderColor), CircleShape)
            else Modifier,
          )
          .padding(horizontal = if (showLabel) 14.dp else 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = fg,
        modifier = Modifier.size(22.dp),
      )
      if (showLabel) {
        Text(
          text = label,
          color = fg,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(start = 6.dp),
        )
      }
    }
  }
}
