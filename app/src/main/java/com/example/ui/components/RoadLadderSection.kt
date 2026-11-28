package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.LadderOrder
import com.example.data.model.LadderTextSize
import com.example.data.model.NameOrder
import com.example.data.model.PassByRelation
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadFeature
import com.example.data.model.UnitSystem
import com.example.domain.GeoMath
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.LocalGlassConfig
import com.example.ui.theme.LocalRoadColors
import com.example.ui.theme.NotoArabicFontFamily
import com.example.ui.theme.RoadColors
import com.example.ui.theme.RobotoFontFamily
import kotlin.math.abs

data class RoadLadderBranch(
  val name: BilingualName,
  val isUnnamed: Boolean,
  val isExitRamp: Boolean,
  val leadsTo: String?,
  val status: BranchStatus,
  val highway: String?,
  val passByRelation: PassByRelation? = null,
)

data class RoadLadderRow(
  val id: String,
  val distanceMeters: Double,
  val featureIndex: Int?,
  val featureType: FeatureType,
  val leftBranches: List<RoadLadderBranch>,
  val rightBranches: List<RoadLadderBranch>,
  val aheadBranch: RoadLadderBranch?,
  val centerName: BilingualName?,
  val isPassByStreet: Boolean,
  val isLoadingContinues: Boolean = false,
  val isSelected: Boolean = false,
  val continuesAsName: BilingualName? = null,
  val forkMainName: BilingualName? = null,
  val isSlowRoadStretch: Boolean = false,
  val slowRoadEndDistMeters: Double? = null,
)

private data class LadderDisplayMetrics(
  val primarySp: Float,
  val secondarySp: Float,
  val distanceSp: Float,
  val minRowHeightDp: Int,
)

private fun resolveLadderMetrics(
  textSize: LadderTextSize,
  isLandscape: Boolean,
  windowWidthDp: Int,
  forceLargeMinimum: Boolean = false,
): LadderDisplayMetrics {
  val largeMode = isLandscape || windowWidthDp >= 800
  return when {
    largeMode ->
      LadderDisplayMetrics(
        primarySp = maxOf(textSize.primarySp.toFloat(), 22f),
        secondarySp = maxOf(textSize.secondarySp.toFloat(), 18f),
        distanceSp = maxOf(textSize.distanceSp.toFloat(), 26f),
        minRowHeightDp = 76,
      )
    forceLargeMinimum ->
      LadderDisplayMetrics(
        primarySp = maxOf(textSize.primarySp.toFloat(), 18f),
        secondarySp = maxOf(textSize.secondarySp.toFloat(), 16f),
        distanceSp = maxOf(textSize.distanceSp.toFloat(), 22f),
        minRowHeightDp = 68,
      )
    else ->
      LadderDisplayMetrics(
        primarySp = textSize.primarySp.toFloat(),
        secondarySp = textSize.secondarySp.toFloat(),
        distanceSp = textSize.distanceSp.toFloat(),
        minRowHeightDp = 64,
      )
  }
}

object RoadLadderBuilder {
  fun buildRows(
    features: List<RoadFeature>,
    timelineEntries: List<AheadTimelineEntry>,
    selectedFeatureIndex: Int,
    ladderOrder: LadderOrder,
    horizonStoppedAtUnloadedBorder: Boolean = false,
  ): List<RoadLadderRow> {
    val rows = ArrayList<RoadLadderRow>(features.size + timelineEntries.size + 1)
    val coveredWayIds = HashSet<Long>()
    // Track FRONTAGE_ROAD rows so isSlowRoadStretch entries merge once per stretch instead of duplicating
    val frontageRowIndices = ArrayList<Triple<Double, RelativeDirection?, Int>>()

    for (feat in features) {
      val displayType = feat.effectiveDisplayType()
      val lefts = ArrayList<RoadLadderBranch>()
      val rights = ArrayList<RoadLadderBranch>()
      var ahead: RoadLadderBranch? = null

      for (br in feat.branches) {
        if (br.isMainContinuation) continue
        coveredWayIds += br.wayId
        val hwLower = br.osmTags.highway?.lowercase()
        val isRamp =
          hwLower?.endsWith("_link") == true ||
            displayType == FeatureType.OFF_RAMP ||
            displayType == FeatureType.ON_RAMP
        val item =
          RoadLadderBranch(
            name = br.name,
            isUnnamed = !br.name.hasAny,
            isExitRamp = isRamp,
            leadsTo = br.leadsTo,
            status = br.status,
            highway = br.osmTags.highway,
            passByRelation = if (br.isAcrossMedian) PassByRelation.ACROSS_MEDIAN else null,
          )
        when (br.relativeDirection) {
          RelativeDirection.LEFT -> lefts += item
          RelativeDirection.RIGHT -> rights += item
          RelativeDirection.AHEAD -> if (ahead == null) ahead = item else rights += item
        }
      }

      val centerName =
        feat.continuesAsName?.takeIf { it.hasAny }
          ?: feat.forkMainName?.takeIf { it.hasAny }
          ?: ahead?.name?.takeIf { it.hasAny }
          ?: BilingualName.fromOsmTags(feat.osmTags).takeIf { it.hasAny }

      val isFrontage = displayType == FeatureType.FRONTAGE_ROAD
      val rowIdx = rows.size
      if (isFrontage) {
        frontageRowIndices += Triple(feat.distanceMeters, feat.frontageSide, rowIdx)
      }

      rows +=
        RoadLadderRow(
          id = "feat_${feat.index}_${feat.anchorNodeId ?: feat.distanceMeters.toInt()}",
          distanceMeters = feat.distanceMeters,
          featureIndex = feat.index,
          featureType = displayType,
          leftBranches = lefts,
          rightBranches = rights,
          aheadBranch = ahead,
          centerName = centerName,
          isPassByStreet = false,
          isSelected = feat.index == selectedFeatureIndex,
          continuesAsName = feat.continuesAsName,
          forkMainName = feat.forkMainName,
          isSlowRoadStretch = isFrontage,
          slowRoadEndDistMeters = feat.rangeEndDistanceMeters,
        )
    }

    for (entry in timelineEntries) {
      val wantsRow = entry.isPassByStreet || entry.isSlowRoadStretch || entry.continuesAsName != null
      if (!wantsRow) continue

      if (entry.isSlowRoadStretch) {
        val matchingFrontage =
          frontageRowIndices.firstOrNull { (dist, side, _) ->
            abs(dist - entry.distanceMeters) <= 60.0 &&
              (side == null || entry.passBySide == null || side == entry.passBySide)
          }
        if (matchingFrontage != null) {
          val idx = matchingFrontage.third
          val existing = rows[idx]
          rows[idx] =
            existing.copy(
              isSlowRoadStretch = true,
              slowRoadEndDistMeters = entry.slowRoadEndDistMeters ?: existing.slowRoadEndDistMeters,
            )
          continue
        }
      }

      if (entry.continuesAsName != null && !entry.isPassByStreet && !entry.isSlowRoadStretch) {
        if (rows.any { abs(it.distanceMeters - entry.distanceMeters) <= 25.0 && it.continuesAsName != null }) {
          continue
        }
        rows +=
          RoadLadderRow(
            id = "cont_${entry.primaryWayId ?: entry.distanceMeters.toInt()}_${entry.distanceMeters.toInt()}",
            distanceMeters = entry.distanceMeters,
            featureIndex = entry.featureIndex,
            featureType = entry.type,
            leftBranches = emptyList(),
            rightBranches = emptyList(),
            aheadBranch = null,
            centerName = entry.continuesAsName,
            isPassByStreet = false,
            isSelected = entry.featureIndex != null && entry.featureIndex == selectedFeatureIndex,
            continuesAsName = entry.continuesAsName,
          )
        continue
      }

      if (!entry.isSlowRoadStretch &&
        entry.primaryWayId != null &&
        entry.primaryWayId in coveredWayIds &&
        rows.any { abs(it.distanceMeters - entry.distanceMeters) <= 25.0 && !it.isSlowRoadStretch }
      ) {
        continue
      }

      val item =
        RoadLadderBranch(
          name = entry.title,
          isUnnamed = !entry.title.hasAny,
          isExitRamp = false,
          leadsTo = entry.branchSummary,
          status =
            when (entry.passByRelation) {
              PassByRelation.NOT_CONNECTED, PassByRelation.GRADE_SEPARATED -> BranchStatus.UNVERIFIED
              else -> BranchStatus.OPEN
            },
          highway = null,
          passByRelation = entry.passByRelation,
        )
      val lefts = if (entry.passBySide == RelativeDirection.LEFT) listOf(item) else emptyList()
      val rights = if (entry.passBySide == RelativeDirection.RIGHT) listOf(item) else emptyList()
      val ahead = if (entry.passBySide == RelativeDirection.AHEAD) item else null

      rows +=
        RoadLadderRow(
          id =
            if (entry.isSlowRoadStretch) {
              "slow_${entry.primaryWayId ?: entry.distanceMeters.toInt()}_${entry.passBySide?.name ?: "NONE"}_${entry.distanceMeters.toInt()}"
            } else {
              "passby_${entry.primaryWayId ?: entry.distanceMeters.toInt()}_${entry.passBySide?.name ?: "NONE"}"
            },
          distanceMeters = entry.distanceMeters,
          featureIndex = entry.featureIndex,
          featureType = if (entry.isSlowRoadStretch) FeatureType.FRONTAGE_ROAD else entry.type,
          leftBranches = lefts,
          rightBranches = rights,
          aheadBranch = ahead,
          centerName = entry.title.takeIf { it.hasAny },
          isPassByStreet = entry.isPassByStreet,
          isSelected = entry.featureIndex != null && entry.featureIndex == selectedFeatureIndex,
          continuesAsName = entry.continuesAsName,
          forkMainName = entry.forkMainName,
          isSlowRoadStretch = entry.isSlowRoadStretch,
          slowRoadEndDistMeters = entry.slowRoadEndDistMeters,
        )
    }

    val sortedAsc =
      rows.sortedWith(
        compareBy<RoadLadderRow> { it.distanceMeters }
          .thenBy { it.featureIndex ?: Int.MAX_VALUE }
          .thenBy { it.id },
      )
    val withLoading =
      if (horizonStoppedAtUnloadedBorder) {
        val farDist = (sortedAsc.lastOrNull()?.distanceMeters ?: 0.0) + 1.0
        sortedAsc +
          RoadLadderRow(
            id = "loading_continues",
            distanceMeters = farDist,
            featureIndex = null,
            featureType = FeatureType.FORK,
            leftBranches = emptyList(),
            rightBranches = emptyList(),
            aheadBranch = null,
            centerName = null,
            isPassByStreet = false,
            isLoadingContinues = true,
          )
      } else {
        sortedAsc
      }

    return when (ladderOrder) {
      LadderOrder.NEAREST_AT_BOTTOM -> withLoading.asReversed()
      LadderOrder.NEAREST_AT_TOP -> withLoading
    }
  }

  fun countLeftRight(rows: List<RoadLadderRow>): Pair<Int, Int> {
    var leftCount = 0
    var rightCount = 0
    for (r in rows) {
      if (r.isLoadingContinues) continue
      leftCount += r.leftBranches.size
      rightCount += r.rightBranches.size
    }
    return leftCount to rightCount
  }
}

@Composable
fun RoadLadderSection(
  features: List<RoadFeature>,
  timelineEntries: List<AheadTimelineEntry>,
  selectedFeatureIndex: Int,
  ladderOrder: LadderOrder,
  unitSystem: UnitSystem,
  horizonStoppedAtUnloadedBorder: Boolean,
  compactMode: Boolean,
  onSelectFeature: (Int) -> Unit,
  onExpandRequest: () -> Unit,
  modifier: Modifier = Modifier,
  textSize: LadderTextSize = LadderTextSize.LARGE,
  nameOrder: NameOrder = LocalNameOrder.current,
  isDividedRoad: Boolean = false,
  isLandscape: Boolean = false,
  windowWidthDp: Int = 400,
  onOpenWholeRoad: () -> Unit = onExpandRequest,
) {
  val allRows =
    remember(features, timelineEntries, selectedFeatureIndex, ladderOrder, horizonStoppedAtUnloadedBorder) {
      RoadLadderBuilder.buildRows(
        features = features,
        timelineEntries = timelineEntries,
        selectedFeatureIndex = selectedFeatureIndex,
        ladderOrder = ladderOrder,
        horizonStoppedAtUnloadedBorder = horizonStoppedAtUnloadedBorder,
      )
    }

  if (allRows.isEmpty()) return

  val isDark = LocalGlassConfig.current.isDark
  val nearestRowId = remember(allRows) {
    allRows.filterNot { it.isLoadingContinues }.minByOrNull { it.distanceMeters }?.id
  }
  val metrics = remember(textSize, isLandscape, windowWidthDp) {
    resolveLadderMetrics(
      textSize = textSize,
      isLandscape = isLandscape,
      windowWidthDp = windowWidthDp,
    )
  }
  val listState = rememberLazyListState()

  LaunchedEffect(nearestRowId, ladderOrder, allRows.size) {
    if (allRows.isNotEmpty()) {
      val targetIndex =
        if (ladderOrder == LadderOrder.NEAREST_AT_BOTTOM) {
          (allRows.lastIndex - 3).coerceAtLeast(0)
        } else {
          0
        }
      listState.scrollToItem(targetIndex)
    }
  }

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("road_ladder_section"),
  ) {
    val compactSpacer = if (compactMode && maxHeight < 220.dp) 3.dp else 6.dp

    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier =
          Modifier
            .align(Alignment.Center)
            .width(3.dp)
            .fillMaxHeight()
            .background(
              if (isDark) Color.White.copy(alpha = 0.22f)
              else MaterialTheme.colorScheme.primary.copy(alpha = 0.26f),
              RoundedCornerShape(2.dp),
            ),
      )

      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(compactSpacer),
      ) {
        if (isDividedRoad) {
          LadderDividedRoadBanner(
            modifier = Modifier.align(Alignment.CenterHorizontally),
          )
        }

        if (ladderOrder == LadderOrder.NEAREST_AT_TOP) {
          LadderYouHerePill(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxWidth().weight(1f).testTag("road_ladder_list"),
          verticalArrangement = Arrangement.spacedBy(compactSpacer),
          reverseLayout = false,
        ) {
          itemsIndexed(allRows, key = { _, row -> row.id }) { rowIndex, row ->
            AnimatedVisibility(
              visible = true,
              enter = fadeIn(tween(180)) + expandVertically(tween(180)),
              exit = fadeOut(tween(150)),
            ) {
              RoadLadderRowItem(
                row = row,
                rowIndex = rowIndex,
                isNearest = row.id == nearestRowId,
                unitSystem = unitSystem,
                metrics = metrics,
                nameOrder = nameOrder,
                onClick = {
                  row.featureIndex?.let { idx ->
                    TouchTelemetry.recordControlHandled("ladder_row_${row.id}")
                    onSelectFeature(idx)
                  }
                },
              )
            }
          }
        }

        if (ladderOrder == LadderOrder.NEAREST_AT_BOTTOM) {
          LadderYouHerePill(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        WholeRoadTriggerButton(
          onClick = {
            TouchTelemetry.recordControlHandled("ladder_whole_road_button")
            onOpenWholeRoad()
          },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
fun WholeRoadSheet(
  features: List<RoadFeature>,
  timelineEntries: List<AheadTimelineEntry>,
  selectedFeatureIndex: Int,
  unitSystem: UnitSystem,
  horizonMeters: Double,
  showMinorJunctions: Boolean,
  isDividedRoad: Boolean,
  horizonStoppedAtUnloadedBorder: Boolean,
  textSize: LadderTextSize,
  nameOrder: NameOrder,
  isLandscape: Boolean,
  windowWidthDp: Int,
  onSelectFeature: (Int) -> Unit,
  onSelectHorizon: (Double) -> Unit,
  onToggleShowMinor: (Boolean) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onDismiss)

  val isDark = LocalGlassConfig.current.isDark
  val rowsAsc =
    remember(features, timelineEntries, selectedFeatureIndex, horizonStoppedAtUnloadedBorder) {
      RoadLadderBuilder.buildRows(
        features = features,
        timelineEntries = timelineEntries,
        selectedFeatureIndex = selectedFeatureIndex,
        ladderOrder = LadderOrder.NEAREST_AT_TOP,
        horizonStoppedAtUnloadedBorder = horizonStoppedAtUnloadedBorder,
      )
    }
  val (leftCount, rightCount) = remember(rowsAsc) { RoadLadderBuilder.countLeftRight(rowsAsc) }
  val nearestRowId = remember(rowsAsc) {
    rowsAsc.filterNot { it.isLoadingContinues }.minByOrNull { it.distanceMeters }?.id
  }
  val metrics = remember(textSize, isLandscape, windowWidthDp) {
    resolveLadderMetrics(
      textSize = textSize,
      isLandscape = isLandscape,
      windowWidthDp = windowWidthDp,
      forceLargeMinimum = true,
    )
  }

  val surfaceColor =
    if (isDark) Color(0xF50B1220) else Color(0xF7F8FAFC)
  val primaryTextColor = if (isDark) Color.White else Color(0xFF0F172A)
  val secondaryTextColor = if (isDark) Color(0xFFD5DCE6) else Color(0xFF334155)

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.56f))
        .clickable(onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = surfaceColor,
      tonalElevation = 8.dp,
      modifier =
        Modifier
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(12.dp)
          .widthIn(max = 840.dp)
          .fillMaxSize()
          .clickable(enabled = false, onClick = {})
          .testTag("whole_road_sheet"),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Header: Title + Left/Right counts + Close button
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.whole_road_title),
              color = primaryTextColor,
              fontSize = 22.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
            )
            Text(
              text = stringResource(R.string.whole_road_counts_format, leftCount, rightCount),
              color = secondaryTextColor,
              fontSize = 16.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              modifier = Modifier.testTag("whole_road_counts"),
            )
          }
          IconButton(
            onClick = {
              TouchTelemetry.recordControlHandled("whole_road_close_button")
              onDismiss()
            },
            modifier =
              Modifier
                .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                .testTag("whole_road_close_button"),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.action_close),
              tint = primaryTextColor,
              modifier = Modifier.size(26.dp),
            )
          }
        }

        // Horizon chips (1 / 2 / 3 / 5 km) + Show minor toggle
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          listOf(1000.0, 2000.0, 3000.0, 5000.0).forEach { hMeters ->
            val selected = abs(horizonMeters - hMeters) <= 100.0
            val label = formatRoadDistance(hMeters, unitSystem)
            FilterChip(
              selected = selected,
              onClick = {
                TouchTelemetry.recordControlHandled("whole_road_horizon_${hMeters.toInt()}")
                onSelectHorizon(hMeters)
              },
              label = {
                Text(
                  text = label,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                )
              },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = RoadColors.Primary,
                  selectedLabelColor = Color.White,
                  containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                  labelColor = primaryTextColor,
                ),
              modifier =
                Modifier
                  .heightIn(min = 56.dp)
                  .testTag("whole_road_horizon_${(hMeters / 1000.0).toInt()}km"),
            )
          }

          FilterChip(
            selected = showMinorJunctions,
            onClick = {
              TouchTelemetry.recordControlHandled("whole_road_show_minor")
              onToggleShowMinor(!showMinorJunctions)
            },
            label = {
              Text(
                text = stringResource(R.string.whole_road_show_minor),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
              )
            },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = RoadColors.Primary,
                selectedLabelColor = Color.White,
                containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                labelColor = primaryTextColor,
              ),
            modifier = Modifier.heightIn(min = 56.dp).testTag("whole_road_show_minor"),
          )
        }

        if (isDividedRoad) {
          LadderDividedRoadBanner(
            modifier = Modifier.align(Alignment.Start),
          )
        }

        if (rowsAsc.isEmpty()) {
          Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.whole_road_empty),
              color = secondaryTextColor,
              fontSize = 18.sp,
              fontWeight = FontWeight.Medium,
              textAlign = TextAlign.Center,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("whole_road_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            itemsIndexed(rowsAsc, key = { _, row -> "wr_${row.id}" }) { index, row ->
              val prevTickBucket =
                if (index > 0) (rowsAsc[index - 1].distanceMeters / 500.0).toInt() * 500 else 0
              val currTickBucket = (row.distanceMeters / 500.0).toInt() * 500
              if (!row.isLoadingContinues && currTickBucket >= 500 && currTickBucket > prevTickBucket) {
                DistanceTickDivider(
                  tickMeters = currTickBucket.toDouble(),
                  unitSystem = unitSystem,
                  textColor = secondaryTextColor,
                )
              }

              RoadLadderRowItem(
                row = row,
                rowIndex = index,
                isNearest = row.id == nearestRowId,
                unitSystem = unitSystem,
                metrics = metrics,
                nameOrder = nameOrder,
                onClick = {
                  row.featureIndex?.let { idx ->
                    TouchTelemetry.recordControlHandled("whole_road_row_${row.id}")
                    onSelectFeature(idx)
                    onDismiss()
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DistanceTickDivider(
  tickMeters: Double,
  unitSystem: UnitSystem,
  textColor: Color,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HorizontalDivider(modifier = Modifier.weight(1f), color = textColor.copy(alpha = 0.25f))
    Text(
      text = formatRoadDistance(tickMeters, unitSystem),
      color = textColor,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
    HorizontalDivider(modifier = Modifier.weight(1f), color = textColor.copy(alpha = 0.25f))
  }
}

@Composable
private fun WholeRoadTriggerButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isDark = LocalGlassConfig.current.isDark
  val interactionSource = rememberPressHapticInteractionSource()
  FilledTonalButton(
    onClick = onClick,
    interactionSource = interactionSource,
    shape = RoundedCornerShape(14.dp),
    colors =
      ButtonDefaults.filledTonalButtonColors(
        containerColor = if (isDark) Color(0xFF1E293B) else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
      ),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    modifier = modifier.heightIn(min = 56.dp).testTag("ladder_whole_road_button"),
  ) {
    Row(
      modifier = Modifier.innerPressScale(interactionSource),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = stringResource(R.string.action_whole_road),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun LadderDividedRoadBanner(modifier: Modifier = Modifier) {
  val isDark = LocalGlassConfig.current.isDark
  Surface(
    shape = CircleShape,
    color = if (isDark) Color(0xFF1E293B) else MaterialTheme.colorScheme.secondaryContainer,
    modifier = modifier.testTag("ladder_divided_road_banner"),
  ) {
    Text(
      text = stringResource(R.string.ladder_divided_road_note),
      color = if (isDark) Color(0xFFE2E8F0) else MaterialTheme.colorScheme.onSecondaryContainer,
      fontSize = 14.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun LadderYouHerePill(modifier: Modifier = Modifier) {
  Row(
    modifier =
      modifier
        .clip(CircleShape)
        .background(RoadColors.Primary)
        .padding(horizontal = 12.dp, vertical = 3.dp)
        .testTag("ladder_you_here"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = Icons.Default.Navigation,
      contentDescription = stringResource(R.string.ladder_you_here),
      tint = Color.White,
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = stringResource(R.string.ladder_you_here),
      color = Color.White,
      fontSize = 14.sp,
      lineHeight = 17.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

@Composable
private fun RoadLadderRowItem(
  row: RoadLadderRow,
  rowIndex: Int,
  isNearest: Boolean,
  unitSystem: UnitSystem,
  metrics: LadderDisplayMetrics,
  nameOrder: NameOrder,
  onClick: () -> Unit,
) {
  val isDark = LocalGlassConfig.current.isDark

  if (row.isLoadingContinues) {
    GlassSurface(
      shape = RoundedCornerShape(12.dp),
      isLighterInner = true,
      withShadow = false,
      modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ladder_loading_row"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.Sync,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.ladder_continues_loading),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Clip,
        )
      }
    }
    return
  }

  val pinColor =
    if (row.featureIndex != null) RoadColors.pinColorForIndex(row.featureIndex)
    else MaterialTheme.colorScheme.primary
  val borderMod =
    when {
      isNearest -> Modifier.border(2.5.dp, pinColor, RoundedCornerShape(12.dp))
      row.isSelected -> Modifier.border(1.8.dp, pinColor.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
      else -> Modifier
    }

  val baseDarkAlpha = if (rowIndex % 2 == 0) 0.08f else 0.04f
  val overlayColor =
    if (isDark) {
      val extra = (if (isNearest) 0.06f else 0f) + (if (row.isSelected) 0.04f else 0f)
      Color.White.copy(alpha = (baseDarkAlpha + extra).coerceAtMost(0.18f))
    } else {
      val baseLight = if (rowIndex % 2 == 0) 0.05f else 0.02f
      val extra = (if (isNearest) 0.05f else 0f) + (if (row.isSelected) 0.04f else 0f)
      pinColor.copy(alpha = (baseLight + extra).coerceAtMost(0.16f))
    }

  val minHeightDp = metrics.minRowHeightDp.dp
  val rowModifier =
    if (row.featureIndex != null) {
      Modifier
        .fillMaxWidth()
        .heightIn(min = minHeightDp)
        .then(borderMod)
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .testTag("ladder_row_${row.id}")
    } else {
      Modifier
        .fillMaxWidth()
        .heightIn(min = minHeightDp)
        .then(borderMod)
        .testTag("ladder_row_${row.id}")
    }

  val primaryTextColor = if (isDark) Color.White else Color(0xFF0F172A)
  val secondaryTextColor = if (isDark) Color(0xFFD5DCE6) else Color(0xFF334155)

  GlassSurface(
    shape = RoundedCornerShape(12.dp),
    isLighterInner = true,
    withShadow = false,
    tintOverlay = overlayColor,
    modifier = rowModifier,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Left side branches (weight 1f) — render ALL branches, never "+N"
      Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        row.leftBranches.forEach { br ->
          LadderSideBranchBlock(
            branch = br,
            alignEnd = true,
            isSlowRoadStretch = row.isSlowRoadStretch,
            slowRoadEndDistMeters = row.slowRoadEndDistMeters,
            unitSystem = unitSystem,
            metrics = metrics,
            nameOrder = nameOrder,
            primaryColor = primaryTextColor,
            secondaryColor = secondaryTextColor,
          )
        }
      }

      // Centerline pill: distance + type + optional "Road continues as ..." or fork "Main"
      val pillHighlight = row.isSelected || isNearest
      val pillBg =
        if (pillHighlight) pinColor
        else if (isDark) Color(0xFF1E293B)
        else pinColor.copy(alpha = 0.16f)
      val pillTextColor =
        if (pillHighlight || isDark) Color.White
        else Color(0xFF0F172A)
      val pillTypeColor =
        if (pillHighlight) Color.White.copy(alpha = 0.94f)
        else if (isDark) Color(0xFFD5DCE6)
        else Color(0xFF334155)

      Column(
        modifier =
          Modifier
            .widthIn(min = 88.dp, max = 160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(pillBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = formatRoadDistance(row.distanceMeters, unitSystem),
          fontSize = metrics.distanceSp.sp,
          lineHeight = (metrics.distanceSp + 2f).sp,
          fontWeight = FontWeight.Bold,
          color = pillTextColor,
          maxLines = 1,
          softWrap = false,
          textAlign = TextAlign.Center,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = ladderTypeIcon(row.featureType),
            contentDescription = featureTypeTitle(row.featureType),
            tint = if (pillHighlight) Color.White else pinColor,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = featureTypeTitle(row.featureType),
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = pillTypeColor,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
          )
        }

        val contName = row.continuesAsName
        if (contName != null && contName.hasAny) {
          val (cPrimary, cSec) = resolveDisplayLines(contName, nameOrder)
          val contDisplay = cPrimary ?: cSec ?: ""
          if (contDisplay.isNotBlank()) {
            Text(
              text = stringResource(R.string.ladder_road_continues_as, contDisplay),
              fontFamily = if (BilingualName.containsArabic(contDisplay)) NotoArabicFontFamily else RobotoFontFamily,
              fontSize = 15.sp,
              lineHeight = 18.sp,
              fontWeight = FontWeight.SemiBold,
              color = pillTextColor,
              maxLines = 3,
              softWrap = true,
              overflow = TextOverflow.Clip,
              textAlign = TextAlign.Center,
            )
          }
        }

        val fMain = row.forkMainName
        if (row.featureType == FeatureType.FORK && fMain != null) {
          val (mPrimary, mSec) = resolveDisplayLines(fMain, nameOrder)
          val mainText = mPrimary ?: mSec
          val mainBadge = stringResource(R.string.ladder_main_branch_tag)
          val combined = if (!mainText.isNullOrBlank()) "$mainBadge · $mainText" else mainBadge
          Text(
            text = combined,
            fontFamily = if (BilingualName.containsArabic(combined)) NotoArabicFontFamily else RobotoFontFamily,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            color = pillTextColor,
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
          )
        }
      }

      // Right side branches OR centered/non-branching event label (weight 1f) — render ALL branches, never "+N"
      Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        when {
          row.rightBranches.isNotEmpty() -> {
            row.rightBranches.forEach { br ->
              LadderSideBranchBlock(
                branch = br,
                alignEnd = false,
                isSlowRoadStretch = row.isSlowRoadStretch,
                slowRoadEndDistMeters = row.slowRoadEndDistMeters,
                unitSystem = unitSystem,
                metrics = metrics,
                nameOrder = nameOrder,
                primaryColor = primaryTextColor,
                secondaryColor = secondaryTextColor,
              )
            }
          }
          row.aheadBranch != null -> {
            LadderSideBranchBlock(
              branch = row.aheadBranch,
              alignEnd = false,
              isSlowRoadStretch = row.isSlowRoadStretch,
              slowRoadEndDistMeters = row.slowRoadEndDistMeters,
              unitSystem = unitSystem,
              metrics = metrics,
              nameOrder = nameOrder,
              primaryColor = primaryTextColor,
              secondaryColor = secondaryTextColor,
            )
          }
          row.leftBranches.isEmpty() && row.continuesAsName == null -> {
            val (cPrimary, cSecondary) = resolveDisplayLines(row.centerName, nameOrder)
            val evtPrimary = cPrimary ?: cSecondary ?: featureTypeTitle(row.featureType)
            AutoShrinkMultilineText(
              text = evtPrimary,
              preferredFontSize = metrics.primarySp.sp,
              minFontSize = 15.sp,
              fontWeight = FontWeight.SemiBold,
              color = primaryTextColor,
              fontFamily = if (BilingualName.containsArabic(evtPrimary)) NotoArabicFontFamily else RobotoFontFamily,
              maxLines = 3,
              modifier = Modifier.fillMaxWidth(),
            )
            if (cPrimary != null && cSecondary != null) {
              AutoShrinkMultilineText(
                text = cSecondary,
                preferredFontSize = metrics.secondarySp.sp,
                minFontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = secondaryTextColor,
                fontFamily = if (BilingualName.containsArabic(cSecondary)) NotoArabicFontFamily else RobotoFontFamily,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LadderSideBranchBlock(
  branch: RoadLadderBranch,
  alignEnd: Boolean,
  isSlowRoadStretch: Boolean,
  slowRoadEndDistMeters: Double?,
  unitSystem: UnitSystem,
  metrics: LadderDisplayMetrics,
  nameOrder: NameOrder,
  primaryColor: Color,
  secondaryColor: Color,
) {
  val roadColors = LocalRoadColors.current
  val unnamedFallback =
    if (branch.highway != null) unnamedRoadWithClassText(branch.highway)
    else stringResource(R.string.ladder_unnamed_street)
  val unknownDest = stringResource(R.string.ladder_unknown_dest)

  val (resolvedPrimary, resolvedSecondary) =
    if (isSlowRoadStretch) {
      val sideLabel =
        if (alignEnd) stringResource(R.string.ladder_slow_road_left)
        else stringResource(R.string.ladder_slow_road_right)
      val (nFirst, nSecond) = resolveDisplayLines(branch.name, nameOrder)
      val subName = nFirst ?: nSecond
      val endRange = slowRoadEndDistMeters?.let { "→ ${formatRoadDistance(it, unitSystem)}" }
      val sub = listOfNotNull(subName, endRange).joinToString(" · ").takeIf { it.isNotBlank() }
      sideLabel to sub
    } else if (branch.isExitRamp) {
      val (destFirst, destSecond) = resolveDisplayLines(branch.name, nameOrder)
      val dest = branch.leadsTo?.takeIf { it.isNotBlank() } ?: destFirst ?: destSecond ?: unknownDest
      stringResource(R.string.ladder_ramp_to_format, dest) to null
    } else if (branch.name.hasAny) {
      resolveDisplayLines(branch.name, nameOrder)
    } else {
      unnamedFallback to null
    }

  val primaryLine = resolvedPrimary ?: resolvedSecondary ?: unnamedFallback
  val secondaryLine = if (resolvedPrimary != null) resolvedSecondary else null

  val relSuffix =
    when (branch.passByRelation) {
      PassByRelation.DIRECT -> stringResource(R.string.relation_direct)
      PassByRelation.VIA_FRONTAGE_OR_RAMP -> stringResource(R.string.relation_via_slow_road)
      PassByRelation.ACROSS_MEDIAN -> stringResource(R.string.relation_across_median)
      PassByRelation.GRADE_SEPARATED -> branch.leadsTo ?: stringResource(R.string.relation_overpass)
      PassByRelation.NOT_CONNECTED -> stringResource(R.string.relation_not_connected)
      null -> null
    }

  val (statusIcon, statusTint, statusWord) =
    when (branch.status) {
      BranchStatus.OPEN -> Triple(Icons.Default.CheckCircle, roadColors.openText, null)
      BranchStatus.BLOCKED -> Triple(Icons.Default.Close, roadColors.blockedText, stringResource(R.string.branch_blocked))
      BranchStatus.UNVERIFIED -> Triple(Icons.Default.HelpOutline, roadColors.unverifiedText, stringResource(R.string.branch_unverified))
    }

  val textAlign = if (alignEnd) TextAlign.End else TextAlign.Start

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (alignEnd) {
        Icon(
          imageVector = statusIcon,
          contentDescription = statusWord ?: branch.status.name,
          tint = statusTint,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
      }
      val primaryDir = if (GeoMath.isRtlScript(primaryLine)) LayoutDirection.Rtl else LayoutDirection.Ltr
      CompositionLocalProvider(LocalLayoutDirection provides primaryDir) {
        AutoShrinkMultilineText(
          text = primaryLine,
          preferredFontSize = metrics.primarySp.sp,
          minFontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          color = primaryColor,
          fontFamily = if (BilingualName.containsArabic(primaryLine)) NotoArabicFontFamily else RobotoFontFamily,
          maxLines = 3,
          textAlign = textAlign,
          modifier = Modifier.weight(1f),
        )
      }
      if (!alignEnd) {
        Spacer(modifier = Modifier.width(5.dp))
        Icon(
          imageVector = statusIcon,
          contentDescription = statusWord ?: branch.status.name,
          tint = statusTint,
          modifier = Modifier.size(16.dp),
        )
      }
    }

    if (!secondaryLine.isNullOrBlank()) {
      val secDir = if (GeoMath.isRtlScript(secondaryLine)) LayoutDirection.Rtl else LayoutDirection.Ltr
      CompositionLocalProvider(LocalLayoutDirection provides secDir) {
        AutoShrinkMultilineText(
          text = secondaryLine,
          preferredFontSize = metrics.secondarySp.sp,
          minFontSize = 15.sp,
          fontWeight = FontWeight.Normal,
          color = secondaryColor,
          fontFamily = if (BilingualName.containsArabic(secondaryLine)) NotoArabicFontFamily else RobotoFontFamily,
          maxLines = 3,
          textAlign = textAlign,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (statusWord != null || relSuffix != null) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (statusWord != null) {
          Text(
            text = statusWord,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            color = statusTint,
            maxLines = 2,
            overflow = TextOverflow.Clip,
          )
          if (relSuffix != null) {
            Spacer(modifier = Modifier.width(6.dp))
          }
        }
        if (relSuffix != null) {
          Text(
            text = relSuffix,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = secondaryColor,
            maxLines = 2,
            overflow = TextOverflow.Clip,
          )
        }
      }
    }
  }
}

private fun ladderTypeIcon(type: FeatureType): ImageVector =
  when (type) {
    FeatureType.ROUNDABOUT, FeatureType.MINI_ROUNDABOUT -> Icons.Default.Loop
    FeatureType.FORK -> Icons.AutoMirrored.Filled.CallSplit
    FeatureType.MERGE -> Icons.AutoMirrored.Filled.MergeType
    FeatureType.OFF_RAMP, FeatureType.ON_RAMP,
    FeatureType.INTERCHANGE, FeatureType.CLOVERLEAF,
    FeatureType.DIAMOND, FeatureType.TRUMPET, FeatureType.DIRECTIONAL -> Icons.AutoMirrored.Filled.AltRoute
    FeatureType.SHARP_CURVE, FeatureType.HAIRPIN, FeatureType.WINDING_ROAD -> Icons.Default.TurnLeft
    FeatureType.U_TURN, FeatureType.MEDIAN_OPENING -> Icons.Default.UTurnLeft
    FeatureType.DEAD_END, FeatureType.BARRIER, FeatureType.ROAD_WORKS -> Icons.Default.Warning
    FeatureType.BRIDGE, FeatureType.OVERPASS_ABOVE, FeatureType.UNDERPASS_BELOW, FeatureType.TUNNEL -> Icons.Default.ArrowUpward
    FeatureType.SERVICE_ROAD, FeatureType.FRONTAGE_ROAD -> Icons.Default.Home
    else -> Icons.Default.Directions
  }
