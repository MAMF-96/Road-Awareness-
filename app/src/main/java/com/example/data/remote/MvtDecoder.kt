package com.example.data.remote

import com.example.data.model.GeoPoint
import com.example.domain.GeoMath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

/**
 * Pure-Kotlin Mapbox Vector Tile (MVT) protobuf v2 decoder + Web Mercator projection.
 * Handles GZIP-compressed and raw PBF payloads cleanly without external protobuf dependencies.
 */
object MvtDecoder {

  enum class GeomType(val code: Int) {
    UNKNOWN(0),
    POINT(1),
    LINESTRING(2),
    POLYGON(3),
    ;

    companion object {
      fun fromCode(code: Int): GeomType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
  }

  data class MvtFeature(
    val id: Long,
    val geomType: GeomType,
    val tags: Map<String, String>,
    val geometries: List<List<GeoPoint>>,
  )

  data class MvtLayer(
    val name: String,
    val extent: Int,
    val features: List<MvtFeature>,
  )

  data class MvtTile(
    val z: Int,
    val x: Int,
    val y: Int,
    val layers: Map<String, MvtLayer>,
  )

  data class DecodeWithTimingResult(
    val tile: MvtTile,
    val gunzipMs: Long,
    val decodeMs: Long,
  )

  fun decode(rawBytes: ByteArray, z: Int, x: Int, y: Int, layerFilter: Set<String>? = null): MvtTile =
    decodeWithTiming(rawBytes, z, x, y, layerFilter).tile

  fun decodeWithTiming(
    rawBytes: ByteArray,
    z: Int,
    x: Int,
    y: Int,
    layerFilter: Set<String>? = null,
  ): DecodeWithTimingResult {
    val t0 = System.nanoTime()
    val bytes = maybeGunzip(rawBytes)
    val t1 = System.nanoTime()
    val reader = ProtoReader(bytes, 0, bytes.size)
    val layers = HashMap<String, MvtLayer>()

    while (reader.hasRemaining()) {
      val tag = reader.readVarint32()
      val field = tag ushr 3
      val wire = tag and 0x7
      if (field == 3 && wire == WIRE_LEN) {
        val len = reader.readVarint32()
        val end = reader.pos + len
        val layer = decodeLayer(bytes, reader.pos, end, z, x, y, layerFilter)
        if (layer != null) {
          layers[layer.name] = layer
        }
        reader.pos = end
      } else {
        reader.skipField(wire)
      }
    }
    val t2 = System.nanoTime()
    val gunzipMs = ((t1 - t0) / 1_000_000L).coerceAtLeast(0L)
    val decodeMs = ((t2 - t1) / 1_000_000L).coerceAtLeast(0L)
    return DecodeWithTimingResult(
      tile = MvtTile(z = z, x = x, y = y, layers = layers),
      gunzipMs = gunzipMs,
      decodeMs = decodeMs,
    )
  }

  fun tilePxToGeoPoint(z: Int, x: Int, y: Int, px: Int, py: Int, extent: Int): GeoPoint =
    tilePxToGeoPoint(z, x, y, px.toDouble(), py.toDouble(), extent)

  fun tilePxToGeoPoint(z: Int, x: Int, y: Int, px: Double, py: Double, extent: Int): GeoPoint {
    val nInt = 1 shl z
    val wrappedX = ((x % nInt) + nInt) % nInt
    val n = 2.0.pow(z)
    val xf = wrappedX.toDouble() + px / extent.toDouble()
    val yf = y.toDouble() + py / extent.toDouble()
    val lon = GeoMath.normalizeLon((xf / n) * 360.0 - 180.0)
    val latRad = atan(sinh(PI * (1.0 - 2.0 * yf / n)))
    return GeoPoint(lat = GeoMath.clampMercatorLat(Math.toDegrees(latRad)), lon = lon)
  }

  private fun maybeGunzip(raw: ByteArray): ByteArray {
    if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
      return GZIPInputStream(ByteArrayInputStream(raw)).use { gz ->
        val out = ByteArrayOutputStream(raw.size * 2)
        val buf = ByteArray(4096)
        while (true) {
          val n = gz.read(buf)
          if (n <= 0) break
          out.write(buf, 0, n)
        }
        out.toByteArray()
      }
    }
    return raw
  }

  private fun decodeLayer(
    buf: ByteArray,
    start: Int,
    end: Int,
    z: Int,
    x: Int,
    y: Int,
    layerFilter: Set<String>?,
  ): MvtLayer? {
    var name = ""
    var extent = 4096
    val keys = mutableListOf<String>()
    val values = mutableListOf<String>()
    val featureSpans = mutableListOf<Pair<Int, Int>>()

    val r = ProtoReader(buf, start, end)
    while (r.hasRemaining()) {
      val tag = r.readVarint32()
      val field = tag ushr 3
      val wire = tag and 0x7
      when (field) {
        1 -> if (wire == WIRE_LEN) name = r.readString() else r.skipField(wire)
        2 -> if (wire == WIRE_LEN) {
          val len = r.readVarint32()
          val fStart = r.pos
          val fEnd = (fStart + len).coerceAtMost(end)
          featureSpans += fStart to fEnd
          r.pos = fEnd
        } else {
          r.skipField(wire)
        }
        3 -> if (wire == WIRE_LEN) keys += r.readString() else r.skipField(wire)
        4 -> if (wire == WIRE_LEN) {
          val len = r.readVarint32()
          val vEnd = (r.pos + len).coerceAtMost(end)
          values += decodeValue(buf, r.pos, vEnd)
          r.pos = vEnd
        } else {
          r.skipField(wire)
        }
        5 -> if (wire == WIRE_VARINT) extent = r.readVarint32().coerceAtLeast(1) else r.skipField(wire)
        else -> r.skipField(wire)
      }
    }

    if (layerFilter != null && name !in layerFilter) {
      return null
    }

    val features = ArrayList<MvtFeature>(featureSpans.size)
    for ((fStart, fEnd) in featureSpans) {
      features += decodeFeature(buf, fStart, fEnd, keys, values, z, x, y, extent)
    }
    return MvtLayer(name = name, extent = extent, features = features)
  }

  private fun decodeValue(buf: ByteArray, start: Int, end: Int): String {
    val r = ProtoReader(buf, start, end)
    var strVal: String? = null
    while (r.hasRemaining()) {
      val tag = r.readVarint32()
      val field = tag ushr 3
      val wire = tag and 0x7
      when (field) {
        1 -> if (wire == WIRE_LEN) strVal = r.readString() else r.skipField(wire)
        2 -> if (wire == WIRE_32) strVal = r.readFloat().toString() else r.skipField(wire)
        3 -> if (wire == WIRE_64) strVal = r.readDouble().toString() else r.skipField(wire)
        4 -> if (wire == WIRE_VARINT) strVal = r.readVarint64().toString() else r.skipField(wire)
        5 -> if (wire == WIRE_VARINT) strVal = r.readVarint64().toString() else r.skipField(wire)
        6 -> if (wire == WIRE_VARINT) strVal = zigzag64(r.readVarint64()).toString() else r.skipField(wire)
        7 -> if (wire == WIRE_VARINT) strVal = (r.readVarint64() != 0L).toString() else r.skipField(wire)
        else -> r.skipField(wire)
      }
    }
    return strVal ?: ""
  }

  private fun decodeFeature(
    buf: ByteArray,
    start: Int,
    end: Int,
    keys: List<String>,
    values: List<String>,
    z: Int,
    x: Int,
    y: Int,
    extent: Int,
  ): MvtFeature {
    var id = 0L
    var geomType = GeomType.UNKNOWN
    val rawTags = mutableListOf<Int>()
    val rawGeom = mutableListOf<Int>()

    val r = ProtoReader(buf, start, end)
    while (r.hasRemaining()) {
      val tag = r.readVarint32()
      val field = tag ushr 3
      val wire = tag and 0x7
      when (field) {
        1 -> if (wire == WIRE_VARINT) id = r.readVarint64() else r.skipField(wire)
        2 -> when (wire) {
          WIRE_LEN -> r.readPackedVarints32(rawTags)
          WIRE_VARINT -> rawTags += r.readVarint32()
          else -> r.skipField(wire)
        }
        3 -> if (wire == WIRE_VARINT) geomType = GeomType.fromCode(r.readVarint32()) else r.skipField(wire)
        4 -> when (wire) {
          WIRE_LEN -> r.readPackedVarints32(rawGeom)
          WIRE_VARINT -> rawGeom += r.readVarint32()
          else -> r.skipField(wire)
        }
        else -> r.skipField(wire)
      }
    }

    val tagMap = HashMap<String, String>(rawTags.size / 2)
    var i = 0
    while (i + 1 < rawTags.size) {
      val kIdx = rawTags[i]
      val vIdx = rawTags[i + 1]
      if (kIdx in keys.indices && vIdx in values.indices) {
        tagMap[keys[kIdx]] = values[vIdx]
      }
      i += 2
    }

    val geometries = decodeGeometryCommands(rawGeom, geomType, z, x, y, extent)
    return MvtFeature(id = id, geomType = geomType, tags = tagMap, geometries = geometries)
  }

  private data class PxPoint(val x: Double, val y: Double)

  private fun decodeGeometryCommands(
    commands: List<Int>,
    geomType: GeomType,
    z: Int,
    x: Int,
    y: Int,
    extent: Int,
  ): List<List<GeoPoint>> {
    if (commands.isEmpty()) return emptyList()
    val rawRings = mutableListOf<List<PxPoint>>()
    var currentRing = mutableListOf<PxPoint>()
    var cursorX = 0
    var cursorY = 0
    var idx = 0

    while (idx < commands.size) {
      val cmdInt = commands[idx++]
      val cmd = cmdInt and 0x7
      val count = cmdInt ushr 3
      when (cmd) {
        CMD_MOVE_TO -> {
          repeat(count) {
            if (idx + 1 >= commands.size) return@repeat
            val dx = zigzag32(commands[idx++])
            val dy = zigzag32(commands[idx++])
            cursorX += dx
            cursorY += dy
            if (currentRing.isNotEmpty()) {
              rawRings += currentRing
              currentRing = mutableListOf()
            }
            currentRing += PxPoint(cursorX.toDouble(), cursorY.toDouble())
          }
        }
        CMD_LINE_TO -> {
          repeat(count) {
            if (idx + 1 >= commands.size) return@repeat
            val dx = zigzag32(commands[idx++])
            val dy = zigzag32(commands[idx++])
            cursorX += dx
            cursorY += dy
            currentRing += PxPoint(cursorX.toDouble(), cursorY.toDouble())
          }
        }
        CMD_CLOSE_PATH -> {
          if (geomType == GeomType.POLYGON && currentRing.isNotEmpty()) {
            currentRing += currentRing.first()
            rawRings += currentRing
            currentRing = mutableListOf()
          }
        }
        else -> break
      }
    }
    if (currentRing.isNotEmpty()) {
      rawRings += currentRing
    }

    val ext = extent.toDouble()
    return if (geomType == GeomType.LINESTRING) {
      val clipped = mutableListOf<List<GeoPoint>>()
      for (line in rawRings) {
        for (piece in clipPolylineToTileBounds(line, ext)) {
          if (piece.size >= 2) {
            clipped += piece.map { p -> tilePxToGeoPoint(z, x, y, p.x, p.y, extent) }
          }
        }
      }
      clipped
    } else {
      rawRings.map { ring ->
        ring.map { p -> tilePxToGeoPoint(z, x, y, p.x, p.y, extent) }
      }
    }
  }

  private fun clipPolylineToTileBounds(
    pts: List<PxPoint>,
    extent: Double,
  ): List<List<PxPoint>> {
    if (pts.size < 2) return emptyList()
    val out = mutableListOf<List<PxPoint>>()
    var current = mutableListOf<PxPoint>()
    val eps = 1e-6

    fun flushCurrent() {
      if (current.size >= 2) {
        out += current
      }
      current = mutableListOf()
    }

    fun samePx(a: PxPoint, b: PxPoint): Boolean =
      kotlin.math.abs(a.x - b.x) <= 1e-4 && kotlin.math.abs(a.y - b.y) <= 1e-4

    for (i in 0 until pts.lastIndex) {
      val p0 = pts[i]
      val p1 = pts[i + 1]
      val clippedSeg = clipSegmentLiangBarsky(p0, p1, 0.0, extent, 0.0, extent)
      if (clippedSeg == null) {
        flushCurrent()
        continue
      }
      val (c0, c1) = clippedSeg
      if (kotlin.math.abs(c0.x - c1.x) <= eps && kotlin.math.abs(c0.y - c1.y) <= eps) {
        continue
      }
      if (current.isEmpty()) {
        current += c0
        current += c1
      } else if (samePx(current.last(), c0)) {
        current += c1
      } else {
        flushCurrent()
        current += c0
        current += c1
      }
      // If segment exited the tile at c1 before reaching p1, close this piece cleanly.
      if (!samePx(c1, p1)) {
        flushCurrent()
      }
    }
    flushCurrent()
    return out
  }

  private fun clipSegmentLiangBarsky(
    p0: PxPoint,
    p1: PxPoint,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
  ): Pair<PxPoint, PxPoint>? {
    val dx = p1.x - p0.x
    val dy = p1.y - p0.y
    var t0 = 0.0
    var t1 = 1.0

    fun clipTest(p: Double, q: Double): Boolean {
      if (kotlin.math.abs(p) < 1e-12) {
        return q >= 0.0
      }
      val r = q / p
      if (p < 0.0) {
        if (r > t1) return false
        if (r > t0) t0 = r
      } else {
        if (r < t0) return false
        if (r < t1) t1 = r
      }
      return true
    }

    if (!clipTest(-dx, p0.x - xMin)) return null
    if (!clipTest(dx, xMax - p0.x)) return null
    if (!clipTest(-dy, p0.y - yMin)) return null
    if (!clipTest(dy, yMax - p0.y)) return null

    val cx0 = (p0.x + t0 * dx).coerceIn(xMin, xMax)
    val cy0 = (p0.y + t0 * dy).coerceIn(yMin, yMax)
    val cx1 = (p0.x + t1 * dx).coerceIn(xMin, xMax)
    val cy1 = (p0.y + t1 * dy).coerceIn(yMin, yMax)
    return PxPoint(cx0, cy0) to PxPoint(cx1, cy1)
  }

  private fun zigzag32(n: Int): Int = (n ushr 1) xor -(n and 1)

  private fun zigzag64(n: Long): Long = (n ushr 1) xor -(n and 1L)

  private class ProtoReader(
    private val buf: ByteArray,
    var pos: Int,
    private val limit: Int,
  ) {
    fun hasRemaining(): Boolean = pos < limit

    fun readVarint32(): Int {
      var res = 0
      var shift = 0
      while (pos < limit && shift < 35) {
        val b = buf[pos++].toInt() and 0xff
        res = res or ((b and 0x7f) shl shift)
        if ((b and 0x80) == 0) return res
        shift += 7
      }
      return res
    }

    fun readVarint64(): Long {
      var res = 0L
      var shift = 0
      while (pos < limit && shift < 70) {
        val b = buf[pos++].toInt() and 0xff
        res = res or ((b and 0x7f).toLong() shl shift)
        if ((b and 0x80) == 0) return res
        shift += 7
      }
      return res
    }

    fun readString(): String {
      val len = readVarint32().coerceAtLeast(0)
      val end = (pos + len).coerceAtMost(limit)
      val s = String(buf, pos, end - pos, Charsets.UTF_8)
      pos = end
      return s
    }

    fun readPackedVarints32(out: MutableList<Int>) {
      val len = readVarint32().coerceAtLeast(0)
      val end = (pos + len).coerceAtMost(limit)
      while (pos < end) {
        out += readVarint32()
      }
      pos = end
    }

    fun readFloat(): Float {
      val bits = readFixed32()
      return java.lang.Float.intBitsToFloat(bits)
    }

    fun readDouble(): Double {
      val bits = readFixed64()
      return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readFixed32(): Int {
      if (pos + 4 > limit) {
        pos = limit
        return 0
      }
      val v =
        (buf[pos].toInt() and 0xff) or
          ((buf[pos + 1].toInt() and 0xff) shl 8) or
          ((buf[pos + 2].toInt() and 0xff) shl 16) or
          ((buf[pos + 3].toInt() and 0xff) shl 24)
      pos += 4
      return v
    }

    private fun readFixed64(): Long {
      if (pos + 8 > limit) {
        pos = limit
        return 0L
      }
      var v = 0L
      for (i in 0 until 8) {
        v = v or ((buf[pos + i].toLong() and 0xffL) shl (i * 8))
      }
      pos += 8
      return v
    }

    fun skipField(wireType: Int) {
      when (wireType) {
        WIRE_VARINT -> readVarint64()
        WIRE_64 -> pos = (pos + 8).coerceAtMost(limit)
        WIRE_LEN -> {
          val len = readVarint32().coerceAtLeast(0)
          pos = (pos + len).coerceAtMost(limit)
        }
        WIRE_32 -> pos = (pos + 4).coerceAtMost(limit)
        else -> pos = limit
      }
    }
  }

  private const val WIRE_VARINT = 0
  private const val WIRE_64 = 1
  private const val WIRE_LEN = 2
  private const val WIRE_32 = 5

  private const val CMD_MOVE_TO = 1
  private const val CMD_LINE_TO = 2
  private const val CMD_CLOSE_PATH = 7
}
