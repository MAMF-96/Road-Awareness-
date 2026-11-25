package com.example.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.LocalPoint
import com.example.data.model.OffRoadState
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadFeature
import com.example.data.model.TravelMode
import com.example.ui.theme.LocalRoadColors
import com.example.ui.theme.RoadColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Heading-up geometric schematic built strictly from the real connected OSM road geometry
 * around the selected upcoming feature, OR centered off-road surrounding stubs (R1..R3).
 */
@Composable
fun RoadSchematicCanvas(
  feature: RoadFeature?,
  travelMode: TravelMode,
  modifier: Modifier = Modifier,
  offRoadState: OffRoadState? = null,
  dividedRoadSideOffsetMeters: Double? = null,
) {
  val roadColors = LocalRoadColors.current
  val pinColor = feature?.let { RoadColors.pinColorForIndex(it.index) } ?: RoadColors.Primary

  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(18.dp))
        .testTag("schematic_canvas"),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      if (offRoadState != null && offRoadState.isOffRoad && offRoadState.selectedCandidate == null) {
        drawOffRoadSchematic(
          offRoadState = offRoadState,
          roadFill = roadColors.schematicRoadFill,
          roadCasing = roadColors.schematicRoadCasing,
        )
        return@Canvas
      }

      if (feature == null) {
        drawEmptyCorridorSchematic(
          roadFill = roadColors.schematicRoadFill,
          roadCasing = roadColors.schematicRoadCasing,
          isWalking = travelMode == TravelMode.WALKING,
          dividedSideSign = dividedRoadSideOffsetMeters?.let { if (it < 0.0) -1f else 1f },
        )
        return@Canvas
      }

      val displayType = feature.effectiveDisplayType()
      val isCurveFeature =
        displayType == FeatureType.SHARP_CURVE ||
          displayType == FeatureType.HAIRPIN ||
          displayType == FeatureType.WINDING_ROAD
      val schematicBranches =
        when {
          isCurveFeature -> emptyList()
          feature.isInterchangeGroup() ->
            feature.branches.filter { (it.distanceMeters - feature.distanceMeters) <= 250.0 }
          else -> feature.branches
        }
      val outgoingForScale =
        if (feature.isInterchangeGroup()) {
          feature.outgoingMainLocalPoints.filter { it.yMeters <= 250.0 }.ifEmpty { feature.outgoingMainLocalPoints.take(2) }
        } else {
          feature.outgoingMainLocalPoints
        }

      // Compute uniform isotropic scale tightly fitted to geometry bounding box
      val allPts = buildList {
        addAll(feature.incomingLocalPoints)
        addAll(outgoingForScale)
        if (!isCurveFeature) {
          addAll(feature.crossingLocalPoints)
          schematicBranches.forEach { addAll(it.localPoints) }
        }
      }

      val minX = (allPts.minOfOrNull { it.xMeters } ?: -18.0).coerceAtMost(-12.0)
      val maxX = (allPts.maxOfOrNull { it.xMeters } ?: 18.0).coerceAtLeast(12.0)
      val minY = (allPts.minOfOrNull { it.yMeters } ?: -22.0).coerceAtMost(-14.0)
      val maxY = (allPts.maxOfOrNull { it.yMeters } ?: 24.0).coerceAtLeast(16.0)

      val spanX = (maxX - minX).coerceAtLeast(24.0)
      val spanY = (maxY - minY).coerceAtLeast(28.0)
      val pad = 16.dp.toPx()
      val availW = (size.width - pad * 2f).coerceAtLeast(16f)
      val availH = (size.height - pad * 2f).coerceAtLeast(16f)
      val pxPerMeter = min(availW / spanX.toFloat(), availH / spanY.toFloat())

      val midX = (minX + maxX) * 0.5
      val midY = (minY + maxY) * 0.5
      val canvasCenterX = size.width * 0.5f
      val canvasCenterY = size.height * 0.5f

      fun toCanvas(pt: LocalPoint): Offset {
        return Offset(
          x = canvasCenterX + ((pt.xMeters - midX).toFloat() * pxPerMeter),
          y = canvasCenterY - ((pt.yMeters - midY).toFloat() * pxPerMeter),
        )
      }

      val anchorOffset = toCanvas(LocalPoint(0.0, 0.0))
      val isFootwayStyle =
        travelMode == TravelMode.WALKING &&
          (displayType == FeatureType.FOOTPATH ||
            displayType == FeatureType.CROSSWALK ||
            displayType == FeatureType.FOOTBRIDGE)
      val roadWidth =
        if (isFootwayStyle) (size.minDimension * 0.12f).coerceIn(10f, 20f)
        else (size.minDimension * 0.17f).coerceIn(14f, 28f)
      val casingWidth = roadWidth + 5.dp.toPx()
      val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

      val crossPts: List<Offset> =
        if (feature.crossingLocalPoints.size >= 2) {
          feature.crossingLocalPoints.map(::toCanvas)
        } else {
          val span = (size.minDimension * 0.36f).coerceAtLeast(26f)
          listOf(
            Offset(anchorOffset.x - span, anchorOffset.y),
            Offset(anchorOffset.x + span, anchorOffset.y),
          )
        }

      // 0) UNDERPASS_BELOW: draw crossing road BELOW (dashed) before user's solid road
      if (displayType == FeatureType.UNDERPASS_BELOW) {
        drawPolyline(crossPts, roadColors.schematicRoadCasing.copy(alpha = 0.55f), casingWidth * 0.85f, dashEffect)
        drawPolyline(crossPts, roadColors.schematicRoadFill.copy(alpha = 0.60f), roadWidth * 0.80f, dashEffect)
      }

      // 0b) Divided carriageway (when dual carriageway detected or MEDIAN_OPENING)
      val dualOffsetMeters =
        feature.dualCarriagewayOffsetMeters
          ?: dividedRoadSideOffsetMeters
          ?: if (displayType == FeatureType.MEDIAN_OPENING) -18.0 else null
      val dualShiftPx: Float? =
        if (dualOffsetMeters != null && displayType != FeatureType.FRONTAGE_ROAD) {
          val sign = if (dualOffsetMeters < 0.0) -1f else 1f
          sign * roadWidth * 1.48f
        } else {
          null
        }
      if (dualShiftPx != null) {
        val partnerShiftPts =
          if (feature.incomingLocalPoints.size >= 2 || feature.outgoingMainLocalPoints.size >= 2) {
            (feature.incomingLocalPoints + feature.outgoingMainLocalPoints.drop(1))
              .distinct()
              .map { pt -> toCanvas(pt).let { Offset((it.x + dualShiftPx).coerceIn(6f, size.width - 6f), it.y) } }
          } else {
            listOf(
              Offset((anchorOffset.x + dualShiftPx).coerceIn(6f, size.width - 6f), size.height - 10.dp.toPx()),
              Offset((anchorOffset.x + dualShiftPx).coerceIn(6f, size.width - 6f), 10.dp.toPx()),
            )
          }
        if (partnerShiftPts.size >= 2) {
          drawPolyline(partnerShiftPts, roadColors.schematicRoadCasing, casingWidth * 0.86f)
          drawPolyline(partnerShiftPts, roadColors.schematicRoadFill, roadWidth * 0.82f)
          drawPolyline(partnerShiftPts, Color.White.copy(alpha = 0.78f), 1.8.dp.toPx(), dashEffect)
          // Median divider line between the carriageways
          val medianPts =
            partnerShiftPts.map { Offset(it.x - dualShiftPx * 0.5f, it.y) }
          drawPolyline(medianPts, Color(0xFFF59E0B).copy(alpha = 0.85f), 2.2.dp.toPx(), dashEffect)
        }
      }

      // Compute user marker position at bottom and ensure incoming road reaches it seamlessly
      val mappedIncoming = feature.incomingLocalPoints.map(::toCanvas)
      val userStart =
        if (mappedIncoming.isNotEmpty()) {
          mappedIncoming.first().let {
            Offset(
              x = it.x.coerceIn(size.width * 0.25f, size.width * 0.75f),
              y = max(it.y, size.height - 14.dp.toPx()).coerceAtMost(size.height - 10.dp.toPx()),
            )
          }
        } else {
          Offset(anchorOffset.x, size.height - 12.dp.toPx())
        }
      val incomingCanvasPts =
        if (mappedIncoming.size >= 2) {
          val firstPt = mappedIncoming.first()
          if (userStart.y > firstPt.y + 1f) listOf(userStart) + mappedIncoming else mappedIncoming
        } else {
          mappedIncoming
        }

      // 1) Draw user road & branch casings first
      if (incomingCanvasPts.size >= 2) {
        drawPolyline(incomingCanvasPts, roadColors.schematicRoadCasing, casingWidth)
      } else {
        drawLine(
          color = roadColors.schematicRoadCasing,
          start = userStart,
          end = anchorOffset,
          strokeWidth = casingWidth,
          cap = StrokeCap.Round,
        )
      }

      schematicBranches.forEach { branch ->
        if (branch.localPoints.size >= 2) {
          drawPolyline(branch.localPoints.map(::toCanvas), roadColors.schematicRoadCasing, casingWidth * 0.92f)
        }
      }

      val drawOutgoingMain =
        (isCurveFeature ||
          schematicBranches.isEmpty() ||
          displayType == FeatureType.OVERPASS_ABOVE ||
          displayType == FeatureType.UNDERPASS_BELOW ||
          displayType == FeatureType.TOLL_PLAZA ||
          displayType == FeatureType.CHECKPOINT ||
          displayType == FeatureType.BARRIER ||
          displayType == FeatureType.STAGGERED_JUNCTION ||
          displayType == FeatureType.MEDIAN_OPENING ||
          displayType == FeatureType.FRONTAGE_ROAD ||
          feature.isInterchangeGroup()) &&
          outgoingForScale.size >= 2
      if (drawOutgoingMain) {
        drawPolyline(outgoingForScale.map(::toCanvas), roadColors.schematicRoadCasing, casingWidth)
      }

      // 2) Draw thick road fills
      if (incomingCanvasPts.size >= 2) {
        drawPolyline(incomingCanvasPts, roadColors.schematicRoadFill, roadWidth)
      } else {
        drawLine(
          color = roadColors.schematicRoadFill,
          start = userStart,
          end = anchorOffset,
          strokeWidth = roadWidth,
          cap = StrokeCap.Round,
        )
      }

      schematicBranches.forEach { branch ->
        if (branch.localPoints.size >= 2) {
          drawPolyline(branch.localPoints.map(::toCanvas), roadColors.schematicRoadFill, roadWidth * 0.90f)
        }
      }

      if (drawOutgoingMain) {
        drawPolyline(outgoingForScale.map(::toCanvas), roadColors.schematicRoadFill, roadWidth)
      }

      // Centerline dashes on incoming + outgoing main
      if (incomingCanvasPts.size >= 2) {
        drawPolyline(incomingCanvasPts, Color.White.copy(alpha = 0.85f), 2.dp.toPx(), dashEffect)
      }
      if (drawOutgoingMain) {
        drawPolyline(outgoingForScale.map(::toCanvas), Color.White.copy(alpha = 0.85f), 2.dp.toPx(), dashEffect)
      }

      // 3) Per-FeatureType schematic templates
      when (displayType) {
        FeatureType.ROUNDABOUT, FeatureType.MINI_ROUNDABOUT -> {
          val r = if (displayType == FeatureType.MINI_ROUNDABOUT) roadWidth * 0.95f else roadWidth * 1.32f
          if (feature.branches.isEmpty()) {
            val stubLen = r * 1.65f
            listOf(
              Offset(anchorOffset.x - stubLen, anchorOffset.y),
              Offset(anchorOffset.x + stubLen, anchorOffset.y),
              Offset(anchorOffset.x, anchorOffset.y - stubLen),
            ).forEach { tip ->
              drawLine(roadColors.schematicRoadCasing, anchorOffset, tip, strokeWidth = casingWidth * 0.72f, cap = StrokeCap.Round)
              drawLine(roadColors.schematicRoadFill, anchorOffset, tip, strokeWidth = roadWidth * 0.72f, cap = StrokeCap.Round)
            }
          }
          drawCircle(
            color = roadColors.schematicRoadCasing,
            radius = r,
            center = anchorOffset,
            style = Stroke(width = casingWidth * 0.72f),
          )
          drawCircle(
            color = roadColors.schematicRoadFill,
            radius = r,
            center = anchorOffset,
            style = Stroke(width = roadWidth * 0.72f),
          )
          drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = r * 0.34f,
            center = anchorOffset,
          )
        }

        FeatureType.OVERPASS_ABOVE -> {
          // Cross road drawn OVER user's road with a bridge deck
          drawPolyline(crossPts, roadColors.schematicRoadCasing, casingWidth)
          drawPolyline(crossPts, roadColors.schematicRoadFill, roadWidth)
          drawPolyline(crossPts, Color(0xFFD97706), 2.5.dp.toPx(), dashEffect)
          val deckHalf = roadWidth * 0.82f
          drawLine(
            color = Color(0xFFD97706),
            start = Offset(anchorOffset.x - deckHalf * 1.15f, anchorOffset.y - roadWidth * 0.58f),
            end = Offset(anchorOffset.x + deckHalf * 1.15f, anchorOffset.y - roadWidth * 0.58f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
          )
          drawLine(
            color = Color(0xFFD97706),
            start = Offset(anchorOffset.x - deckHalf * 1.15f, anchorOffset.y + roadWidth * 0.58f),
            end = Offset(anchorOffset.x + deckHalf * 1.15f, anchorOffset.y + roadWidth * 0.58f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }

        FeatureType.STAGGERED_JUNCTION -> {
          val offsetY = roadWidth * 1.05f
          val stubLen = roadWidth * 1.75f
          if (feature.branches.size < 2) {
            val leftStart = Offset(anchorOffset.x, anchorOffset.y + offsetY * 0.5f)
            val leftEnd = Offset((anchorOffset.x - stubLen).coerceAtLeast(8.dp.toPx()), leftStart.y)
            val rightStart = Offset(anchorOffset.x, anchorOffset.y - offsetY * 0.6f)
            val rightEnd = Offset((anchorOffset.x + stubLen).coerceAtMost(size.width - 8.dp.toPx()), rightStart.y)
            drawLine(roadColors.schematicRoadCasing, leftStart, leftEnd, strokeWidth = casingWidth * 0.82f, cap = StrokeCap.Round)
            drawLine(roadColors.schematicRoadFill, leftStart, leftEnd, strokeWidth = roadWidth * 0.82f, cap = StrokeCap.Round)
            drawLine(roadColors.schematicRoadCasing, rightStart, rightEnd, strokeWidth = casingWidth * 0.82f, cap = StrokeCap.Round)
            drawLine(roadColors.schematicRoadFill, rightStart, rightEnd, strokeWidth = roadWidth * 0.82f, cap = StrokeCap.Round)
          }
          // Subtle stagger zone bracket indicator
          drawLine(
            color = pinColor.copy(alpha = 0.85f),
            start = Offset(anchorOffset.x, anchorOffset.y - offsetY * 0.7f),
            end = Offset(anchorOffset.x, anchorOffset.y + offsetY * 0.7f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }

        FeatureType.MEDIAN_OPENING -> {
          val shift = dualShiftPx ?: (-roadWidth * 1.48f)
          val crossStart = Offset(anchorOffset.x, anchorOffset.y)
          val crossEnd = Offset((anchorOffset.x + shift).coerceIn(8.dp.toPx(), size.width - 8.dp.toPx()), anchorOffset.y)
          drawLine(roadColors.schematicRoadCasing, crossStart, crossEnd, strokeWidth = casingWidth * 0.86f, cap = StrokeCap.Round)
          drawLine(roadColors.schematicRoadFill, crossStart, crossEnd, strokeWidth = roadWidth * 0.82f, cap = StrokeCap.Round)
          drawLine(pinColor, crossStart, crossEnd, strokeWidth = 2.8.dp.toPx(), cap = StrokeCap.Round)
        }

        FeatureType.FRONTAGE_ROAD -> {
          val sideSign = if (feature.frontageSide == RelativeDirection.LEFT) -1f else 1f
          val shift = sideSign * roadWidth * 1.38f
          val frontageX = (anchorOffset.x + shift).coerceIn(10.dp.toPx(), size.width - 10.dp.toPx())
          val fTop = Offset(frontageX, 10.dp.toPx())
          val fBot = Offset(frontageX, size.height - 10.dp.toPx())
          drawLine(roadColors.schematicRoadCasing, fBot, fTop, strokeWidth = casingWidth * 0.72f, cap = StrokeCap.Round)
          drawLine(roadColors.schematicRoadFill, fBot, fTop, strokeWidth = roadWidth * 0.68f, cap = StrokeCap.Round)
          // Connector between main road & parallel frontage road
          val connStart = anchorOffset
          val connEnd = Offset(frontageX, anchorOffset.y)
          drawLine(roadColors.schematicRoadCasing, connStart, connEnd, strokeWidth = casingWidth * 0.68f, cap = StrokeCap.Round)
          drawLine(roadColors.schematicRoadFill, connStart, connEnd, strokeWidth = roadWidth * 0.64f, cap = StrokeCap.Round)
          drawLine(
            color = Color(0xFF94A3B8),
            start = Offset((anchorOffset.x + frontageX) * 0.5f, 12.dp.toPx()),
            end = Offset((anchorOffset.x + frontageX) * 0.5f, size.height - 12.dp.toPx()),
            strokeWidth = 1.6.dp.toPx(),
            pathEffect = dashEffect,
          )
        }

        FeatureType.CLOVERLEAF -> {
          // Crossing highway + 4 quadrant loop ramps
          drawPolyline(crossPts, roadColors.schematicRoadCasing, casingWidth * 0.90f)
          drawPolyline(crossPts, roadColors.schematicRoadFill, roadWidth * 0.86f)
          drawPolyline(crossPts, Color.White.copy(alpha = 0.8f), 1.8.dp.toPx(), dashEffect)
          val loopR = (size.minDimension * 0.135f).coerceIn(10.dp.toPx(), 20.dp.toPx())
          val offsetDist = loopR * 1.25f
          listOf(
            Offset(anchorOffset.x - offsetDist, anchorOffset.y - offsetDist),
            Offset(anchorOffset.x + offsetDist, anchorOffset.y - offsetDist),
            Offset(anchorOffset.x - offsetDist, anchorOffset.y + offsetDist),
            Offset(anchorOffset.x + offsetDist, anchorOffset.y + offsetDist),
          ).forEach { c ->
            val clamped = Offset(c.x.coerceIn(loopR + 4f, size.width - loopR - 4f), c.y.coerceIn(loopR + 4f, size.height - loopR - 4f))
            drawCircle(roadColors.schematicRoadCasing, radius = loopR, center = clamped, style = Stroke(width = roadWidth * 0.52f))
            drawCircle(roadColors.schematicRoadFill, radius = loopR, center = clamped, style = Stroke(width = roadWidth * 0.38f))
          }
        }

        FeatureType.DIAMOND -> {
          // Grade-separated crossing road + 4 diagonal straight diamond ramps
          drawPolyline(crossPts, roadColors.schematicRoadCasing, casingWidth * 0.90f)
          drawPolyline(crossPts, roadColors.schematicRoadFill, roadWidth * 0.86f)
          drawPolyline(crossPts, Color.White.copy(alpha = 0.8f), 1.8.dp.toPx(), dashEffect)
          val dx = (size.width * 0.28f).coerceAtLeast(roadWidth * 1.9f)
          val dy = (size.height * 0.27f).coerceAtLeast(roadWidth * 1.9f)
          val topPt = Offset(anchorOffset.x, (anchorOffset.y - dy).coerceAtLeast(8.dp.toPx()))
          val botPt = Offset(anchorOffset.x, (anchorOffset.y + dy).coerceAtMost(size.height - 8.dp.toPx()))
          val leftPt = Offset((anchorOffset.x - dx).coerceAtLeast(8.dp.toPx()), anchorOffset.y)
          val rightPt = Offset((anchorOffset.x + dx).coerceAtMost(size.width - 8.dp.toPx()), anchorOffset.y)
          listOf(topPt to leftPt, topPt to rightPt, botPt to leftPt, botPt to rightPt).forEach { (s, e) ->
            drawLine(roadColors.schematicRoadCasing, s, e, strokeWidth = roadWidth * 0.52f, cap = StrokeCap.Round)
            drawLine(roadColors.schematicRoadFill, s, e, strokeWidth = roadWidth * 0.38f, cap = StrokeCap.Round)
          }
        }

        FeatureType.TRUMPET -> {
          // T-interchange crossing road ending on left + 1 loop ramp + sweeping outer ramp
          val rightEnd = Offset((anchorOffset.x + size.width * 0.34f).coerceAtMost(size.width - 8.dp.toPx()), anchorOffset.y)
          drawLine(roadColors.schematicRoadCasing, anchorOffset, rightEnd, strokeWidth = casingWidth * 0.90f, cap = StrokeCap.Round)
          drawLine(roadColors.schematicRoadFill, anchorOffset, rightEnd, strokeWidth = roadWidth * 0.86f, cap = StrokeCap.Round)
          drawLine(Color.White.copy(alpha = 0.8f), anchorOffset, rightEnd, strokeWidth = 1.8.dp.toPx(), pathEffect = dashEffect)
          val loopR = (size.minDimension * 0.14f).coerceIn(10.dp.toPx(), 20.dp.toPx())
          val loopCenter = Offset(
            (anchorOffset.x + loopR * 1.2f).coerceAtMost(size.width - loopR - 6f),
            (anchorOffset.y - loopR * 1.2f).coerceAtLeast(loopR + 6f),
          )
          drawCircle(roadColors.schematicRoadCasing, radius = loopR, center = loopCenter, style = Stroke(width = roadWidth * 0.50f))
          drawCircle(roadColors.schematicRoadFill, radius = loopR, center = loopCenter, style = Stroke(width = roadWidth * 0.36f))
          val outerStart = Offset(anchorOffset.x, (anchorOffset.y + roadWidth * 1.9f).coerceAtMost(size.height - 8.dp.toPx()))
          drawLine(roadColors.schematicRoadCasing, outerStart, rightEnd, strokeWidth = roadWidth * 0.50f, cap = StrokeCap.Round)
          drawLine(roadColors.schematicRoadFill, outerStart, rightEnd, strokeWidth = roadWidth * 0.36f, cap = StrokeCap.Round)
        }

        FeatureType.INTERCHANGE, FeatureType.DIRECTIONAL -> {
          drawPolyline(crossPts, roadColors.schematicRoadCasing, casingWidth * 0.88f)
          drawPolyline(crossPts, roadColors.schematicRoadFill, roadWidth * 0.84f)
          drawPolyline(crossPts, Color.White.copy(alpha = 0.8f), 1.8.dp.toPx(), dashEffect)
          if (feature.branches.isEmpty()) {
            val sx = roadWidth * 1.7f
            val sy = roadWidth * 1.4f
            listOf(
              Offset(anchorOffset.x, anchorOffset.y + sy) to Offset(anchorOffset.x + sx, anchorOffset.y),
              Offset(anchorOffset.x, anchorOffset.y - sy) to Offset(anchorOffset.x - sx, anchorOffset.y),
            ).forEach { (s, e) ->
              drawLine(roadColors.schematicRoadCasing, s, e, strokeWidth = roadWidth * 0.52f, cap = StrokeCap.Round)
              drawLine(roadColors.schematicRoadFill, s, e, strokeWidth = roadWidth * 0.38f, cap = StrokeCap.Round)
            }
          }
        }

        FeatureType.CUL_DE_SAC -> {
          val r = roadWidth * 0.92f
          drawCircle(color = roadColors.schematicRoadCasing, radius = r + 2.5.dp.toPx(), center = anchorOffset)
          drawCircle(color = roadColors.schematicRoadFill, radius = r, center = anchorOffset)
          drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = r * 0.38f,
            center = anchorOffset,
            style = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect),
          )
        }

        FeatureType.ROAD_WORKS -> {
          val halfW = roadWidth * 0.78f
          val halfH = roadWidth * 0.45f
          val rectTopLeft = Offset(anchorOffset.x - halfW, anchorOffset.y - halfH)
          val rectSize = androidx.compose.ui.geometry.Size(halfW * 2f, halfH * 2f)
          drawRect(color = Color(0xFFD97706), topLeft = rectTopLeft, size = rectSize)
          for (i in -2..2) {
            val sx = anchorOffset.x + i * (halfW * 0.42f)
            drawLine(
              color = Color(0xFFDC2626),
              start = Offset((sx - halfH * 0.6f).coerceIn(rectTopLeft.x, rectTopLeft.x + rectSize.width), anchorOffset.y + halfH),
              end = Offset((sx + halfH * 0.6f).coerceIn(rectTopLeft.x, rectTopLeft.x + rectSize.width), anchorOffset.y - halfH),
              strokeWidth = 3.dp.toPx(),
            )
          }
          drawRect(
            color = Color(0xFFDC2626),
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = 2.dp.toPx()),
          )
        }

        FeatureType.TOLL_PLAZA, FeatureType.CHECKPOINT, FeatureType.BARRIER -> {
          val halfSpan = roadWidth * 0.95f
          val barColor = if (displayType == FeatureType.TOLL_PLAZA) Color(0xFFF59E0B) else Color(0xFFDC2626)
          drawLine(
            color = Color.White,
            start = Offset(anchorOffset.x - halfSpan, anchorOffset.y),
            end = Offset(anchorOffset.x + halfSpan, anchorOffset.y),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round,
          )
          drawLine(
            color = barColor,
            start = Offset(anchorOffset.x - halfSpan, anchorOffset.y),
            end = Offset(anchorOffset.x + halfSpan, anchorOffset.y),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
          )
          if (displayType == FeatureType.TOLL_PLAZA || displayType == FeatureType.CHECKPOINT) {
            drawCircle(color = barColor, radius = 4.5.dp.toPx(), center = Offset(anchorOffset.x - halfSpan, anchorOffset.y))
            drawCircle(color = barColor, radius = 4.5.dp.toPx(), center = Offset(anchorOffset.x + halfSpan, anchorOffset.y))
          }
        }

        FeatureType.ON_RAMP, FeatureType.OFF_RAMP, FeatureType.MERGE -> {
          if (feature.branches.isEmpty()) {
            val stubEnd =
              when (displayType) {
                FeatureType.ON_RAMP -> Offset(anchorOffset.x + roadWidth * 1.6f, anchorOffset.y + roadWidth * 1.4f)
                else -> Offset(anchorOffset.x + roadWidth * 1.6f, anchorOffset.y - roadWidth * 1.4f)
              }
            drawLine(roadColors.schematicRoadCasing, anchorOffset, stubEnd, strokeWidth = casingWidth * 0.85f, cap = StrokeCap.Round)
            drawLine(roadColors.schematicRoadFill, anchorOffset, stubEnd, strokeWidth = roadWidth * 0.85f, cap = StrokeCap.Round)
          }
        }

        FeatureType.SHARP_CURVE, FeatureType.HAIRPIN, FeatureType.WINDING_ROAD -> {
          val curvePts = outgoingForScale.map(::toCanvas)
          if (curvePts.size >= 2) {
            drawPolyline(curvePts, pinColor, 4.dp.toPx())
            val pPrev = curvePts[curvePts.size - 2]
            val pTip = curvePts.last()
            val angle = atan2((pTip.y - pPrev.y).toDouble(), (pTip.x - pPrev.x).toDouble())
            val arrowLen = 9.dp.toPx()
            val wing = 0.55
            val leftPt = Offset(
              pTip.x - arrowLen * cos(angle - wing).toFloat(),
              pTip.y - arrowLen * sin(angle - wing).toFloat(),
            )
            val rightPt = Offset(
              pTip.x - arrowLen * cos(angle + wing).toFloat(),
              pTip.y - arrowLen * sin(angle + wing).toFloat(),
            )
            val arrowPath = Path().apply {
              moveTo(pTip.x, pTip.y)
              lineTo(leftPt.x, leftPt.y)
              lineTo(rightPt.x, rightPt.y)
              close()
            }
            drawPath(arrowPath, color = pinColor)
          }
          val curveLabel =
            when {
              displayType == FeatureType.WINDING_ROAD && feature.windingCurveCount != null ->
                "Winding · ${feature.windingCurveCount} curves"
              feature.curveAngleDeg != null && feature.curveRadiusMeters != null -> {
                val dirWord =
                  when (feature.curveDirection) {
                    RelativeDirection.LEFT -> "Left"
                    RelativeDirection.RIGHT -> "Right"
                    else -> "Curve"
                  }
                "$dirWord ${feature.curveAngleDeg.toInt()}° · R ${feature.curveRadiusMeters.toInt()} m"
              }
              else -> null
            }
          if (curveLabel != null) {
            drawContext.canvas.nativeCanvas.apply {
              val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 9.5.dp.toPx()
                typeface = Typeface.DEFAULT_BOLD
              }
              val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(185, 15, 23, 42)
              }
              val tw = textPaint.measureText(curveLabel)
              val padH = 6.dp.toPx()
              val cx = size.width * 0.5f
              val cy = 14.dp.toPx()
              drawRoundRect(
                cx - tw * 0.5f - padH,
                cy - 9.dp.toPx(),
                cx + tw * 0.5f + padH,
                cy + 5.dp.toPx(),
                7.dp.toPx(),
                7.dp.toPx(),
                bgPaint,
              )
              drawText(curveLabel, cx, cy, textPaint)
            }
          }
        }

        else -> Unit
      }

      if (feature.isCrosswalk || displayType == FeatureType.CROSSWALK) {
        val stripeCount = 5
        val stripeW = roadWidth * 0.14f
        val halfSpan = roadWidth * 0.55f
        for (i in 0 until stripeCount) {
          val offsetX = -halfSpan + (i * (halfSpan * 2f) / (stripeCount - 1))
          drawLine(
            color = Color.White.copy(alpha = 0.95f),
            start = Offset(anchorOffset.x + offsetX, anchorOffset.y - roadWidth * 0.42f),
            end = Offset(anchorOffset.x + offsetX, anchorOffset.y + roadWidth * 0.42f),
            strokeWidth = stripeW,
          )
        }
      }

      if (feature.isBridge || feature.isFootbridge) {
        val span = roadWidth * 0.78f
        drawLine(
          color = Color(0xFFD97706),
          start = Offset(anchorOffset.x - span, anchorOffset.y - roadWidth * 0.85f),
          end = Offset(anchorOffset.x - span, anchorOffset.y + roadWidth * 0.85f),
          strokeWidth = 3.5.dp.toPx(),
          cap = StrokeCap.Round,
        )
        drawLine(
          color = Color(0xFFD97706),
          start = Offset(anchorOffset.x + span, anchorOffset.y - roadWidth * 0.85f),
          end = Offset(anchorOffset.x + span, anchorOffset.y + roadWidth * 0.85f),
          strokeWidth = 3.5.dp.toPx(),
          cap = StrokeCap.Round,
        )
      }

      if (feature.isTunnel) {
        drawCircle(
          color = Color(0xFF0F172A).copy(alpha = 0.6f),
          radius = roadWidth * 0.82f,
          center = anchorOffset,
          style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        )
      }

      val placedBadgeCenters = mutableListOf<Offset>()
      val minBadgeDistPx = 18.dp.toPx()
      val badgeMargin = 14.dp.toPx()
      schematicBranches.forEach { branch ->
        if (branch.localPoints.size >= 2) {
          val pts = branch.localPoints.map(::toCanvas)
          drawPolyline(pts, Color.White.copy(alpha = 0.8f), 1.8.dp.toPx(), dashEffect)
          val accent =
            when (branch.status) {
              BranchStatus.OPEN -> pinColor
              BranchStatus.UNVERIFIED -> roadColors.unverifiedText
              BranchStatus.BLOCKED -> roadColors.blockedText
            }
          drawPolyline(pts.take(2), accent, 3.8.dp.toPx())

          val tip = pts.last()
          var badgePos =
            Offset(
              x = tip.x.coerceIn(badgeMargin, size.width - badgeMargin),
              y = tip.y.coerceIn(badgeMargin, size.height - badgeMargin),
            )
          var attempts = 0
          while (attempts < 8 && placedBadgeCenters.any { hypot(it.x - badgePos.x, it.y - badgePos.y) < minBadgeDistPx }) {
            val stepAngle = attempts * 0.85
            val stepR = minBadgeDistPx * (1f + attempts / 4)
            badgePos =
              Offset(
                x = (badgePos.x + stepR * cos(stepAngle).toFloat()).coerceIn(badgeMargin, size.width - badgeMargin),
                y = (badgePos.y + stepR * sin(stepAngle).toFloat()).coerceIn(badgeMargin, size.height - badgeMargin),
              )
            attempts++
          }
          placedBadgeCenters += badgePos
          drawBadge(badgePos, branch.badgeId, accent)
        }
      }

      if (schematicBranches.isEmpty()) {
        drawBadge(
          Offset(anchorOffset.x, anchorOffset.y.coerceIn(16.dp.toPx(), size.height - 20.dp.toPx())),
          "${feature.index}",
          pinColor,
        )
      }

      // 4) Draw user marker at bottom of incoming road
      drawUserMarker(userStart)
    }
  }
}

private fun DrawScope.drawOffRoadSchematic(
  offRoadState: OffRoadState,
  roadFill: Color,
  roadCasing: Color,
) {
  val center = Offset(size.width * 0.5f, size.height * 0.5f)
  val candidates = offRoadState.candidates
  if (candidates.isEmpty()) {
    // Subtle off-road dashed ring + user marker centered
    drawCircle(
      color = roadCasing.copy(alpha = 0.45f),
      radius = (size.minDimension * 0.34f).coerceAtLeast(18f),
      center = center,
      style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
    )
    drawUserMarker(center)
    return
  }

  val maxExtent =
    candidates
      .flatMap { it.stubLocalPoints }
      .maxOfOrNull { max(abs(it.xMeters), abs(it.yMeters)) }
      ?.coerceAtLeast(25.0)
      ?: 45.0

  val pad = 16.dp.toPx()
  val halfAvail = ((size.minDimension * 0.5f) - pad).coerceAtLeast(18f)
  val pxPerMeter = halfAvail / maxExtent.toFloat()

  fun toCanvasCentered(pt: LocalPoint): Offset =
    Offset(
      x = center.x + (pt.xMeters.toFloat() * pxPerMeter),
      y = center.y - (pt.yMeters.toFloat() * pxPerMeter),
    )

  val stubWidth = (size.minDimension * 0.13f).coerceIn(9f, 16f)
  val casingWidth = stubWidth + 4.dp.toPx()

  // Draw short stub for each surrounding R1..R3 candidate (never draw a line from user to the road)
  candidates.forEachIndexed { idx, cand ->
    val pts = cand.stubLocalPoints.map(::toCanvasCentered)
    if (pts.size >= 2) {
      drawPolyline(pts, roadCasing, casingWidth)
      drawPolyline(pts, roadFill, stubWidth)
      val mid = pts[pts.size / 2]
      val clamped =
        Offset(
          x = mid.x.coerceIn(14.dp.toPx(), size.width - 14.dp.toPx()),
          y = mid.y.coerceIn(14.dp.toPx(), size.height - 14.dp.toPx()),
        )
      drawBadge(clamped, cand.badgeId, RoadColors.pinColorForIndex(idx + 1))
    }
  }

  drawUserMarker(center)
}

private fun DrawScope.drawEmptyCorridorSchematic(
  roadFill: Color,
  roadCasing: Color,
  isWalking: Boolean,
  dividedSideSign: Float? = null,
) {
  val width = if (isWalking) (size.minDimension * 0.13f).coerceIn(10f, 18f) else (size.minDimension * 0.18f).coerceIn(14f, 26f)
  val cx =
    if (dividedSideSign != null) size.width * 0.5f - dividedSideSign * width * 0.72f
    else size.width * 0.5f
  if (dividedSideSign != null) {
    val px = (cx + dividedSideSign * width * 1.44f).coerceIn(10.dp.toPx(), size.width - 10.dp.toPx())
    drawLine(
      color = roadCasing,
      start = Offset(px, size.height * 0.90f),
      end = Offset(px, size.height * 0.10f),
      strokeWidth = width * 0.85f + 5.dp.toPx(),
      cap = StrokeCap.Round,
    )
    drawLine(
      color = roadFill,
      start = Offset(px, size.height * 0.90f),
      end = Offset(px, size.height * 0.10f),
      strokeWidth = width * 0.85f,
      cap = StrokeCap.Round,
    )
    drawLine(
      color = Color.White.copy(alpha = 0.75f),
      start = Offset(px, size.height * 0.88f),
      end = Offset(px, size.height * 0.12f),
      strokeWidth = 1.8.dp.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )
    val mx = (cx + px) * 0.5f
    drawLine(
      color = Color(0xFFF59E0B).copy(alpha = 0.85f),
      start = Offset(mx, size.height * 0.88f),
      end = Offset(mx, size.height * 0.12f),
      strokeWidth = 2.dp.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
    )
  }
  drawLine(
    color = roadCasing,
    start = Offset(cx, size.height * 0.90f),
    end = Offset(cx, size.height * 0.10f),
    strokeWidth = width + 5.dp.toPx(),
    cap = StrokeCap.Round,
  )
  drawLine(
    color = roadFill,
    start = Offset(cx, size.height * 0.90f),
    end = Offset(cx, size.height * 0.10f),
    strokeWidth = width,
    cap = StrokeCap.Round,
  )
  drawLine(
    color = Color.White.copy(alpha = 0.85f),
    start = Offset(cx, size.height * 0.88f),
    end = Offset(cx, size.height * 0.12f),
    strokeWidth = 2.dp.toPx(),
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
  )
  drawUserMarker(Offset(cx, size.height * 0.84f))
}

private fun DrawScope.drawPolyline(
  pts: List<Offset>,
  color: Color,
  width: Float,
  pathEffect: PathEffect? = null,
) {
  if (pts.size < 2) return
  val path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
      lineTo(pts[i].x, pts[i].y)
    }
  }
  drawPath(
    path = path,
    color = color,
    style =
      Stroke(
        width = width,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = pathEffect,
      ),
  )
}

private fun DrawScope.drawBadge(center: Offset, text: String, bgColor: Color) {
  val r = 10.5.dp.toPx()
  drawCircle(color = Color.White, radius = r + 1.5.dp.toPx(), center = center)
  drawCircle(color = bgColor, radius = r, center = center)
  drawContext.canvas.nativeCanvas.apply {
    val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 9.5.dp.toPx()
        typeface = Typeface.DEFAULT_BOLD
      }
    val yOffset = -(paint.descent() + paint.ascent()) / 2f
    drawText(text, center.x, center.y + yOffset, paint)
  }
}

private fun DrawScope.drawUserMarker(center: Offset) {
  drawCircle(
    color = RoadColors.Primary.copy(alpha = 0.24f),
    radius = 13.dp.toPx(),
    center = center,
  )
  drawCircle(
    color = Color.White,
    radius = 7.5.dp.toPx(),
    center = center,
  )
  val arrow =
    Path().apply {
      moveTo(center.x, center.y - 5.dp.toPx())
      lineTo(center.x + 4.dp.toPx(), center.y + 3.5.dp.toPx())
      lineTo(center.x, center.y + 1.5.dp.toPx())
      lineTo(center.x - 4.dp.toPx(), center.y + 3.5.dp.toPx())
      close()
    }
  drawPath(path = arrow, color = RoadColors.Primary)
}
