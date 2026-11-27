package com.example.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

data class GlassRenderConfig(
  val isDark: Boolean = true,
  val isMapDark: Boolean = true,
  val reduceTransparency: Boolean = false,
  val useRealBlur: Boolean = false,
  val hazeState: HazeState? = null,
  val lowQuality: Boolean = false,
)

val LocalGlassConfig = compositionLocalOf { GlassRenderConfig() }

object GlassTokens {
  val InfoCardRadius = 28.dp

  // Light translucent surfaces (#EEF2F8 @ 92% with subtle cool top highlight gradient)
  val LightFillTop = Color(0xF2F7FAFE) // #EEF2F8 + subtle top highlight wash
  val LightFillBottom = Color(0xEBEEF2F8) // #EEF2F8 @ 92%
  val LightCardFillTop = Color(0xF2F7FAFE)
  val LightCardFillBottom = Color(0xEBEEF2F8)
  // Branch / inner cards inside panel: #FFFFFF at 85% with 1dp #D5DCE8 border
  val LightInnerFillTop = Color(0xD9FFFFFF) // #FFFFFF @ 85%
  val LightInnerFillBottom = Color(0xD9FFFFFF)
  val LightInnerBorder = Color(0xFFD5DCE8)
  val LightOuterBorder = Color(0x1F0B1220) // #0B1220 @ 12%
  val LightInnerHighlightBorder = Color(0xE6FFFFFF) // white @ 90%
  val LightSolidSurface = Color(0xFFEEF2F8)
  val LightSolidInner = Color(0xFFFFFFFF)
  val LightSolidBorder = Color(0xFFD5DCE8)

  // Light dual shadows (0 12 32 black 28% + 0 2 6 black 16%)
  val LightSoftShadowColor = Color(0x47000000) // 28% black
  val LightTightShadowColor = Color(0x29000000) // 16% black

  // Dark translucent gradients (#121821 at 78–88%, unchanged)
  val DarkFillTop = Color(0xDB121821) // 86%
  val DarkFillBottom = Color(0xC7121821) // 78%
  val DarkCardFillTop = Color(0xE0121821) // 88%
  val DarkCardFillBottom = Color(0xD60E141C) // 84%
  val DarkInnerFillTop = Color(0xB31E2736) // 70%
  val DarkInnerFillBottom = Color(0x99161E2B) // 60%
  val DarkBorderTopLeft = Color(0x59FFFFFF) // 35%
  val DarkBorderBottomRight = Color(0x14FFFFFF) // 8%
  val DarkTopInnerHighlight = Color(0x47FFFFFF)
  val DarkSolidSurface = Color(0xFF121821)
  val DarkSolidInner = Color(0xFF1C2533)
  val DarkSolidBorder = Color(0xFF334155)

  // Dark translucent gradients over light map (>= 88% opacity for crisp readability)
  val DarkOverLightFillTop = Color(0xE8121821) // 91%
  val DarkOverLightFillBottom = Color(0xE30E141C) // 89%
  val DarkOverLightCardFillTop = Color(0xEB121821) // 92%
  val DarkOverLightCardFillBottom = Color(0xE30E141C) // 89%
  val DarkOverLightInnerFillTop = Color(0xC71E2736) // 78%
  val DarkOverLightInnerFillBottom = Color(0xB8161E2B) // 72%
  val DarkOverLightSoftShadowColor = Color(0x3D000000) // ~24% black
  val DarkOverLightTightShadowColor = Color(0x24000000) // ~14% black

  val SoftShadowColor = Color(0x24000000) // ~14% black
  val TightShadowColor = Color(0x1A000000) // ~10% black
}

fun isSystemReduceTransparencyEnabled(context: Context): Boolean =
  runCatching {
    val cr = context.contentResolver
    Settings.Secure.getInt(cr, "reduce_transparency", 0) == 1 ||
      Settings.Global.getInt(cr, "accessibility_reduce_transparency", 0) == 1
  }.getOrDefault(false)

/**
 * Purely visual Liquid Glass container with translucent gradient fill, outer/inner specular borders,
 * soft + tight shadows, optional Haze backdrop blur, and solid opaque fallback.
 * Never handles pointer events directly.
 */
@Composable
fun GlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(GlassTokens.InfoCardRadius),
  highOpacity: Boolean = false,
  isLighterInner: Boolean = false,
  withShadow: Boolean = !isLighterInner,
  tintOverlay: Color = Color.Transparent,
  content: @Composable BoxScope.() -> Unit,
) {
  val config = LocalGlassConfig.current
  val isDark = config.isDark
  val opaque = config.reduceTransparency
  val lowQuality = config.lowQuality

  val fillBrush =
    when {
      opaque || lowQuality -> {
        val solid =
          if (opaque) {
            if (isDark) {
              if (isLighterInner) GlassTokens.DarkSolidInner else GlassTokens.DarkSolidSurface
            } else {
              if (isLighterInner) GlassTokens.LightSolidInner else GlassTokens.LightSolidSurface
            }
          } else {
            // Flat translucent color without gradient allocations when in low quality mode
            if (isDark) {
              when {
                isLighterInner -> GlassTokens.DarkInnerFillTop
                highOpacity -> GlassTokens.DarkCardFillTop
                else -> GlassTokens.DarkFillTop
              }
            } else {
              when {
                isLighterInner -> GlassTokens.LightInnerFillTop
                highOpacity -> GlassTokens.LightCardFillTop
                else -> GlassTokens.LightFillTop
              }
            }
          }
        Brush.verticalGradient(listOf(solid, solid))
      }
      isDark && !config.isMapDark -> {
        val (top, bot) =
          when {
            isLighterInner -> GlassTokens.DarkOverLightInnerFillTop to GlassTokens.DarkOverLightInnerFillBottom
            highOpacity -> GlassTokens.DarkOverLightCardFillTop to GlassTokens.DarkOverLightCardFillBottom
            else -> GlassTokens.DarkOverLightFillTop to GlassTokens.DarkOverLightFillBottom
          }
        Brush.verticalGradient(listOf(top, bot))
      }
      isDark -> {
        val (top, bot) =
          when {
            isLighterInner -> GlassTokens.DarkInnerFillTop to GlassTokens.DarkInnerFillBottom
            highOpacity -> GlassTokens.DarkCardFillTop to GlassTokens.DarkCardFillBottom
            else -> GlassTokens.DarkFillTop to GlassTokens.DarkFillBottom
          }
        Brush.verticalGradient(listOf(top, bot))
      }
      else -> {
        val (top, bot) =
          when {
            isLighterInner -> GlassTokens.LightInnerFillTop to GlassTokens.LightInnerFillBottom
            highOpacity -> GlassTokens.LightCardFillTop to GlassTokens.LightCardFillBottom
            else -> GlassTokens.LightFillTop to GlassTokens.LightFillBottom
          }
        Brush.verticalGradient(listOf(top, bot))
      }
    }

  val borderBrush =
    when {
      opaque || lowQuality -> {
        val bColor =
          if (isDark) GlassTokens.DarkSolidBorder
          else if (isLighterInner) GlassTokens.LightInnerBorder
          else GlassTokens.LightSolidBorder
        Brush.linearGradient(listOf(bColor, bColor))
      }
      isDark -> Brush.linearGradient(listOf(GlassTokens.DarkBorderTopLeft, GlassTokens.DarkBorderBottomRight))
      isLighterInner -> Brush.linearGradient(listOf(GlassTokens.LightInnerBorder, GlassTokens.LightInnerBorder))
      else -> Brush.linearGradient(listOf(GlassTokens.LightOuterBorder, GlassTokens.LightOuterBorder))
    }

  val topHighlightColor =
    when {
      opaque || lowQuality -> Color.Transparent
      isDark -> GlassTokens.DarkTopInnerHighlight
      isLighterInner -> Color.Transparent
      else -> GlassTokens.LightInnerHighlightBorder
    }

  val hazeModifier =
    if (Build.VERSION.SDK_INT >= 31 && !opaque && !lowQuality && config.useRealBlur && config.hazeState != null && !isLighterInner) {
      Modifier.hazeEffect(
        state = config.hazeState,
        style =
          HazeStyle(
            backgroundColor = if (isDark) GlassTokens.DarkSolidSurface else GlassTokens.LightSolidSurface,
            tint =
              HazeTint(
                color =
                  when {
                    isDark && !config.isMapDark -> Color(0xFF121821).copy(alpha = if (highOpacity) 0.72f else 0.64f)
                    isDark -> Color(0xFF121821).copy(alpha = if (highOpacity) 0.56f else 0.42f)
                    else -> Color.White.copy(alpha = if (highOpacity) 0.72f else 0.58f)
                  },
              ),
            blurRadius = 22.dp,
            noiseFactor = 0.04f,
          ),
      )
    } else {
      Modifier
    }

  val shadowMod =
    if (withShadow && !lowQuality) {
      if (isDark) {
        val softShadow =
          if (!config.isMapDark) GlassTokens.DarkOverLightSoftShadowColor else GlassTokens.SoftShadowColor
        val tightShadow =
          if (!config.isMapDark) GlassTokens.DarkOverLightTightShadowColor else GlassTokens.TightShadowColor
        Modifier
          .shadow(
            elevation = if (!config.isMapDark) 18.dp else 16.dp,
            shape = shape,
            ambientColor = softShadow,
            spotColor = softShadow,
          )
          .shadow(
            elevation = if (!config.isMapDark) 3.dp else 2.dp,
            shape = shape,
            ambientColor = tightShadow,
            spotColor = tightShadow,
          )
      } else {
        Modifier
          .shadow(
            elevation = 32.dp,
            shape = shape,
            ambientColor = GlassTokens.LightSoftShadowColor,
            spotColor = GlassTokens.LightSoftShadowColor,
          )
          .shadow(
            elevation = 6.dp,
            shape = shape,
            ambientColor = GlassTokens.LightTightShadowColor,
            spotColor = GlassTokens.LightTightShadowColor,
          )
      }
    } else {
      Modifier
    }

  Box(
    modifier =
      modifier
        .then(shadowMod)
        .clip(shape)
        .then(hazeModifier)
        .background(fillBrush, shape)
        .background(tintOverlay, shape)
        .border(width = 1.dp, brush = borderBrush, shape = shape)
        .drawWithContent {
          drawContent()
          if (topHighlightColor != Color.Transparent && size.width > 12f && size.height > 12f) {
            if (!isDark) {
              val insetPx = 1.dp.toPx()
              val innerSize =
                Size(
                  (size.width - insetPx * 2f).coerceAtLeast(1f),
                  (size.height - insetPx * 2f).coerceAtLeast(1f),
                )
              val outline = shape.createOutline(innerSize, layoutDirection, this)
              translate(insetPx, insetPx) {
                drawOutline(
                  outline = outline,
                  color = topHighlightColor,
                  style = Stroke(width = insetPx),
                )
              }
            } else {
              drawLine(
                brush =
                  Brush.horizontalGradient(
                    listOf(
                      Color.Transparent,
                      topHighlightColor,
                      topHighlightColor,
                      Color.Transparent,
                    ),
                  ),
                start = Offset(14f, 1.2.dp.toPx()),
                end = Offset(size.width - 14f, 1.2.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
              )
            }
          }
        },
    content = content,
  )
}
