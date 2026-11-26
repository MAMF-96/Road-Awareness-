package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.LadderOrder
import com.example.data.model.PassByRelation
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadFeature
import com.example.data.model.UnitSystem
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
)

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

    for (feat in features) {
      val displayType = feat.effectiveDisplayType()
      val lefts = ArrayList<RoadLadderBranch>()
      val rights = ArrayList<RoadLadderBranch>()
      var ahead: RoadLadderBranch? = null

      for (br in feat.branches) {
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
        ahead?.name?.takeIf { it.hasAny }
          ?: BilingualName.fromOsmTags(feat.osmTags).takeIf { it.hasAny }

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
        )
    }

    for (entry in timelineEntries) {
      if (!entry.isPassByStreet) continue
      if (entry.primaryWayId != null && entry.primaryWayId in coveredWayIds &&
        rows.any { abs(it.distanceMeters - entry.distanceMeters) <= 25.0 }
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
          id = "passby_${entry.primaryWayId ?: entry.distanceMeters.toInt()}_${entry.passBySide?.name ?: "NONE"}",
          distanceMeters = entry.distanceMeters,
          featureIndex = entry.featureIndex,
          featureType = entry.type,
          leftBranches = lefts,
          rightBranches = rights,
          aheadBranch = ahead,
          centerName = entry.title.takeIf { it.hasAny },
          isPassByStreet = true,
          isSelected = entry.featureIndex != null && entry.featureIndex == selectedFeatureIndex,
        )
    }

    val sortedAsc = rows.sortedWith(compareBy<RoadLadderRow> { it.distanceMeters }.thenBy { it.featureIndex ?: Int.MAX_VALUE })
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
) {
  val allRows =
    RoadLadderBuilder.buildRows(
      features = features,
      timelineEntries = timelineEntries,
      selectedFeatureIndex = selectedFeatureIndex,
      ladderOrder = ladderOrder,
      horizonStoppedAtUnloadedBorder = horizonStoppedAtUnloadedBorder,
    )

  if (allRows.isEmpty()) return

  val roadColors = LocalRoadColors.current
  val isDark = LocalGlassConfig.current.isDark

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().testTag("road_ladder_section"),
  ) {
    val availH = maxHeight
    val userMarkerH = 18.dp
    val chipReserveH = if (allRows.size > 4) 24.dp else 0.dp
    val estRowH = if (compactMode) 28.dp else 36.dp
    val maxWholeRows =
      if (compactMode) {
        ((availH - userMarkerH - chipReserveH) / estRowH).toInt().coerceAtLeast(1)
      } else {
        allRows.size
      }

    // In NEAREST_AT_BOTTOM keep the rows nearest to user (at end of list); in NEAREST_AT_TOP keep nearest (at start).
    val visibleRows =
      if (allRows.size <= maxWholeRows) {
        allRows
      } else if (ladderOrder == LadderOrder.NEAREST_AT_BOTTOM) {
        allRows.takeLast(maxWholeRows)
      } else {
        allRows.take(maxWholeRows)
      }
    val overflowCount = (allRows.size - visibleRows.size).coerceAtLeast(0)

    Box(modifier = Modifier.fillMaxSize()) {
      // Continuous vertical centerline representing your road
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
        verticalArrangement =
          if (ladderOrder == LadderOrder.NEAREST_AT_BOTTOM) Arrangement.Bottom else Arrangement.Top,
      ) {
        if (overflowCount > 0 && ladderOrder == LadderOrder.NEAREST_AT_BOTTOM) {
          LadderOverflowChip(
            overflowCount = overflowCount,
            onClick = onExpandRequest,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp),
          )
        }

        if (ladderOrder == LadderOrder.NEAREST_AT_TOP) {
          LadderYouHerePill(modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp))
        }

        Column(
          modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          visibleRows.forEach { row ->
            AnimatedVisibility(
              visible = true,
              enter = fadeIn(),
              exit = fadeOut(),
            ) {
              RoadLadderRowItem(
                row = row,
                unitSystem = unitSystem,
                compact = compactMode,
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
          LadderYouHerePill(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))
        }

        if (overflowCount > 0 && ladderOrder == LadderOrder.NEAREST_AT_TOP) {
          LadderOverflowChip(
            overflowCount = overflowCount,
            onClick = onExpandRequest,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun LadderYouHerePill(modifier: Modifier = Modifier) {
  Row(
    modifier =
      modifier
        .clip(CircleShape)
        .background(RoadColors.Primary)
        .padding(horizontal = 7.dp, vertical = 1.dp)
        .testTag("ladder_you_here"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Icon(
      imageVector = Icons.Default.Navigation,
      contentDescription = stringResource(R.string.ladder_you_here),
      tint = Color.White,
      modifier = Modifier.size(10.dp),
    )
    Text(
      text = stringResource(R.string.ladder_you_here),
      color = Color.White,
      fontSize = 9.5.sp,
      lineHeight = 11.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

@Composable
private fun LadderOverflowChip(
  overflowCount: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = rememberPressHapticInteractionSource()
  val pressed by interactionSource.collectIsPressedAsState()
  Surface(
    onClick = {
      TouchTelemetry.recordControlHandled("ladder_overflow_chip")
      onClick()
    },
    interactionSource = interactionSource,
    shape = CircleShape,
    color =
      if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
      else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.80f),
    modifier = modifier.heightIn(min = 20.dp).testTag("ladder_overflow_chip"),
  ) {
    Text(
      text = stringResource(R.string.ladder_more_rows_format, overflowCount),
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      fontSize = 10.sp,
      lineHeight = 12.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.innerPressScale(interactionSource).padding(horizontal = 8.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun RoadLadderRowItem(
  row: RoadLadderRow,
  unitSystem: UnitSystem,
  compact: Boolean,
  onClick: () -> Unit,
) {
  val roadColors = LocalRoadColors.current
  val isDark = LocalGlassConfig.current.isDark

  if (row.isLoadingContinues) {
    GlassSurface(
      shape = RoundedCornerShape(10.dp),
      isLighterInner = true,
      withShadow = false,
      modifier = Modifier.fillMaxWidth().testTag("ladder_loading_row"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.Sync,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = stringResource(R.string.ladder_continues_loading),
          fontSize = 10.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
        )
      }
    }
    return
  }

  val pinColor =
    if (row.featureIndex != null) RoadColors.pinColorForIndex(row.featureIndex)
    else MaterialTheme.colorScheme.primary
  val borderMod =
    if (row.isSelected) Modifier.border(1.4.dp, pinColor, RoundedCornerShape(10.dp))
    else Modifier

  val rowModifier =
    if (row.featureIndex != null) {
      Modifier
        .fillMaxWidth()
        .then(borderMod)
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .testTag("ladder_row_${row.id}")
    } else {
      Modifier
        .fillMaxWidth()
        .then(borderMod)
        .testTag("ladder_row_${row.id}")
    }

  GlassSurface(
    shape = RoundedCornerShape(10.dp),
    isLighterInner = true,
    withShadow = false,
    tintOverlay =
      if (row.isSelected) pinColor.copy(alpha = if (isDark) 0.14f else 0.08f)
      else Color.Transparent,
    modifier = rowModifier,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = if (compact) 2.dp else 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      // Left side branches
      Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.CenterEnd,
      ) {
        val left = row.leftBranches.firstOrNull()
        if (left != null) {
          LadderSideBranchText(
            branch = left,
            alignEnd = true,
            compact = compact,
            extraCount = (row.leftBranches.size - 1).coerceAtLeast(0),
          )
        } else if (row.rightBranches.isEmpty() && row.aheadBranch == null) {
          // Centered event label split softly or shown right of badge
          Spacer(modifier = Modifier.width(1.dp))
        }
      }

      // Centerline pill: distance + type icon
      Row(
        modifier =
          Modifier
            .clip(CircleShape)
            .background(if (row.isSelected) pinColor else pinColor.copy(alpha = 0.18f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Icon(
          imageVector = ladderTypeIcon(row.featureType),
          contentDescription = featureTypeTitle(row.featureType),
          tint = if (row.isSelected) Color.White else pinColor,
          modifier = Modifier.size(11.dp),
        )
        Text(
          text = formatRoadDistance(row.distanceMeters, unitSystem),
          fontSize = 10.sp,
          lineHeight = 12.sp,
          fontWeight = FontWeight.Bold,
          color = if (row.isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
      }

      // Right side branches OR centered/non-branching event label
      Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.CenterStart,
      ) {
        val right = row.rightBranches.firstOrNull()
        when {
          right != null -> {
            LadderSideBranchText(
              branch = right,
              alignEnd = false,
              compact = compact,
              extraCount = (row.rightBranches.size - 1).coerceAtLeast(0),
            )
          }
          row.aheadBranch != null -> {
            LadderSideBranchText(
              branch = row.aheadBranch,
              alignEnd = false,
              compact = compact,
              extraCount = 0,
            )
          }
          row.leftBranches.isEmpty() -> {
            val evtLabel =
              row.centerName?.primaryText
                ?: row.centerName?.ref
                ?: featureTypeTitle(row.featureType)
            Text(
              text = evtLabel,
              fontFamily = if (BilingualName.containsArabic(evtLabel)) NotoArabicFontFamily else RobotoFontFamily,
              fontSize = if (compact) 11.sp else 12.sp,
              lineHeight = if (compact) 12.5.sp else 14.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Clip,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LadderSideBranchText(
  branch: RoadLadderBranch,
  alignEnd: Boolean,
  compact: Boolean,
  extraCount: Int,
) {
  val roadColors = LocalRoadColors.current
  val unnamedFallback = stringResource(R.string.ladder_unnamed_street)
  val unknownDest = stringResource(R.string.ladder_unknown_dest)

  val rawPrimary =
    when {
      branch.isExitRamp -> {
        val dest = branch.leadsTo?.takeIf { it.isNotBlank() } ?: branch.name.primaryText ?: unknownDest
        stringResource(R.string.ladder_ramp_to_format, dest)
      }
      branch.name.hasAny -> {
        val ar = branch.name.arabic?.takeIf { it.isNotBlank() }
        val en = branch.name.english?.takeIf { it.isNotBlank() } ?: branch.name.ref?.takeIf { it.isNotBlank() }
        when {
          ar != null && en != null && ar != en -> "$ar\n$en"
          ar != null -> ar
          en != null -> en
          else -> unnamedFallback
        }
      }
      else -> unnamedFallback
    }

  val relSuffix =
    when (branch.passByRelation) {
      PassByRelation.ACROSS_MEDIAN -> " · median"
      PassByRelation.VIA_FRONTAGE_OR_RAMP -> " · via ramp"
      PassByRelation.GRADE_SEPARATED -> branch.leadsTo?.let { " · $it" } ?: " · overpass"
      PassByRelation.NOT_CONNECTED -> " · no link"
      PassByRelation.DIRECT, null -> ""
    }
  val displayText = if (extraCount > 0) "$rawPrimary$relSuffix (+$extraCount)" else "$rawPrimary$relSuffix"

  val (statusIcon, statusTint) =
    when (branch.status) {
      BranchStatus.OPEN -> Icons.Default.CheckCircle to roadColors.openText
      BranchStatus.BLOCKED -> Icons.Default.Close to roadColors.blockedText
      BranchStatus.UNVERIFIED -> Icons.Default.HelpOutline to roadColors.unverifiedText
    }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (alignEnd) {
      Icon(
        imageVector = statusIcon,
        contentDescription = branch.status.name,
        tint = statusTint,
        modifier = Modifier.size(10.dp),
      )
      Spacer(modifier = Modifier.width(3.dp))
    }
    Text(
      text = displayText,
      fontFamily = if (BilingualName.containsArabic(displayText)) NotoArabicFontFamily else RobotoFontFamily,
      fontSize = if (compact) 11.sp else 12.sp,
      lineHeight = if (compact) 12.5.sp else 14.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      softWrap = true,
      overflow = TextOverflow.Clip,
      textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
      modifier = Modifier.weight(1f, fill = false),
    )
    if (!alignEnd) {
      Spacer(modifier = Modifier.width(3.dp))
      Icon(
        imageVector = statusIcon,
        contentDescription = branch.status.name,
        tint = statusTint,
        modifier = Modifier.size(10.dp),
      )
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
