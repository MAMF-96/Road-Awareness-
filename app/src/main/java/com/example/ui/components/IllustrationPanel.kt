package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.FoundRoadMatch
import com.example.data.model.OffRoadCandidate
import com.example.data.model.OffRoadDirectionGroup
import com.example.data.model.OffRoadSide
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadFeature
import com.example.data.model.TravelMode
import com.example.data.model.UnitSystem
import com.example.domain.GeoMath
import com.example.domain.LocalRoadSearchIndex
import com.example.ui.RoadHudUiState
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.GlassTokens
import com.example.ui.theme.LocalGlassConfig
import com.example.ui.theme.LocalRoadColors
import com.example.ui.theme.RoadColors
import kotlin.math.roundToInt

/**
 * Unified top Liquid Glass info card containing Header + Feature Title + Geometric Schematic + Branch/Candidate Info.
 * Always floats over the edge-to-edge map and never exceeds 40% of screen height.
 */
@Composable
fun IllustrationPanel(
  uiState: RoadHudUiState,
  maxScreenHeight: Dp,
  outerVerticalMargin: Dp,
  isLandscape: Boolean,
  isTablet: Boolean,
  isExpanded: Boolean,
  onToggleExpand: (Boolean) -> Unit,
  onSelectFeature: (Int) -> Unit,
  onSelectOffRoadCandidate: (Long) -> Unit = {},
  onSelectOffRoadDirection: (Boolean) -> Unit = {},
  onClearOffRoadSelection: () -> Unit = {},
  onSelectSimulationBranch: (Long) -> Unit = {},
  onRetryRoadData: () -> Unit = {},
  onOpenInspectorSpots: () -> Unit = {},
  onToggleOverview: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val selectedFeature = uiState.selectedFeature

  val hardMaxCardHeight = (maxScreenHeight * 0.40f - outerVerticalMargin).coerceAtLeast(100.dp)
  val maxPanelHeight =
    if (isExpanded) hardMaxCardHeight
    else minOf((maxScreenHeight * 0.36f).coerceAtLeast(200.dp), hardMaxCardHeight)

  val surfaceModifier =
    if (isLandscape) {
      modifier
        .fillMaxHeight()
        .testTag("illustration_panel")
    } else {
      modifier
        .fillMaxWidth()
        .heightIn(max = maxPanelHeight)
        .animateContentSize(animationSpec = spring(stiffness = 650f))
        .testTag("illustration_panel")
    }

  GlassSurface(
    modifier = surfaceModifier,
    shape = RoundedCornerShape(GlassTokens.InfoCardRadius),
    highOpacity = true,
  ) {
    if (isLandscape) {
      LandscapeSidePanelContent(
        uiState = uiState,
        selectedFeature = selectedFeature,
        isTablet = isTablet,
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        onSelectFeature = onSelectFeature,
        onSelectOffRoadCandidate = onSelectOffRoadCandidate,
        onSelectOffRoadDirection = onSelectOffRoadDirection,
        onClearOffRoadSelection = onClearOffRoadSelection,
        onSelectSimulationBranch = onSelectSimulationBranch,
        onRetryRoadData = onRetryRoadData,
        onOpenInspectorSpots = onOpenInspectorSpots,
        onToggleOverview = onToggleOverview,
      )
    } else {
      PortraitAndTabletCardContent(
        uiState = uiState,
        selectedFeature = selectedFeature,
        maxPanelHeight = maxPanelHeight,
        isTablet = isTablet,
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        onSelectFeature = onSelectFeature,
        onSelectOffRoadCandidate = onSelectOffRoadCandidate,
        onSelectOffRoadDirection = onSelectOffRoadDirection,
        onClearOffRoadSelection = onClearOffRoadSelection,
        onSelectSimulationBranch = onSelectSimulationBranch,
        onRetryRoadData = onRetryRoadData,
        onOpenInspectorSpots = onOpenInspectorSpots,
        onToggleOverview = onToggleOverview,
      )
    }
  }
}

@Composable
private fun PortraitAndTabletCardContent(
  uiState: RoadHudUiState,
  selectedFeature: RoadFeature?,
  maxPanelHeight: Dp,
  isTablet: Boolean,
  isExpanded: Boolean,
  onToggleExpand: (Boolean) -> Unit,
  onSelectFeature: (Int) -> Unit,
  onSelectOffRoadCandidate: (Long) -> Unit,
  onSelectOffRoadDirection: (Boolean) -> Unit,
  onClearOffRoadSelection: () -> Unit,
  onSelectSimulationBranch: (Long) -> Unit,
  onRetryRoadData: () -> Unit,
  onOpenInspectorSpots: () -> Unit,
  onToggleOverview: () -> Unit,
) {
  val offRoad = uiState.offRoadState
  val displayRoadCandidate = uiState.matchResult.primary ?: offRoad.selectedCandidate?.candidate
  var dragAccumulator by remember(isExpanded) { mutableFloatStateOf(0f) }
  val hasFeatureOrOffRoadChips =
    uiState.features.size > 1 || (offRoad.isOffRoad && offRoad.selectedCandidate != null)

  Column(
    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // Row 1: ONLY road name using FULL card width (AR line 1, EN line 2)
    HeaderRoadNameSlot(
      uiState = uiState,
      displayRoadCandidate = displayRoadCandidate,
      maxArabicFontSize = if (isTablet) 24.sp else 22.sp,
      minArabicFontSize = 16.sp,
      maxEnglishFontSize = if (isTablet) 18.sp else 16.sp,
      minEnglishFontSize = 13.sp,
      onOpenInspectorSpots = onOpenInspectorSpots,
      modifier = Modifier.fillMaxWidth(),
    )

    // Feature Title (full width, 1-line auto-shrink down to 20sp then wrap max 2 lines)
    FeatureHeaderTitle(
      uiState = uiState,
      selectedFeature = selectedFeature,
      preferredFontSize = if (isTablet) 30.sp else 28.sp,
      minFontSize = 20.sp,
      modifier = Modifier.fillMaxWidth(),
    )

    // Feature selector chips (1 2 3 4) / off-road controls on their own row under Feature Title
    if (hasFeatureOrOffRoadChips) {
      FeatureAndOffRoadChipsRow(
        uiState = uiState,
        selectedFeature = selectedFeature,
        onSelectFeature = onSelectFeature,
        onSelectOffRoadDirection = onSelectOffRoadDirection,
        onClearOffRoadSelection = onClearOffRoadSelection,
      )
    }

    // Body: Left (~40%) transparent schematic canvas + Right (~60%) branches / nearest roads / direction picker
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .then(if (isExpanded) Modifier else Modifier.height(IntrinsicSize.Min)),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      RoadSchematicCanvas(
        feature = selectedFeature,
        travelMode = uiState.travelMode,
        offRoadState = offRoad,
        dividedRoadSideOffsetMeters = uiState.dividedRoadSideOffsetMeters,
        modifier =
          Modifier
            .weight(0.40f)
            .then(
              if (isExpanded) {
                Modifier.height(if (isTablet) 148.dp else 124.dp)
              } else {
                Modifier
                  .fillMaxHeight()
                  .heightIn(min = if (isTablet) 136.dp else 104.dp, max = 124.dp)
              },
            ),
      )

      if (isExpanded) {
        LazyColumn(
          modifier = Modifier.weight(0.60f),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          when {
            offRoad.isOffRoad && offRoad.selectedCandidate == null -> {
              if (offRoad.candidates.isEmpty()) {
                item(key = "offroad_empty") { OffRoadEmptyRow(uiState = uiState, onRetryRoadData = onRetryRoadData) }
              } else {
                itemsIndexed(offRoad.candidates, key = { _, c -> c.candidate.way.id }) { idx, cand ->
                  PortraitOffRoadCandidateRow(
                    candidate = cand,
                    colorIndex = idx + 1,
                    unitSystem = uiState.effectiveUnitSystem,
                    onClick = { onSelectOffRoadCandidate(cand.candidate.way.id) },
                  )
                }
              }
            }
            offRoad.isOffRoad && offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay == null -> {
              itemsIndexed(offRoad.directionGroups, key = { idx, _ -> idx }) { idx, group ->
                DirectionChoiceCardRow(
                  group = group,
                  index = idx,
                  onClick = { onSelectOffRoadDirection(group.forwardAlongWay) },
                )
              }
            }
            selectedFeature == null -> {
              item(key = "no_feature") {
                PortraitEmptyOrErrorRow(uiState = uiState, onRetryRoadData = onRetryRoadData)
              }
            }
            selectedFeature.branches.isEmpty() -> {
              item(key = "non_branching") { NonBranchingDetailRow(feature = selectedFeature, unitSystem = uiState.effectiveUnitSystem) }
            }
            else -> {
              val simSelectable = BuildConfig.DEBUG && uiState.isMockModeActive
              items(selectedFeature.branches, key = { "${it.badgeId}_${it.wayId}" }) { branch ->
                val isSimSelected = simSelectable && uiState.selectedSimulationBranchWayId == branch.wayId
                val isFoundBranch = isFoundBranchMatch(branch, uiState.activeFoundRoad)
                PortraitBranchRow(
                  branch = branch,
                  featureIndex = selectedFeature.index,
                  parentType = selectedFeature.effectiveDisplayType(),
                  staggerOffsetMeters = selectedFeature.staggerOffsetMeters,
                  frontageSide = selectedFeature.frontageSide,
                  unitSystem = uiState.effectiveUnitSystem,
                  isExpanded = true,
                  isFoundHighlight = isFoundBranch,
                  isSelectedForSimulation = isSimSelected,
                  onClickForSimulation =
                    if (simSelectable) {
                      { onSelectSimulationBranch(branch.wayId) }
                    } else {
                      null
                    },
                )
              }
            }
          }

          if (uiState.timelineEntries.isNotEmpty() || uiState.loadedCorridorMeters > 0.0) {
            item(key = "portrait_timeline_section") {
              RoadAheadTimelineSection(
                uiState = uiState,
                onSelectFeature = onSelectFeature,
                onToggleOverview = onToggleOverview,
              )
            }
          }
        }
      } else {
        Column(
          modifier = Modifier.weight(0.60f),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          when {
            offRoad.isOffRoad && offRoad.selectedCandidate == null -> {
              if (offRoad.candidates.isEmpty()) {
                OffRoadEmptyRow(uiState = uiState, onRetryRoadData = onRetryRoadData)
              } else {
                offRoad.candidates.take(2).forEachIndexed { idx, cand ->
                  PortraitOffRoadCandidateRow(
                    candidate = cand,
                    colorIndex = idx + 1,
                    unitSystem = uiState.effectiveUnitSystem,
                    onClick = { onSelectOffRoadCandidate(cand.candidate.way.id) },
                  )
                }
              }
            }
            offRoad.isOffRoad && offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay == null -> {
              offRoad.directionGroups.forEachIndexed { idx, group ->
                DirectionChoiceCardRow(
                  group = group,
                  index = idx,
                  onClick = { onSelectOffRoadDirection(group.forwardAlongWay) },
                )
              }
            }
            selectedFeature == null -> {
              PortraitEmptyOrErrorRow(uiState = uiState, onRetryRoadData = onRetryRoadData)
            }
            selectedFeature.branches.isEmpty() -> {
              NonBranchingDetailRow(feature = selectedFeature, unitSystem = uiState.effectiveUnitSystem)
            }
            else -> {
              val compactCap =
                when {
                  selectedFeature.isInterchangeGroup() -> 3
                  !isTablet && (hasFeatureOrOffRoadChips || maxPanelHeight < 260.dp) -> 1
                  else -> 2
                }
              val shownBranches = selectedFeature.branches.take(compactCap)
              val overflow = (selectedFeature.branches.size - shownBranches.size).coerceAtLeast(0)
              val simSelectable = BuildConfig.DEBUG && uiState.isMockModeActive
              shownBranches.forEach { branch ->
                val isSimSelected = simSelectable && uiState.selectedSimulationBranchWayId == branch.wayId
                val isFoundBranch = isFoundBranchMatch(branch, uiState.activeFoundRoad)
                PortraitBranchRow(
                  branch = branch,
                  featureIndex = selectedFeature.index,
                  parentType = selectedFeature.effectiveDisplayType(),
                  staggerOffsetMeters = selectedFeature.staggerOffsetMeters,
                  frontageSide = selectedFeature.frontageSide,
                  unitSystem = uiState.effectiveUnitSystem,
                  isExpanded = false,
                  isFoundHighlight = isFoundBranch,
                  isSelectedForSimulation = isSimSelected,
                  onClickForSimulation =
                    if (simSelectable) {
                      { onSelectSimulationBranch(branch.wayId) }
                    } else {
                      null
                    },
                )
              }
              if (overflow > 0) {
                MoreBranchesChip(
                  overflow = overflow,
                  compact = false,
                  onClick = { onToggleExpand(true) },
                )
              }
            }
          }
        }
      }
    }

    // 48dp drag handle to switch Compact <-> Expanded (vertical drags restricted strictly to this handle)
    val handleInteraction = rememberPressHapticInteractionSource()
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(48.dp)
          .clickable(
            interactionSource = handleInteraction,
            indication = null,
          ) {
            TouchTelemetry.recordControlHandled("panel_drag_handle")
            onToggleExpand(!isExpanded)
          }
          .pointerInput(isExpanded) {
            detectVerticalDragGestures(
              onDragStart = { dragAccumulator = 0f },
              onDragCancel = { dragAccumulator = 0f },
              onDragEnd = {
                if (dragAccumulator > 18f) {
                  TouchTelemetry.recordControlHandled("panel_drag_handle")
                  onToggleExpand(true)
                } else if (dragAccumulator < -18f) {
                  TouchTelemetry.recordControlHandled("panel_drag_handle")
                  onToggleExpand(false)
                }
                dragAccumulator = 0f
              },
              onVerticalDrag = { _, dragAmount -> dragAccumulator += dragAmount },
            )
          }
          .testTag("panel_drag_handle"),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier =
          Modifier
            .innerPressScale(handleInteraction)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
      )
    }
  }
}

@Composable
private fun PortraitEmptyOrErrorRow(
  uiState: RoadHudUiState,
  onRetryRoadData: () -> Unit = {},
) {
  when {
    uiState.isGraphErrorState -> {
      val failedStage = uiState.roadDataFailedStage
      val failureMsg =
        when {
          failedStage != null -> stringResource(R.string.roads_failed_stage, failedStage)
          uiState.isDataStaleOver10s -> stringResource(R.string.roads_unavailable_retrying)
          else -> stringResource(R.string.roads_searching)
        }
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth().testTag("panel_failure_notice"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = failureMsg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
          )
          if (failedStage != null) {
            TextButton(
              onClick = {
                TouchTelemetry.recordControlHandled("panel_retry_button")
                onRetryRoadData()
              },
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 36.dp).testTag("panel_retry_button"),
            ) {
              Text(
                text = stringResource(R.string.action_retry),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
    uiState.isLoadingGraph || (!uiState.hasConfirmedGraphData && !(BuildConfig.DEBUG && uiState.isMockModeActive && uiState.location == null) && uiState.pipelineDiagnostics.stages.none { it.isEmptyArea }) -> {
      PanelRowsSkeleton(count = 2)
    }
    BuildConfig.DEBUG && uiState.isMockModeActive && uiState.location == null -> {
      Spacer(modifier = Modifier.height(4.dp))
    }
    uiState.pipelineDiagnostics.stages.any { it.isEmptyArea } -> {
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth().testTag("panel_empty_area_notice"),
      ) {
        Text(
          text = stringResource(R.string.no_map_data_here),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
      }
    }
    else -> {
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text =
            stringResource(
              R.string.no_features_ahead,
              formatRoadDistance(uiState.lookaheadMeters, uiState.effectiveUnitSystem),
            ),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun LandscapeSidePanelContent(
  uiState: RoadHudUiState,
  selectedFeature: RoadFeature?,
  isTablet: Boolean,
  isExpanded: Boolean,
  onToggleExpand: (Boolean) -> Unit,
  onSelectFeature: (Int) -> Unit,
  onSelectOffRoadCandidate: (Long) -> Unit,
  onSelectOffRoadDirection: (Boolean) -> Unit,
  onClearOffRoadSelection: () -> Unit,
  onSelectSimulationBranch: (Long) -> Unit,
  onRetryRoadData: () -> Unit,
  onOpenInspectorSpots: () -> Unit,
  onToggleOverview: () -> Unit,
) {
  val offRoad = uiState.offRoadState
  val displayRoadCandidate = uiState.matchResult.primary ?: offRoad.selectedCandidate?.candidate
  val hasOffRoadControls = offRoad.isOffRoad && offRoad.selectedCandidate != null

  BoxWithConstraints(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 0.dp),
  ) {
    val panelH = maxHeight
    val innerW = maxWidth
    val compactSpace = panelH < 430.dp
    val sectionSpacing = if (compactSpace) 1.dp else 5.dp
    val schematicFraction = if (compactSpace) 0.30f else 0.35f
    val maxSchematicH = minOf(innerW, panelH * 0.40f).coerceAtLeast(84.dp)
    val schematicH = (panelH * schematicFraction).coerceIn(84.dp, maxSchematicH)

    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
      // (a) Road name block: AR 20sp bold over EN 14sp, auto-shrink then wrap, never ellipsis
      HeaderRoadNameSlot(
        uiState = uiState,
        displayRoadCandidate = displayRoadCandidate,
        maxArabicFontSize = 20.sp,
        minArabicFontSize = 14.sp,
        maxEnglishFontSize = 14.sp,
        minEnglishFontSize = 11.sp,
        onOpenInspectorSpots = onOpenInspectorSpots,
        modifier = Modifier.fillMaxWidth(),
      )

      // (b) Feature title + feature selector as dots on ONE row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FeatureHeaderTitle(
          uiState = uiState,
          selectedFeature = selectedFeature,
          preferredFontSize = 18.sp,
          minFontSize = 13.sp,
          modifier = Modifier.weight(1f),
        )
        FeatureSelectorDots(
          features = uiState.features,
          selectedIndex = selectedFeature?.index,
          onSelectFeature = onSelectFeature,
        )
      }

      if (hasOffRoadControls) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay != null) {
            SmallCardActionChip(
              icon = Icons.Default.SwapHoriz,
              label = "",
              onClick = { onSelectOffRoadDirection(!offRoad.chosenForwardAlongWay) },
              modifier = Modifier.testTag("offroad_flip_direction_button"),
            )
          }
          SmallCardActionChip(
            icon = Icons.Default.Close,
            label = stringResource(R.string.action_clear),
            onClick = onClearOffRoadSelection,
            modifier = Modifier.testTag("offroad_clear_button"),
          )
        }
      }

      // (c) Schematic: keeps 1:1 aspect ratio, 30-40% of panel height (min 84dp), user at bottom
      RoadSchematicCanvas(
        feature = selectedFeature,
        travelMode = uiState.travelMode,
        offRoadState = offRoad,
        dividedRoadSideOffsetMeters = uiState.dividedRoadSideOffsetMeters,
        modifier =
          Modifier
            .align(Alignment.CenterHorizontally)
            .height(schematicH)
            .aspectRatio(1f),
      )

      // (d) Branches / status area
      Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
      ) {
        if (isExpanded) {
          LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("landscape_expanded_branches_list"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            when {
              offRoad.isOffRoad && offRoad.selectedCandidate == null -> {
                if (offRoad.candidates.isEmpty()) {
                  item(key = "offroad_empty") { OffRoadEmptyRow(uiState = uiState, onRetryRoadData = onRetryRoadData) }
                } else {
                  itemsIndexed(offRoad.candidates, key = { _, c -> c.candidate.way.id }) { idx, cand ->
                    PortraitOffRoadCandidateRow(
                      candidate = cand,
                      colorIndex = idx + 1,
                      unitSystem = uiState.effectiveUnitSystem,
                      onClick = { onSelectOffRoadCandidate(cand.candidate.way.id) },
                    )
                  }
                }
              }
              offRoad.isOffRoad && offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay == null -> {
                itemsIndexed(offRoad.directionGroups, key = { idx, _ -> idx }) { idx, group ->
                  DirectionChoiceCardRow(
                    group = group,
                    index = idx,
                    onClick = { onSelectOffRoadDirection(group.forwardAlongWay) },
                  )
                }
              }
              selectedFeature == null -> {
                item(key = "landscape_empty") { LandscapeEmptyOrErrorRow(uiState = uiState, onRetryRoadData = onRetryRoadData) }
              }
              selectedFeature.branches.isEmpty() -> {
                item(key = "non_branching") { NonBranchingDetailRow(feature = selectedFeature, unitSystem = uiState.effectiveUnitSystem) }
              }
              else -> {
                val simSelectable = BuildConfig.DEBUG && uiState.isMockModeActive
                items(selectedFeature.branches, key = { "${it.badgeId}_${it.wayId}" }) { branch ->
                  val isSimSelected = simSelectable && uiState.selectedSimulationBranchWayId == branch.wayId
                  val isFoundBranch = isFoundBranchMatch(branch, uiState.activeFoundRoad)
                  PortraitBranchRow(
                    branch = branch,
                    featureIndex = selectedFeature.index,
                    parentType = selectedFeature.effectiveDisplayType(),
                    staggerOffsetMeters = selectedFeature.staggerOffsetMeters,
                    frontageSide = selectedFeature.frontageSide,
                    unitSystem = uiState.effectiveUnitSystem,
                    isExpanded = true,
                    isFoundHighlight = isFoundBranch,
                    isSelectedForSimulation = isSimSelected,
                    onClickForSimulation =
                      if (simSelectable) {
                        { onSelectSimulationBranch(branch.wayId) }
                      } else {
                        null
                      },
                  )
                }
              }
            }

            if (isTablet && uiState.features.isNotEmpty()) {
              item(key = "upcoming_features") {
                SidePanelUpcomingFeaturesSection(
                  features = uiState.features,
                  selectedIndex = uiState.selectedFeatureIndex,
                  unitSystem = uiState.effectiveUnitSystem,
                  onSelect = onSelectFeature,
                )
              }
            }

            if (uiState.timelineEntries.isNotEmpty() || uiState.loadedCorridorMeters > 0.0) {
              item(key = "landscape_timeline_section") {
                RoadAheadTimelineSection(
                  uiState = uiState,
                  onSelectFeature = onSelectFeature,
                  onToggleOverview = onToggleOverview,
                )
              }
            }
          }
        } else {
          when {
            offRoad.isOffRoad && offRoad.selectedCandidate == null -> {
              if (offRoad.candidates.isEmpty()) {
                OffRoadEmptyRow(uiState = uiState, onRetryRoadData = onRetryRoadData)
              } else {
                WholeRowBranchPacker(
                  itemCount = offRoad.candidates.size,
                  spacingDp = if (compactSpace) 2.dp else 4.dp,
                  modifier = Modifier.fillMaxSize(),
                  itemContent = { idx, _ ->
                    val cand = offRoad.candidates[idx]
                    PortraitOffRoadCandidateRow(
                      candidate = cand,
                      colorIndex = idx + 1,
                      unitSystem = uiState.effectiveUnitSystem,
                      onClick = { onSelectOffRoadCandidate(cand.candidate.way.id) },
                    )
                  },
                  overflowChipContent = { overflow, forMeasurementOnly ->
                    MoreBranchesChip(
                      overflow = overflow,
                      compact = compactSpace,
                      onClick = { onToggleExpand(true) },
                      attachTestTag = !forMeasurementOnly,
                    )
                  },
                )
              }
            }
            offRoad.isOffRoad && offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay == null -> {
              WholeRowBranchPacker(
                itemCount = offRoad.directionGroups.size,
                spacingDp = if (compactSpace) 2.dp else 4.dp,
                modifier = Modifier.fillMaxSize(),
                itemContent = { idx, _ ->
                  val group = offRoad.directionGroups[idx]
                  DirectionChoiceCardRow(
                    group = group,
                    index = idx,
                    onClick = { onSelectOffRoadDirection(group.forwardAlongWay) },
                  )
                },
                overflowChipContent = { overflow, forMeasurementOnly ->
                  MoreBranchesChip(
                    overflow = overflow,
                    compact = compactSpace,
                    onClick = { onToggleExpand(true) },
                    attachTestTag = !forMeasurementOnly,
                  )
                },
              )
            }
            selectedFeature == null -> {
              LandscapeEmptyOrErrorRow(uiState = uiState, onRetryRoadData = onRetryRoadData)
            }
            selectedFeature.branches.isEmpty() -> {
              NonBranchingDetailRow(feature = selectedFeature, unitSystem = uiState.effectiveUnitSystem)
            }
            else -> {
              val simSelectable = BuildConfig.DEBUG && uiState.isMockModeActive
              WholeRowBranchPacker(
                itemCount = selectedFeature.branches.size,
                spacingDp = if (compactSpace) 2.dp else 4.dp,
                modifier = Modifier.fillMaxSize(),
                itemContent = { idx, forMeasurementOnly ->
                  val branch = selectedFeature.branches[idx]
                  val isSimSelected = simSelectable && uiState.selectedSimulationBranchWayId == branch.wayId
                  val isFoundBranch = isFoundBranchMatch(branch, uiState.activeFoundRoad)
                  LandscapeSideCompactBranchRow(
                    branch = branch,
                    featureIndex = selectedFeature.index,
                    parentType = selectedFeature.effectiveDisplayType(),
                    unitSystem = uiState.effectiveUnitSystem,
                    compact = compactSpace,
                    attachTestTag = !forMeasurementOnly,
                    isFoundHighlight = isFoundBranch,
                    isSelectedForSimulation = isSimSelected,
                    onClickForSimulation =
                      if (simSelectable) {
                        { onSelectSimulationBranch(branch.wayId) }
                      } else {
                        null
                      },
                  )
                },
                overflowChipContent = { overflow, forMeasurementOnly ->
                  MoreBranchesChip(
                    overflow = overflow,
                    compact = compactSpace,
                    onClick = { onToggleExpand(true) },
                    attachTestTag = !forMeasurementOnly,
                  )
                },
              )
            }
          }
        }
      }

      // Bottom edge chevron toggle button (Material3 TextButton, no vertical drag)
      val chevronInteraction = rememberPressHapticInteractionSource()
      val chevronPressed by chevronInteraction.collectIsPressedAsState()
      val chevronHeight = if (compactSpace) 24.dp else 48.dp
      TextButton(
        onClick = {
          TouchTelemetry.recordControlHandled("panel_chevron_toggle")
          onToggleExpand(!isExpanded)
        },
        interactionSource = chevronInteraction,
        shape = RoundedCornerShape(14.dp),
        colors =
          ButtonDefaults.textButtonColors(
            containerColor =
              if (chevronPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
              else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        contentPadding = PaddingValues(0.dp),
        modifier =
          Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = chevronHeight)
            .height(chevronHeight)
            .testTag("panel_chevron_toggle"),
      ) {
        Icon(
          imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = stringResource(if (isExpanded) R.string.panel_collapse else R.string.panel_expand),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.innerPressScale(chevronInteraction).size(20.dp),
        )
      }
    }
  }
}

@Composable
private fun LandscapeEmptyOrErrorRow(
  uiState: RoadHudUiState,
  onRetryRoadData: () -> Unit = {},
) {
  when {
    uiState.isGraphErrorState -> {
      val failedStage = uiState.roadDataFailedStage
      val failureMsg =
        when {
          failedStage != null -> stringResource(R.string.roads_failed_stage, failedStage)
          uiState.isDataStaleOver10s -> stringResource(R.string.roads_unavailable_retrying)
          else -> stringResource(R.string.roads_searching)
        }
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth().testTag("panel_failure_notice"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = failureMsg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f),
          )
          if (failedStage != null) {
            TextButton(
              onClick = {
                TouchTelemetry.recordControlHandled("panel_retry_button")
                onRetryRoadData()
              },
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 36.dp).testTag("panel_retry_button"),
            ) {
              Text(
                text = stringResource(R.string.action_retry),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
    uiState.isLoadingGraph || (!uiState.hasConfirmedGraphData && !(BuildConfig.DEBUG && uiState.isMockModeActive && uiState.location == null) && uiState.pipelineDiagnostics.stages.none { it.isEmptyArea }) -> {
      PanelRowsSkeleton(count = 2)
    }
    BuildConfig.DEBUG && uiState.isMockModeActive && uiState.location == null -> {
      Spacer(modifier = Modifier.height(4.dp))
    }
    uiState.pipelineDiagnostics.stages.any { it.isEmptyArea } -> {
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth().testTag("panel_empty_area_notice"),
      ) {
        Text(
          text = stringResource(R.string.no_map_data_here),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
      }
    }
    else -> {
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text =
            stringResource(
              R.string.no_features_ahead,
              formatRoadDistance(uiState.lookaheadMeters, uiState.effectiveUnitSystem),
            ),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun MoreBranchesChip(
  overflow: Int,
  compact: Boolean,
  onClick: () -> Unit,
  attachTestTag: Boolean = true,
) {
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()
  val isDark = LocalGlassConfig.current.isDark
  val chipMinH = if (compact) 26.dp else 48.dp
  Button(
    onClick = {
      TouchTelemetry.recordControlHandled("more_branches_chip")
      onClick()
    },
    interactionSource = interactionSource,
    shape = CircleShape,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
      ),
    elevation = null,
    contentPadding = PaddingValues(0.dp),
    modifier =
      Modifier
        .defaultMinSize(minWidth = 48.dp, minHeight = chipMinH)
        .heightIn(max = chipMinH)
        .then(if (attachTestTag) Modifier.testTag("more_branches_chip") else Modifier),
  ) {
    GlassSurface(
      shape = CircleShape,
      isLighterInner = true,
      withShadow = false,
      tintOverlay =
        if (pressed) {
          if (isDark) Color.White.copy(alpha = 0.16f) else Color(0x180A64D8)
        } else {
          Color.Transparent
        },
      modifier = Modifier.innerPressScale(interactionSource),
    ) {
      Text(
        text = stringResource(R.string.more_branches_format, overflow),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = if (compact) 11.sp else 13.sp,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = if (compact) 1.dp else 6.dp),
      )
    }
  }
}

@Composable
private fun WholeRowBranchPacker(
  itemCount: Int,
  spacingDp: Dp = 4.dp,
  modifier: Modifier = Modifier,
  itemContent: @Composable (index: Int, forMeasurementOnly: Boolean) -> Unit,
  overflowChipContent: @Composable (remainingCount: Int, forMeasurementOnly: Boolean) -> Unit,
) {
  SubcomposeLayout(modifier = modifier) { constraints ->
    val maxH = constraints.maxHeight
    val width = constraints.maxWidth
    val spacingPx = spacingDp.roundToPx()
    val itemConstraints = Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity)
    val chipConstraints = Constraints(minWidth = 0, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity)

    val probeChipPlaceable =
      if (itemCount > 0) {
        subcompose("chip_probe") { overflowChipContent(itemCount, true) }.firstOrNull()?.measure(chipConstraints)
      } else {
        null
      }
    val chipH = probeChipPlaceable?.height ?: 0

    var y = 0
    var fittedCount = 0

    for (i in 0 until itemCount) {
      val measured = subcompose("probe_item_$i") { itemContent(i, true) }.firstOrNull()?.measure(itemConstraints) ?: continue
      val gapBefore = if (fittedCount > 0) spacingPx else 0
      val remainingAfterThis = itemCount - (i + 1)
      val neededChipSpace = if (remainingAfterThis > 0 && chipH > 0) spacingPx + chipH else 0
      if (y + gapBefore + measured.height + neededChipSpace <= maxH) {
        y += gapBefore + measured.height
        fittedCount++
      } else {
        break
      }
    }

    val placeables =
      (0 until fittedCount).mapNotNull { i ->
        subcompose("placed_item_$i") { itemContent(i, false) }.firstOrNull()?.measure(itemConstraints)
      }

    val overflow = itemCount - placeables.size
    val finalChipPlaceable =
      if (overflow > 0) {
        subcompose("chip_final") { overflowChipContent(overflow, false) }.firstOrNull()?.measure(chipConstraints)
      } else {
        null
      }
    val chipToPlace =
      if (finalChipPlaceable != null) {
        val gapBeforeChip = if (placeables.isNotEmpty()) spacingPx else 0
        if (y + gapBeforeChip + finalChipPlaceable.height <= maxH) finalChipPlaceable else null
      } else {
        null
      }

    layout(width, maxH) {
      var curY = 0
      placeables.forEachIndexed { idx, p ->
        if (idx > 0) curY += spacingPx
        p.placeRelative(0, curY)
        curY += p.height
      }
      if (chipToPlace != null) {
        if (placeables.isNotEmpty()) curY += spacingPx
        chipToPlace.placeRelative(0, curY)
      }
    }
  }
}

@Composable
private fun FeatureSelectorDots(
  features: List<RoadFeature>,
  selectedIndex: Int?,
  onSelectFeature: (Int) -> Unit,
) {
  if (features.size <= 1) return
  Row(
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    features.take(4).forEach { feat ->
      val isSelected = feat.index == selectedIndex
      val pinColor = RoadColors.pinColorForIndex(feat.index)
      val dotSize = if (isSelected) 14.dp else 9.dp
      val interactionSource = rememberPressHapticInteractionSource()
      val pressed by interactionSource.collectIsPressedAsState()
      Surface(
        onClick = {
          TouchTelemetry.recordControlHandled("feature_chip_${feat.index}")
          onSelectFeature(feat.index)
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        color =
          if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
          else Color.Transparent,
        modifier =
          Modifier
            .size(26.dp)
            .testTag("feature_chip_${feat.index}"),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Box(
            modifier =
              Modifier
                .innerPressScale(interactionSource)
                .size(dotSize)
                .clip(CircleShape)
                .background(
                  if (isSelected) pinColor
                  else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
                .then(
                  if (isSelected) Modifier.border(1.5.dp, Color.White, CircleShape)
                  else Modifier,
                ),
          )
        }
      }
    }
  }
}

@Composable
private fun LandscapeSideCompactBranchRow(
  branch: RoadBranch,
  featureIndex: Int,
  parentType: FeatureType,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  compact: Boolean = false,
  attachTestTag: Boolean = true,
  isFoundHighlight: Boolean = false,
  isSelectedForSimulation: Boolean = false,
  onClickForSimulation: (() -> Unit)? = null,
) {
  val badgeColor = RoadColors.pinColorForIndex(featureIndex)
  val dirLabel = relativeDirText(branch.relativeDirection)
  val (statusWord, statusIcon, statusFg, statusBg) = branchStatusVisuals(branch.status)
  val isRoadWorksBranch =
    parentType == FeatureType.ROAD_WORKS ||
      branch.osmTags.highway?.lowercase() == "construction" ||
      branch.osmTags.access?.lowercase() == "no"
  val isDark = LocalGlassConfig.current.isDark

  val borderMod =
    when {
      isFoundHighlight -> Modifier.border(2.dp, RoadColors.PinPurple, RoundedCornerShape(14.dp))
      isSelectedForSimulation -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
      else -> Modifier
    }

  val tagMod = if (attachTestTag) Modifier.testTag("branch_row_${branch.badgeId}") else Modifier

  val rowContent: @Composable (Modifier, Color) -> Unit = { innerMod, pressedTint ->
    GlassSurface(
      shape = RoundedCornerShape(14.dp),
      isLighterInner = true,
      withShadow = false,
      tintOverlay = pressedTint,
      modifier =
        innerMod
          .fillMaxWidth()
          .then(if (compact) Modifier.heightIn(max = 34.dp) else Modifier)
          .then(borderMod),
    ) {
      if (compact) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Box(
            modifier =
              Modifier
                .clip(CircleShape)
                .background(badgeColor)
                .padding(horizontal = 5.dp, vertical = 1.dp),
          ) {
            Text(text = branch.badgeId, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
          }
          Text(
            text = dirLabel,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
          Box(modifier = Modifier.weight(1f)) {
            val compactName = branch.name.primaryText ?: branch.name.ref ?: stringResource(R.string.unnamed_branch)
            Text(
              text = compactName,
              fontSize = 11.5.sp,
              lineHeight = 13.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
            )
          }
          Icon(
            imageVector = statusIcon,
            contentDescription = statusWord,
            tint = statusFg,
            modifier = Modifier.size(12.dp),
          )
          if (isFoundHighlight) {
            Box(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(RoadColors.PinPurple)
                  .padding(horizontal = 5.dp, vertical = 1.dp)
                  .then(if (attachTestTag) Modifier.testTag("found_match_badge") else Modifier),
            ) {
              Text(
                text = stringResource(R.string.found_match_badge),
                color = Color.White,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
              )
            }
          }
          Text(
            text = formatRoadDistance(branch.distanceMeters, unitSystem),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
        }
      } else {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(badgeColor)
                  .padding(horizontal = 5.dp, vertical = 0.5.dp),
            ) {
              Text(text = branch.badgeId, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
              text = dirLabel,
              fontSize = 10.sp,
              lineHeight = 12.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
            Box(modifier = Modifier.weight(1f)) {
              val compactName = branch.name.primaryText ?: branch.name.ref ?: stringResource(R.string.unnamed_branch)
              Text(
                text = compactName,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
              )
            }
            Text(
              text = formatRoadDistance(branch.distanceMeters, unitSystem),
              fontSize = 11.sp,
              lineHeight = 13.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
            )
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Row(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(statusBg)
                  .border(1.dp, statusFg.copy(alpha = 0.30f), CircleShape)
                  .padding(horizontal = 6.dp, vertical = 1.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Icon(imageVector = statusIcon, contentDescription = statusWord, tint = statusFg, modifier = Modifier.size(10.dp))
              Text(text = statusWord, color = statusFg, fontSize = 9.5.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (branch.status == BranchStatus.BLOCKED) {
              Text(
                text =
                  if (isRoadWorksBranch) stringResource(R.string.branch_blocked_road_works)
                  else stringResource(R.string.branch_blocked_reason),
                color = LocalRoadColors.current.blockedText,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
              )
            }
            if (isFoundHighlight) {
              Box(
                modifier =
                  Modifier
                    .clip(CircleShape)
                    .background(RoadColors.PinPurple)
                    .padding(horizontal = 5.dp, vertical = 0.5.dp)
                    .then(if (attachTestTag) Modifier.testTag("found_match_badge") else Modifier),
              ) {
                Text(
                  text = stringResource(R.string.found_match_badge),
                  color = Color.White,
                  fontSize = 9.sp,
                  lineHeight = 11.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
            if (isSelectedForSimulation) {
              Box(
                modifier =
                  Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 5.dp, vertical = 0.5.dp)
                    .testTag("sim_branch_badge_${branch.badgeId}"),
              ) {
                Text(
                  text = stringResource(R.string.sim_branch_selected),
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  fontSize = 9.sp,
                  lineHeight = 11.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
      }
    }
  }

  if (onClickForSimulation != null) {
    val interactionSource = rememberPressHapticInteractionSource()
    val pressed by interactionSource.collectIsPressedAsState()
    val tint =
      if (pressed) {
        if (isDark) Color.White.copy(alpha = 0.14f) else Color(0x140A64D8)
      } else Color.Transparent
    val minTouchSize = if (compact) Dp.Unspecified else 48.dp
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides minTouchSize) {
      Surface(
        onClick = {
          TouchTelemetry.recordControlHandled("branch_row_${branch.badgeId}")
          onClickForSimulation()
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().then(tagMod),
      ) {
        rowContent(Modifier.innerPressScale(interactionSource), tint)
      }
    }
  } else {
    rowContent(tagMod, Color.Transparent)
  }
}

@Composable
private fun SidePanelUpcomingFeaturesSection(
  features: List<RoadFeature>,
  selectedIndex: Int,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  onSelect: (Int) -> Unit,
) {
  val roadColors = LocalRoadColors.current
  val isDark = LocalGlassConfig.current.isDark
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = 6.dp)
        .testTag("upcoming_features_panel"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    HorizontalDivider(color = roadColors.divider)
    Text(
      text = stringResource(R.string.upcoming_features_title),
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(top = 2.dp),
    )
    features.forEach { feat ->
      val selected = feat.index == selectedIndex
      val interactionSource = rememberPressHapticInteractionSource()
      val pressed by interactionSource.collectIsPressedAsState()
      Surface(
        onClick = {
          TouchTelemetry.recordControlHandled("upcoming_feature_${feat.index}")
          onSelect(feat.index)
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        GlassSurface(
          shape = RoundedCornerShape(12.dp),
          isLighterInner = true,
          withShadow = false,
          tintOverlay =
            if (pressed) {
              if (isDark) Color.White.copy(alpha = 0.14f) else Color(0x140A64D8)
            } else Color.Transparent,
          modifier = Modifier.fillMaxWidth().innerPressScale(interactionSource),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.weight(1f),
            ) {
              Surface(
                shape = CircleShape,
                color = RoadColors.pinColorForIndex(feat.index),
              ) {
                Text(
                  text = "${feat.index}",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
              Text(
                text = featureTypeTitle(feat.effectiveDisplayType()),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
              )
            }
            Text(
              text = formatRoadDistance(feat.distanceMeters, unitSystem),
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RoadAheadTimelineSection(
  uiState: RoadHudUiState,
  onSelectFeature: (Int) -> Unit,
  onToggleOverview: () -> Unit,
) {
  val roadColors = LocalRoadColors.current
  val isDark = LocalGlassConfig.current.isDark
  val entries = uiState.timelineEntries

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = 6.dp)
        .testTag("road_ahead_timeline"),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    HorizontalDivider(color = roadColors.divider)

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.timeline_title),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (uiState.landmarksAhead.isNotEmpty()) {
          Text(
            text = stringResource(R.string.landmarks_ahead_format, uiState.landmarksAhead.size),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      val overviewInteraction = rememberPressHapticInteractionSource()
      FilledTonalButton(
        onClick = {
          TouchTelemetry.recordControlHandled("overview_toggle_button")
          onToggleOverview()
        },
        interactionSource = overviewInteraction,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).testTag("overview_toggle_button"),
      ) {
        Row(
          modifier = Modifier.innerPressScale(overviewInteraction),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Map,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text =
              stringResource(
                if (uiState.isOverviewMode) R.string.action_exit_overview
                else R.string.action_overview,
              ),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }
      }
    }

    entries.forEachIndexed { idx, entry ->
      val interactionSource = rememberPressHapticInteractionSource()
      val pressed by interactionSource.collectIsPressedAsState()
      val isFoundEntry = isFoundTimelineMatch(entry, uiState.activeFoundRoad)
      val badgeColor =
        when {
          isFoundEntry -> RoadColors.PinPurple
          entry.isLandmark -> MaterialTheme.colorScheme.tertiary
          else -> RoadColors.pinColorForIndex(entry.featureIndex ?: (idx + 1))
        }

      val cardRow: @Composable (Modifier) -> Unit = { mod ->
        GlassSurface(
          shape = RoundedCornerShape(12.dp),
          isLighterInner = true,
          withShadow = false,
          tintOverlay =
            if (pressed) {
              if (isDark) Color.White.copy(alpha = 0.14f) else Color(0x140A64D8)
            } else {
              Color.Transparent
            },
          modifier =
            mod
              .fillMaxWidth()
              .then(if (isFoundEntry) Modifier.border(2.dp, RoadColors.PinPurple, RoundedCornerShape(12.dp)) else Modifier),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (entry.isLandmark) {
              Box(
                modifier =
                  Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Default.Place,
                  contentDescription = null,
                  tint = badgeColor,
                  modifier = Modifier.size(14.dp),
                )
              }
            } else {
              Surface(shape = CircleShape, color = badgeColor) {
                Text(
                  text = entry.featureIndex?.toString() ?: "•",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
              val label =
                entry.title.primaryText
                  ?: entry.title.ref
                  ?: if (entry.isLandmark) "" else featureTypeTitle(entry.type)
              Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (!entry.branchSummary.isNullOrBlank()) {
                Text(
                  text = entry.branchSummary,
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }

            if (isFoundEntry) {
              Box(
                modifier =
                  Modifier
                    .clip(CircleShape)
                    .background(RoadColors.PinPurple)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("found_timeline_badge_$idx"),
              ) {
                Text(
                  text = stringResource(R.string.found_match_badge),
                  color = Color.White,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
            }

            Text(
              text = formatRoadDistance(entry.distanceMeters, uiState.effectiveUnitSystem),
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = if (isFoundEntry) RoadColors.PinPurple else MaterialTheme.colorScheme.primary,
            )
          }
        }
      }

      if (entry.featureIndex != null) {
        Surface(
          onClick = {
            TouchTelemetry.recordControlHandled("timeline_entry_$idx")
            onSelectFeature(entry.featureIndex)
          },
          interactionSource = interactionSource,
          shape = RoundedCornerShape(12.dp),
          color = Color.Transparent,
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("timeline_entry_$idx"),
        ) {
          cardRow(Modifier.innerPressScale(interactionSource))
        }
      } else {
        cardRow(Modifier.testTag("timeline_entry_$idx"))
      }
    }

    val loadedDistText = formatRoadDistance(uiState.loadedCorridorMeters, uiState.effectiveUnitSystem)
    val footerParts = buildList {
      add(stringResource(R.string.timeline_loaded_format, loadedDistText))
      if (uiState.hiddenMinorCount > 0) {
        add(stringResource(R.string.timeline_hidden_minor_format, uiState.hiddenMinorCount))
      }
    }
    Text(
      text = footerParts.joinToString(" · "),
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp, bottom = 4.dp).testTag("timeline_footer"),
    )
  }
}

@Composable
private fun FeatureAndOffRoadChipsRow(
  uiState: RoadHudUiState,
  selectedFeature: RoadFeature?,
  onSelectFeature: (Int) -> Unit,
  onSelectOffRoadDirection: (Boolean) -> Unit,
  onClearOffRoadSelection: () -> Unit,
) {
  val offRoad = uiState.offRoadState
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FeatureSwitcherChips(
      features = uiState.features,
      selectedIndex = selectedFeature?.index,
      onSelectFeature = onSelectFeature,
    )
    if (offRoad.isOffRoad && offRoad.selectedCandidate != null) {
      if (offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay != null) {
        SmallCardActionChip(
          icon = Icons.Default.SwapHoriz,
          label = "",
          onClick = { onSelectOffRoadDirection(!offRoad.chosenForwardAlongWay) },
          modifier = Modifier.testTag("offroad_flip_direction_button"),
        )
      }
      SmallCardActionChip(
        icon = Icons.Default.Close,
        label = stringResource(R.string.action_clear),
        onClick = onClearOffRoadSelection,
        modifier = Modifier.testTag("offroad_clear_button"),
      )
    }
  }
}

@Composable
private fun FeatureHeaderTitle(
  uiState: RoadHudUiState,
  selectedFeature: RoadFeature?,
  preferredFontSize: androidx.compose.ui.unit.TextUnit,
  minFontSize: androidx.compose.ui.unit.TextUnit,
  modifier: Modifier = Modifier,
) {
  val offRoad = uiState.offRoadState
  when {
    offRoad.isOffRoad && offRoad.selectedCandidate == null -> {
      if (uiState.isLoadingGraph && offRoad.candidates.isEmpty() && !uiState.hasConfirmedGraphData) {
        SkeletonShimmerBar(
          modifier = modifier.height(26.dp).fillMaxWidth(0.55f).testTag("panel_title_skeleton"),
        )
      } else {
        AutoShrinkSingleLineText(
          text = stringResource(R.string.offroad_nearest_roads),
          preferredFontSize = preferredFontSize,
          minFontSize = minFontSize,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          modifier = modifier.testTag("offroad_panel_title"),
        )
      }
    }
    offRoad.isOffRoad && offRoad.needsDirectionChoice && offRoad.chosenForwardAlongWay == null -> {
      AutoShrinkSingleLineText(
        text = stringResource(R.string.offroad_choose_direction),
        preferredFontSize = 18.sp,
        minFontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        modifier = modifier.testTag("offroad_direction_title"),
      )
    }
    selectedFeature != null -> {
      val distStr = formatRoadDistance(selectedFeature.distanceMeters, uiState.effectiveUnitSystem)
      val typeStr = featureTypeTitle(selectedFeature.effectiveDisplayType())
      AutoShrinkSingleLineText(
        text = "$distStr · $typeStr",
        preferredFontSize = preferredFontSize,
        minFontSize = minFontSize,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        modifier = modifier,
      )
    }
    uiState.isGraphErrorState -> {
      // Failure renders ONE single localized line inside the body row, never duplicated in title.
      Spacer(modifier = modifier.height(2.dp))
    }
    BuildConfig.DEBUG && uiState.isMockModeActive && uiState.location == null && !uiState.isLoadingGraph -> {
      Spacer(modifier = modifier.height(2.dp))
    }
    uiState.isLoadingGraph || !uiState.hasConfirmedGraphData -> {
      SkeletonShimmerBar(
        modifier = modifier.height(24.dp).fillMaxWidth(0.58f).testTag("panel_title_skeleton"),
      )
    }
    else -> {
      AutoShrinkSingleLineText(
        text =
          stringResource(
            R.string.no_features_ahead,
            formatRoadDistance(uiState.lookaheadMeters, uiState.effectiveUnitSystem),
          ),
        preferredFontSize = 18.sp,
        minFontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = modifier,
      )
    }
  }
}

@Composable
private fun OffRoadEmptyRow(
  uiState: RoadHudUiState,
  onRetryRoadData: () -> Unit = {},
) {
  when {
    uiState.isGraphErrorState -> {
      val failedStage = uiState.roadDataFailedStage
      val msg =
        when {
          failedStage != null -> stringResource(R.string.roads_failed_stage, failedStage)
          uiState.isDataStaleOver10s -> stringResource(R.string.roads_unavailable_retrying)
          else -> stringResource(R.string.roads_searching)
        }
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth().testTag("panel_failure_notice"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = msg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
          )
          if (failedStage != null) {
            TextButton(
              onClick = {
                TouchTelemetry.recordControlHandled("panel_retry_button")
                onRetryRoadData()
              },
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 36.dp).testTag("panel_retry_button"),
            ) {
              Text(
                text = stringResource(R.string.action_retry),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
    uiState.isLoadingGraph && !uiState.hasConfirmedGraphData -> {
      PanelRowsSkeleton(count = 2)
    }
    else -> {
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text =
            stringResource(
              R.string.offroad_no_roads_nearby,
              formatRoadDistance(uiState.offRoadState.searchRadiusMeters, uiState.effectiveUnitSystem),
            ),
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun HeaderRoadNameSlot(
  uiState: RoadHudUiState,
  displayRoadCandidate: com.example.data.model.RoadCandidate?,
  maxArabicFontSize: androidx.compose.ui.unit.TextUnit,
  minArabicFontSize: androidx.compose.ui.unit.TextUnit,
  maxEnglishFontSize: androidx.compose.ui.unit.TextUnit,
  minEnglishFontSize: androidx.compose.ui.unit.TextUnit,
  onOpenInspectorSpots: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val offRoad = uiState.offRoadState
  val tileHint = uiState.tileFallbackRoad
  val lastConfirmed = uiState.lastConfirmedRoadName

  when {
    displayRoadCandidate != null && displayRoadCandidate.name.hasAny -> {
      AutoShrinkBilingualNameStack(
        name = displayRoadCandidate.name,
        fallbackText = stringResource(R.string.current_road_unknown),
        maxArabicFontSize = maxArabicFontSize,
        minArabicFontSize = minArabicFontSize,
        maxEnglishFontSize = maxEnglishFontSize,
        minEnglishFontSize = minEnglishFontSize,
        modifier = modifier.testTag("header_road_name_slot"),
      )
    }
    offRoad.isOffRoad && offRoad.selectedCandidate == null -> {
      // In OFF-ROAD state keep the last confirmed road name (greyed) or "—"; never put status words here.
      val preservedName =
        when {
          lastConfirmed != null && lastConfirmed.hasAny -> lastConfirmed
          tileHint != null && tileHint.name.hasAny -> tileHint.name
          else -> null
        }
      Box(modifier = modifier.alpha(0.56f).testTag("header_road_name_slot")) {
        if (preservedName != null) {
          AutoShrinkBilingualNameStack(
            name = preservedName,
            fallbackText = stringResource(R.string.off_road_header_dash),
            maxArabicFontSize = maxArabicFontSize,
            minArabicFontSize = minArabicFontSize,
            maxEnglishFontSize = maxEnglishFontSize,
            minEnglishFontSize = minEnglishFontSize,
          )
        } else {
          Text(
            text = stringResource(R.string.off_road_header_dash),
            fontSize = maxArabicFontSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
        }
      }
    }
    tileHint != null && tileHint.name.hasAny -> {
      AutoShrinkBilingualNameStack(
        name = tileHint.name,
        fallbackText = stringResource(R.string.current_road_unknown),
        maxArabicFontSize = maxArabicFontSize,
        minArabicFontSize = minArabicFontSize,
        maxEnglishFontSize = maxEnglishFontSize,
        minEnglishFontSize = minEnglishFontSize,
        modifier = modifier.testTag("header_road_name_slot"),
      )
    }
    lastConfirmed != null && lastConfirmed.hasAny -> {
      val staleAlpha = if (uiState.isDataStaleOver10s) 0.56f else 1f
      Box(modifier = modifier.alpha(staleAlpha).testTag("header_road_name_slot")) {
        AutoShrinkBilingualNameStack(
          name = lastConfirmed,
          fallbackText = stringResource(R.string.current_road_unknown),
          maxArabicFontSize = maxArabicFontSize,
          minArabicFontSize = minArabicFontSize,
          maxEnglishFontSize = maxEnglishFontSize,
          minEnglishFontSize = minEnglishFontSize,
        )
      }
    }
    displayRoadCandidate != null || uiState.hasConfirmedGraphData -> {
      AutoShrinkBilingualNameStack(
        name = null,
        fallbackText = stringResource(R.string.current_road_unknown),
        maxArabicFontSize = maxArabicFontSize,
        minArabicFontSize = minArabicFontSize,
        maxEnglishFontSize = maxEnglishFontSize,
        minEnglishFontSize = minEnglishFontSize,
        modifier = modifier.testTag("header_road_name_slot"),
      )
    }
    BuildConfig.DEBUG && uiState.isMockModeActive && uiState.location == null -> {
      val promptInteraction = rememberPressHapticInteractionSource()
      TextButton(
        onClick = {
          TouchTelemetry.recordControlHandled("header_mock_no_position_prompt")
          onOpenInspectorSpots()
        },
        interactionSource = promptInteraction,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        modifier =
          modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag("header_mock_no_position_prompt"),
      ) {
        Text(
          text = stringResource(R.string.mock_no_position_prompt),
          fontSize = minOf(maxEnglishFontSize.value, 14f).sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth().innerPressScale(promptInteraction),
        )
      }
    }
    !uiState.isMockModeActive && uiState.location == null -> {
      Text(
        text = stringResource(R.string.waiting_for_gps),
        fontSize = maxArabicFontSize,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = modifier.testTag("header_waiting_gps"),
      )
    }
    else -> {
      SkeletonShimmerBar(
        modifier =
          Modifier
            .width(132.dp)
            .height(20.dp)
            .testTag("header_road_skeleton"),
      )
    }
  }
}

@Composable
private fun PanelRowsSkeleton(count: Int) {
  Column(
    modifier = Modifier.fillMaxWidth().testTag("panel_rows_skeleton"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    repeat(count) { idx ->
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          SkeletonShimmerBar(modifier = Modifier.fillMaxWidth(if (idx == 0) 0.78f else 0.64f).height(13.dp))
          SkeletonShimmerBar(modifier = Modifier.fillMaxWidth(0.45f).height(10.dp))
        }
      }
    }
  }
}

@Composable
private fun SkeletonShimmerBar(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "skeletonShimmer")
  val shift by transition.animateFloat(
    initialValue = -200f,
    targetValue = 600f,
    animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1100), repeatMode = RepeatMode.Restart),
    label = "shimmerX",
  )
  val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)
  val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
  val brush =
    Brush.linearGradient(
      colors = listOf(base, highlight, base),
      start = Offset(shift, 0f),
      end = Offset(shift + 220f, 40f),
    )
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(8.dp))
        .background(brush),
  )
}

@Composable
private fun PortraitOffRoadCandidateRow(
  candidate: OffRoadCandidate,
  colorIndex: Int,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  onClick: () -> Unit,
) {
  val badgeColor = RoadColors.pinColorForIndex(colorIndex)
  val sideText = offRoadSideText(candidate.side)
  val distLabel =
    stringResource(R.string.offroad_closest_point_format, formatRoadDistance(candidate.distanceMeters, unitSystem))
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()
  val isDark = LocalGlassConfig.current.isDark

  Surface(
    onClick = {
      TouchTelemetry.recordControlHandled("offroad_candidate_row_${candidate.badgeId}")
      onClick()
    },
    interactionSource = interactionSource,
    shape = RoundedCornerShape(14.dp),
    color = Color.Transparent,
    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("offroad_candidate_row_${candidate.badgeId}"),
  ) {
    GlassSurface(
      shape = RoundedCornerShape(14.dp),
      isLighterInner = true,
      withShadow = false,
      tintOverlay =
        if (pressed) {
          if (isDark) Color.White.copy(alpha = 0.14f) else Color(0x140A64D8)
        } else Color.Transparent,
      modifier = Modifier.fillMaxWidth().innerPressScale(interactionSource),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Box(
            modifier =
              Modifier
                .clip(CircleShape)
                .background(badgeColor)
                .padding(horizontal = 6.dp, vertical = 1.dp),
          ) {
            Text(text = candidate.badgeId, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
          Text(
            text = sideText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Box(modifier = Modifier.weight(1f)) {
            BilingualNameStack(
              name = candidate.candidate.name,
              fallbackText = stringResource(R.string.unknown_value),
              arabicFontSize = 14.sp,
              englishFontSize = 12.sp,
              maxLinesPerScript = 2,
            )
          }
        }
        Text(
          text = distLabel,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
        )
      }
    }
  }
}


@Composable
private fun DirectionChoiceCardRow(
  group: OffRoadDirectionGroup,
  index: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()
  val isDark = LocalGlassConfig.current.isDark

  Surface(
    onClick = {
      TouchTelemetry.recordControlHandled("offroad_direction_$index")
      onClick()
    },
    interactionSource = interactionSource,
    shape = RoundedCornerShape(14.dp),
    color = Color.Transparent,
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("offroad_direction_$index"),
  ) {
    GlassSurface(
      shape = RoundedCornerShape(14.dp),
      isLighterInner = true,
      withShadow = false,
      tintOverlay =
        if (pressed) {
          if (isDark) Color.White.copy(alpha = 0.14f) else Color(0x140A64D8)
        } else Color.Transparent,
      modifier = Modifier.fillMaxWidth().innerPressScale(interactionSource),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = stringResource(R.string.offroad_direction_format, group.compassCardinal, group.travelBearingDeg.roundToInt()),
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
        )
        Text(
          text = stringResource(R.string.offroad_direction_features_format, group.features.size),
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun SmallCardActionChip(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fg = MaterialTheme.colorScheme.onPrimaryContainer
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()

  Button(
    onClick = {
      TouchTelemetry.recordControlHandled(if (label.isEmpty()) "offroad_flip_direction_button" else "offroad_clear_button")
      onClick()
    },
    interactionSource = interactionSource,
    shape = CircleShape,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = fg,
      ),
    elevation = null,
    contentPadding = PaddingValues(0.dp),
    modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 36.dp).heightIn(max = 40.dp),
  ) {
    GlassSurface(
      shape = CircleShape,
      isLighterInner = true,
      withShadow = false,
      tintOverlay =
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (pressed) 0.94f else 0.72f),
      modifier =
        Modifier
          .innerPressScale(interactionSource)
          .border(1.dp, fg.copy(alpha = 0.30f), CircleShape),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = label.ifEmpty { null },
          tint = fg,
          modifier = Modifier.size(13.dp),
        )
        if (label.isNotEmpty()) {
          Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun offRoadSideText(side: OffRoadSide): String =
  when (side) {
    OffRoadSide.AHEAD -> stringResource(R.string.dir_ahead)
    OffRoadSide.LEFT -> stringResource(R.string.dir_left)
    OffRoadSide.RIGHT -> stringResource(R.string.dir_right)
    OffRoadSide.BEHIND -> stringResource(R.string.dir_behind)
  }

@Composable
private fun FeatureSwitcherChips(
  features: List<RoadFeature>,
  selectedIndex: Int?,
  onSelectFeature: (Int) -> Unit,
) {
  if (features.size <= 1) return
  val isDark = LocalGlassConfig.current.isDark
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    features.take(4).forEach { feat ->
      val isSelected = feat.index == selectedIndex
      val pinColor = RoadColors.pinColorForIndex(feat.index)
      val interactionSource = rememberPressHapticInteractionSource()
      val pressed by interactionSource.collectIsPressedAsState()

      Button(
        onClick = {
          TouchTelemetry.recordControlHandled("feature_chip_${feat.index}")
          onSelectFeature(feat.index)
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
          ),
        elevation = null,
        contentPadding = PaddingValues(0.dp),
        modifier =
          Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 36.dp)
            .size(width = 40.dp, height = 36.dp)
            .testTag("feature_chip_${feat.index}"),
      ) {
        if (isSelected) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .innerPressScale(interactionSource)
                .clip(CircleShape)
                .background(
                  if (pressed) pinColor.copy(alpha = 0.88f) else pinColor,
                  CircleShape,
                ),
            contentAlignment = Alignment.Center,
          ) {
            Text(text = "${feat.index}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
          }
        } else {
          GlassSurface(
            shape = CircleShape,
            isLighterInner = true,
            withShadow = false,
            tintOverlay =
              if (pressed) {
                if (isDark) Color.White.copy(alpha = 0.16f) else Color(0x160A64D8)
              } else Color.Transparent,
            modifier = Modifier.fillMaxSize().innerPressScale(interactionSource),
          ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                text = "${feat.index}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PortraitBranchRow(
  branch: RoadBranch,
  featureIndex: Int,
  parentType: FeatureType,
  staggerOffsetMeters: Double? = null,
  frontageSide: RelativeDirection? = null,
  unitSystem: UnitSystem = UnitSystem.METRIC,
  isExpanded: Boolean,
  isFoundHighlight: Boolean = false,
  isSelectedForSimulation: Boolean = false,
  onClickForSimulation: (() -> Unit)? = null,
) {
  val roadColors = LocalRoadColors.current
  val isDark = LocalGlassConfig.current.isDark
  val badgeColor = RoadColors.pinColorForIndex(featureIndex)
  val dirLabel = relativeDirText(branch.relativeDirection)
  val (statusWord, statusIcon, statusFg, statusBg) = branchStatusVisuals(branch.status)
  val isLink = branch.osmTags.highway?.lowercase()?.endsWith("_link") == true
  val isRoadWorksBranch =
    parentType == FeatureType.ROAD_WORKS ||
      branch.osmTags.highway?.lowercase() == "construction" ||
      branch.osmTags.access?.lowercase() == "no"

  val borderMod =
    when {
      isFoundHighlight -> Modifier.border(2.dp, RoadColors.PinPurple, RoundedCornerShape(14.dp))
      isSelectedForSimulation -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
      else -> Modifier
    }

  val rowContent: @Composable (Modifier, Color) -> Unit = { innerMod, pressedTint ->
    GlassSurface(
      shape = RoundedCornerShape(14.dp),
      isLighterInner = true,
      withShadow = false,
      tintOverlay = pressedTint,
      modifier = innerMod.fillMaxWidth().then(borderMod),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Box(
            modifier =
              Modifier
                .clip(CircleShape)
                .background(badgeColor)
                .padding(horizontal = 5.dp, vertical = 1.dp),
          ) {
            Text(text = branch.badgeId, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
          Text(
            text = dirLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Box(modifier = Modifier.weight(1f)) {
            BilingualNameStack(
              name = branch.name,
              fallbackText = stringResource(R.string.unnamed_branch),
              arabicFontSize = 14.sp,
              englishFontSize = 12.sp,
              maxLinesPerScript = 2,
            )
          }
          Text(
            text = formatRoadDistance(branch.distanceMeters, unitSystem),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        if (parentType == FeatureType.ON_RAMP && isLink) {
          val joinText =
            when (branch.relativeDirection) {
              RelativeDirection.LEFT -> stringResource(R.string.ramp_joins_from_left)
              RelativeDirection.RIGHT -> stringResource(R.string.ramp_joins_from_right)
              RelativeDirection.AHEAD -> stringResource(R.string.feature_on_ramp)
            }
          Text(
            text = joinText,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        } else if (parentType == FeatureType.MERGE && !isLink) {
          val targetName =
            branch.name.primaryText ?: branch.name.ref ?: stringResource(R.string.unknown_value)
          Text(
            text = stringResource(R.string.merge_into_format, isolateRefsForBidi(targetName)),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        } else if (parentType == FeatureType.STAGGERED_JUNCTION && staggerOffsetMeters != null && branch.badgeId == "1A") {
          Text(
            text = stringResource(R.string.staggered_offset_format, formatRoadDistance(staggerOffsetMeters, unitSystem)),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        } else if (parentType == FeatureType.MEDIAN_OPENING) {
          Text(
            text = stringResource(R.string.median_opening_subtitle),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        } else if (parentType == FeatureType.FRONTAGE_ROAD) {
          val side = frontageSide ?: branch.relativeDirection
          val label =
            if (side == RelativeDirection.LEFT) stringResource(R.string.frontage_road_left)
            else stringResource(R.string.frontage_road_right)
          Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        }

        if (branch.leadsTo != null || isExpanded) {
          val leadsText =
            if (branch.leadsTo != null) {
              stringResource(R.string.leads_to_format, isolateRefsForBidi(branch.leadsTo))
            } else {
              stringResource(R.string.leads_to_unknown)
            }
          Text(
            text = leadsText,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
          )
        }

        // Open / Blocked / Unverified keep SOLID tinted fills with 1dp border at 30%
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(
            modifier =
              Modifier
                .clip(CircleShape)
                .background(statusBg)
                .border(1.dp, statusFg.copy(alpha = 0.30f), CircleShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Icon(imageVector = statusIcon, contentDescription = statusWord, tint = statusFg, modifier = Modifier.size(11.dp))
            Text(text = statusWord, color = statusFg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
          if (branch.status == BranchStatus.BLOCKED) {
            Text(
              text =
                if (isRoadWorksBranch) stringResource(R.string.branch_blocked_road_works)
                else stringResource(R.string.branch_blocked_reason),
              color = roadColors.blockedText,
              fontSize = 10.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
            )
          }
          if (isFoundHighlight) {
            Box(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(RoadColors.PinPurple)
                  .padding(horizontal = 6.dp, vertical = 2.dp)
                  .testTag("found_match_badge"),
            ) {
              Text(
                text = stringResource(R.string.found_match_badge),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
          if (isSelectedForSimulation) {
            Box(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primaryContainer)
                  .padding(horizontal = 6.dp, vertical = 2.dp)
                  .testTag("sim_branch_badge_${branch.badgeId}"),
            ) {
              Text(
                text = stringResource(R.string.sim_branch_selected),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
  }

  if (onClickForSimulation != null) {
    val interactionSource = rememberPressHapticInteractionSource()
    val pressed by interactionSource.collectIsPressedAsState()
    val tint =
      if (pressed) {
        if (isDark) Color.White.copy(alpha = 0.14f) else Color(0x140A64D8)
      } else Color.Transparent
    Surface(
      onClick = {
        TouchTelemetry.recordControlHandled("branch_row_${branch.badgeId}")
        onClickForSimulation()
      },
      interactionSource = interactionSource,
      shape = RoundedCornerShape(14.dp),
      color = Color.Transparent,
      modifier = Modifier.fillMaxWidth().testTag("branch_row_${branch.badgeId}"),
    ) {
      rowContent(Modifier.innerPressScale(interactionSource), tint)
    }
  } else {
    rowContent(Modifier.testTag("branch_row_${branch.badgeId}"), Color.Transparent)
  }
}


@Composable
private fun NonBranchingDetailRow(
  feature: RoadFeature,
  unitSystem: UnitSystem = UnitSystem.METRIC,
) {
  val roadColors = LocalRoadColors.current
  val displayType = feature.effectiveDisplayType()
  val subtitle =
    when (displayType) {
      FeatureType.OVERPASS_ABOVE -> stringResource(R.string.overpass_above_subtitle)
      FeatureType.UNDERPASS_BELOW -> stringResource(R.string.underpass_below_subtitle)
      FeatureType.ROAD_WORKS -> stringResource(R.string.branch_blocked_road_works)
      FeatureType.MEDIAN_OPENING -> stringResource(R.string.median_opening_subtitle)
      FeatureType.FRONTAGE_ROAD ->
        if (feature.frontageSide == RelativeDirection.LEFT) stringResource(R.string.frontage_road_left)
        else stringResource(R.string.frontage_road_right)
      FeatureType.STAGGERED_JUNCTION ->
        feature.staggerOffsetMeters?.let { stringResource(R.string.staggered_offset_format, formatRoadDistance(it, unitSystem)) }
      else -> null
    }
  val fallbackName = subtitle ?: featureTypeTitle(displayType)

  GlassSurface(
    shape = RoundedCornerShape(14.dp),
    isLighterInner = true,
    withShadow = false,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BilingualNameStack(
          name = BilingualName.fromOsmTags(feature.osmTags),
          fallbackText = fallbackName,
          arabicFontSize = 16.sp,
          englishFontSize = 14.sp,
          maxLinesPerScript = 2,
          modifier = Modifier.weight(1f),
        )
        if (feature.curveAngleDeg != null && feature.curveRadiusMeters != null) {
          Text(
            text = "${feature.curveAngleDeg.toInt()}° · R ${feature.curveRadiusMeters.toInt()}m",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      if (subtitle != null && BilingualName.fromOsmTags(feature.osmTags).hasAny) {
        Text(
          text = subtitle,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = if (feature.type == FeatureType.ROAD_WORKS) roadColors.blockedText else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
        )
      }
    }
  }
}

/**
 * Floating status chips rendered directly BELOW the top card over the map, only when conditions apply.
 */
@Composable
fun FloatingStatusChipsStrip(
  uiState: RoadHudUiState,
  onGrantLocationClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val roadColors = LocalRoadColors.current
  val acc = uiState.location?.accuracyMeters
  val poorGps =
    uiState.debugPoorGps ||
      (!uiState.hasLocationPermission && !uiState.isMockModeActive) ||
      (!uiState.isMockModeActive && (uiState.location == null || uiState.isGpsSearching)) ||
      (acc != null && acc > 15f && !uiState.isMockModeActive)
  val isOffline = uiState.debugOffline || uiState.showOfflineChipAfter5s
  val isSlowData = uiState.showSlowDataChipAfter5s
  val isUpdatingStale = uiState.isUpdatingStaleData && !isOffline && !isSlowData
  val isUncertain = uiState.debugUncertain || uiState.matchResult.isUncertain
  val isOffRoad = uiState.offRoadState.isOffRoad

  val hasAny =
    isOffRoad ||
      poorGps ||
      isOffline ||
      (isSlowData && !isOffline) ||
      isUpdatingStale ||
      (uiState.isUsingBasicTileData && !isOffline) ||
      isUncertain

  if (!hasAny) return

  Row(
    modifier = modifier.testTag("floating_status_chips_row"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    if (isOffRoad) {
      InlineStatusChip(
        icon = Icons.Default.ExploreOff,
        label = stringResource(R.string.status_off_road),
        fg = roadColors.unverifiedText,
        bg = roadColors.unverifiedBg,
        modifier = Modifier.testTag("off_road_chip"),
      )
    }

    if (poorGps) {
      val label =
        when {
          !uiState.hasLocationPermission && !uiState.isMockModeActive -> stringResource(R.string.status_gps_denied)
          uiState.debugPoorGps -> stringResource(R.string.gps_accuracy_format, 28)
          acc != null && acc > 15f -> stringResource(R.string.gps_accuracy_format, acc.roundToInt())
          else -> stringResource(R.string.status_gps_searching)
        }
      InlineStatusChip(
        icon = Icons.Default.GpsNotFixed,
        label = label,
        fg = roadColors.unverifiedText,
        bg = roadColors.unverifiedBg,
        onClick =
          if (!uiState.hasLocationPermission && !uiState.isMockModeActive) onGrantLocationClick
          else null,
        modifier =
          Modifier.testTag(
            if (!uiState.hasLocationPermission && !uiState.isMockModeActive) "grant_location_chip"
            else "bad_gps_chip",
          ),
      )
    }

    if (isOffline) {
      InlineStatusChip(
        icon = Icons.Default.CloudOff,
        label = stringResource(R.string.status_offline_cached),
        fg = roadColors.unverifiedText,
        bg = roadColors.unverifiedBg,
        modifier = Modifier.testTag("offline_chip"),
      )
    }

    if (isSlowData && !isOffline) {
      InlineStatusChip(
        icon = Icons.Default.HourglassTop,
        label = stringResource(R.string.status_slow_data),
        fg = roadColors.unverifiedText,
        bg = roadColors.unverifiedBg,
        modifier = Modifier.testTag("slow_data_chip"),
      )
    }

    if (isUpdatingStale) {
      InlineStatusChip(
        icon = Icons.Default.Sync,
        label = stringResource(R.string.status_updating),
        fg = MaterialTheme.colorScheme.primary,
        bg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        modifier = Modifier.testTag("updating_data_chip"),
      )
    }

    if (uiState.isUsingBasicTileData && !isOffline) {
      InlineStatusChip(
        icon = Icons.Default.Info,
        label = stringResource(R.string.status_basic_data),
        fg = roadColors.unverifiedText,
        bg = roadColors.unverifiedBg,
        modifier = Modifier.testTag("basic_data_chip"),
      )
    }

    if (isUncertain) {
      InlineStatusChip(
        icon = Icons.Default.Warning,
        label = stringResource(R.string.status_uncertain),
        fg = roadColors.unverifiedText,
        bg = roadColors.unverifiedBg,
        modifier = Modifier.testTag("uncertain_chip"),
      )
    }
  }
}

@Composable
private fun InlineStatusChip(
  icon: ImageVector,
  label: String,
  fg: Color,
  bg: Color,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val glass = LocalGlassConfig.current
  val chipBody: @Composable (Modifier, Color) -> Unit = { innerMod, tintOverlay ->
    if (!glass.isDark) {
      GlassSurface(
        shape = CircleShape,
        withShadow = true,
        tintOverlay = bg.copy(alpha = 0.28f).let { base ->
          if (tintOverlay != Color.Transparent) tintOverlay else base
        },
        modifier = innerMod,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(13.dp))
          Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
      }
    } else {
      Row(
        modifier =
          innerMod
            .clip(CircleShape)
            .background(if (tintOverlay != Color.Transparent) tintOverlay else bg)
            .border(1.dp, fg.copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(13.dp))
        Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
    }
  }

  if (onClick != null) {
    val interactionSource = rememberPressHapticInteractionSource()
    val pressed by interactionSource.collectIsPressedAsState()
    val tint = if (pressed) fg.copy(alpha = 0.28f) else Color.Transparent
    Button(
      onClick = {
        TouchTelemetry.recordControlHandled("grant_location_chip")
        onClick()
      },
      interactionSource = interactionSource,
      shape = CircleShape,
      colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = fg),
      elevation = null,
      contentPadding = PaddingValues(0.dp),
      modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
    ) {
      chipBody(Modifier.innerPressScale(interactionSource), tint)
    }
  } else {
    chipBody(modifier, Color.Transparent)
  }
}

/**
 * Capsule Liquid Glass segmented control (48dp tall) built with a Row of standard Material3 TextButtons.
 */
@Composable
fun CompactModeSegmentedControl(
  selectedMode: TravelMode,
  compactHeight: Dp = 48.dp,
  onSelect: (TravelMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val glass = LocalGlassConfig.current
  val selectedIndex = if (selectedMode == TravelMode.DRIVING) 0 else 1

  GlassSurface(
    shape = CircleShape,
    isLighterInner = false,
    withShadow = true,
    tintOverlay =
      if (!glass.isDark) RoadColors.LightSegmentedTrack
      else Color.Transparent,
    modifier = modifier.heightIn(min = maxOf(compactHeight, 48.dp)).width(172.dp).testTag("mode_segmented_control"),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(maxOf(compactHeight, 48.dp)).padding(2.dp)) {
      val slotW = maxWidth / 2f
      val thumbOffset by animateDpAsState(
        targetValue = if (selectedIndex == 0) 0.dp else slotW,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
        label = "modeThumbSlide",
      )

      val thumbColor =
        when {
          glass.reduceTransparency -> MaterialTheme.colorScheme.primary
          glass.isDark -> Color.White.copy(alpha = 0.22f)
          else -> RoadColors.Primary // Solid #0A64D8
        }
      val thumbBorder =
        when {
          glass.reduceTransparency -> Color.Transparent
          glass.isDark -> Color.White.copy(alpha = 0.40f)
          else -> Color.Transparent
        }
      val thumbShadowMod =
        if (!glass.isDark) {
          Modifier.shadow(elevation = 3.dp, shape = CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
        } else {
          Modifier
        }

      // Sliding capsule thumb
      Box(
        modifier =
          Modifier
            .offset(x = thumbOffset)
            .width(slotW)
            .fillMaxHeight()
            .then(thumbShadowMod)
            .clip(CircleShape)
            .background(thumbColor, CircleShape)
            .border(1.dp, thumbBorder, CircleShape)
            .testTag("mode_segmented_thumb"),
      )

      Row(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CompactSegmentBtn(
          selected = selectedMode == TravelMode.DRIVING,
          icon = Icons.Default.DirectionsCar,
          label = stringResource(R.string.mode_driving),
          testTag = "mode_driving_button",
          onClick = { onSelect(TravelMode.DRIVING) },
          modifier = Modifier.weight(1f),
        )
        CompactSegmentBtn(
          selected = selectedMode == TravelMode.WALKING,
          icon = Icons.AutoMirrored.Filled.DirectionsWalk,
          label = stringResource(R.string.mode_walking),
          testTag = "mode_walking_button",
          onClick = { onSelect(TravelMode.WALKING) },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun CompactSegmentBtn(
  selected: Boolean,
  icon: ImageVector,
  label: String,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val glass = LocalGlassConfig.current
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()

  val activeFg = Color.White
  val inactiveFg =
    if (glass.isDark) MaterialTheme.colorScheme.onSurfaceVariant
    else RoadColors.LightTextPrimary.copy(alpha = 0.80f)
  val fg by animateColorAsState(
    targetValue = if (selected) activeFg else inactiveFg,
    animationSpec = spring(stiffness = 900f),
    label = "segFg",
  )

  TextButton(
    onClick = {
      TouchTelemetry.recordControlHandled(testTag)
      onClick()
    },
    interactionSource = interactionSource,
    shape = CircleShape,
    colors =
      ButtonDefaults.textButtonColors(
        containerColor =
          if (pressed) Color.White.copy(alpha = if (selected) 0.14f else 0.10f)
          else Color.Transparent,
        contentColor = fg,
      ),
    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    modifier =
      modifier
        .fillMaxHeight()
        .defaultMinSize(minHeight = 48.dp)
        .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.innerPressScale(interactionSource),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(15.dp))
      Spacer(modifier = Modifier.width(3.dp))
      Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
  }
}

@Composable
private fun relativeDirText(dir: RelativeDirection): String =
  when (dir) {
    RelativeDirection.LEFT -> stringResource(R.string.dir_left)
    RelativeDirection.RIGHT -> stringResource(R.string.dir_right)
    RelativeDirection.AHEAD -> stringResource(R.string.dir_ahead)
  }

@Composable
private fun branchStatusVisuals(status: BranchStatus): Quadruple<String, ImageVector, Color, Color> {
  val roadColors = LocalRoadColors.current
  return when (status) {
    BranchStatus.OPEN ->
      Quadruple(
        stringResource(R.string.branch_open),
        Icons.Default.CheckCircle,
        roadColors.openText,
        roadColors.openBg,
      )
    BranchStatus.UNVERIFIED ->
      Quadruple(
        stringResource(R.string.branch_unverified),
        Icons.Default.HelpOutline,
        roadColors.unverifiedText,
        roadColors.unverifiedBg,
      )
    BranchStatus.BLOCKED ->
      Quadruple(
        stringResource(R.string.branch_blocked),
        Icons.Default.Close,
        roadColors.blockedText,
        roadColors.blockedBg,
      )
  }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun isFoundBranchMatch(branch: RoadBranch, found: FoundRoadMatch?): Boolean {
  if (found == null || !found.isStreet) return false
  if (found.wayId != null && branch.wayId == found.wayId) return true
  val foundNorms =
    listOfNotNull(found.name.arabic, found.name.english, found.name.ref)
      .map { LocalRoadSearchIndex.normalizeSearchText(it) }
      .filter { it.isNotEmpty() }
      .toSet()
  if (foundNorms.isEmpty()) return false
  val branchNorms =
    listOfNotNull(branch.name.arabic, branch.name.english, branch.name.ref, branch.name.primaryText)
      .map { LocalRoadSearchIndex.normalizeSearchText(it) }
      .filter { it.isNotEmpty() }
  return branchNorms.any { it in foundNorms }
}

private fun isFoundTimelineMatch(entry: AheadTimelineEntry, found: FoundRoadMatch?): Boolean {
  if (found == null) return false
  if (found.wayId != null && (entry.primaryWayId == found.wayId || found.wayId in entry.branchWayIds)) {
    return true
  }
  if (found.matchedFeatureIndex != null && entry.featureIndex == found.matchedFeatureIndex) {
    return true
  }
  if (entry.isLandmark && GeoMath.haversineMeters(entry.anchor, found.targetPoint) < 35.0) {
    return true
  }
  val foundNorms =
    listOfNotNull(found.name.arabic, found.name.english, found.name.ref)
      .map { LocalRoadSearchIndex.normalizeSearchText(it) }
      .filter { it.isNotEmpty() }
      .toSet()
  if (foundNorms.isEmpty()) return false
  val entryNorms =
    listOfNotNull(entry.title.arabic, entry.title.english, entry.title.ref, entry.title.primaryText)
      .map { LocalRoadSearchIndex.normalizeSearchText(it) }
      .filter { it.isNotEmpty() }
  return entryNorms.any { it in foundNorms }
}

