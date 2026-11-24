package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.BilingualName
import com.example.data.model.FeatureType
import com.example.data.model.UnitSystem
import com.example.domain.GeoMath
import com.example.ui.theme.NotoArabicFontFamily
import com.example.ui.theme.RobotoFontFamily
import kotlin.math.floor

/**
 * Wraps alphanumeric route refs like "M12" with Unicode LRI/PDI isolates so they stay readable
 * inside RTL Arabic text.
 */
fun isolateRefsForBidi(text: String): String {
  return text.replace(Regex("\\b[A-Za-z]{1,4}\\d+[A-Za-z0-9-]*\\b")) { match ->
    "\u2066${match.value}\u2069"
  }
}

/**
 * Auto-shrinks text from [preferredFontSize] down to [minFontSize] to fit on 1 line if possible;
 * if it still doesn't fit at [minFontSize], wraps at [minFontSize] up to [maxLines] using normal
 * word wrapping (never ellipsis, never character-by-character wrapping).
 */
@Composable
fun AutoShrinkSingleLineText(
  text: String,
  preferredFontSize: TextUnit,
  minFontSize: TextUnit,
  fontWeight: FontWeight,
  color: Color,
  fontFamily: FontFamily = RobotoFontFamily,
  maxLines: Int = 2,
  textAlign: TextAlign = TextAlign.Start,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current

  BoxWithConstraints(modifier = modifier) {
    val maxWidthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
    val chosenSize =
      remember(text, maxWidthPx, preferredFontSize, minFontSize, fontWeight, fontFamily) {
        val maxSp = preferredFontSize.value
        val minSp = minFontSize.value.coerceAtMost(maxSp)
        var sizeSp = maxSp
        var found = minSp
        while (sizeSp >= minSp - 0.01f) {
          val candidate = sizeSp.coerceAtLeast(minSp).sp
          val result =
            textMeasurer.measure(
              text = text,
              style =
                TextStyle(
                  fontFamily = fontFamily,
                  fontSize = candidate,
                  fontWeight = fontWeight,
                ),
              maxLines = 1,
              softWrap = false,
              constraints = Constraints(maxWidth = maxWidthPx),
            )
          if (!result.hasVisualOverflow && result.lineCount == 1) {
            found = sizeSp.coerceAtLeast(minSp)
            break
          }
          sizeSp -= 1f
        }
        found.sp
      }

    Text(
      text = text,
      fontFamily = fontFamily,
      fontSize = chosenSize,
      lineHeight = chosenSize * 1.15f,
      fontWeight = fontWeight,
      color = color,
      maxLines = maxLines,
      softWrap = true,
      overflow = TextOverflow.Clip,
      textAlign = textAlign,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Full-width header bilingual stack:
 * - Arabic (22sp bold -> auto-shrinks down to 16sp, then wraps max 2 lines)
 * - English under it (16sp -> auto-shrinks down to 13sp, then wraps max 2 lines)
 * - Only one language exists -> shows one line/script
 * - Neither exists -> shows [fallbackText] ("غير معروف / unknown" or "—")
 */
private fun fontFamilyForText(text: String): FontFamily =
  if (BilingualName.containsArabic(text)) NotoArabicFontFamily else FontFamily.SansSerif

private fun layoutDirectionForText(text: String): LayoutDirection =
  if (GeoMath.isRtlScript(text)) LayoutDirection.Rtl else LayoutDirection.Ltr

private fun resolveDisplayLines(name: BilingualName?): Pair<String?, String?> {
  if (name == null) return null to null
  val refSuffix = name.ref?.takeIf { it.isNotBlank() }?.let { " \u2066$it\u2069" }.orEmpty()
  val localPrimary = name.localPrimary?.takeIf { it.isNotBlank() }
  if (localPrimary != null) {
    val first =
      if (BilingualName.containsArabic(localPrimary)) {
        isolateRefsForBidi(localPrimary) + if (name.ref != null && !localPrimary.contains(name.ref)) refSuffix else ""
      } else {
        localPrimary + if (name.ref != null && !localPrimary.contains(name.ref)) " ${name.ref}" else ""
      }
    val sec = name.secondaryText?.takeIf { it.isNotBlank() && it != localPrimary }
    return first to sec
  }

  val arLine =
    name.arabic?.takeIf { it.isNotBlank() }?.let {
      isolateRefsForBidi(it) + if (!it.contains(name.ref ?: "\u0000")) refSuffix else ""
    }
  val enLine =
    name.english?.takeIf { it.isNotBlank() }?.let {
      it + if (name.ref != null && !it.contains(name.ref)) " ${name.ref}" else ""
    } ?: if (arLine == null) name.ref?.takeIf { it.isNotBlank() } else null
  return arLine to enLine
}

@Composable
fun AutoShrinkBilingualNameStack(
  name: BilingualName?,
  fallbackText: String,
  modifier: Modifier = Modifier,
  maxArabicFontSize: TextUnit = 22.sp,
  minArabicFontSize: TextUnit = 16.sp,
  maxEnglishFontSize: TextUnit = 16.sp,
  minEnglishFontSize: TextUnit = 13.sp,
  arabicWeight: FontWeight = FontWeight.Bold,
  englishWeight: FontWeight = FontWeight.Medium,
  primaryColor: Color = MaterialTheme.colorScheme.onSurface,
  secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  maxLinesPerScript: Int = 2,
) {
  val (primaryLine, secondaryLine) = resolveDisplayLines(name)

  Column(modifier = modifier.fillMaxWidth()) {
    if (primaryLine == null && secondaryLine == null) {
      AutoShrinkSingleLineText(
        text = fallbackText,
        preferredFontSize = maxArabicFontSize,
        minFontSize = minArabicFontSize,
        fontWeight = arabicWeight,
        color = primaryColor,
        fontFamily = fontFamilyForText(fallbackText),
        maxLines = maxLinesPerScript,
        modifier = Modifier.fillMaxWidth(),
      )
    } else {
      if (primaryLine != null) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionForText(primaryLine)) {
          AutoShrinkSingleLineText(
            text = primaryLine,
            preferredFontSize = maxArabicFontSize,
            minFontSize = minArabicFontSize,
            fontWeight = arabicWeight,
            color = primaryColor,
            fontFamily = fontFamilyForText(primaryLine),
            maxLines = maxLinesPerScript,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      if (secondaryLine != null) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionForText(secondaryLine)) {
          val isOnlyLine = primaryLine == null
          AutoShrinkSingleLineText(
            text = secondaryLine,
            preferredFontSize = if (isOnlyLine) maxArabicFontSize else maxEnglishFontSize,
            minFontSize = if (isOnlyLine) minArabicFontSize else minEnglishFontSize,
            fontWeight = if (isOnlyLine) arabicWeight else englishWeight,
            color = if (isOnlyLine) primaryColor else secondaryColor,
            fontFamily =
              if (BilingualName.containsArabic(secondaryLine)) NotoArabicFontFamily else RobotoFontFamily,
            maxLines = maxLinesPerScript,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

@Composable
fun BilingualNameStack(
  name: BilingualName,
  fallbackText: String,
  modifier: Modifier = Modifier,
  arabicFontSize: TextUnit = 22.sp,
  englishFontSize: TextUnit = 16.sp,
  arabicWeight: FontWeight = FontWeight.SemiBold,
  englishWeight: FontWeight = FontWeight.Medium,
  primaryColor: Color = MaterialTheme.colorScheme.onSurface,
  secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  centerAlign: Boolean = false,
  maxLinesPerScript: Int = 2,
) {
  val (primaryLine, secondaryLine) = resolveDisplayLines(name)

  Column(modifier = modifier) {
    if (primaryLine == null && secondaryLine == null) {
      Text(
        text = fallbackText,
        fontFamily = fontFamilyForText(fallbackText),
        fontSize = arabicFontSize,
        lineHeight = arabicFontSize * 1.18f,
        fontWeight = arabicWeight,
        color = primaryColor,
        maxLines = maxLinesPerScript,
        softWrap = true,
        overflow = TextOverflow.Clip,
        textAlign = if (centerAlign) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
      )
    } else {
      if (primaryLine != null) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionForText(primaryLine)) {
          Text(
            text = primaryLine,
            fontFamily = fontFamilyForText(primaryLine),
            fontSize = arabicFontSize,
            lineHeight = arabicFontSize * 1.18f,
            fontWeight = arabicWeight,
            color = primaryColor,
            maxLines = maxLinesPerScript,
            softWrap = true,
            overflow = TextOverflow.Clip,
            textAlign = if (centerAlign) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      if (secondaryLine != null) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionForText(secondaryLine)) {
          Text(
            text = secondaryLine,
            fontFamily =
              if (BilingualName.containsArabic(secondaryLine)) NotoArabicFontFamily else RobotoFontFamily,
            fontSize = if (primaryLine != null) englishFontSize else arabicFontSize,
            lineHeight = (if (primaryLine != null) englishFontSize else arabicFontSize) * 1.18f,
            fontWeight = if (primaryLine != null) englishWeight else arabicWeight,
            color = if (primaryLine != null) secondaryColor else primaryColor,
            maxLines = maxLinesPerScript,
            softWrap = true,
            overflow = TextOverflow.Clip,
            textAlign = if (centerAlign) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

/**
 * Single-line inline bilingual name for compact rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineBilingualRoadName(
  name: BilingualName?,
  fallbackText: String,
  modifier: Modifier = Modifier,
  arabicFontSize: TextUnit = 20.sp,
  englishFontSize: TextUnit = 14.sp,
) {
  val (primaryLine, secondaryLine) = resolveDisplayLines(name)

  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.Center,
    maxLines = 2,
  ) {
    if (primaryLine == null && secondaryLine == null) {
      Text(
        text = fallbackText,
        fontFamily = fontFamilyForText(fallbackText),
        fontSize = arabicFontSize,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Clip,
      )
    } else {
      if (primaryLine != null) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionForText(primaryLine)) {
          Text(
            text = primaryLine,
            fontFamily = fontFamilyForText(primaryLine),
            fontSize = arabicFontSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
        }
      }
      if (secondaryLine != null) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirectionForText(secondaryLine)) {
          Text(
            text = secondaryLine,
            fontFamily =
              if (BilingualName.containsArabic(secondaryLine)) NotoArabicFontFamily else RobotoFontFamily,
            fontSize = if (primaryLine != null) englishFontSize else arabicFontSize,
            fontWeight = if (primaryLine != null) FontWeight.Medium else FontWeight.Bold,
            color =
              if (primaryLine != null) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
        }
      }
    }
  }
}

@Composable
fun featureTypeTitle(type: FeatureType): String =
  when (type) {
    FeatureType.FOUR_WAY -> stringResource(R.string.feature_four_way)
    FeatureType.MULTI_WAY -> stringResource(R.string.feature_multi_way)
    FeatureType.T_JUNCTION -> stringResource(R.string.feature_t_junction)
    FeatureType.Y_JUNCTION -> stringResource(R.string.feature_y_junction)
    FeatureType.ROUNDABOUT -> stringResource(R.string.feature_roundabout)
    FeatureType.MINI_ROUNDABOUT -> stringResource(R.string.feature_mini_roundabout)
    FeatureType.FORK -> stringResource(R.string.feature_fork)
    FeatureType.MERGE -> stringResource(R.string.feature_merge)
    FeatureType.ON_RAMP -> stringResource(R.string.feature_on_ramp)
    FeatureType.OFF_RAMP -> stringResource(R.string.feature_off_ramp)
    FeatureType.BRIDGE -> stringResource(R.string.feature_bridge)
    FeatureType.TUNNEL -> stringResource(R.string.feature_tunnel)
    FeatureType.OVERPASS_ABOVE -> stringResource(R.string.feature_overpass_above)
    FeatureType.UNDERPASS_BELOW -> stringResource(R.string.feature_underpass_below)
    FeatureType.U_TURN -> stringResource(R.string.feature_u_turn)
    FeatureType.DEAD_END -> stringResource(R.string.feature_dead_end)
    FeatureType.CUL_DE_SAC -> stringResource(R.string.feature_cul_de_sac)
    FeatureType.SERVICE_ROAD -> stringResource(R.string.feature_service_road)
    FeatureType.SHARP_CURVE -> stringResource(R.string.feature_sharp_curve)
    FeatureType.FOOTPATH -> stringResource(R.string.feature_footpath)
    FeatureType.CROSSWALK -> stringResource(R.string.feature_crosswalk)
    FeatureType.FOOTBRIDGE -> stringResource(R.string.feature_footbridge)
    FeatureType.TOLL_PLAZA -> stringResource(R.string.feature_toll_plaza)
    FeatureType.CHECKPOINT -> stringResource(R.string.feature_checkpoint)
    FeatureType.BARRIER -> stringResource(R.string.feature_barrier)
    FeatureType.ROAD_WORKS -> stringResource(R.string.feature_road_works)
    FeatureType.STAGGERED_JUNCTION -> stringResource(R.string.feature_staggered_junction)
    FeatureType.DIVIDED_ROAD -> stringResource(R.string.feature_divided_road)
    FeatureType.MEDIAN_OPENING -> stringResource(R.string.feature_median_opening)
    FeatureType.FRONTAGE_ROAD -> stringResource(R.string.feature_frontage_road)
    FeatureType.INTERCHANGE, FeatureType.DIRECTIONAL -> stringResource(R.string.feature_interchange)
    FeatureType.CLOVERLEAF -> stringResource(R.string.feature_cloverleaf)
    FeatureType.DIAMOND -> stringResource(R.string.feature_diamond)
    FeatureType.TRUMPET -> stringResource(R.string.feature_trumpet)
  }

@Composable
fun formatRoadDistance(meters: Double): String {
  val mFmt = stringResource(R.string.distance_meters_format)
  val kmFmt = stringResource(R.string.distance_km_format)
  return GeoMath.formatDistance(meters, mFmt, kmFmt)
}

@Composable
fun formatRoadDistance(meters: Double, unitSystem: UnitSystem): String {
  val mFmt = stringResource(R.string.distance_meters_format)
  val kmFmt = stringResource(R.string.distance_km_format)
  val ftFmt = stringResource(R.string.distance_feet_format)
  val miFmt = stringResource(R.string.distance_miles_format)
  return GeoMath.formatDistance(meters, unitSystem, mFmt, kmFmt, ftFmt, miFmt)
}
