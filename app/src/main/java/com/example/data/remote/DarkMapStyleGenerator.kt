package com.example.data.remote

import android.content.Context
import com.example.config.AppConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates a balanced dark-mode MapLibre style JSON directly from the keyless light vector style JSON
 * and caches both the source hash and generated JSON on disk so it only regenerates when the source changes.
 */
object DarkMapStyleGenerator {
  private const val CACHE_DIR = "map_styles"
  private const val DARK_JSON_FILE = "dark_style.json"
  private const val SOFT_LIGHT_JSON_FILE = "soft_light_style.json"
  private const val SOURCE_HASH_FILE = "light_style_hash.txt"
  private const val LIGHT_JSON_FILE = "light_style.json"

  // Version bump whenever color or road label transform rules change to invalidate stale disk caches safely.
  private const val TRANSFORM_VERSION = "v6"

  private val HEX_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
  private val RGB_REGEX = Regex("^rgba?\\(\\s*([^,]+)\\s*,\\s*([^,]+)\\s*,\\s*([^,)]+)(?:\\s*,\\s*([^)]+))?\\s*\\)$", RegexOption.IGNORE_CASE)
  private val HSL_REGEX = Regex("^hsla?\\(\\s*([^,]+)\\s*,\\s*([^,%]+)%\\s*,\\s*([^,%]+)%\\s*(?:,\\s*([^)]+))?\\s*\\)$", RegexOption.IGNORE_CASE)

  data class StyleLoadResult(
    val darkStyleJson: String?,
    val lightFallbackJson: String?,
    val diagnosticError: String?,
    val softLightStyleJson: String? = null,
  )

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
      .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
      .readTimeout(AppConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
      .addInterceptor(DebugNetworkSimulator.interceptor)
      .build()
  }

  private enum class LayerRole {
    DEFAULT,
    BACKGROUND,
    WATER,
    PARK,
    BUILDING,
    ROAD_CASING,
    ROAD_MOTORWAY_TRUNK,
    ROAD_PRIMARY,
    ROAD_MINOR_FILL,
    ROAD_FILL,
    SYMBOL_LABEL,
  }

  private data class HslColor(
    val h: Double, // [0, 360)
    val s: Double, // [0, 1]
    val l: Double, // [0, 1]
    val a: Double, // [0, 1]
  )

  suspend fun loadOrBuildDarkStyle(context: Context): StyleLoadResult =
    loadStyleForTheme(context, isDark = true)

  suspend fun loadStyleForTheme(context: Context, isDark: Boolean): StyleLoadResult =
    withContext(Dispatchers.IO) {
      val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
      val darkFile = File(dir, DARK_JSON_FILE)
      val softLightFile = File(dir, SOFT_LIGHT_JSON_FILE)
      val hashFile = File(dir, SOURCE_HASH_FILE)
      val lightFile = File(dir, LIGHT_JSON_FILE)

      val fetched = fetchLightStyleJson()
      fetched.fold(
        onSuccess = { rawLightJson ->
          val sourceHash = sha256("$TRANSFORM_VERSION:$rawLightJson")
          val cachedHash = runCatching { hashFile.takeIf { it.exists() }?.readText() }.getOrNull()
          val cachedLight = runCatching { lightFile.takeIf { it.exists() }?.readText() }.getOrNull()
          val cachedDark = runCatching { darkFile.takeIf { it.exists() }?.readText() }.getOrNull()
          val cachedSoftLight = runCatching { softLightFile.takeIf { it.exists() }?.readText() }.getOrNull()
          if (cachedHash == sourceHash && !cachedLight.isNullOrBlank() && !cachedDark.isNullOrBlank() && !cachedSoftLight.isNullOrBlank()) {
            return@withContext StyleLoadResult(
              darkStyleJson = if (isDark) cachedDark else null,
              lightFallbackJson = cachedLight,
              diagnosticError = null,
              softLightStyleJson = if (!isDark) cachedSoftLight else null,
            )
          }

          return@withContext runCatching {
            val enhancedLight = enhanceRoadLabelsInStyleJson(rawLightJson)
            val generatedDark = transformLightStyleToDarkJson(enhancedLight)
            val generatedSoftLight = transformLightStyleToSoftLightJson(enhancedLight)
            lightFile.writeText(enhancedLight)
            darkFile.writeText(generatedDark)
            softLightFile.writeText(generatedSoftLight)
            hashFile.writeText(sourceHash)
            StyleLoadResult(
              darkStyleJson = if (isDark) generatedDark else null,
              lightFallbackJson = enhancedLight,
              diagnosticError = null,
              softLightStyleJson = if (!isDark) generatedSoftLight else null,
            )
          }.getOrElse { err ->
            StyleLoadResult(
              darkStyleJson = null,
              lightFallbackJson = rawLightJson,
              diagnosticError = "Style transform failed: ${err.message ?: "unknown"}",
              softLightStyleJson = null,
            )
          }
        },
        onFailure = { err ->
          val cachedDark = runCatching { darkFile.takeIf { it.exists() }?.readText() }.getOrNull()
          val cachedSoftLight = runCatching { softLightFile.takeIf { it.exists() }?.readText() }.getOrNull()
          val cachedLight = runCatching { lightFile.takeIf { it.exists() }?.readText() }.getOrNull()
          val msg = "Style fetch failed (${err.message ?: "network error"})"
          val hasHit =
            if (isDark) !cachedDark.isNullOrBlank()
            else !cachedSoftLight.isNullOrBlank() || !cachedLight.isNullOrBlank()
          StyleLoadResult(
            darkStyleJson = if (isDark) cachedDark else null,
            lightFallbackJson = cachedLight,
            diagnosticError = if (hasHit) "$msg (using cached style)" else msg,
            softLightStyleJson = if (!isDark) cachedSoftLight else null,
          )
        },
      )
    }

  private fun fetchLightStyleJson(): Result<String> =
    runCatching {
      val req =
        Request.Builder()
          .url(AppConfig.MAP_STYLE_LIGHT_URL)
          .header("User-Agent", AppConfig.USER_AGENT)
          .get()
          .build()
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IOException("HTTP ${resp.code}")
        }
        val body = resp.body?.string()
        if (body.isNullOrBlank()) {
          throw IOException("Empty style body")
        }
        body
      }
    }

  /**
   * Enhances road-name symbol layers in any MapLibre GL style JSON:
   * - Raises road-name text-size by +15% at zoom >= 15
   * - Ensures text-halo-width >= 1.5
   * - Keeps both Arabic and Latin road names together
   */
  fun enhanceRoadLabelsInStyleJson(styleJsonStr: String): String {
    val root = JSONObject(styleJsonStr)
    val layers = root.optJSONArray("layers") ?: return root.toString()
    for (i in 0 until layers.length()) {
      val layer = layers.optJSONObject(i) ?: continue
      if (!isRoadLabelSymbolLayer(layer)) continue

      val layout = layer.optJSONObject("layout") ?: JSONObject().also { layer.put("layout", it) }
      val paint = layer.optJSONObject("paint") ?: JSONObject().also { layer.put("paint", it) }

      // +15% text-size at zoom >= 15
      val existingSize = layout.opt("text-size")
      layout.put("text-size", scaleTextSizeAtZoom15(existingSize))

      // Bilingual Arabic + Latin text-field when not a pure shield/number layer
      val idLower = layer.optString("id", "").lowercase(Locale.US)
      if (!idLower.contains("shield") && layout.has("text-field")) {
        layout.put("text-field", buildBilingualRoadNameExpression())
      }

      // Enforce 1.5 text-halo-width (add white halo color if missing in light style)
      val currentHaloWidth = paint.optDouble("text-halo-width", Double.NaN)
      if (currentHaloWidth.isNaN() || currentHaloWidth < 1.5) {
        paint.put("text-halo-width", 1.5)
      }
      if (!paint.has("text-halo-color")) {
        paint.put("text-halo-color", "rgba(255, 255, 255, 0.90)")
      }
    }
    return root.toString()
  }

  private fun isRoadLabelSymbolLayer(layer: JSONObject): Boolean {
    if (!layer.optString("type", "").equals("symbol", ignoreCase = true)) return false
    val srcLayer = layer.optString("source-layer", "").lowercase(Locale.US)
    val id = layer.optString("id", "").lowercase(Locale.US)
    return srcLayer.contains("transportation_name") ||
      (isRoadIdentifier(id, srcLayer) && (id.contains("label") || id.contains("name") || srcLayer.contains("road")))
  }

  private fun scaleTextSizeAtZoom15(existing: Any?): Any {
    val defaultLow = 12.0
    if (existing == null || existing == JSONObject.NULL) {
      return JSONArray(listOf("interpolate", JSONArray(listOf("linear")), JSONArray(listOf("zoom")), 14.99, defaultLow, 15, defaultLow * 1.15))
    }
    if (existing is Number) {
      val s = existing.toDouble()
      return JSONArray(listOf("interpolate", JSONArray(listOf("linear")), JSONArray(listOf("zoom")), 14.99, s, 15, s * 1.15))
    }
    if (existing is JSONArray && existing.length() >= 5 && existing.optString(0).equals("interpolate", ignoreCase = true)) {
      val out = JSONArray()
      out.put(existing.get(0))
      out.put(existing.get(1))
      out.put(existing.get(2))
      var inserted15 = false
      var prevZoom: Double? = null
      var prevVal: Any? = null
      var i = 3
      while (i + 1 < existing.length()) {
        val z = existing.optDouble(i, Double.NaN)
        val v = existing.get(i + 1)
        if (!z.isNaN()) {
          if (!inserted15 && z >= 15.0) {
            if (z > 15.0 && prevZoom != null && prevVal != null) {
              out.put(14.99)
              out.put(prevVal)
              out.put(15)
              out.put(multiplyNumericValue(prevVal, 1.15))
            } else if (z == 15.0) {
              if (prevZoom != null && prevVal != null && prevZoom < 14.99) {
                out.put(14.99)
                out.put(v)
              }
            }
            inserted15 = true
          }
          out.put(z)
          out.put(if (z >= 15.0) multiplyNumericValue(v, 1.15) else v)
          prevZoom = z
          prevVal = v
        } else {
          out.put(existing.get(i))
          out.put(v)
        }
        i += 2
      }
      if (!inserted15 && prevVal != null) {
        if ((prevZoom ?: 0.0) < 14.99) {
          out.put(14.99)
          out.put(prevVal)
        }
        out.put(15)
        out.put(multiplyNumericValue(prevVal, 1.15))
      }
      return out
    }
    return existing
  }

  private fun multiplyNumericValue(v: Any, factor: Double): Any =
    when (v) {
      is Number -> (v.toDouble() * factor * 100.0).roundToInt() / 100.0
      else -> JSONArray(listOf("*", v, factor))
    }

  private fun buildBilingualRoadNameExpression(): JSONArray {
    val lang = Locale.getDefault().language.lowercase(Locale.US).substringBefore('-').substringBefore('_')
    val langKey = if (lang.isNotBlank() && lang != "ar" && lang != "en" && lang != "latin") "name:$lang" else null
    val coalesceArgs = buildList<Any> {
      add("coalesce")
      if (langKey != null) add(JSONArray(listOf("get", langKey)))
      add(JSONArray(listOf("get", "name:ar")))
      add(JSONArray(listOf("get", "name:nonlatin")))
      add(JSONArray(listOf("get", "name")))
      add(JSONArray(listOf("get", "name:latin")))
      add(JSONArray(listOf("get", "name:en")))
    }
    return JSONArray(
      listOf(
        "case",
        JSONArray(
          listOf(
            "all",
            JSONArray(listOf("has", "name:ar")),
            JSONArray(listOf("has", "name:latin")),
            JSONArray(listOf("!=", JSONArray(listOf("get", "name:ar")), JSONArray(listOf("get", "name:latin")))),
          ),
        ),
        JSONArray(listOf("concat", JSONArray(listOf("get", "name:ar")), "\n", JSONArray(listOf("get", "name:latin")))),
        JSONArray(
          listOf(
            "case",
            JSONArray(
              listOf(
                "all",
                JSONArray(listOf("has", "name:nonlatin")),
                JSONArray(listOf("has", "name:latin")),
                JSONArray(listOf("!=", JSONArray(listOf("get", "name:nonlatin")), JSONArray(listOf("get", "name:latin")))),
              ),
            ),
            JSONArray(listOf("concat", JSONArray(listOf("get", "name:nonlatin")), "\n", JSONArray(listOf("get", "name:latin")))),
            JSONArray(coalesceArgs),
          ),
        ),
      ),
    )
  }

  /**
   * Pure JSON transformation from any MapLibre GL light style JSON string to a balanced dark JSON string.
   * Walks paint/layout/stops/expressions recursively and transforms hex, rgb(a), hsl(a), and rgb/rgba expressions.
   */
  fun transformLightStyleToDarkJson(lightJsonStr: String): String {
    val root = JSONObject(lightJsonStr)
    root.optJSONObject("light")?.let { lightObj ->
      transformPaintObject(lightObj, LayerRole.DEFAULT, isDarkMode = true)
    }
    val layers = root.optJSONArray("layers")
    if (layers != null) {
      for (i in 0 until layers.length()) {
        val layer = layers.optJSONObject(i) ?: continue
        val role = classifyLayerRole(layer)
        layer.optJSONObject("paint")?.let { paint ->
          transformPaintObject(paint, role, isDarkMode = true)
        }
      }
    }
    return root.toString()
  }

  /**
   * Pure JSON transformation from any MapLibre GL light style JSON string to a balanced "Soft Light" JSON string:
   * - Background #EDEFF3
   * - Water #BCD6EE
   * - Parks #D7EBD0
   * - Buildings #E1E4EA (3D fill-extrusion opacity 0.65)
   * - Road fills desaturated ~25% (major roads muted warm yellow/orange, minor roads #FFFFFF); road casings slightly darker
   * - Road labels dark #1B2430 with 2dp white halo
   */
  fun transformLightStyleToSoftLightJson(lightJsonStr: String): String {
    val root = JSONObject(lightJsonStr)
    root.optJSONObject("light")?.let { lightObj ->
      transformPaintObject(lightObj, LayerRole.DEFAULT, isDarkMode = false)
    }
    val layers = root.optJSONArray("layers")
    if (layers != null) {
      for (i in 0 until layers.length()) {
        val layer = layers.optJSONObject(i) ?: continue
        val role = classifyLayerRole(layer)
        val typeLower = layer.optString("type", "").lowercase(Locale.US)
        val paint = layer.optJSONObject("paint")
        if (paint != null) {
          transformPaintObject(paint, role, isDarkMode = false)
          if (role == LayerRole.BUILDING && typeLower == "fill-extrusion") {
            paint.put("fill-extrusion-opacity", 0.65)
          }
          if (role == LayerRole.SYMBOL_LABEL && isRoadLabelSymbolLayer(layer)) {
            paint.put("text-color", "#1B2430")
            paint.put("text-halo-color", "#FFFFFF")
            paint.put("text-halo-width", 2.0)
          } else if (role == LayerRole.SYMBOL_LABEL) {
            if (paint.has("text-halo-width")) {
              val w = paint.optDouble("text-halo-width", Double.NaN)
              if (!w.isNaN() && w < 2.0) paint.put("text-halo-width", 2.0)
            }
          }
        }
      }
    }
    return root.toString()
  }

  private fun classifyLayerRole(layer: JSONObject): LayerRole {
    val id = layer.optString("id", "").lowercase(Locale.US)
    val type = layer.optString("type", "").lowercase(Locale.US)
    val srcLayer = layer.optString("source-layer", "").lowercase(Locale.US)

    return when {
      type == "background" || id == "background" -> LayerRole.BACKGROUND
      type == "symbol" -> LayerRole.SYMBOL_LABEL
      srcLayer.contains("water") ||
        (type != "symbol" && (id.contains("water") || id.contains("ocean") || id.contains("river") || id.contains("lake") || id.contains("canal"))) ->
        LayerRole.WATER
      srcLayer.contains("building") || id.contains("building") -> LayerRole.BUILDING
      id.contains("casing") || id.contains("outline") || id.contains("border") -> {
        if (isRoadIdentifier(id, srcLayer)) LayerRole.ROAD_CASING else LayerRole.DEFAULT
      }
      id.contains("motorway") || id.contains("trunk") -> LayerRole.ROAD_MOTORWAY_TRUNK
      id.contains("primary") && isRoadIdentifier(id, srcLayer) && (type == "line" || type == "fill") ->
        LayerRole.ROAD_PRIMARY
      isRoadIdentifier(id, srcLayer) &&
        (id.contains("minor") || id.contains("residential") || id.contains("service") ||
          id.contains("unclassified") || id.contains("living") || id.contains("street")) &&
        (type == "line" || type == "fill") ->
        LayerRole.ROAD_MINOR_FILL
      isRoadIdentifier(id, srcLayer) && (type == "line" || type == "fill") -> LayerRole.ROAD_FILL
      (type == "fill" || type == "fill-extrusion") && isParkIdentifier(id, srcLayer) -> LayerRole.PARK
      else -> LayerRole.DEFAULT
    }
  }

  private fun isParkIdentifier(id: String, srcLayer: String): Boolean {
    return srcLayer.contains("park") ||
      id.contains("park") ||
      id.contains("grass") ||
      id.contains("wood") ||
      id.contains("forest") ||
      id.contains("green") ||
      id.contains("garden") ||
      id.contains("scrub") ||
      id.contains("meadow") ||
      id.contains("wetland") ||
      id.contains("nature") ||
      id.contains("cemetery") ||
      id.contains("recreation") ||
      id.contains("pitch") ||
      id.contains("vegetation")
  }

  private fun isRoadIdentifier(id: String, srcLayer: String): Boolean {
    return srcLayer.contains("transportation") ||
      srcLayer.contains("road") ||
      id.contains("road") ||
      id.contains("highway") ||
      id.contains("street") ||
      id.contains("motorway") ||
      id.contains("trunk") ||
      id.contains("primary") ||
      id.contains("secondary") ||
      id.contains("tertiary") ||
      id.contains("minor") ||
      id.contains("residential") ||
      id.contains("service") ||
      id.contains("bridge") ||
      id.contains("tunnel") ||
      id.contains("link") ||
      id.contains("path") ||
      id.contains("track")
  }

  private fun transformPaintObject(paint: JSONObject, layerRole: LayerRole, isDarkMode: Boolean = true) {
    val keys = paint.keys().asSequence().toList()
    for (key in keys) {
      val value = paint.get(key)
      val propRole =
        when {
          key.contains("text-halo") -> PropertyRole.TEXT_HALO
          key.contains("text-color") -> PropertyRole.TEXT_FILL
          key.contains("icon-halo") -> PropertyRole.TEXT_HALO
          key.contains("icon-color") -> PropertyRole.TEXT_FILL
          else -> PropertyRole.FROM_LAYER
        }
      paint.put(key, walkValue(value, layerRole, propRole, isDarkMode))
    }
  }

  private enum class PropertyRole {
    FROM_LAYER,
    TEXT_FILL,
    TEXT_HALO,
  }

  private fun walkValue(value: Any, layerRole: LayerRole, propRole: PropertyRole, isDarkMode: Boolean): Any {
    return when (value) {
      is String -> transformColorStringIfColor(value, layerRole, propRole, isDarkMode) ?: value
      is JSONObject -> {
        val keys = value.keys().asSequence().toList()
        for (k in keys) {
          value.put(k, walkValue(value.get(k), layerRole, propRole, isDarkMode))
        }
        value
      }
      is JSONArray -> transformJsonArray(value, layerRole, propRole, isDarkMode)
      else -> value
    }
  }

  private fun transformJsonArray(arr: JSONArray, layerRole: LayerRole, propRole: PropertyRole, isDarkMode: Boolean): Any {
    if (arr.length() >= 4 && arr.optString(0).equals("rgb", ignoreCase = true)) {
      val r = arr.optDouble(1, Double.NaN)
      val g = arr.optDouble(2, Double.NaN)
      val b = arr.optDouble(3, Double.NaN)
      if (!r.isNaN() && !g.isNaN() && !b.isNaN()) {
        val hsl = rgbToHsl(r / 255.0, g / 255.0, b / 255.0, 1.0)
        val out =
          if (isDarkMode) applyDarkRoleTransform(hsl, layerRole, propRole)
          else applySoftLightRoleTransform(hsl, layerRole, propRole)
        return formatColor(out)
      }
    }
    if (arr.length() >= 5 && arr.optString(0).equals("rgba", ignoreCase = true)) {
      val r = arr.optDouble(1, Double.NaN)
      val g = arr.optDouble(2, Double.NaN)
      val b = arr.optDouble(3, Double.NaN)
      val a = arr.optDouble(4, Double.NaN)
      if (!r.isNaN() && !g.isNaN() && !b.isNaN() && !a.isNaN()) {
        val hsl = rgbToHsl(r / 255.0, g / 255.0, b / 255.0, a.coerceIn(0.0, 1.0))
        val out =
          if (isDarkMode) applyDarkRoleTransform(hsl, layerRole, propRole)
          else applySoftLightRoleTransform(hsl, layerRole, propRole)
        return formatColor(out)
      }
    }
    for (i in 0 until arr.length()) {
      arr.put(i, walkValue(arr.get(i), layerRole, propRole, isDarkMode))
    }
    return arr
  }

  fun transformColorStringIfColor(
    raw: String,
    layerRoleName: String = "DEFAULT",
    isTextHalo: Boolean = false,
    isTextFill: Boolean = false,
  ): String? {
    val role = runCatching { LayerRole.valueOf(layerRoleName) }.getOrDefault(LayerRole.DEFAULT)
    val prop =
      when {
        isTextHalo -> PropertyRole.TEXT_HALO
        isTextFill -> PropertyRole.TEXT_FILL
        else -> PropertyRole.FROM_LAYER
      }
    return transformColorStringIfColor(raw, role, prop, isDarkMode = true)
  }

  private fun transformColorStringIfColor(
    raw: String,
    layerRole: LayerRole,
    propRole: PropertyRole,
    isDarkMode: Boolean,
  ): String? {
    val trimmed = raw.trim()
    val parsed = parseColorToHsl(trimmed) ?: return null
    val transformed =
      if (isDarkMode) applyDarkRoleTransform(parsed, layerRole, propRole)
      else applySoftLightRoleTransform(parsed, layerRole, propRole)
    return formatColor(transformed)
  }

  private fun applyDarkRoleTransform(
    src: HslColor,
    layerRole: LayerRole,
    propRole: PropertyRole,
  ): HslColor {
    // Base transform: invert lightness compressed to [0.08, 0.80], reduce saturation ~15%
    val invertedL = 1.0 - src.l
    val compressedL = (0.08 + invertedL * 0.72).coerceIn(0.08, 0.80)
    val reducedS = (src.s * 0.85).coerceIn(0.0, 1.0)

    if (propRole == PropertyRole.TEXT_HALO) {
      // Dark halo #12161C
      return HslColor(h = 216.0, s = 0.217, l = 0.09, a = src.a)
    }
    if (propRole == PropertyRole.TEXT_FILL) {
      // Near-white labels while preserving subtle tint on highway shields/colored labels
      val targetL = compressedL.coerceIn(0.82, 0.94)
      val targetS = (src.s * 0.65).coerceIn(0.0, 0.55)
      return HslColor(h = src.h, s = targetS, l = targetL, a = src.a)
    }

    return when (layerRole) {
      LayerRole.BACKGROUND -> {
        // #12161C -> hsl(216, 22%, 9%)
        HslColor(h = 216.0, s = 0.217, l = 0.09, a = src.a)
      }
      LayerRole.WATER -> {
        // #1B2A3A -> hsl(211, 36%, 16.7%)
        HslColor(h = 211.0, s = 0.365, l = 0.167, a = src.a)
      }
      LayerRole.BUILDING -> {
        // Slightly lighter than #12161C background (L ~0.13..0.18)
        HslColor(
          h = if (src.s < 0.08) 216.0 else src.h,
          s = if (src.s < 0.08) 0.16 else (src.s * 0.45).coerceAtMost(0.24),
          l = compressedL.coerceIn(0.135, 0.19),
          a = src.a,
        )
      }
      LayerRole.ROAD_CASING -> {
        // Casings strictly darker than road fills
        HslColor(
          h = if (src.s < 0.08) 218.0 else src.h,
          s = (reducedS * 0.65).coerceAtMost(0.32),
          l = compressedL.coerceIn(0.08, 0.15),
          a = src.a,
        )
      }
      LayerRole.ROAD_MOTORWAY_TRUNK, LayerRole.ROAD_PRIMARY -> {
        // Keep warm motorway/trunk accent but dimmed
        val warmHue =
          if (src.s > 0.20 && src.h <= 65.0) src.h
          else 32.0 // warm amber
        HslColor(
          h = warmHue,
          s = max(0.38, (src.s * 0.72).coerceIn(0.38, 0.62)),
          l = compressedL.coerceIn(0.34, 0.46),
          a = src.a,
        )
      }
      LayerRole.ROAD_FILL, LayerRole.ROAD_MINOR_FILL -> {
        // Road fills clearly lighter than casings [0.24, 0.46]
        HslColor(
          h = if (src.s < 0.08) 216.0 else src.h,
          s = (reducedS * 0.80).coerceIn(0.06, 0.55),
          l = compressedL.coerceIn(0.24, 0.44),
          a = src.a,
        )
      }
      LayerRole.SYMBOL_LABEL -> {
        HslColor(h = src.h, s = (src.s * 0.65).coerceIn(0.0, 0.55), l = compressedL.coerceIn(0.80, 0.92), a = src.a)
      }
      LayerRole.PARK, LayerRole.DEFAULT -> {
        HslColor(h = src.h, s = reducedS, l = compressedL, a = src.a)
      }
    }
  }

  private fun applySoftLightRoleTransform(
    src: HslColor,
    layerRole: LayerRole,
    propRole: PropertyRole,
  ): HslColor {
    if (propRole == PropertyRole.TEXT_HALO) {
      return HslColor(h = 0.0, s = 0.0, l = 1.0, a = src.a) // #FFFFFF
    }
    if (propRole == PropertyRole.TEXT_FILL) {
      return HslColor(h = 214.0, s = 0.28, l = 0.147, a = src.a) // #1B2430
    }

    return when (layerRole) {
      LayerRole.BACKGROUND -> {
        // #EDEFF3
        rgbToHsl(0xED / 255.0, 0xEF / 255.0, 0xF3 / 255.0, src.a)
      }
      LayerRole.WATER -> {
        // #BCD6EE
        rgbToHsl(0xBC / 255.0, 0xD6 / 255.0, 0xEE / 255.0, src.a)
      }
      LayerRole.PARK -> {
        // #D7EBD0
        rgbToHsl(0xD7 / 255.0, 0xEB / 255.0, 0xD0 / 255.0, src.a)
      }
      LayerRole.BUILDING -> {
        // #E1E4EA
        rgbToHsl(0xE1 / 255.0, 0xE4 / 255.0, 0xEA / 255.0, src.a)
      }
      LayerRole.ROAD_CASING -> {
        // Slightly darker than road fills
        val desat = (src.s * 0.75).coerceIn(0.0, 0.50)
        val darkenedL = min(src.l, 0.76).coerceIn(0.56, 0.76)
        HslColor(h = if (src.s < 0.08) 216.0 else src.h, s = desat, l = darkenedL, a = src.a)
      }
      LayerRole.ROAD_MOTORWAY_TRUNK, LayerRole.ROAD_PRIMARY -> {
        // Major roads in muted warm yellow/orange (~25% desaturated)
        val warmHue = if (src.s > 0.15 && src.h <= 62.0) src.h else 38.0
        val desat = (if (src.s > 0.20) src.s * 0.75 else 0.52).coerceIn(0.42, 0.68)
        HslColor(h = warmHue, s = desat, l = src.l.coerceIn(0.68, 0.80), a = src.a)
      }
      LayerRole.ROAD_MINOR_FILL -> {
        // Minor roads #FFFFFF
        HslColor(h = 0.0, s = 0.0, l = 1.0, a = src.a)
      }
      LayerRole.ROAD_FILL -> {
        if (src.s < 0.12 && src.l > 0.75) {
          HslColor(h = 0.0, s = 0.0, l = 1.0, a = src.a)
        } else {
          // Desaturated ~25%
          HslColor(h = src.h, s = (src.s * 0.75).coerceIn(0.0, 0.72), l = src.l.coerceIn(0.66, 0.90), a = src.a)
        }
      }
      LayerRole.SYMBOL_LABEL -> {
        rgbToHsl(0x1B / 255.0, 0x24 / 255.0, 0x30 / 255.0, src.a)
      }
      LayerRole.DEFAULT -> {
        HslColor(h = src.h, s = (src.s * 0.75).coerceIn(0.0, 1.0), l = src.l.coerceIn(0.20, 0.96), a = src.a)
      }
    }
  }

  private fun parseColorToHsl(raw: String): HslColor? {
    HEX_REGEX.matchEntire(raw)?.let { m ->
      val hex = m.groupValues[1]
      val (r, g, b, a) =
        when (hex.length) {
          3 -> {
            val r = hex[0].toString().repeat(2).toInt(16) / 255.0
            val g = hex[1].toString().repeat(2).toInt(16) / 255.0
            val b = hex[2].toString().repeat(2).toInt(16) / 255.0
            listOf(r, g, b, 1.0)
          }
          4 -> {
            val r = hex[0].toString().repeat(2).toInt(16) / 255.0
            val g = hex[1].toString().repeat(2).toInt(16) / 255.0
            val b = hex[2].toString().repeat(2).toInt(16) / 255.0
            val a = hex[3].toString().repeat(2).toInt(16) / 255.0
            listOf(r, g, b, a)
          }
          6 -> {
            val r = hex.substring(0, 2).toInt(16) / 255.0
            val g = hex.substring(2, 4).toInt(16) / 255.0
            val b = hex.substring(4, 6).toInt(16) / 255.0
            listOf(r, g, b, 1.0)
          }
          8 -> {
            val r = hex.substring(0, 2).toInt(16) / 255.0
            val g = hex.substring(2, 4).toInt(16) / 255.0
            val b = hex.substring(4, 6).toInt(16) / 255.0
            val a = hex.substring(6, 8).toInt(16) / 255.0
            listOf(r, g, b, a)
          }
          else -> return null
        }
      return rgbToHsl(r, g, b, a)
    }

    RGB_REGEX.matchEntire(raw)?.let { m ->
      val r = m.groupValues[1].trim().toDoubleOrNull()?.div(255.0) ?: return null
      val g = m.groupValues[2].trim().toDoubleOrNull()?.div(255.0) ?: return null
      val b = m.groupValues[3].trim().toDoubleOrNull()?.div(255.0) ?: return null
      val a = m.groupValues[4].trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
      return rgbToHsl(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0), a.coerceIn(0.0, 1.0))
    }

    HSL_REGEX.matchEntire(raw)?.let { m ->
      val h = m.groupValues[1].trim().toDoubleOrNull() ?: return null
      val s = m.groupValues[2].trim().toDoubleOrNull()?.div(100.0) ?: return null
      val l = m.groupValues[3].trim().toDoubleOrNull()?.div(100.0) ?: return null
      val a = m.groupValues[4].trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
      return HslColor(
        h = ((h % 360.0) + 360.0) % 360.0,
        s = s.coerceIn(0.0, 1.0),
        l = l.coerceIn(0.0, 1.0),
        a = a.coerceIn(0.0, 1.0),
      )
    }

    return null
  }

  private fun rgbToHsl(r: Double, g: Double, b: Double, a: Double): HslColor {
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) * 0.5
    val d = maxC - minC
    val s: Double
    var h: Double
    if (d < 1e-6) {
      s = 0.0
      h = 0.0
    } else {
      s = if (l > 0.5) d / (2.0 - maxC - minC) else d / (maxC + minC)
      h =
        when (maxC) {
          r -> (g - b) / d + (if (g < b) 6.0 else 0.0)
          g -> (b - r) / d + 2.0
          else -> (r - g) / d + 4.0
        }
      h *= 60.0
    }
    return HslColor(
      h = ((h % 360.0) + 360.0) % 360.0,
      s = s.coerceIn(0.0, 1.0),
      l = l.coerceIn(0.0, 1.0),
      a = a.coerceIn(0.0, 1.0),
    )
  }

  private fun hslToRgb(hsl: HslColor): Triple<Int, Int, Int> {
    val c = (1.0 - abs(2.0 * hsl.l - 1.0)) * hsl.s
    val x = c * (1.0 - abs(((hsl.h / 60.0) % 2.0) - 1.0))
    val m = hsl.l - c * 0.5
    val (r1, g1, b1) =
      when {
        hsl.h < 60.0 -> Triple(c, x, 0.0)
        hsl.h < 120.0 -> Triple(x, c, 0.0)
        hsl.h < 180.0 -> Triple(0.0, c, x)
        hsl.h < 240.0 -> Triple(0.0, x, c)
        hsl.h < 300.0 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
      }
    val r = ((r1 + m) * 255.0).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255.0).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255.0).roundToInt().coerceIn(0, 255)
    return Triple(r, g, b)
  }

  private fun formatColor(hsl: HslColor): String {
    val (r, g, b) = hslToRgb(hsl)
    return if (hsl.a >= 0.999) {
      String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    } else {
      String.format(Locale.US, "rgba(%d, %d, %d, %.3f)", r, g, b, hsl.a)
    }
  }

  private fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }
}
