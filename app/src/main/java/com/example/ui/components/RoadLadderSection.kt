package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
  textSize: LadderTextSize = LadderTextSize.LARGE,
  nameOrder: NameOrder = LocalNameOrder.current,
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

  val isDark = LocalGlassConfig.current.isDark
  val nearestRowId = allRows.filterNot { it.isLoadingContinues }.minByOrNull { it.distanceMeters }?.id

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("road_ladder_section"),
  ) {
    val availH = maxHeight
    val userMarkerH = 24.dp
    val chipReserveH = if (allRows.size > 3) 26.dp else 0.dp
    val estRowH = if (compactMode) 62.dp else 72.dp
    val maxWholeRows =
      if (compactMode) {
        ((availH - userMarkerH - chipReserveH) / estRowH).toInt().coerceIn(2, 5)
      } else {
        allRows.size
      }

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
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
          )
        }

        if (ladderOrder == LadderOrder.NEAREST_AT_TOP) {
          LadderYouHerePill(modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
        }

        Column(
          modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          visibleRows.forEachIndexed { rowIndex, row ->
            key(row.id) {
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
                  textSize = textSize,
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
        }

        if (ladderOrder == LadderOrder.NEAREST_AT_BOTTOM) {
          LadderYouHerePill(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
        }

        if (overflowCount > 0 && ladderOrder == LadderOrder.NEAREST_AT_TOP) {
          LadderOverflowChip(
            overflowCount = overflowCount,
            onClick = onExpandRequest,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
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
        .padding(horizontal = 10.dp, vertical = 2.dp)
        .testTag("ladder_you_here"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = Icons.Default.Navigation,
      contentDescription = stringResource(R.string.ladder_you_here),
      tint = Color.White,
      modifier = Modifier.size(13.dp),
    )
    Text(
      text = stringResource(R.string.ladder_you_here),
      color = Color.White,
      fontSize = 13.sp,
      lineHeight = 16.sp,
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
      if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
      else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
    modifier = modifier.heightIn(min = 24.dp).testTag("ladder_overflow_chip"),
  ) {
    Text(
      text = stringResource(R.string.ladder_more_rows_format, overflowCount),
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      fontSize = 13.sp,
      lineHeight = 16.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.innerPressScale(interactionSource).padding(horizontal = 10.dp, vertical = 3.dp),
    )
  }
}

@Composable
private fun RoadLadderRowItem(
  row: RoadLadderRow,
  rowIndex: Int,
  isNearest: Boolean,
  unitSystem: UnitSystem,
  textSize: LadderTextSize,
  nameOrder: NameOrder,
  onClick: () -> Unit,
) {
  val isDark = LocalGlassConfig.current.isDark

  if (row.isLoadingContinues) {
    GlassSurface(
      shape = RoundedCornerShape(12.dp),
      isLighterInner = true,
      withShadow = false,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ladder_loading_row"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.Sync,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.ladder_continues_loading),
          fontSize = 14.sp,
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
    when {
      isNearest -> Modifier.border(1.6.dp, pinColor, RoundedCornerShape(12.dp))
      row.isSelected -> Modifier.border(1.4.dp, pinColor.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
      else -> Modifier
    }

  val baseDarkAlpha = if (rowIndex % 2 == 0) 0.06f else 0.03f
  val overlayColor =
    if (isDark) {
      val extra = (if (isNearest) 0.04f else 0f) + (if (row.isSelected) 0.04f else 0f)
      Color.White.copy(alpha = (baseDarkAlpha + extra).coerceAtMost(0.16f))
    } else {
      val baseLight = if (rowIndex % 2 == 0) 0.05f else 0.02f
      val extra = (if (isNearest) 0.05f else 0f) + (if (row.isSelected) 0.04f else 0f)
      pinColor.copy(alpha = (baseLight + extra).coerceAtMost(0.16f))
    }

  val rowModifier =
    if (row.featureIndex != null) {
      Modifier
        .fillMaxWidth()
        .heightIn(min = 60.dp)
        .then(borderMod)
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .testTag("ladder_row_${row.id}")
    } else {
      Modifier
        .fillMaxWidth()
        .heightIn(min = 60.dp)
        .then(borderMod)
        .testTag("ladder_row_${row.id}")
    }

  val primaryTextColor = if (isDark) Color.White else Color(0xFF0F172A)
  val secondaryTextColor = if (isDark) Color(0xFFC9D1DC) else Color(0xFF334155)

  GlassSurface(
    shape = RoundedCornerShape(12.dp),
    isLighterInner = true,
    withShadow = false,
    tintOverlay = overlayColor,
    modifier = rowModifier,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Left side branches (weight 1f)
      Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        if (row.leftBranches.isNotEmpty()) {
          val shown = row.leftBranches.take(2)
          shown.forEach { br ->
            LadderSideBranchBlock(
              branch = br,
              alignEnd = true,
              textSize = textSize,
              nameOrder = nameOrder,
              primaryColor = primaryTextColor,
              secondaryColor = secondaryTextColor,
            )
          }
          val extra = row.leftBranches.size - shown.size
          if (extra > 0) {
            Text(
              text = stringResource(R.string.ladder_more_rows_format, extra),
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
            )
          }
        }
      }

      // Centerline pill: minimum 80.dp width, bold 20/22/24sp distance + 12sp type label & icon
      val pillHighlight = row.isSelected || isNearest
      val pillBg =
        if (pillHighlight) pinColor
        else if (isDark) Color(0xFF1E293B)
        else pinColor.copy(alpha = 0.16f)
      val pillTextColor =
        if (pillHighlight || isDark) Color.White
        else Color(0xFF0F172A)
      val pillTypeColor =
        if (pillHighlight) Color.White.copy(alpha = 0.92f)
        else if (isDark) Color(0xFFC9D1DC)
        else Color(0xFF334155)

      Column(
        modifier =
          Modifier
            .widthIn(min = 80.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(pillBg)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = formatRoadDistance(row.distanceMeters, unitSystem),
          fontSize = textSize.distanceSp.sp,
          lineHeight = (textSize.distanceSp + 2).sp,
          fontWeight = FontWeight.Bold,
          color = pillTextColor,
          maxLines = 1,
          softWrap = false,
          textAlign = TextAlign.Center,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          Icon(
            imageVector = ladderTypeIcon(row.featureType),
            contentDescription = featureTypeTitle(row.featureType),
            tint = if (pillHighlight) Color.White else pinColor,
            modifier = Modifier.size(13.dp),
          )
          Text(
            text = featureTypeTitle(row.featureType),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = pillTypeColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // Right side branches OR centered/non-branching event label (weight 1f)
      Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        when {
          row.rightBranches.isNotEmpty() -> {
            val shown = row.rightBranches.take(2)
            shown.forEach { br ->
              LadderSideBranchBlock(
                branch = br,
                alignEnd = false,
                textSize = textSize,
                nameOrder = nameOrder,
                primaryColor = primaryTextColor,
                secondaryColor = secondaryTextColor,
              )
            }
            val extra = row.rightBranches.size - shown.size
            if (extra > 0) {
              Text(
                text = stringResource(R.string.ladder_more_rows_format, extra),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
              )
            }
          }
          row.aheadBranch != null -> {
            LadderSideBranchBlock(
              branch = row.aheadBranch,
              alignEnd = false,
              textSize = textSize,
              nameOrder = nameOrder,
              primaryColor = primaryTextColor,
              secondaryColor = secondaryTextColor,
            )
          }
          row.leftBranches.isEmpty() -> {
            val (cPrimary, cSecondary) = resolveDisplayLines(row.centerName, nameOrder)
            val evtPrimary = cPrimary ?: cSecondary ?: featureTypeTitle(row.featureType)
            Text(
              text = evtPrimary,
              fontFamily = if (BilingualName.containsArabic(evtPrimary)) NotoArabicFontFamily else RobotoFontFamily,
              fontSize = textSize.primarySp.sp,
              lineHeight = (textSize.primarySp + 3).sp,
              fontWeight = FontWeight.SemiBold,
              color = primaryTextColor,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            if (cPrimary != null && cSecondary != null) {
              Text(
                text = cSecondary,
                fontFamily = if (BilingualName.containsArabic(cSecondary)) NotoArabicFontFamily else RobotoFontFamily,
                fontSize = textSize.secondarySp.sp,
                lineHeight = (textSize.secondarySp + 3).sp,
                fontWeight = FontWeight.Normal,
                color = secondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
  textSize: LadderTextSize,
  nameOrder: NameOrder,
  primaryColor: Color,
  secondaryColor: Color,
) {
  val roadColors = LocalRoadColors.current
  val unnamedFallback = unnamedRoadWithClassText(branch.highway)
  val unknownDest = stringResource(R.string.ladder_unknown_dest)

  val (resolvedPrimary, resolvedSecondary) =
    if (branch.isExitRamp) {
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
      PassByRelation.ACROSS_MEDIAN -> "median"
      PassByRelation.VIA_FRONTAGE_OR_RAMP -> "via ramp"
      PassByRelation.GRADE_SEPARATED -> branch.leadsTo ?: "overpass"
      PassByRelation.NOT_CONNECTED -> "no link"
      PassByRelation.DIRECT, null -> null
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
    verticalArrangement = Arrangement.spacedBy(1.dp),
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
          modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
      }
      val primaryDir = if (GeoMath.isRtlScript(primaryLine)) LayoutDirection.Rtl else LayoutDirection.Ltr
      CompositionLocalProvider(LocalLayoutDirection provides primaryDir) {
        Text(
          text = primaryLine,
          fontFamily = if (BilingualName.containsArabic(primaryLine)) NotoArabicFontFamily else RobotoFontFamily,
          fontSize = textSize.primarySp.sp,
          lineHeight = (textSize.primarySp + 3).sp,
          fontWeight = FontWeight.SemiBold,
          color = primaryColor,
          maxLines = 2,
          softWrap = true,
          overflow = TextOverflow.Ellipsis,
          textAlign = textAlign,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
      if (!alignEnd) {
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = statusIcon,
          contentDescription = statusWord ?: branch.status.name,
          tint = statusTint,
          modifier = Modifier.size(14.dp),
        )
      }
    }

    if (!secondaryLine.isNullOrBlank()) {
      val secDir = if (GeoMath.isRtlScript(secondaryLine)) LayoutDirection.Rtl else LayoutDirection.Ltr
      CompositionLocalProvider(LocalLayoutDirection provides secDir) {
        Text(
          text = secondaryLine,
          fontFamily = if (BilingualName.containsArabic(secondaryLine)) NotoArabicFontFamily else RobotoFontFamily,
          fontSize = textSize.secondarySp.sp,
          lineHeight = (textSize.secondarySp + 2).sp,
          fontWeight = FontWeight.Normal,
          color = secondaryColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
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
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            color = statusTint,
            maxLines = 1,
          )
          if (relSuffix != null) {
            Spacer(modifier = Modifier.width(4.dp))
          }
        }
        if (relSuffix != null) {
          Text(
            text = relSuffix,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = secondaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
