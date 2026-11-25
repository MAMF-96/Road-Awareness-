package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.R
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DeveloperOrientation
import com.example.data.model.FeatureCensusEntry
import com.example.data.model.FeatureType
import com.example.data.model.LandscapePanelSide
import com.example.data.model.PanelStyle
import com.example.data.model.RelativeDirection
import com.example.data.model.TestSpot
import com.example.data.model.ThemePreference
import com.example.data.model.TravelMode
import com.example.data.model.UnitPreference
import com.example.domain.GeoMath
import com.example.ui.InspectorSheetDetent
import com.example.ui.RoadHudUiState
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.GlassTokens
import com.example.ui.theme.LocalRoadColors
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
  uiState: RoadHudUiState,
  onSelectTheme: (ThemePreference) -> Unit,
  onSelectPanelStyle: (PanelStyle) -> Unit = {},
  onToggleExperimentalBlur: (Boolean) -> Unit,
  onToggleReduceTransparency: (Boolean) -> Unit,
  onToggleArabicTts: (Boolean) -> Unit,
  onToggleShowInspector: (Boolean) -> Unit,
  onToggleMockMode: (Boolean) -> Unit,
  onOfflineOverrideChange: (Boolean) -> Unit = {},
  onSelectDeveloperOrientation: (DeveloperOrientation) -> Unit = {},
  onSelectLandscapePanelSide: (LandscapePanelSide) -> Unit = {},
  onSelectUnitPreference: (UnitPreference) -> Unit = {},
  onSelectHorizon: (Double) -> Unit = {},
  onToggleShowMinorJunctions: (Boolean) -> Unit = {},
  onToggleLandmarksAhead: (Boolean) -> Unit = {},
  onToggleShowGraphEdges: (Boolean) -> Unit = {},
  onResetDebugOverrides: () -> Unit,
  onClearCache: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val roadColors = LocalRoadColors.current
  val layoutDir = LocalLayoutDirection.current
  val effectiveLandscapeSide =
    uiState.settings.landscapePanelSide
      ?: if (layoutDir == LayoutDirection.Rtl) LandscapePanelSide.RIGHT else LandscapePanelSide.LEFT

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = if (roadColors.isDark) BottomSheetDefaults.ContainerColor else GlassTokens.LightSolidSurface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = stringResource(R.string.settings_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )

      // Appearance: Theme + Experimental Glass Blur + Reduce Transparency
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = stringResource(R.string.settings_theme),
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ThemeOptionChip(
            label = stringResource(R.string.theme_auto),
            selected = uiState.settings.themePreference == ThemePreference.AUTO,
            onClick = { onSelectTheme(ThemePreference.AUTO) },
            modifier = Modifier.weight(1f).testTag("theme_auto_chip"),
          )
          ThemeOptionChip(
            label = stringResource(R.string.theme_light),
            selected = uiState.settings.themePreference == ThemePreference.LIGHT,
            onClick = { onSelectTheme(ThemePreference.LIGHT) },
            modifier = Modifier.weight(1f).testTag("theme_light_chip"),
          )
          ThemeOptionChip(
            label = stringResource(R.string.theme_dark),
            selected = uiState.settings.themePreference == ThemePreference.DARK,
            onClick = { onSelectTheme(ThemePreference.DARK) },
            modifier = Modifier.weight(1f).testTag("theme_dark_chip"),
          )
        }

        Text(
          text = stringResource(R.string.settings_panel_style),
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ThemeOptionChip(
            label = stringResource(R.string.panel_style_always_dark),
            selected = uiState.settings.panelStyle == PanelStyle.ALWAYS_DARK,
            onClick = { onSelectPanelStyle(PanelStyle.ALWAYS_DARK) },
            modifier = Modifier.weight(1f).testTag("panel_style_always_dark_chip"),
          )
          ThemeOptionChip(
            label = stringResource(R.string.panel_style_follow_map),
            selected = uiState.settings.panelStyle == PanelStyle.FOLLOW_MAP,
            onClick = { onSelectPanelStyle(PanelStyle.FOLLOW_MAP) },
            modifier = Modifier.weight(1f).testTag("panel_style_follow_map_chip"),
          )
        }

        SettingsSwitchRow(
          title = stringResource(R.string.settings_glass_blur_title),
          subtitle = stringResource(R.string.settings_glass_blur_subtitle),
          checked = uiState.settings.experimentalBlurEnabled,
          onCheckedChange = onToggleExperimentalBlur,
          switchTestTag = "experimental_blur_switch",
        )

        SettingsSwitchRow(
          title = stringResource(R.string.settings_reduce_transparency_title),
          subtitle = stringResource(R.string.settings_reduce_transparency_subtitle),
          checked = uiState.settings.reduceTransparencyEnabled,
          onCheckedChange = onToggleReduceTransparency,
          switchTestTag = "reduce_transparency_switch",
        )
      }

      HorizontalDivider(color = roadColors.divider)

      // Units: Auto / Metric / Imperial
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = stringResource(R.string.settings_units),
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ThemeOptionChip(
            label = stringResource(R.string.units_auto),
            selected = uiState.settings.unitPreference == UnitPreference.AUTO,
            onClick = { onSelectUnitPreference(UnitPreference.AUTO) },
            modifier = Modifier.weight(1f).testTag("units_auto_chip"),
          )
          ThemeOptionChip(
            label = stringResource(R.string.units_metric),
            selected = uiState.settings.unitPreference == UnitPreference.METRIC,
            onClick = { onSelectUnitPreference(UnitPreference.METRIC) },
            modifier = Modifier.weight(1f).testTag("units_metric_chip"),
          )
          ThemeOptionChip(
            label = stringResource(R.string.units_imperial),
            selected = uiState.settings.unitPreference == UnitPreference.IMPERIAL,
            onClick = { onSelectUnitPreference(UnitPreference.IMPERIAL) },
            modifier = Modifier.weight(1f).testTag("units_imperial_chip"),
          )
        }
      }

      HorizontalDivider(color = roadColors.divider)

      // Road-ahead horizon (1 / 2 / 3 / 5 km) + minor junctions + landmarks ahead
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = stringResource(R.string.settings_horizon),
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          listOf(1000.0, 2000.0, 3000.0, 5000.0).forEach { meters ->
            val kmInt = (meters / 1000.0).toInt()
            val selected = kotlin.math.abs(uiState.settings.horizonDistanceMeters - meters) < 100.0
            ThemeOptionChip(
              label = formatRoadDistance(meters, uiState.effectiveUnitSystem),
              selected = selected,
              onClick = { onSelectHorizon(meters) },
              modifier = Modifier.weight(1f).testTag("horizon_option_${kmInt}km"),
            )
          }
        }

        SettingsSwitchRow(
          title = stringResource(R.string.settings_show_minor_junctions),
          subtitle = stringResource(R.string.settings_show_minor_junctions_sub),
          checked = uiState.settings.showMinorJunctions,
          onCheckedChange = onToggleShowMinorJunctions,
          switchTestTag = "show_minor_junctions_switch",
        )

        SettingsSwitchRow(
          title = stringResource(R.string.settings_landmarks_ahead),
          subtitle = stringResource(R.string.settings_landmarks_ahead_sub),
          checked = uiState.settings.landmarksAheadEnabled,
          onCheckedChange = onToggleLandmarksAhead,
          switchTestTag = "landmarks_ahead_switch",
        )
      }

      HorizontalDivider(color = roadColors.divider)

      // Layout: Landscape panel side (Left / Right)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = stringResource(R.string.settings_layout_title),
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.settings_landscape_panel_side),
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ThemeOptionChip(
            label = stringResource(R.string.side_left),
            selected = effectiveLandscapeSide == LandscapePanelSide.LEFT,
            onClick = { onSelectLandscapePanelSide(LandscapePanelSide.LEFT) },
            modifier = Modifier.weight(1f).testTag("layout_side_left"),
          )
          ThemeOptionChip(
            label = stringResource(R.string.side_right),
            selected = effectiveLandscapeSide == LandscapePanelSide.RIGHT,
            onClick = { onSelectLandscapePanelSide(LandscapePanelSide.RIGHT) },
            modifier = Modifier.weight(1f).testTag("layout_side_right"),
          )
        }
      }

      HorizontalDivider(color = roadColors.divider)

      // Optional Arabic TTS (Default OFF)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.settings_tts_title),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = stringResource(R.string.settings_tts_subtitle),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        val ttsInteraction = rememberPressHapticInteractionSource()
        Switch(
          checked = uiState.settings.arabicTtsEnabled,
          onCheckedChange = {
            TouchTelemetry.recordControlHandled("tts_switch")
            onToggleArabicTts(it)
          },
          interactionSource = ttsInteraction,
          modifier = Modifier.testTag("tts_switch"),
        )
      }

      // Debug-only Developer section (not compiled into release UI)
      if (BuildConfig.DEBUG) {
        HorizontalDivider(color = roadColors.divider)

        Column(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.testTag("settings_developer_section"),
        ) {
          Text(
            text = stringResource(R.string.settings_developer_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )

          SettingsSwitchRow(
            title = stringResource(R.string.settings_show_inspector),
            subtitle = stringResource(R.string.settings_inspector_subtitle),
            checked = uiState.isInspectorOpen,
            onCheckedChange = onToggleShowInspector,
            switchTestTag = "show_inspector_switch",
          )

          SettingsSwitchRow(
            title = stringResource(R.string.settings_mock_mode),
            subtitle = null,
            checked = uiState.isMockModeActive,
            onCheckedChange = onToggleMockMode,
            switchTestTag = "mock_mode_switch",
          )

          SettingsSwitchRow(
            title = stringResource(R.string.settings_offline_mode),
            subtitle = null,
            checked = uiState.debugOffline,
            onCheckedChange = onOfflineOverrideChange,
            switchTestTag = "settings_offline_switch",
          )

          SettingsSwitchRow(
            title = stringResource(R.string.inspector_show_graph_edges),
            subtitle = null,
            checked = uiState.settings.showGraphEdges,
            onCheckedChange = onToggleShowGraphEdges,
            switchTestTag = "inspector_show_graph_edges",
          )

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = stringResource(R.string.settings_dev_orientation),
              fontSize = 14.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              ThemeOptionChip(
                label = stringResource(R.string.orientation_auto),
                selected = uiState.settings.developerOrientation == DeveloperOrientation.AUTO,
                onClick = { onSelectDeveloperOrientation(DeveloperOrientation.AUTO) },
                modifier = Modifier.weight(1f).testTag("dev_orientation_auto"),
              )
              ThemeOptionChip(
                label = stringResource(R.string.orientation_landscape),
                selected = uiState.settings.developerOrientation == DeveloperOrientation.LANDSCAPE,
                onClick = { onSelectDeveloperOrientation(DeveloperOrientation.LANDSCAPE) },
                modifier = Modifier.weight(1f).testTag("dev_orientation_landscape"),
              )
              ThemeOptionChip(
                label = stringResource(R.string.orientation_portrait),
                selected = uiState.settings.developerOrientation == DeveloperOrientation.PORTRAIT,
                onClick = { onSelectDeveloperOrientation(DeveloperOrientation.PORTRAIT) },
                modifier = Modifier.weight(1f).testTag("dev_orientation_portrait"),
              )
            }
          }

          val resetInteraction = rememberPressHapticInteractionSource()
          OutlinedButton(
            onClick = {
              TouchTelemetry.recordControlHandled("reset_overrides_button")
              onResetDebugOverrides()
            },
            enabled = uiState.hasAnyDebugOverride,
            interactionSource = resetInteraction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reset_overrides_button"),
          ) {
            Text(
              text = stringResource(R.string.settings_reset_overrides),
              modifier = Modifier.innerPressScale(resetInteraction),
            )
          }
        }
      }

      HorizontalDivider(color = roadColors.divider)

      // Cache clear
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = formatCacheStatsText(uiState),
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("settings_cache_stats"),
        )
        val clearCacheInteraction = rememberPressHapticInteractionSource()
        OutlinedButton(
          onClick = {
            TouchTelemetry.recordControlHandled("clear_cache_button")
            onClearCache()
          },
          interactionSource = clearCacheInteraction,
          modifier = Modifier.heightIn(min = 48.dp).testTag("clear_cache_button"),
        ) {
          Row(
            modifier = Modifier.innerPressScale(clearCacheInteraction),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null)
            Text(
              text = stringResource(R.string.settings_clear_cache),
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  subtitle: String?,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  switchTestTag: String,
) {
  val switchInteraction = rememberPressHapticInteractionSource()
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Switch(
      checked = checked,
      onCheckedChange = {
        TouchTelemetry.recordControlHandled(switchTestTag)
        onCheckedChange(it)
      },
      interactionSource = switchInteraction,
      modifier = Modifier.testTag(switchTestTag),
    )
  }
}

@Composable
private fun CompactDebugSwitchRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  switchTestTag: String,
) {
  val switchInteraction = rememberPressHapticInteractionSource()
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f).padding(end = 8.dp),
    )
    Switch(
      checked = checked,
      onCheckedChange = {
        TouchTelemetry.recordControlHandled(switchTestTag)
        onCheckedChange(it)
      },
      interactionSource = switchInteraction,
      modifier = Modifier.testTag(switchTestTag),
    )
  }
}

@Composable
private fun ThemeOptionChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()
  val bg =
    when {
      selected && pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
      selected -> MaterialTheme.colorScheme.primary
      pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
      else -> MaterialTheme.colorScheme.surfaceVariant
    }
  val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  Button(
    onClick = {
      TouchTelemetry.recordControlHandled("theme_or_layout_chip_$label")
      onClick()
    },
    interactionSource = interactionSource,
    shape = CircleShape,
    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
    elevation = null,
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    modifier = modifier.height(48.dp),
  ) {
    Text(
      text = label,
      color = fg,
      fontWeight = FontWeight.SemiBold,
      fontSize = 15.sp,
      maxLines = 1,
      modifier = Modifier.innerPressScale(interactionSource),
    )
  }
}

/**
 * Debug-only non-modal 3-state draggable Liquid Glass Graph Inspector bottom sheet:
 * - COLLAPSED (48dp): single summary bar
 * - HALF ("Controls"): heading slider + snap, along-road steppers, mode/flags + telemetry
 * - FULL ("Details"): touch telemetry, 3x3 Tap test grid & Slider, status, simulation, Overpass logs, features
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GraphInspectorBottomSheet(
  uiState: RoadHudUiState,
  detent: InspectorSheetDetent,
  maxScreenHeight: Dp,
  windowWidthDp: Int = 0,
  windowHeightDp: Int = 0,
  windowSizeClassSummary: String = "",
  onDetentChange: (InspectorSheetDetent) -> Unit,
  onHeadingChange: (Double) -> Unit,
  onSnapHeadingToRoad: () -> Unit,
  onToggleMockMode: (Boolean) -> Unit,
  onStepAlongRoad: (Double) -> Unit = {},
  onToggleSimulateDrive: () -> Unit = {},
  onMockSpeedChange: (Double) -> Unit = {},
  onMockAccuracyChange: (Float) -> Unit = {},
  onMockJitterChange: (Boolean) -> Unit = {},
  onSaveSpot: (String) -> Unit = {},
  onLoadSpot: (TestSpot) -> Unit = {},
  onDeleteSpot: (String) -> Unit = {},
  onRestoreDefaultSpots: () -> Unit = {},
  onDebugFlagsChange: (poorGps: Boolean, offline: Boolean, uncertain: Boolean) -> Unit,
  onDebugNetworkSimulationChange: (
    blockOverpass: Boolean,
    blockTiles: Boolean,
    slowNetwork: Boolean,
    forceOverpassError: Int?,
  ) -> Unit = { _, _, _, _ -> },
  onClearCache: () -> Unit = {},
  onTestOverpass: () -> Unit = {},
  onPingOverpass: () -> Unit = {},
  onReloadRoadData: () -> Unit = {},
  onResetDebugOverrides: () -> Unit = {},
  onJumpToCensusFeature: (FeatureCensusEntry) -> Unit = {},
  onSaveCensusAsSpot: (FeatureCensusEntry) -> Unit = {},
  onReportCopied: () -> Unit = {},
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  maxFullFraction: Float = 0.72f,
) {
  val roadColors = LocalRoadColors.current
  val clipboardManager = LocalClipboardManager.current
  var copiedRecently by remember { mutableStateOf(false) }
  var showReportDialog by rememberSaveable { mutableStateOf(false) }
  var showSaveSpotDialog by rememberSaveable { mutableStateOf(false) }
  var spotNameInput by rememberSaveable { mutableStateOf("") }

  LaunchedEffect(copiedRecently) {
    if (copiedRecently) {
      delay(1500)
      copiedRecently = false
    }
  }

  val sheetMaxHeight =
    when (detent) {
      InspectorSheetDetent.COLLAPSED -> 48.dp
      InspectorSheetDetent.HALF -> (maxScreenHeight * 0.45f).coerceIn(212.dp, 340.dp)
      InspectorSheetDetent.FULL -> (maxScreenHeight * maxFullFraction).coerceAtLeast(minOf(260.dp, maxScreenHeight * maxFullFraction))
    }

  val headingDeg = ((uiState.location?.headingDeg ?: 0.0).roundToInt() + 360) % 360
  val primary = uiState.matchResult.primary
  val offRoad = uiState.offRoadState
  val selectedOffRoad = offRoad.selectedCandidate
  val currentRoadLabel =
    primary?.name?.let { displayNameOrRef(it) }
      ?: selectedOffRoad?.candidate?.name?.let { displayNameOrRef(it) }
      ?: if (offRoad.isOffRoad) "OFF-ROAD" else "unknown"
  val firstFeature = uiState.features.firstOrNull()
  val nextSummary =
    when {
      firstFeature != null -> "${shortFeatureTypeName(firstFeature.effectiveDisplayType())} ${firstFeature.distanceMeters.toInt()} m"
      offRoad.isOffRoad && selectedOffRoad == null -> "${offRoad.candidates.size} nearest"
      else -> "none"
    }
  val modeTag = if (uiState.isMockModeActive) "MOCK" else "LIVE"
  val (collapsedSpeedVal, collapsedSpeedUnit) =
    GeoMath.formatSpeedValueAndUnit(uiState.mockSpeedKmh, uiState.effectiveUnitSystem)
  val speedPart = if (uiState.isMockModeActive) " · $collapsedSpeedVal$collapsedSpeedUnit" else ""
  val collapsedLine = "$modeTag$speedPart · $headingDeg° · $currentRoadLabel · next: $nextSummary"
  val diagnosticReason = computeDiagnosticReason(uiState)

  var dragAccum by remember(detent) { mutableFloatStateOf(0f) }

  GlassSurface(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(max = sheetMaxHeight)
        .animateContentSize(animationSpec = spring(stiffness = 650f))
        .testTag("graph_inspector_sheet"),
    shape = RoundedCornerShape(24.dp),
    highOpacity = true,
  ) {
    when (detent) {
      InspectorSheetDetent.COLLAPSED -> {
        val barInteraction = rememberPressHapticInteractionSource()
        val closeInteraction = rememberPressHapticInteractionSource()
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .padding(start = 4.dp, end = 4.dp)
              .testTag("inspector_collapsed_bar"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          TextButton(
            onClick = {
              TouchTelemetry.recordControlHandled("inspector_collapsed_bar")
              onDetentChange(InspectorSheetDetent.HALF)
            },
            interactionSource = barInteraction,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().innerPressScale(barInteraction),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              if (uiState.isMockModeActive) {
                Box(
                  modifier =
                    Modifier
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.primaryContainer)
                      .padding(horizontal = 7.dp, vertical = 2.dp)
                      .testTag("inspector_mock_badge"),
                ) {
                  Text(
                    text = stringResource(R.string.inspector_mode_mock),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                  )
                }
              }
              Text(
                text = collapsedLine,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (uiState.hasAnyDebugOverride) {
                DebugOverrideBadge()
              }
            }
          }
          IconButton(
            onClick = {
              TouchTelemetry.recordControlHandled("close_inspector_button")
              onClose()
            },
            interactionSource = closeInteraction,
            modifier = Modifier.size(48.dp).testTag("close_inspector_button"),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.action_close),
              modifier = Modifier.innerPressScale(closeInteraction).size(18.dp),
            )
          }
        }
      }

      InspectorSheetDetent.HALF -> {
        val handleInteraction = rememberPressHapticInteractionSource()
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 2.dp)
              .testTag("inspector_controls_container"),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          // 48dp drag handle (vertical drags restricted strictly to this handle)
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(
                  interactionSource = handleInteraction,
                  indication = null,
                ) {
                  TouchTelemetry.recordControlHandled("inspector_drag_handle")
                  onDetentChange(InspectorSheetDetent.FULL)
                }
                .pointerInput(detent) {
                  detectVerticalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f },
                    onDragEnd = {
                      when {
                        dragAccum < -18f -> {
                          TouchTelemetry.recordControlHandled("inspector_drag_handle")
                          onDetentChange(InspectorSheetDetent.FULL)
                        }
                        dragAccum > 18f -> {
                          TouchTelemetry.recordControlHandled("inspector_drag_handle")
                          onDetentChange(InspectorSheetDetent.COLLAPSED)
                        }
                      }
                      dragAccum = 0f
                    },
                    onVerticalDrag = { _, dy -> dragAccum += dy },
                  )
                }
                .testTag("inspector_drag_handle"),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier =
                Modifier
                  .innerPressScale(handleInteraction)
                  .size(width = 40.dp, height = 4.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)),
            )
          }

          // Row 1: Continuous heading Slider (0-359°) + current degree + Snap (48dp touch height, zero parent drag/scroll)
          var isDraggingSlider by remember { mutableStateOf(false) }
          var sliderFloat by remember { mutableFloatStateOf(headingDeg.toFloat()) }
          LaunchedEffect(headingDeg) {
            if (!isDraggingSlider) {
              sliderFloat = headingDeg.toFloat()
            }
          }
          val displayedHeading = sliderFloat.roundToInt().coerceIn(0, 359)
          val headingSliderInteraction = rememberPressHapticInteractionSource()

          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("inspector_heading_slider_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Slider(
              value = sliderFloat,
              onValueChange = { newVal ->
                isDraggingSlider = true
                sliderFloat = newVal
                TouchTelemetry.recordControlHandled("inspector_heading_slider")
                onHeadingChange(newVal.roundToInt().toDouble())
              },
              onValueChangeFinished = {
                isDraggingSlider = false
              },
              valueRange = 0f..359f,
              interactionSource = headingSliderInteraction,
              modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inspector_heading_slider"),
            )
            Text(
              text = "$displayedHeading°",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.width(36.dp).testTag("inspector_heading_value"),
            )
            InspectorMiniChip(
              label = stringResource(R.string.inspector_snap_road),
              active = false,
              enabled = primary != null || selectedOffRoad != null,
              controlId = "inspector_snap_road_button",
              onClick = onSnapHeadingToRoad,
              modifier = Modifier.testTag("inspector_snap_road_button"),
            )
          }

          // Row 2: Play/Pause + Speed preset + Along road (-50m -10m +10m +50m) + Save spot (FlowRow, no horizontalScroll)
          FlowRow(
            modifier = Modifier.fillMaxWidth().testTag("inspector_along_road_row"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            InspectorMiniChip(
              label = if (uiState.isSimulatingDrive) "⏸" else "▶",
              active = uiState.isSimulatingDrive,
              enabled = uiState.isMockModeActive,
              controlId = "inspector_half_play_pause",
              onClick = onToggleSimulateDrive,
              modifier = Modifier.testTag("inspector_half_play_pause"),
            )
            val isImperial = uiState.effectiveUnitSystem == com.example.data.model.UnitSystem.IMPERIAL
            val (halfSpdVal, halfSpdUnit) =
              GeoMath.formatSpeedValueAndUnit(uiState.mockSpeedKmh, uiState.effectiveUnitSystem)
            InspectorMiniChip(
              label = "$halfSpdVal $halfSpdUnit",
              active = false,
              enabled = uiState.isMockModeActive,
              controlId = "inspector_half_speed_cycle",
              onClick = {
                val presetsKmh =
                  if (isImperial) listOf(3, 20, 40, 55).map { it * 1.60934 }
                  else listOf(5.0, 30.0, 60.0, 90.0)
                val next = presetsKmh.firstOrNull { it > uiState.mockSpeedKmh + 1.5 } ?: presetsKmh.first()
                onMockSpeedChange(next)
              },
              modifier = Modifier.testTag("inspector_half_speed_cycle"),
            )
            InspectorMiniChip(
              label = "-50m",
              active = false,
              enabled = uiState.isMockModeActive,
              controlId = "inspector_half_step_-50",
              onClick = { onStepAlongRoad(-50.0) },
              modifier = Modifier.testTag("inspector_half_step_-50"),
            )
            InspectorMiniChip(
              label = "-10m",
              active = false,
              enabled = uiState.isMockModeActive,
              controlId = "inspector_half_step_-10",
              onClick = { onStepAlongRoad(-10.0) },
              modifier = Modifier.testTag("inspector_half_step_-10"),
            )
            InspectorMiniChip(
              label = "+10m",
              active = false,
              enabled = uiState.isMockModeActive,
              controlId = "inspector_half_step_+10",
              onClick = { onStepAlongRoad(10.0) },
              modifier = Modifier.testTag("inspector_half_step_+10"),
            )
            InspectorMiniChip(
              label = "+50m",
              active = false,
              enabled = uiState.isMockModeActive,
              controlId = "inspector_half_step_+50",
              onClick = { onStepAlongRoad(50.0) },
              modifier = Modifier.testTag("inspector_half_step_+50"),
            )
            InspectorMiniChip(
              label = stringResource(R.string.inspector_spot_save_short),
              active = false,
              controlId = "inspector_half_save_spot",
              onClick = {
                spotNameInput = currentRoadLabel.takeIf { it != "unknown" && it != "OFF-ROAD" } ?: ""
                showSaveSpotDialog = true
              },
              modifier = Modifier.testTag("inspector_half_save_spot"),
            )
          }

          // Row 3: Mock/Live + Jitter + debug override chips (FlowRow) + Details + Copy + Close X
          Row(
            modifier = Modifier.fillMaxWidth().testTag("inspector_controls_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            FlowRow(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(4.dp),
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              val mockSwitchInteraction = rememberPressHapticInteractionSource()
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text =
                    if (uiState.isMockModeActive) stringResource(R.string.inspector_mode_mock)
                    else stringResource(R.string.inspector_mode_live),
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color =
                    if (uiState.isMockModeActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                  checked = uiState.isMockModeActive,
                  onCheckedChange = {
                    TouchTelemetry.recordControlHandled("inspector_mock_toggle")
                    onToggleMockMode(it)
                  },
                  interactionSource = mockSwitchInteraction,
                  modifier = Modifier.testTag("inspector_mock_toggle"),
                )
              }

              InspectorMiniChip(
                label = stringResource(R.string.inspector_jitter),
                active = uiState.mockJitterEnabled,
                enabled = uiState.isMockModeActive,
                controlId = "inspector_flag_jitter",
                onClick = { onMockJitterChange(!uiState.mockJitterEnabled) },
                modifier = Modifier.testTag("inspector_flag_jitter"),
              )
              InspectorMiniChip(
                label = "GPS>15m",
                active = uiState.debugPoorGps,
                controlId = "inspector_flag_gps",
                onClick = {
                  onDebugFlagsChange(!uiState.debugPoorGps, uiState.debugOffline, uiState.debugUncertain)
                },
                modifier = Modifier.testTag("inspector_flag_gps"),
              )
              InspectorMiniChip(
                label = "Offline",
                active = uiState.debugOffline,
                controlId = "inspector_flag_offline",
                onClick = {
                  onDebugFlagsChange(uiState.debugPoorGps, !uiState.debugOffline, uiState.debugUncertain)
                },
                modifier = Modifier.testTag("inspector_flag_offline"),
              )
              InspectorMiniChip(
                label = "Uncertain",
                active = uiState.debugUncertain,
                controlId = "inspector_flag_uncertain",
                onClick = {
                  onDebugFlagsChange(uiState.debugPoorGps, uiState.debugOffline, !uiState.debugUncertain)
                },
                modifier = Modifier.testTag("inspector_flag_uncertain"),
              )
              if (uiState.hasAnyDebugOverride) {
                DebugOverrideBadge()
              }
            }

            val detailsBtnInteraction = rememberPressHapticInteractionSource()
            IconButton(
              onClick = {
                TouchTelemetry.recordControlHandled("inspector_details_button")
                onDetentChange(InspectorSheetDetent.FULL)
              },
              interactionSource = detailsBtnInteraction,
              modifier = Modifier.size(48.dp).testTag("inspector_details_button"),
            ) {
              Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.inspector_details_tab),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.innerPressScale(detailsBtnInteraction).size(20.dp),
              )
            }

            val copyBtnInteraction = rememberPressHapticInteractionSource()
            IconButton(
              onClick = {
                TouchTelemetry.recordControlHandled("inspector_copy_report_button")
                clipboardManager.setText(AnnotatedString(buildInspectorPlainText(uiState)))
                copiedRecently = true
                onReportCopied()
                showReportDialog = true
              },
              interactionSource = copyBtnInteraction,
              modifier = Modifier.size(48.dp).testTag("inspector_copy_report_button"),
            ) {
              Icon(
                imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.inspector_copy_report),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.innerPressScale(copyBtnInteraction).size(18.dp),
              )
            }

            val closeHalfInteraction = rememberPressHapticInteractionSource()
            IconButton(
              onClick = {
                TouchTelemetry.recordControlHandled("close_inspector_button")
                onClose()
              },
              interactionSource = closeHalfInteraction,
              modifier = Modifier.size(48.dp).testTag("close_inspector_button"),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close),
                modifier = Modifier.innerPressScale(closeHalfInteraction).size(18.dp),
              )
            }
          }

          if (BuildConfig.DEBUG) {
            DebugTouchTelemetryLine()
          }
        }
      }

      InspectorSheetDetent.FULL -> {
        val expandedFeatureKeys = remember { mutableStateMapOf<Int, Boolean>() }
        val expandedBranchKeys = remember { mutableStateMapOf<String, Boolean>() }
        val fullHandleInteraction = rememberPressHapticInteractionSource()
        val spotsBringIntoViewRequester = remember { BringIntoViewRequester() }
        LaunchedEffect(uiState.inspectorScrollToSpotsSignal) {
          if (uiState.inspectorScrollToSpotsSignal > 0) {
            delay(80L)
            spotsBringIntoViewRequester.bringIntoView()
          }
        }

        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 4.dp)
              .testTag("inspector_details_container"),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          // 48dp Drag handle (tap or drag down -> Controls)
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(
                  interactionSource = fullHandleInteraction,
                  indication = null,
                ) {
                  TouchTelemetry.recordControlHandled("inspector_drag_handle")
                  onDetentChange(InspectorSheetDetent.HALF)
                }
                .pointerInput(detent) {
                  detectVerticalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f },
                    onDragEnd = {
                      if (dragAccum > 18f) {
                        TouchTelemetry.recordControlHandled("inspector_drag_handle")
                        onDetentChange(InspectorSheetDetent.HALF)
                      }
                      dragAccum = 0f
                    },
                    onVerticalDrag = { _, dy -> dragAccum += dy },
                  )
                }
                .testTag("inspector_drag_handle"),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier =
                Modifier
                  .innerPressScale(fullHandleInteraction)
                  .size(width = 40.dp, height = 4.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)),
            )
          }

          // Details header row
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.weight(1f),
            ) {
              Text(
                text = stringResource(R.string.inspector_details_tab),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (uiState.hasAnyDebugOverride) {
                DebugOverrideBadge()
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              val copyInteraction = rememberPressHapticInteractionSource()
              FilledTonalButton(
                onClick = {
                  TouchTelemetry.recordControlHandled("inspector_copy_report_button")
                  clipboardManager.setText(AnnotatedString(buildInspectorPlainText(uiState)))
                  copiedRecently = true
                  onReportCopied()
                  showReportDialog = true
                },
                interactionSource = copyInteraction,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("inspector_copy_report_button"),
              ) {
                Row(
                  modifier = Modifier.innerPressScale(copyInteraction),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Icon(
                    imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.inspector_copy_report),
                    modifier = Modifier.size(14.dp),
                  )
                  Text(
                    text =
                      if (copiedRecently) stringResource(R.string.inspector_copied)
                      else stringResource(R.string.inspector_copy_report),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }

              val collapseBtnInteraction = rememberPressHapticInteractionSource()
              IconButton(
                onClick = {
                  TouchTelemetry.recordControlHandled("inspector_expand_toggle_button")
                  onDetentChange(InspectorSheetDetent.HALF)
                },
                interactionSource = collapseBtnInteraction,
                modifier = Modifier.size(48.dp).testTag("inspector_expand_toggle_button"),
              ) {
                Icon(
                  imageVector = Icons.Default.ExpandMore,
                  contentDescription = null,
                  modifier = Modifier.innerPressScale(collapseBtnInteraction).size(20.dp),
                )
              }

              val closeFullInteraction = rememberPressHapticInteractionSource()
              IconButton(
                onClick = {
                  TouchTelemetry.recordControlHandled("close_inspector_button")
                  onClose()
                },
                interactionSource = closeFullInteraction,
                modifier = Modifier.size(48.dp).testTag("close_inspector_button"),
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = stringResource(R.string.action_close),
                  modifier = Modifier.innerPressScale(closeFullInteraction).size(20.dp),
                )
              }
            }
          }

          HorizontalDivider(color = roadColors.divider)

          // Scrollable Details body
          Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (BuildConfig.DEBUG && uiState.pipelineDiagnostics.stages.isNotEmpty()) {
              val summaryPrefix = stringResource(R.string.inspector_pipeline_summary_prefix)
              val summaryAnnotated =
                buildAnnotatedString {
                  withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) {
                    append(summaryPrefix)
                  }
                  uiState.pipelineDiagnostics.stages.forEach { st ->
                    append(" ")
                    val icon = if (st.ok) "✓" else "✗"
                    val tint = if (st.ok) roadColors.openText else roadColors.blockedText
                    withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold)) {
                      append("${st.stageId}$icon")
                    }
                  }
                  val colCount = uiState.pipelineDiagnostics.idCollisions
                  val dangCount = uiState.pipelineDiagnostics.danglingNodeRefs
                  val integrityTint =
                    if (colCount == 0 && dangCount == 0) roadColors.openText else roadColors.blockedText
                  withStyle(SpanStyle(color = integrityTint, fontWeight = FontWeight.Bold)) {
                    append(" | col=$colCount dang=$dangCount")
                  }
                }
              Text(
                text = summaryAnnotated,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth().testTag("inspector_pipeline_summary"),
              )
            }

            // Single status + match summary line (shown ONCE)
            val statusSummary = buildSingleStatusSummary(uiState, currentRoadLabel, diagnosticReason)
            Text(
              text = statusSummary,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              color =
                if (diagnosticReason != null) roadColors.unverifiedText
                else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("inspector_diagnostic_reason"),
            )

            // Name source
            val tileInfo =
              uiState.tileFallbackRoad?.let { " | tileClass=${it.roadClass ?: "?"}" } ?: ""
            Text(
              text = "Name source: ${uiState.roadNameSource}$tileInfo | stale10s=${uiState.isDataStaleOver10s}",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("inspector_name_source"),
            )

            if (uiState.isDividedRoad) {
              Text(
                text =
                  String.format(
                    Locale.US,
                    "Divided road · sep=%dm · rule=%s (%.2f)",
                    uiState.dividedRoadSeparationMeters?.roundToInt() ?: 0,
                    uiState.dividedRoadRuleId ?: "divided",
                    uiState.dividedRoadConfidence,
                  ),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("inspector_divided_road"),
              )
            }

            if (uiState.pipelineDiagnostics.stages.isNotEmpty()) {
              GlassSurface(
                shape = RoundedCornerShape(12.dp),
                isLighterInner = true,
                withShadow = false,
                modifier = Modifier.fillMaxWidth().testTag("inspector_pipeline_stages"),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    text = "Pipeline (a–i) · v${uiState.pipelineDiagnostics.graphVersion} · ${uiState.pipelineDiagnostics.nearestEdgeSummary}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  if (uiState.pipelineDiagnostics.rollingWindowSummary.isNotBlank()) {
                    Text(
                      text = "Window: ${uiState.pipelineDiagnostics.rollingWindowSummary}",
                      fontFamily = FontFamily.Monospace,
                      fontSize = 10.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.testTag("inspector_rolling_window"),
                    )
                  }
                  if (uiState.pipelineDiagnostics.horizonStatsSummary.isNotBlank()) {
                    Text(
                      text = "Horizon: ${uiState.pipelineDiagnostics.horizonStatsSummary}",
                      fontFamily = FontFamily.Monospace,
                      fontSize = 10.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.testTag("inspector_horizon_stats"),
                    )
                  }
                  if (uiState.pipelineDiagnostics.isInconsistentMatch && uiState.pipelineDiagnostics.inconsistentBannerText != null) {
                    Text(
                      text = uiState.pipelineDiagnostics.inconsistentBannerText,
                      fontFamily = FontFamily.Monospace,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold,
                      color = roadColors.blockedText,
                      modifier = Modifier.testTag("inspector_inconsistent_banner"),
                    )
                  }
                  uiState.pipelineDiagnostics.stages.forEach { stage ->
                    Text(
                      text = stage.toReportLine(),
                      fontFamily = FontFamily.Monospace,
                      fontSize = 10.sp,
                      lineHeight = 13.sp,
                      color = if (stage.ok) MaterialTheme.colorScheme.onSurface else roadColors.unverifiedText,
                      modifier = Modifier.testTag("pipeline_stage_${stage.stageId}"),
                    )
                  }
                  if (uiState.pipelineDiagnostics.nearbyTileLabels.isNotEmpty()) {
                    Text(
                      text = "Labels <=20m: " + uiState.pipelineDiagnostics.nearbyTileLabels.take(3).joinToString(" | ") { it.toReportString() },
                      fontFamily = FontFamily.Monospace,
                      fontSize = 9.sp,
                      lineHeight = 12.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.testTag("inspector_nearby_tile_labels"),
                    )
                  }
                  val borderStats = uiState.pipelineDiagnostics.borderStitchStats
                  if (borderStats.totalBorderEndpoints > 0) {
                    Text(
                      text = "Borders: total=${borderStats.totalBorderEndpoints} conn=${borderStats.connectedBorderEndpoints} unconn=${borderStats.unconnectedBorderEndpoints}",
                      fontFamily = FontFamily.Monospace,
                      fontSize = 9.sp,
                      lineHeight = 12.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.testTag("inspector_border_stats"),
                    )
                    borderStats.worstUnconnected.take(5).forEachIndexed { bIdx, ep ->
                      Text(
                        text = String.format(Locale.US, "  unconn[%d]: %.5f,%.5f %s @%s", bIdx + 1, ep.lat, ep.lon, ep.roadClass, ep.tileKey),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = roadColors.unverifiedText,
                      )
                    }
                  }
                  if (uiState.tracedPathSegments.isNotEmpty()) {
                    Text(
                      text = "Main path trace (${uiState.tracedPathSegments.size} segs):",
                      fontFamily = FontFamily.Monospace,
                      fontSize = 9.sp,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.testTag("inspector_path_trace"),
                    )
                    uiState.tracedPathSegments.take(8).forEach { seg ->
                      val ow = seg.oneway ?: "-"
                      val fork = seg.forkReason?.let { " [$it]" } ?: ""
                      Text(
                        text = String.format(Locale.US, "  way=%d %s (%s 1w=%s) %.0f..%.0fm%s", seg.wayId, seg.name.take(20), seg.highway, ow, seg.startDistMeters, seg.endDistMeters, fork),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                  if (uiState.nearbyUnreachableLinks.isNotEmpty()) {
                    Text(
                      text = "Links skipped: " + uiState.nearbyUnreachableLinks.take(5).joinToString(" | "),
                      fontFamily = FontFamily.Monospace,
                      fontSize = 9.sp,
                      lineHeight = 11.sp,
                      color = roadColors.unverifiedText,
                      modifier = Modifier.testTag("inspector_unreachable_links"),
                    )
                  }
                }
              }
            }

            if (BuildConfig.DEBUG) {
              DebugTouchTelemetryLine()
              InspectorTapTestSection()
            }

            // Controllable Mock Position, Simulate Drive, Signal & Test Spots
            GlassSurface(
              shape = RoundedCornerShape(12.dp),
              isLighterInner = true,
              withShadow = false,
              modifier = Modifier.fillMaxWidth().testTag("inspector_mock_controls_section"),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = stringResource(R.string.inspector_along_road),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    listOf(-50.0, -10.0, 10.0, 50.0).forEach { delta ->
                      val lbl = if (delta > 0) "+${delta.toInt()}m" else "${delta.toInt()}m"
                      val stepId = "inspector_step_${delta.toInt()}"
                      InspectorMiniChip(
                        label = lbl,
                        active = false,
                        enabled = uiState.isMockModeActive,
                        controlId = stepId,
                        onClick = { onStepAlongRoad(delta) },
                        modifier = Modifier.testTag(stepId),
                      )
                    }
                  }
                }

                // Simulate Drive Play/Pause + Speed Slider (0-120 km/h) + presets 5/30/60/90
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  val simBtnInteraction = rememberPressHapticInteractionSource()
                  OutlinedButton(
                    onClick = {
                      TouchTelemetry.recordControlHandled("inspector_sim_play_pause")
                      onToggleSimulateDrive()
                    },
                    enabled = uiState.isMockModeActive,
                    interactionSource = simBtnInteraction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_sim_play_pause"),
                  ) {
                    Row(
                      modifier = Modifier.innerPressScale(simBtnInteraction),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Icon(
                        imageVector = if (uiState.isSimulatingDrive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Text(
                        text =
                          if (uiState.isSimulatingDrive) stringResource(R.string.inspector_sim_pause)
                          else stringResource(R.string.inspector_sim_play),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                      )
                    }
                  }
                  val (simSpdVal, simSpdUnit) =
                    GeoMath.formatSpeedValueAndUnit(uiState.mockSpeedKmh, uiState.effectiveUnitSystem)
                  Text(
                    text =
                      if (simSpdUnit == "mph") stringResource(R.string.inspector_sim_speed_mph_format, simSpdVal)
                      else stringResource(R.string.inspector_sim_speed_format, simSpdVal),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  if (uiState.simulationStatusMessage != null) {
                    Text(
                      text = uiState.simulationStatusMessage,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = roadColors.unverifiedText,
                      maxLines = 1,
                      modifier = Modifier.weight(1f),
                    )
                  }
                }

                val isImperial = uiState.effectiveUnitSystem == com.example.data.model.UnitSystem.IMPERIAL
                val displayMaxSpeed = if (isImperial) 75f else 120f
                var isDraggingSpeed by remember { mutableStateOf(false) }
                var speedSliderFloat by remember {
                  mutableFloatStateOf(
                    if (isImperial) (uiState.mockSpeedKmh / 1.60934).toFloat().coerceIn(0f, 75f)
                    else uiState.mockSpeedKmh.toFloat().coerceIn(0f, 120f),
                  )
                }
                LaunchedEffect(uiState.mockSpeedKmh, isImperial) {
                  if (!isDraggingSpeed) {
                    speedSliderFloat =
                      if (isImperial) (uiState.mockSpeedKmh / 1.60934).toFloat().coerceIn(0f, 75f)
                      else uiState.mockSpeedKmh.toFloat().coerceIn(0f, 120f)
                  }
                }
                val speedSliderInteraction = rememberPressHapticInteractionSource()
                Row(
                  modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Slider(
                    value = speedSliderFloat.coerceIn(0f, displayMaxSpeed),
                    onValueChange = {
                      isDraggingSpeed = true
                      speedSliderFloat = it
                      TouchTelemetry.recordControlHandled("inspector_speed_slider")
                      val kmh = if (isImperial) it.roundToInt() * 1.60934 else it.roundToInt().toDouble()
                      onMockSpeedChange(kmh)
                    },
                    onValueChangeFinished = { isDraggingSpeed = false },
                    valueRange = 0f..displayMaxSpeed,
                    enabled = uiState.isMockModeActive,
                    interactionSource = speedSliderInteraction,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inspector_speed_slider"),
                  )
                  val presetsDisplay = if (isImperial) listOf(3, 20, 40, 55) else listOf(5, 30, 60, 90)
                  val curDisplayVal = if (isImperial) (uiState.mockSpeedKmh / 1.60934).roundToInt() else uiState.mockSpeedKmh.roundToInt()
                  presetsDisplay.forEach { preset ->
                    val presetTag = "inspector_speed_preset_$preset"
                    InspectorMiniChip(
                      label = "$preset",
                      active = curDisplayVal == preset,
                      enabled = uiState.isMockModeActive,
                      controlId = presetTag,
                      onClick = {
                        val kmh = if (isImperial) preset * 1.60934 else preset.toDouble()
                        onMockSpeedChange(kmh)
                      },
                      modifier = Modifier.testTag(presetTag),
                    )
                  }
                }

                // Accuracy slider (3-50 m) + Jitter switch
                var isDraggingAcc by remember { mutableStateOf(false) }
                var accSliderFloat by remember { mutableFloatStateOf(uiState.mockAccuracyMeters) }
                LaunchedEffect(uiState.mockAccuracyMeters) {
                  if (!isDraggingAcc) accSliderFloat = uiState.mockAccuracyMeters
                }
                val accSliderInteraction = rememberPressHapticInteractionSource()
                val jitterSwitchInteraction = rememberPressHapticInteractionSource()
                Row(
                  modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text(
                    text = stringResource(R.string.inspector_accuracy_format, accSliderFloat.roundToInt()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Slider(
                    value = accSliderFloat,
                    onValueChange = {
                      isDraggingAcc = true
                      accSliderFloat = it
                      TouchTelemetry.recordControlHandled("inspector_accuracy_slider")
                      onMockAccuracyChange(it.roundToInt().toFloat())
                    },
                    onValueChangeFinished = { isDraggingAcc = false },
                    valueRange = 3f..50f,
                    enabled = uiState.isMockModeActive,
                    interactionSource = accSliderInteraction,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inspector_accuracy_slider"),
                  )
                  Text(
                    text = stringResource(R.string.inspector_jitter),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Switch(
                    checked = uiState.mockJitterEnabled,
                    onCheckedChange = {
                      TouchTelemetry.recordControlHandled("inspector_jitter_switch")
                      onMockJitterChange(it)
                    },
                    enabled = uiState.isMockModeActive,
                    interactionSource = jitterSwitchInteraction,
                    modifier = Modifier.testTag("inspector_jitter_switch"),
                  )
                }

                // Test Spots (Save / Load / Delete / Restore defaults)
                Column(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .bringIntoViewRequester(spotsBringIntoViewRequester)
                      .testTag("inspector_spots_section"),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Text(
                      text = stringResource(R.string.inspector_spots_title),
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    val saveSpotInteraction = rememberPressHapticInteractionSource()
                    OutlinedButton(
                      onClick = {
                        TouchTelemetry.recordControlHandled("inspector_save_spot_button")
                        spotNameInput = currentRoadLabel.takeIf { it != "unknown" && it != "OFF-ROAD" } ?: ""
                        showSaveSpotDialog = true
                      },
                      enabled = uiState.location != null,
                      interactionSource = saveSpotInteraction,
                      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                      modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_save_spot_button"),
                    ) {
                      Row(
                        modifier = Modifier.innerPressScale(saveSpotInteraction),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(
                          text = stringResource(R.string.inspector_save_spot),
                          fontSize = 11.sp,
                          modifier = Modifier.padding(start = 4.dp),
                        )
                      }
                    }
                  }
                  if (uiState.savedTestSpots.isEmpty()) {
                    Text(
                      text = stringResource(R.string.inspector_spots_empty),
                      fontSize = 11.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (BuildConfig.DEBUG) {
                      val restoreInteraction = rememberPressHapticInteractionSource()
                      OutlinedButton(
                        onClick = {
                          TouchTelemetry.recordControlHandled("inspector_restore_default_spots_button")
                          onRestoreDefaultSpots()
                        },
                        interactionSource = restoreInteraction,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_restore_default_spots_button"),
                      ) {
                        Text(
                          text = stringResource(R.string.inspector_restore_default_spots),
                          fontSize = 11.sp,
                          modifier = Modifier.innerPressScale(restoreInteraction),
                        )
                      }
                    }
                  } else {
                  uiState.savedTestSpots.forEach { spot ->
                    val loadInteraction = rememberPressHapticInteractionSource()
                    val deleteInteraction = rememberPressHapticInteractionSource()
                    Row(
                      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("test_spot_row_${spot.id}"),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      TextButton(
                        onClick = {
                          TouchTelemetry.recordControlHandled("test_spot_load_${spot.id}")
                          onLoadSpot(spot)
                        },
                        interactionSource = loadInteraction,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("test_spot_load_${spot.id}"),
                      ) {
                        Text(
                          text =
                            String.format(
                              Locale.US,
                              "%s · %.0f° · %s",
                              spot.name,
                              spot.headingDeg,
                              if (spot.travelMode == TravelMode.DRIVING) "Drive" else "Walk",
                            ),
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Medium,
                          color = MaterialTheme.colorScheme.primary,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                          modifier = Modifier.fillMaxWidth().innerPressScale(loadInteraction),
                        )
                      }
                      IconButton(
                        onClick = {
                          TouchTelemetry.recordControlHandled("test_spot_delete_${spot.id}")
                          onDeleteSpot(spot.id)
                        },
                        interactionSource = deleteInteraction,
                        modifier = Modifier.size(48.dp).testTag("test_spot_delete_${spot.id}"),
                      ) {
                        Icon(
                          imageVector = Icons.Default.Delete,
                          contentDescription = stringResource(R.string.action_clear),
                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.innerPressScale(deleteInteraction).size(18.dp),
                        )
                      }
                    }
                  }
                }
              }
            }
          }

            // Network Simulation & Cache Controls (Part D)
            GlassSurface(
              shape = RoundedCornerShape(12.dp),
              isLighterInner = true,
              withShadow = false,
              modifier = Modifier.fillMaxWidth().testTag("inspector_simulation_section"),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                CompactDebugSwitchRow(
                  label = stringResource(R.string.settings_offline_mode),
                  checked = uiState.debugOffline,
                  onCheckedChange = {
                    onDebugFlagsChange(uiState.debugPoorGps, it, uiState.debugUncertain)
                  },
                  switchTestTag = "inspector_details_offline_switch",
                )
                CompactDebugSwitchRow(
                  label = stringResource(R.string.inspector_block_overpass),
                  checked = uiState.debugBlockOverpass,
                  onCheckedChange = {
                    onDebugNetworkSimulationChange(
                      it,
                      uiState.debugBlockTiles,
                      uiState.debugSlowNetwork,
                      uiState.debugForceOverpassError,
                    )
                  },
                  switchTestTag = "inspector_block_overpass_switch",
                )
                CompactDebugSwitchRow(
                  label = stringResource(R.string.inspector_block_tiles),
                  checked = uiState.debugBlockTiles,
                  onCheckedChange = {
                    onDebugNetworkSimulationChange(
                      uiState.debugBlockOverpass,
                      it,
                      uiState.debugSlowNetwork,
                      uiState.debugForceOverpassError,
                    )
                  },
                  switchTestTag = "inspector_block_tiles_switch",
                )
                CompactDebugSwitchRow(
                  label = stringResource(R.string.inspector_slow_network),
                  checked = uiState.debugSlowNetwork,
                  onCheckedChange = {
                    onDebugNetworkSimulationChange(
                      uiState.debugBlockOverpass,
                      uiState.debugBlockTiles,
                      it,
                      uiState.debugForceOverpassError,
                    )
                  },
                  switchTestTag = "inspector_slow_network_switch",
                )

                // Force Overpass error dropdown: Off / 406 / 429 / 502 / 504
                var errorMenuExpanded by remember { mutableStateOf(false) }
                Row(
                  modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = stringResource(R.string.inspector_force_overpass_error),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Box {
                    val errorMenuInteraction = rememberPressHapticInteractionSource()
                    FilledTonalButton(
                      onClick = {
                        TouchTelemetry.recordControlHandled("inspector_force_error_dropdown")
                        errorMenuExpanded = true
                      },
                      interactionSource = errorMenuInteraction,
                      shape = CircleShape,
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                      modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("inspector_force_error_dropdown"),
                    ) {
                      Row(
                        modifier = Modifier.innerPressScale(errorMenuInteraction),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                      ) {
                        Text(
                          text =
                            uiState.debugForceOverpassError?.toString()
                              ?: stringResource(R.string.inspector_error_none),
                          fontSize = 12.sp,
                          fontWeight = FontWeight.Bold,
                          color =
                            if (uiState.debugForceOverpassError != null) roadColors.unverifiedText
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                          imageVector = Icons.Default.ArrowDropDown,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                        )
                      }
                    }
                    DropdownMenu(
                      expanded = errorMenuExpanded,
                      onDismissRequest = { errorMenuExpanded = false },
                    ) {
                      val options: List<Int?> = listOf(null, 406, 429, 502, 504)
                      options.forEach { code ->
                        DropdownMenuItem(
                          text = {
                            Text(
                              text = code?.toString() ?: stringResource(R.string.inspector_error_none),
                              fontSize = 13.sp,
                            )
                          },
                          onClick = {
                            errorMenuExpanded = false
                            TouchTelemetry.recordControlHandled("inspector_force_error_opt_${code ?: "off"}")
                            onDebugNetworkSimulationChange(
                              uiState.debugBlockOverpass,
                              uiState.debugBlockTiles,
                              uiState.debugSlowNetwork,
                              code,
                            )
                          },
                          modifier = Modifier.testTag("inspector_force_error_opt_${code ?: "off"}"),
                        )
                      }
                    }
                  }
                }

                // One-line Cache stats
                Text(
                  text = formatCacheStatsText(uiState),
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  modifier = Modifier.testTag("inspector_cache_stats"),
                )

                // Action buttons: Clear caches | Test Overpass | Reset debug overrides (FlowRow, no horizontalScroll)
                FlowRow(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  val clearCachesInteraction = rememberPressHapticInteractionSource()
                  OutlinedButton(
                    onClick = {
                      TouchTelemetry.recordControlHandled("inspector_clear_caches_button")
                      onClearCache()
                    },
                    interactionSource = clearCachesInteraction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_clear_caches_button"),
                  ) {
                    Text(
                      text = stringResource(R.string.inspector_clear_caches),
                      fontSize = 11.sp,
                      modifier = Modifier.innerPressScale(clearCachesInteraction),
                    )
                  }
                  val testOvpInteraction = rememberPressHapticInteractionSource()
                  OutlinedButton(
                    onClick = {
                      TouchTelemetry.recordControlHandled("inspector_test_overpass_button")
                      onTestOverpass()
                    },
                    enabled = !uiState.isTestingOverpass,
                    interactionSource = testOvpInteraction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_test_overpass_button"),
                  ) {
                    Text(
                      text =
                        if (uiState.isTestingOverpass) stringResource(R.string.inspector_testing_overpass)
                        else stringResource(R.string.inspector_test_overpass),
                      fontSize = 11.sp,
                      modifier = Modifier.innerPressScale(testOvpInteraction),
                    )
                  }
                  val pingOvpInteraction = rememberPressHapticInteractionSource()
                  OutlinedButton(
                    onClick = {
                      TouchTelemetry.recordControlHandled("inspector_ping_overpass_button")
                      onPingOverpass()
                    },
                    enabled = !uiState.isTestingOverpass,
                    interactionSource = pingOvpInteraction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_ping_overpass_button"),
                  ) {
                    Text(
                      text = stringResource(R.string.inspector_ping_overpass),
                      fontSize = 11.sp,
                      modifier = Modifier.innerPressScale(pingOvpInteraction),
                    )
                  }
                  val reloadRoadInteraction = rememberPressHapticInteractionSource()
                  OutlinedButton(
                    onClick = {
                      TouchTelemetry.recordControlHandled("inspector_reload_road_data_button")
                      onReloadRoadData()
                    },
                    interactionSource = reloadRoadInteraction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_reload_road_data_button"),
                  ) {
                    Text(
                      text = stringResource(R.string.inspector_reload_road_data),
                      fontSize = 11.sp,
                      modifier = Modifier.innerPressScale(reloadRoadInteraction),
                    )
                  }
                  val resetOvrInteraction = rememberPressHapticInteractionSource()
                  OutlinedButton(
                    onClick = {
                      TouchTelemetry.recordControlHandled("inspector_reset_overrides_button")
                      onResetDebugOverrides()
                    },
                    enabled = uiState.hasAnyDebugOverride,
                    interactionSource = resetOvrInteraction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inspector_reset_overrides_button"),
                  ) {
                    Text(
                      text = stringResource(R.string.settings_reset_overrides),
                      fontSize = 11.sp,
                      modifier = Modifier.innerPressScale(resetOvrInteraction),
                    )
                  }
                }

                // Test Overpass results
                if (uiState.overpassTestResults.isNotEmpty()) {
                  Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("inspector_test_overpass_results"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                  ) {
                    uiState.overpassTestResults.forEach { res ->
                      val host = extractHost(res.endpoint)
                      Text(
                        text = "$host · tiny=${res.tinyStatus}(${res.tinyDurationMs}ms) · road=${res.roadStatus}(${res.roadDurationMs}ms)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }

            // Recent Request Logs (Overpass + Tiles)
            if (uiState.recentOverpassLogs.isNotEmpty()) {
              GlassSurface(
                shape = RoundedCornerShape(12.dp),
                isLighterInner = true,
                withShadow = false,
                modifier = Modifier.fillMaxWidth().testTag("inspector_overpass_logs"),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                  verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                  Text(
                    text = "Requests (latest ${uiState.recentOverpassLogs.take(5).size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  uiState.recentOverpassLogs.take(5).forEach { log ->
                    val host = extractHost(log.endpoint)
                    val flags =
                      listOfNotNull(
                        if (log.hedged) "hedged" else null,
                        if (log.cancelled) "cancelled" else null,
                      ).joinToString(",")
                    val flagSuffix = if (flags.isNotEmpty()) " [$flags]" else ""
                    Text(
                      text =
                        String.format(
                          Locale.US,
                          "[%s] %s · %s %s · %s · %.1fKB · %dms · f=%d c=%d%s",
                          log.source,
                          host,
                          log.method,
                          log.protocol,
                          log.status,
                          log.responseSizeKb,
                          log.durationMs,
                          log.cellsFetched,
                          log.cellsCached,
                          flagSuffix,
                        ),
                      fontFamily = FontFamily.Monospace,
                      fontSize = 10.sp,
                      color = if (log.succeeded) MaterialTheme.colorScheme.onSurface else roadColors.unverifiedText,
                    )
                    val extra =
                      listOfNotNull(
                        listOfNotNull(
                          log.dnsMs?.let { "dns=${it}ms" },
                          log.connectMs?.let { "tcp=${it}ms" },
                          log.tlsMs?.let { "tls=${it}ms" },
                          log.firstByteMs?.let { "ttfb=${it}ms" },
                        ).takeIf { it.isNotEmpty() }?.joinToString(" "),
                        log.responseHeadersSummary?.takeIf { it.isNotBlank() },
                        log.cooldownSummary.takeIf { it.isNotBlank() },
                        log.failures.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "err=$it" },
                        log.responseSnippet?.takeIf { !log.succeeded && it.isNotBlank() }?.let { "body=$it" },
                      ).joinToString(" | ")
                    if (extra.isNotEmpty()) {
                      Text(
                        text = extra,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                      )
                    }
                  }
                }
              }
            }

            // Off-road diagnostics
            if (offRoad.isOffRoad) {
              GlassSurface(
                shape = RoundedCornerShape(12.dp),
                isLighterInner = true,
                withShadow = false,
                modifier = Modifier.fillMaxWidth().testTag("inspector_offroad_section"),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    text =
                      "OFF-ROAD: ${offRoad.reason ?: "outside road match"} · radius=${offRoad.matchRadiusMeters.toInt()}m -> ${offRoad.searchRadiusMeters.toInt()}m",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = roadColors.unverifiedText,
                  )
                  Text(
                    text =
                      if (selectedOffRoad != null) {
                        "Selected: ${selectedOffRoad.badgeId} · way=${selectedOffRoad.candidate.way.id} (${displayNameOrRef(selectedOffRoad.candidate.name)}) · fwd=${offRoad.chosenForwardAlongWay}"
                      } else {
                        "Selected: none"
                      },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  offRoad.candidates.forEach { c ->
                    Text(
                      text =
                        "${c.badgeId} ${c.side.name} · way=${c.candidate.way.id} · ${displayNameOrRef(c.candidate.name)} · ${c.distanceMeters.toInt()}m closest",
                      fontFamily = FontFamily.Monospace,
                      fontSize = 11.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }

            // Features, branches & raw tags with [tiles]/[overpass] source
            val top5 = uiState.features.take(5)
            if (top5.isEmpty()) {
              Text(
                text =
                  stringResource(
                    R.string.no_features_ahead,
                    formatRoadDistance(uiState.lookaheadMeters, uiState.effectiveUnitSystem),
                  ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
              )
            } else {
              top5.forEach { feat ->
                val isFeatExpanded = expandedFeatureKeys[feat.index] != false
                val featName = displayNameOrRef(BilingualName.fromOsmTags(feat.osmTags))
                val featLeads = feat.branches.firstOrNull { !it.leadsTo.isNullOrBlank() }?.leadsTo ?: "?"
                val featStatus = feat.branches.firstOrNull()?.status?.let { shortStatusWord(it) } ?: "Open"
                val srcLabel = feat.source.name.lowercase(Locale.US)
                val rulePart =
                  if (feat.ruleId.isNotBlank()) {
                    String.format(Locale.US, " · %s (%.2f)", feat.ruleId, feat.confidence)
                  } else {
                    ""
                  }
                val featLine =
                  "${feat.index} ${shortFeatureTypeName(feat.effectiveDisplayType())} · ${feat.distanceMeters.toInt()} m · $featName · leads to $featLeads · $featStatus [$srcLabel]$rulePart"

                GlassSurface(
                  shape = RoundedCornerShape(12.dp),
                  isLighterInner = true,
                  withShadow = false,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                  ) {
                    val featInteraction = rememberPressHapticInteractionSource()
                    Surface(
                      onClick = {
                        TouchTelemetry.recordControlHandled("inspector_feature_row_${feat.index}")
                        expandedFeatureKeys[feat.index] = !isFeatExpanded
                      },
                      interactionSource = featInteraction,
                      shape = RoundedCornerShape(8.dp),
                      color = Color.Transparent,
                      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("inspector_feature_row_${feat.index}"),
                    ) {
                      Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                          text = featLine,
                          fontSize = 12.sp,
                          fontWeight = FontWeight.SemiBold,
                          color = MaterialTheme.colorScheme.onSurface,
                          maxLines = 2,
                          modifier = Modifier.fillMaxWidth().innerPressScale(featInteraction),
                        )
                      }
                    }

                    if (isFeatExpanded) {
                      val extraGeo =
                        buildList {
                          feat.staggerOffsetMeters?.let { add("stagger=${it.roundToInt()}m") }
                          feat.dualCarriagewayOffsetMeters?.let { add("dualOff=${it.roundToInt()}m") }
                          feat.frontageSide?.let { add("side=${it.name}") }
                        }.joinToString(" ")
                      val ruleConf =
                        String.format(
                          Locale.US,
                          "rule=%s conf=%.2f | node=%s %s %s",
                          feat.ruleId.ifBlank { "none" },
                          feat.confidence,
                          feat.anchorNodeId?.toString() ?: "none",
                          extraGeo,
                          feat.osmTags.toDisplayMap(),
                        )
                      Text(
                        text = ruleConf,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).testTag("inspector_feature_tags_${feat.index}"),
                      )
                      feat.hiddenSubFeatures.forEachIndexed { hIdx, hidden ->
                        Text(
                          text =
                            String.format(
                              Locale.US,
                              "  grouped[%d]: %s · %dm · %s (%.2f)",
                              hIdx + 1,
                              shortFeatureTypeName(hidden.effectiveDisplayType()),
                              hidden.distanceMeters.toInt(),
                              hidden.ruleId.ifBlank { "raw" },
                              hidden.confidence,
                            ),
                          fontFamily = FontFamily.Monospace,
                          fontSize = 10.sp,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.padding(start = 10.dp).testTag("inspector_hidden_sub_${feat.index}_$hIdx"),
                        )
                      }
                    }

                    if (expectsMultipleBranches(feat.type) && feat.branches.size == 1) {
                      Text(
                        text = "⚠ only 1 branch found",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = roadColors.unverifiedText,
                        modifier = Modifier.padding(start = 6.dp),
                      )
                    }

                    feat.branches.forEach { br ->
                      val brKey = "${feat.index}_${br.badgeId}"
                      val isBrExpanded = expandedBranchKeys[brKey] != false
                      val brName = displayNameOrRef(br.name, fallback = "unnamed")
                      val brLeads = br.leadsTo ?: "?"
                      val brLine =
                        "${br.badgeId} ${shortDirName(br.relativeDirection)} · ${br.distanceMeters.toInt()} m · $brName · leads to $brLeads · ${shortStatusWord(br.status)}"
                      val brInteraction = rememberPressHapticInteractionSource()

                      Surface(
                        onClick = {
                          TouchTelemetry.recordControlHandled("inspector_branch_row_$brKey")
                          expandedBranchKeys[brKey] = !isBrExpanded
                        },
                        interactionSource = brInteraction,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier =
                          Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(start = 10.dp)
                            .testTag("inspector_branch_row_$brKey"),
                      ) {
                        Box(contentAlignment = Alignment.CenterStart) {
                          Text(
                            text = brLine,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().innerPressScale(brInteraction),
                          )
                        }
                      }

                      if (isBrExpanded) {
                        Text(
                          text = "way=${br.wayId} ${br.osmTags.toDisplayMap()}",
                          fontFamily = FontFamily.Monospace,
                          fontSize = 11.sp,
                          lineHeight = 14.sp,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.padding(start = 16.dp).testTag("inspector_branch_tags_$brKey"),
                        )
                      }
                    }
                  }
                }
              }
            }

            // Feature Census within 1.5 km + Jump buttons (150 m before nearest instance, facing it)
            GlassSurface(
              shape = RoundedCornerShape(12.dp),
              isLighterInner = true,
              withShadow = false,
              modifier = Modifier.fillMaxWidth().testTag("inspector_feature_census_section"),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = stringResource(R.string.inspector_feature_census),
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
                if (uiState.featureCensus.isEmpty()) {
                  Text(
                    text = stringResource(R.string.inspector_census_empty),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else {
                  uiState.featureCensus.forEach { entry ->
                    val jumpInteraction = rememberPressHapticInteractionSource()
                    val saveCensusInteraction = rememberPressHapticInteractionSource()
                    Row(
                      modifier = Modifier.fillMaxWidth().testTag("census_row_${entry.type.name}"),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        text = "${shortFeatureTypeName(entry.type)} × ${entry.count} · ${entry.nearestDistanceMeters.toInt()} m",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                      )
                      OutlinedButton(
                        onClick = {
                          TouchTelemetry.recordControlHandled("census_jump_${entry.type.name}")
                          onJumpToCensusFeature(entry)
                        },
                        interactionSource = jumpInteraction,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("census_jump_${entry.type.name}"),
                      ) {
                        Text(
                          text = stringResource(R.string.inspector_jump),
                          fontSize = 11.sp,
                          modifier = Modifier.innerPressScale(jumpInteraction),
                        )
                      }
                      Spacer(modifier = Modifier.width(4.dp))
                      OutlinedButton(
                        onClick = {
                          TouchTelemetry.recordControlHandled("census_save_${entry.type.name}")
                          onSaveCensusAsSpot(entry)
                        },
                        interactionSource = saveCensusInteraction,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("census_save_${entry.type.name}"),
                      ) {
                        Text(
                          text = stringResource(R.string.inspector_spot_save_short),
                          fontSize = 11.sp,
                          modifier = Modifier.innerPressScale(saveCensusInteraction),
                        )
                      }
                    }
                  }
                }
              }
            }

            // Ignored Live Jumps (>500m in <2s)
            if (uiState.ignoredLiveJumps.isNotEmpty()) {
              GlassSurface(
                shape = RoundedCornerShape(12.dp),
                isLighterInner = true,
                withShadow = false,
                modifier = Modifier.fillMaxWidth().testTag("inspector_live_jumps_section"),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    text = stringResource(R.string.inspector_live_jumps_title),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = roadColors.unverifiedText,
                  )
                  uiState.ignoredLiveJumps.take(5).forEach { jumpLine ->
                    Text(
                      text = jumpLine,
                      fontFamily = FontFamily.Monospace,
                      fontSize = 10.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }

            // Orientation & Window Diagnostics
            OrientationDiagnosticsCard(
              uiState = uiState,
              windowWidthDp = windowWidthDp,
              windowHeightDp = windowHeightDp,
              windowSizeClassSummary = windowSizeClassSummary,
            )
          }
        }
      }
    }
  }

  if (showSaveSpotDialog) {
    AlertDialog(
      onDismissRequest = { showSaveSpotDialog = false },
      title = { Text(text = stringResource(R.string.inspector_save_spot_title)) },
      text = {
        OutlinedTextField(
          value = spotNameInput,
          onValueChange = { spotNameInput = it },
          placeholder = { Text(text = stringResource(R.string.inspector_spot_name_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("save_spot_name_input"),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onSaveSpot(spotNameInput)
            showSaveSpotDialog = false
          },
          modifier = Modifier.testTag("save_spot_confirm_button"),
        ) {
          Text(text = stringResource(R.string.action_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { showSaveSpotDialog = false }) {
          Text(text = stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showReportDialog) {
    val reportText = remember(uiState) { buildInspectorPlainText(uiState) }
    InspectorReportFullScreenDialog(
      reportText = reportText,
      onCopy = {
        clipboardManager.setText(AnnotatedString(reportText))
        copiedRecently = true
        onReportCopied()
      },
      onDismiss = { showReportDialog = false },
    )
  }
}

@Composable
private fun OrientationDiagnosticsCard(
  uiState: RoadHudUiState,
  windowWidthDp: Int,
  windowHeightDp: Int,
  windowSizeClassSummary: String,
) {
  val context = LocalContext.current
  val config = LocalConfiguration.current
  val roadColors = LocalRoadColors.current
  val activity = remember(context) { context.findActivity() }
  val manifestScreenOrientation =
    remember(activity) {
      runCatching {
        activity?.let { act ->
          val pm = act.packageManager
          pm.getActivityInfo(act.componentName, 0).screenOrientation
        } ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }.getOrDefault(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }
  val reqOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  val configOrientationLabel =
    when (config.orientation) {
      Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
      Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
      else -> "UNDEFINED(${config.orientation})"
    }
  val wDp = if (windowWidthDp > 0) windowWidthDp else config.screenWidthDp
  val hDp = if (windowHeightDp > 0) windowHeightDp else config.screenHeightDp
  val sizeClassLabel = windowSizeClassSummary.ifBlank { "${wDp}x${hDp}dp" }

  val requestedIsLandscape =
    reqOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
      reqOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
      reqOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ||
      uiState.settings.developerOrientation == DeveloperOrientation.LANDSCAPE
  val landscapeMismatch = requestedIsLandscape && config.orientation != Configuration.ORIENTATION_LANDSCAPE

  GlassSurface(
    shape = RoundedCornerShape(12.dp),
    isLighterInner = true,
    withShadow = false,
    modifier = Modifier.fillMaxWidth().testTag("inspector_orientation_section"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = stringResource(R.string.inspector_orientation_title),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "cfg=$configOrientationLabel · win=${wDp}x${hDp}dp · $sizeClassLabel",
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text =
          "requested=${orientationConstantName(reqOrientation)}($reqOrientation) · " +
            "manifest=${orientationConstantName(manifestScreenOrientation)}($manifestScreenOrientation)",
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (landscapeMismatch) {
        Text(
          text = stringResource(R.string.inspector_orientation_warning),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = roadColors.unverifiedText,
          modifier = Modifier.testTag("inspector_orientation_warning"),
        )
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun orientationConstantName(code: Int): String =
  when (code) {
    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> "unspecified"
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape"
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "portrait"
    ActivityInfo.SCREEN_ORIENTATION_USER -> "user"
    ActivityInfo.SCREEN_ORIENTATION_BEHIND -> "behind"
    ActivityInfo.SCREEN_ORIENTATION_SENSOR -> "sensor"
    ActivityInfo.SCREEN_ORIENTATION_NOSENSOR -> "nosensor"
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "sensorLandscape"
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "sensorPortrait"
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "reverseLandscape"
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> "reversePortrait"
    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR -> "fullSensor"
    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> "userLandscape"
    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> "userPortrait"
    ActivityInfo.SCREEN_ORIENTATION_FULL_USER -> "fullUser"
    ActivityInfo.SCREEN_ORIENTATION_LOCKED -> "locked"
    else -> "code_$code"
  }

@Composable
private fun InspectorReportFullScreenDialog(
  reportText: String,
  onCopy: () -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag("inspector_report_dialog"),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.inspector_report_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            OutlinedButton(
              onClick = onCopy,
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
              modifier = Modifier.height(36.dp).testTag("report_copy_button"),
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
              Text(
                text = stringResource(R.string.inspector_copy_report),
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp),
              )
            }
            IconButton(
              onClick = onDismiss,
              modifier = Modifier.size(36.dp).testTag("report_close_button"),
            ) {
              Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
            }
          }
        }

        Surface(
          color = LocalRoadColors.current.unverifiedBg,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().testTag("report_clipboard_note"),
        ) {
          Text(
            text = stringResource(R.string.inspector_report_clipboard_note),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = LocalRoadColors.current.unverifiedText,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          )
        }

        HorizontalDivider(color = LocalRoadColors.current.divider)

        SelectionContainer(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState()),
        ) {
          Text(
            text = reportText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            softWrap = true,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag("inspector_report_text"),
          )
        }
      }
    }
  }
}

@Composable
private fun DebugOverrideBadge() {
  val roadColors = LocalRoadColors.current
  Box(
    modifier =
      Modifier
        .clip(CircleShape)
        .background(roadColors.unverifiedBg)
        .padding(horizontal = 7.dp, vertical = 2.dp)
        .testTag("debug_override_badge"),
  ) {
    Text(
      text = stringResource(R.string.inspector_debug_override),
      color = roadColors.unverifiedText,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

@Composable
private fun DebugTouchTelemetryLine() {
  val action by TouchTelemetry.debugLastTouchAction
  val latencyMs by TouchTelemetry.debugLastTouchLatencyMs
  val controlId by TouchTelemetry.debugLastControlId
  Text(
    text = "Last touch: $action · ${latencyMs}ms · id=$controlId",
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    color = MaterialTheme.colorScheme.primary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth().testTag("inspector_touch_telemetry"),
  )
}

@Composable
private fun InspectorTapTestSection() {
  val tapCounts = remember { mutableStateListOf(0, 0, 0, 0, 0, 0, 0, 0, 0) }
  var tapTestSlider by remember { mutableFloatStateOf(50f) }
  val sliderInteraction = rememberPressHapticInteractionSource()

  GlassSurface(
    shape = RoundedCornerShape(12.dp),
    isLighterInner = true,
    withShadow = false,
    modifier = Modifier.fillMaxWidth().testTag("inspector_tap_test_section"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "Tap test",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "slider=${tapTestSlider.roundToInt()}",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("tap_test_slider_value"),
        )
      }

      for (rowIdx in 0 until 3) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          for (colIdx in 0 until 3) {
            val index = rowIdx * 3 + colIdx
            val btnNum = index + 1
            val btnTag = "tap_test_btn_$btnNum"
            val interactionSource = rememberPressHapticInteractionSource()
            val pressed by interactionSource.collectIsPressedAsState()
            Button(
              onClick = {
                tapCounts[index] = tapCounts[index] + 1
                TouchTelemetry.recordControlHandled(btnTag)
              },
              interactionSource = interactionSource,
              colors =
                ButtonDefaults.buttonColors(
                  containerColor =
                    if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.84f)
                    else MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
              modifier = Modifier.weight(1f).height(52.dp).testTag(btnTag),
            ) {
              Text(
                text = "#$btnNum (${tapCounts[index]})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.innerPressScale(interactionSource),
              )
            }
          }
        }
      }

      Slider(
        value = tapTestSlider,
        onValueChange = {
          tapTestSlider = it
          TouchTelemetry.recordControlHandled("tap_test_slider")
        },
        valueRange = 0f..100f,
        interactionSource = sliderInteraction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tap_test_slider"),
      )
    }
  }
}

@Composable
private fun InspectorMiniChip(
  label: String,
  active: Boolean,
  enabled: Boolean = true,
  controlId: String = "inspector_chip_$label",
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()
  val bg =
    when {
      !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
      active && pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
      active -> MaterialTheme.colorScheme.primary
      pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f)
      else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
  val fg =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
      active -> MaterialTheme.colorScheme.onPrimary
      else -> MaterialTheme.colorScheme.onSurface
    }
  FilledTonalButton(
    onClick = {
      TouchTelemetry.recordControlHandled(controlId)
      onClick()
    },
    enabled = enabled,
    interactionSource = interactionSource,
    shape = CircleShape,
    colors =
      ButtonDefaults.filledTonalButtonColors(
        containerColor = bg,
        contentColor = fg,
        disabledContainerColor = bg,
        disabledContentColor = fg,
      ),
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    modifier = modifier.defaultMinSize(minWidth = 44.dp, minHeight = 40.dp).heightIn(min = 40.dp),
  ) {
    Text(
      text = label,
      color = fg,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      modifier = Modifier.innerPressScale(interactionSource),
    )
  }
}

private fun buildSingleStatusSummary(
  uiState: RoadHudUiState,
  currentRoadLabel: String,
  diagnosticReason: String?,
): String {
  val primary = uiState.matchResult.primary
  val offRoad = uiState.offRoadState
  val selectedOffRoad = offRoad.selectedCandidate
  val matchStr =
    when {
      primary != null ->
        String.format(
          Locale.US,
          "%s · conf %.2f · %.0f m",
          currentRoadLabel,
          primary.confidence,
          primary.lateralOffsetMeters,
        )
      selectedOffRoad != null ->
        String.format(
          Locale.US,
          "OFF-ROAD sel %s (%s) · r=%.0f/%.0fm",
          selectedOffRoad.badgeId,
          displayNameOrRef(selectedOffRoad.candidate.name),
          offRoad.matchRadiusMeters,
          offRoad.searchRadiusMeters,
        )
      offRoad.isOffRoad ->
        String.format(
          Locale.US,
          "OFF-ROAD · r=%.0fm/%.0fm · %d cands",
          offRoad.matchRadiusMeters,
          offRoad.searchRadiusMeters,
          offRoad.candidates.size,
        )
      else -> "unknown · conf 0.00 · -- m"
    }
  return if (diagnosticReason != null) "$matchStr | $diagnosticReason" else matchStr
}

private fun computeDiagnosticReason(uiState: RoadHudUiState): String? =
  when {
    uiState.mapStyleError != null -> uiState.mapStyleError
    uiState.loadingStallReason != null -> uiState.loadingStallReason
    uiState.networkError != null -> "Overpass request failed: ${uiState.networkError}"
    uiState.isUsingBasicTileData -> "Using z14 tile graph only (Overpass unavailable)"
    uiState.location == null -> "No GPS fix"
    else -> null
  }

internal fun formatCacheStatsText(uiState: RoadHudUiState): String {
  val ageStr = formatAgeShort(uiState.oldestCacheAgeMs)
  val diskMb = uiState.diskTileBytes / (1024.0 * 1024.0)
  return String.format(
    Locale.US,
    "Cache: %d cells · %d tiles (mem=%d h=%d/m=%d ev=%d disk=%d/%.1fMB) · oldest %s",
    uiState.cachedCellCount,
    uiState.cachedTileCount,
    uiState.memoryTileCount,
    uiState.memoryHits,
    uiState.memoryMisses,
    uiState.memoryEvictions,
    uiState.diskTileCount,
    diskMb,
    ageStr,
  )
}

private fun formatAgeShort(ageMs: Long?): String {
  if (ageMs == null) return "none"
  val totalSec = (ageMs / 1000L).coerceAtLeast(0L)
  return when {
    totalSec < 60L -> "${totalSec}s"
    totalSec < 3600L -> "${totalSec / 60L}m"
    totalSec < 86_400L -> "${totalSec / 3600L}h"
    else -> "${totalSec / 86_400L}d"
  }
}

private fun extractHost(url: String): String =
  runCatching { URI(url).host ?: url }.getOrDefault(url)

private fun displayNameOrRef(name: BilingualName, fallback: String = "unknown"): String {
  val prim = name.primaryText?.takeIf { it.isNotBlank() }
  val en = name.english?.takeIf { it.isNotBlank() }
  val ar = name.arabic?.takeIf { it.isNotBlank() }
  val ref = name.ref?.takeIf { it.isNotBlank() }
  return prim ?: en ?: ar ?: ref ?: fallback
}

private fun shortFeatureTypeName(type: FeatureType): String =
  when (type) {
    FeatureType.FOUR_WAY -> "4-Way"
    FeatureType.MULTI_WAY -> "5-Way+"
    FeatureType.T_JUNCTION -> "T-Junction"
    FeatureType.Y_JUNCTION -> "Y-Junction"
    FeatureType.ROUNDABOUT -> "Roundabout"
    FeatureType.MINI_ROUNDABOUT -> "Mini Roundabout"
    FeatureType.FORK -> "Fork"
    FeatureType.MERGE -> "Merge"
    FeatureType.ON_RAMP -> "On-Ramp"
    FeatureType.OFF_RAMP -> "Off-Ramp"
    FeatureType.BRIDGE -> "Bridge"
    FeatureType.TUNNEL -> "Tunnel"
    FeatureType.OVERPASS_ABOVE -> "Overpass Above"
    FeatureType.UNDERPASS_BELOW -> "Underpass Below"
    FeatureType.U_TURN -> "U-Turn"
    FeatureType.DEAD_END -> "Dead End"
    FeatureType.CUL_DE_SAC -> "Cul-de-Sac"
    FeatureType.SERVICE_ROAD -> "Service Rd"
    FeatureType.SHARP_CURVE -> "Sharp Curve"
    FeatureType.HAIRPIN -> "Hairpin"
    FeatureType.WINDING_ROAD -> "Winding Rd"
    FeatureType.FOOTPATH -> "Footpath"
    FeatureType.CROSSWALK -> "Crosswalk"
    FeatureType.FOOTBRIDGE -> "Footbridge"
    FeatureType.TOLL_PLAZA -> "Toll Plaza"
    FeatureType.CHECKPOINT -> "Checkpoint"
    FeatureType.BARRIER -> "Barrier"
    FeatureType.ROAD_WORKS -> "Road Works"
    FeatureType.STAGGERED_JUNCTION -> "Staggered"
    FeatureType.DIVIDED_ROAD -> "Divided Road"
    FeatureType.MEDIAN_OPENING -> "Median Opening"
    FeatureType.FRONTAGE_ROAD -> "Frontage Road"
    FeatureType.INTERCHANGE -> "Interchange"
    FeatureType.CLOVERLEAF -> "Cloverleaf"
    FeatureType.DIAMOND -> "Diamond"
    FeatureType.TRUMPET -> "Trumpet"
    FeatureType.DIRECTIONAL -> "Directional IC"
  }

private fun shortDirName(dir: RelativeDirection): String =
  when (dir) {
    RelativeDirection.LEFT -> "Left"
    RelativeDirection.RIGHT -> "Right"
    RelativeDirection.AHEAD -> "Ahead"
  }

private fun shortStatusWord(status: BranchStatus): String =
  when (status) {
    BranchStatus.OPEN -> "Open"
    BranchStatus.BLOCKED -> "Blocked"
    BranchStatus.UNVERIFIED -> "Unverified"
  }

private fun expectsMultipleBranches(type: FeatureType): Boolean =
  when (type) {
    FeatureType.FOUR_WAY,
    FeatureType.MULTI_WAY,
    FeatureType.T_JUNCTION,
    FeatureType.Y_JUNCTION,
    FeatureType.ROUNDABOUT,
    FeatureType.FORK,
    FeatureType.MERGE,
    FeatureType.STAGGERED_JUNCTION,
    FeatureType.INTERCHANGE,
    FeatureType.CLOVERLEAF,
    FeatureType.DIAMOND,
    FeatureType.TRUMPET,
    FeatureType.DIRECTIONAL -> true
    else -> false
  }

internal fun buildInspectorPlainText(uiState: RoadHudUiState): String {
  val lines = mutableListOf<String>()
  val loc = uiState.location

  // [POSITION]
  lines += "[POSITION]"
  lines +=
    if (loc != null) {
      String.format(
        Locale.US,
        "%.5f, %.5f | hdg=%.0f° | acc=%s",
        loc.point.lat,
        loc.point.lon,
        loc.headingDeg,
        loc.accuracyMeters?.let { "${it.roundToInt()}m" } ?: "--",
      )
    } else {
      "unknown"
    }

  // [MODE]
  lines += "[MODE]"
  val locMode = if (uiState.isMockModeActive) "MOCK" else "LIVE"
  val jitterState = if (uiState.mockJitterEnabled) "on" else "off"
  val simState = if (uiState.isSimulatingDrive) "sim" else "idle"
  lines +=
    String.format(
      Locale.US,
      "%s | %s(%s) | mockSpd=%dkm/h | mockAcc=%dm | jitter=%s | look=%dm | pins=%d",
      uiState.travelMode.name,
      locMode,
      simState,
      uiState.mockSpeedKmh.roundToInt(),
      uiState.mockAccuracyMeters.roundToInt(),
      jitterState,
      uiState.lookaheadMeters.toInt(),
      uiState.savedPins.size,
    )

  // [ROAD]
  lines += "[ROAD]"
  val p = uiState.matchResult.primary
  if (p != null) {
    lines +=
      String.format(
        Locale.US,
        "%s | conf=%.2f | off=%.1fm | way=%d",
        displayNameOrRef(p.name).take(48),
        p.confidence,
        p.lateralOffsetMeters,
        p.way.id,
      )
  } else if (uiState.offRoadState.isOffRoad) {
    val off = uiState.offRoadState
    lines += "OFF-ROAD r=${off.matchRadiusMeters.toInt()}/${off.searchRadiusMeters.toInt()}m cands=${off.candidates.size}"
    off.selectedCandidate?.let {
      lines += "SEL ${it.badgeId} way=${it.candidate.way.id} fwd=${off.chosenForwardAlongWay}"
    }
  } else {
    lines += "unknown"
  }
  if (uiState.isDividedRoad) {
    lines += "Divided road sep=${uiState.dividedRoadSeparationMeters?.roundToInt() ?: 0}m rule=${uiState.dividedRoadRuleId}"
  }

  // [SOURCES]
  lines += "[SOURCES]"
  lines += "name=${uiState.roadNameSource} tileClass=${uiState.tileFallbackRoad?.roadClass ?: "none"} stale10s=${uiState.isDataStaleOver10s}"
  lines += formatCacheStatsText(uiState)
  if (uiState.pipelineDiagnostics.rollingWindowSummary.isNotBlank() && lines.size < 22) {
    lines += "window: ${uiState.pipelineDiagnostics.rollingWindowSummary}"
  }
  if (uiState.pipelineDiagnostics.horizonStatsSummary.isNotBlank()) {
    lines += "horizon: ${uiState.pipelineDiagnostics.horizonStatsSummary}"
  }
  computeDiagnosticReason(uiState)?.let { reason ->
    lines += "status=${reason.take(96)}"
  }

  // [PIPELINE]
  lines += "[PIPELINE]"
  val stages = uiState.pipelineDiagnostics.stages
  if (stages.isEmpty()) {
    lines += "pending"
  } else {
    stages.forEach { st -> lines += st.toReportLine().take(120) }
  }

  // [OVERRIDES]
  lines += "[OVERRIDES]"
  lines +=
    "poorGps=${uiState.debugPoorGps} offline=${uiState.debugOffline} uncertain=${uiState.debugUncertain} " +
      "blkOvp=${uiState.debugBlockOverpass} blkTile=${uiState.debugBlockTiles} slow=${uiState.debugSlowNetwork} err=${uiState.debugForceOverpassError ?: "off"}"

  // [LAST_5_POS_SOURCES]
  lines += "[LAST_5_POS_SOURCES]"
  val posSources = uiState.recentPositionSources.take(5)
  lines += if (posSources.isEmpty()) "none" else posSources.joinToString(" | ")
  if (uiState.ignoredLiveJumps.isNotEmpty()) {
    lines += "ignoredLiveJumps=${uiState.ignoredLiveJumps.size}: ${uiState.ignoredLiveJumps.first().take(72)}"
  }

  // [LAST_3_REQUESTS]
  lines += "[LAST_3_REQUESTS]"
  val logs = uiState.recentOverpassLogs.take(3)
  if (logs.isEmpty()) {
    lines += "none"
  } else {
    logs.forEachIndexed { idx, log ->
      val netTiming =
        listOfNotNull(
          log.dnsMs?.let { "dns=${it}" },
          log.connectMs?.let { "tcp=${it}" },
          log.tlsMs?.let { "tls=${it}" },
          log.firstByteMs?.let { "ttfb=${it}" },
        ).joinToString(",")
      val tPart = if (netTiming.isNotEmpty()) " [$netTiming]" else ""
      lines +=
        String.format(
          Locale.US,
          "#%d [%s] %s %s status=%s %.1fKB %dms f=%d c=%d%s",
          idx + 1,
          log.source,
          extractHost(log.endpoint),
          log.method,
          log.status,
          log.responseSizeKb,
          log.durationMs,
          log.cellsFetched,
          log.cellsCached,
          tPart,
        )
      if (!log.succeeded && log.failures.isNotEmpty() && lines.size < 44) {
        lines += "ERR: ${log.failures.joinToString("; ").take(84)}"
      }
      if (!log.responseSnippet.isNullOrBlank() && lines.size < 46) {
        lines += "BODY: ${log.responseSnippet.replace('\n', ' ').take(96)}"
      }
      if (idx == 0 && log.queryText.isNotBlank() && lines.size < 48) {
        lines += "QUERY: ${log.queryText.replace('\n', ' ').take(100)}"
      }
    }
  }

  // [FEATURES] — fill remaining budget up to max 60 lines
  val remainingBudget = (60 - lines.size).coerceAtLeast(0)
  if (remainingBudget >= 2) {
    lines += "[FEATURES]"
    val top = uiState.features.take(5)
    if (top.isEmpty()) {
      lines += "none"
    } else {
      for (f in top) {
        if (lines.size >= 60) break
        val fName = displayNameOrRef(BilingualName.fromOsmTags(f.osmTags)).take(28)
        val srcTag = f.source.name.lowercase(Locale.US)
        val ruleStr = if (f.ruleId.isNotBlank()) String.format(Locale.US, " rule=%s(%.2f)", f.ruleId, f.confidence) else ""
        lines += "${f.index}. ${shortFeatureTypeName(f.effectiveDisplayType())} ${f.distanceMeters.toInt()}m ($fName) [$srcTag]$ruleStr"
        for (b in f.branches.take(2)) {
          if (lines.size >= 60) break
          lines += "  ${b.badgeId} ${shortDirName(b.relativeDirection)} ${b.distanceMeters.toInt()}m ${displayNameOrRef(b.name, "unnamed").take(24)} [${shortStatusWord(b.status)}]"
        }
      }
    }
  }

  return lines.take(60).joinToString("\n")
}
