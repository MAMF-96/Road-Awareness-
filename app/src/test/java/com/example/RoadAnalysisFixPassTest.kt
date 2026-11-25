package com.example

import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LoadedTileBounds
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.data.remote.MvtDecoder
import com.example.data.remote.TileRoadGraphSource
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAnalysisFixPassTest {

  private fun graphFrom(
    ways: List<OsmWay>,
    loadedTiles: List<LoadedTileBounds> = emptyList(),
  ): RoadGraph {
    val nodes = LinkedHashMap<Long, OsmNode>()
    val n2w = HashMap<Long, MutableList<Long>>()
    for (w in ways) {
      w.nodeIds.forEachIndexed { idx, nid ->
        nodes.putIfAbsent(nid, OsmNode(nid, w.points[idx]))
        n2w.getOrPut(nid) { mutableListOf() }.add(w.id)
      }
    }
    return RoadGraph(
      nodes = nodes,
      ways = ways.associateBy { it.id },
      nodeToWays = n2w.mapValues { it.value.distinct() },
      fetchedAtMs = 1_000L,
      fromCache = false,
      source = GraphDataSource.TILES,
      loadedTiles = loadedTiles,
    )
  }

  private fun encodeVarint(out: ByteArrayOutputStream, value: Int) {
    var v = value
    while (true) {
      if ((v and 0x7F.inv()) == 0) {
        out.write(v)
        return
      }
      out.write((v and 0x7F) or 0x80)
      v = v ushr 7
    }
  }

  private fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)

  private fun cmd(id: Int, count: Int): Int = (count shl 3) or (id and 0x7)

  private fun buildSingleLineMvtBytes(
    layerName: String,
    extent: Int,
    highwayClass: String,
    ptsPx: List<Pair<Int, Int>>,
  ): ByteArray {
    fun lenField(fieldNum: Int, payload: ByteArray): ByteArray {
      val b = ByteArrayOutputStream()
      encodeVarint(b, (fieldNum shl 3) or 2)
      encodeVarint(b, payload.size)
      b.write(payload)
      return b.toByteArray()
    }

    fun varintField(fieldNum: Int, v: Int): ByteArray {
      val b = ByteArrayOutputStream()
      encodeVarint(b, (fieldNum shl 3) or 0)
      encodeVarint(b, v)
      return b.toByteArray()
    }

    fun strValue(s: String): ByteArray = lenField(1, s.toByteArray(Charsets.UTF_8))

    val geom = ByteArrayOutputStream()
    encodeVarint(geom, cmd(1, 1))
    encodeVarint(geom, zigzag(ptsPx[0].first))
    encodeVarint(geom, zigzag(ptsPx[0].second))
    if (ptsPx.size > 1) {
      encodeVarint(geom, cmd(2, ptsPx.size - 1))
      for (i in 1 until ptsPx.size) {
        encodeVarint(geom, zigzag(ptsPx[i].first - ptsPx[i - 1].first))
        encodeVarint(geom, zigzag(ptsPx[i].second - ptsPx[i - 1].second))
      }
    }
    val geomPacked = ByteArrayOutputStream()
    val gInts = mutableListOf<Int>()
    run {
      // Re-encode commands cleanly as packed uint32 varints
      gInts += cmd(1, 1)
      gInts += zigzag(ptsPx[0].first)
      gInts += zigzag(ptsPx[0].second)
      if (ptsPx.size > 1) {
        gInts += cmd(2, ptsPx.size - 1)
        for (i in 1 until ptsPx.size) {
          gInts += zigzag(ptsPx[i].first - ptsPx[i - 1].first)
          gInts += zigzag(ptsPx[i].second - ptsPx[i - 1].second)
        }
      }
      for (v in gInts) encodeVarint(geomPacked, v)
    }

    val featOut = ByteArrayOutputStream()
    featOut.write(varintField(1, 1))
    val tagBuf = ByteArrayOutputStream()
    encodeVarint(tagBuf, 0)
    encodeVarint(tagBuf, 0)
    featOut.write(lenField(2, tagBuf.toByteArray()))
    featOut.write(varintField(3, 2)) // LINESTRING
    featOut.write(lenField(4, geomPacked.toByteArray()))

    val layerOut = ByteArrayOutputStream()
    layerOut.write(varintField(15, 2)) // version 2
    layerOut.write(lenField(1, layerName.toByteArray(Charsets.UTF_8)))
    layerOut.write(lenField(2, featOut.toByteArray()))
    layerOut.write(lenField(3, "class".toByteArray(Charsets.UTF_8)))
    layerOut.write(lenField(4, strValue(highwayClass)))
    layerOut.write(varintField(5, extent))

    val tileOut = ByteArrayOutputStream()
    tileOut.write(lenField(3, layerOut.toByteArray()))
    return tileOut.toByteArray()
  }

  @Test
  fun scenario1_tileBorderClippingStitchingAndUnloadedBorderDeadEndSuppression() {
    val z = 14
    val x0 = 9610
    val x1 = 9611
    val y = 6750
    // Road crosses tile x0 -> x1 horizontally around py=2048 with buffer overhang (-64..4160)
    val bytesLeft = buildSingleLineMvtBytes("transportation", 4096, "trunk", listOf(500 to 2048, 4160 to 2048))
    val bytesRight = buildSingleLineMvtBytes("transportation", 4096, "trunk", listOf(-64 to 2048, 3500 to 2048))

    val tileLeft = MvtDecoder.decode(bytesLeft, z, x0, y)
    val tileRight = MvtDecoder.decode(bytesRight, z, x1, y)

    val leftPts = tileLeft.layers["transportation"]!!.features.first().geometries.first()
    val rightPts = tileRight.layers["transportation"]!!.features.first().geometries.first()
    val boundsLeft = TileRoadGraphSource.computeTileBounds(z, x0, y)
    val boundsRight = TileRoadGraphSource.computeTileBounds(z, x1, y)

    // Clipped vertices stay strictly within [bounds.west, bounds.east] and no duplicate overlap beyond border
    assertTrue(leftPts.all { it.lon <= boundsLeft.east + 1e-6 })
    assertTrue(rightPts.all { it.lon >= boundsRight.west - 1e-6 })

    // Stitching left + right connects border endpoints cleanly -> no false DEAD_END, horizon reaches 3km/far end
    val stitchedGraph = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(
      tiles = listOf(tileLeft, tileRight),
      mode = TravelMode.DRIVING,
      fetchedAtMs = 1_000L,
      fromCache = false,
    )
    assertTrue(stitchedGraph.borderStitchStats.connectedBorderEndpoints >= 2)
    val startPt = leftPts.first()
    val matchedStitched = MapMatcher.match(stitchedGraph, GeoMath.offsetPoint(startPt, 90.0, 40.0), 90.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val resStitched = AheadGraphAnalyzer.analyzeAheadDetailed(stitchedGraph, matchedStitched, TravelMode.DRIVING, 20.0, configuredHorizonMeters = 3_000.0)
    assertFalse("Stitched border must not produce false DEAD_END", resStitched.features.any { it.type == FeatureType.DEAD_END })

    // Now load ONLY left tile: endpoint lies on tileRight's unloaded border -> no DEAD_END and horizon pauses cleanly
    val singleTileGraph = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(
      tiles = listOf(tileLeft),
      mode = TravelMode.DRIVING,
      fetchedAtMs = 1_000L,
      fromCache = false,
    )
    val matchedSingle = MapMatcher.match(singleTileGraph, GeoMath.offsetPoint(startPt, 90.0, 40.0), 90.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val resSingle = AheadGraphAnalyzer.analyzeAheadDetailed(singleTileGraph, matchedSingle, TravelMode.DRIVING, 20.0, configuredHorizonMeters = 3_000.0)
    assertFalse("Must never emit DEAD_END at unloaded tile border", resSingle.features.any { it.type == FeatureType.DEAD_END })
    assertTrue("Expected horizonStoppedAtUnloadedBorder when neighbor tile missing", resSingle.horizonStoppedAtUnloadedBorder)
  }

  @Test
  fun scenario2_windingHighwayHairpinNotUTurn_andSBendWindingRoadPlusMinorFilter() {
    // 180-deg hairpin on ONE single continuous highway way (no separate U-turn link)
    val p0 = GeoPoint(30.0000, 31.0000)
    val p1 = GeoMath.offsetPoint(p0, 0.0, 120.0)
    val hairpinPts = mutableListOf(p0, p1)
    val r = 20.0
    val c = GeoMath.offsetPoint(p1, 90.0, r)
    for (deg in 180 downTo 0 step 20) {
      val rad = Math.toRadians(deg.toDouble())
      val pt = GeoPoint(
        c.lat + (r * sin(rad)) / 111_320.0,
        c.lon + (r * cos(rad)) / (111_320.0 * cos(Math.toRadians(c.lat))),
      )
      hairpinPts += pt
    }
    val pEnd = GeoMath.offsetPoint(hairpinPts.last(), 180.0, 150.0)
    hairpinPts += pEnd
    val nIds = hairpinPts.indices.map { 100L + it }
    val hairpinWay = OsmWay(1L, nIds, hairpinPts, OsmTags(highway = "trunk", nameEn = "Mountain Pass"))
    val gHairpin = graphFrom(listOf(hairpinWay))
    val mHairpin = MapMatcher.match(gHairpin, GeoMath.offsetPoint(p0, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val resHairpin = AheadGraphAnalyzer.analyzeAheadDetailed(gHairpin, mHairpin, TravelMode.DRIVING, 15.0)
    assertFalse("Same-way hairpin must never be classified as U_TURN", resHairpin.features.any { it.type == FeatureType.U_TURN })
    assertTrue("Expected HAIRPIN feature", resHairpin.features.any { it.type == FeatureType.HAIRPIN })

    // S-bend sequence (>= 3 alternating sharp curves within 250 m) -> 1 WINDING_ROAD feature
    val sPts = mutableListOf<GeoPoint>()
    var cur = GeoPoint(30.0500, 31.0000)
    var hdg = 0.0
    sPts += cur
    repeat(10) {
      cur = GeoMath.offsetPoint(cur, hdg, 10.0)
      sPts += cur
    }
    val deltas = listOf(12.0, 12.0, 12.0, 12.0, 12.0, 0.0, -14.0, -14.0, -14.0, -14.0, -14.0, 0.0, 13.0, 13.0, 13.0, 13.0, 13.0)
    for (d in deltas) {
      hdg = (hdg + d + 360.0) % 360.0
      repeat(2) {
        cur = GeoMath.offsetPoint(cur, hdg, 8.0)
        sPts += cur
      }
    }
    repeat(6) {
      cur = GeoMath.offsetPoint(cur, hdg, 12.0)
      sPts += cur
    }
    val sWay = OsmWay(2L, sPts.indices.map { 1000L + it }, sPts, OsmTags(highway = "secondary", nameEn = "Canyon Road"))
    val gS = graphFrom(listOf(sWay))
    val mS = MapMatcher.match(gS, GeoMath.offsetPoint(sPts[0], 0.0, 15.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val resS = AheadGraphAnalyzer.analyzeAheadDetailed(gS, mS, TravelMode.DRIVING, 15.0)
    val winding = resS.features.firstOrNull { it.type == FeatureType.WINDING_ROAD }
    assertNotNull("Expected 3+ alternating curves within 250m to collapse into WINDING_ROAD", winding)
    assertTrue((winding!!.windingCurveCount ?: 0) >= 3)
  }

  @Test
  fun scenario3_realMedianOpeningUTurnLinkIsClassifiedAsUTurn() {
    val a0 = GeoPoint(30.0000, 31.0000)
    val a1 = GeoMath.offsetPoint(a0, 0.0, 150.0)
    val a2 = GeoMath.offsetPoint(a0, 0.0, 400.0)
    val b2 = GeoMath.offsetPoint(a2, 270.0, 14.0)
    val b1 = GeoMath.offsetPoint(a1, 270.0, 14.0)
    val b0 = GeoMath.offsetPoint(a0, 270.0, 14.0)

    val g = graphFrom(
      listOf(
        OsmWay(1L, listOf(1L, 2L, 3L), listOf(a0, a1, a2), OsmTags(highway = "primary", oneway = "yes", nameEn = "Axis NB")),
        OsmWay(2L, listOf(13L, 12L, 11L), listOf(b2, b1, b0), OsmTags(highway = "primary", oneway = "yes", nameEn = "Axis SB")),
        OsmWay(3L, listOf(2L, 12L), listOf(a1, b1), OsmTags(highway = "primary_link", oneway = "yes")),
      ),
    )
    val m = MapMatcher.match(g, GeoMath.offsetPoint(a0, 0.0, 30.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 18.0)
    assertTrue(
      "Median opening link connecting to opposite carriageway must be U_TURN or MEDIAN_OPENING",
      res.features.any { it.type == FeatureType.U_TURN || it.type == FeatureType.MEDIAN_OPENING },
    )
  }

  @Test
  fun scenario4_sixUnnamedResidentialSideStreetsVisibleWithin500m() {
    val origin = GeoPoint(30.0000, 31.0000)
    val mainPts = mutableListOf(origin)
    val mainIds = mutableListOf(1L)
    val extraWays = mutableListOf<OsmWay>()
    val dists = listOf(80.0, 150.0, 220.0, 300.0, 380.0, 460.0)
    dists.forEachIndexed { idx, d ->
      val jPt = GeoMath.offsetPoint(origin, 0.0, d)
      val jId = 10L + idx
      mainPts += jPt
      mainIds += jId
      val dir = if (idx < 3) 270.0 else 90.0
      val endPt = GeoMath.offsetPoint(jPt, dir, 120.0)
      extraWays += OsmWay(100L + idx, listOf(jId, 1000L + idx), listOf(jPt, endPt), OsmTags(highway = "residential"))
    }
    mainPts += GeoMath.offsetPoint(origin, 0.0, 700.0)
    mainIds += 99L

    val g = graphFrom(listOf(OsmWay(1L, mainIds, mainPts, OsmTags(highway = "primary", nameEn = "Main Arterial"))) + extraWays)
    val m = MapMatcher.match(g, GeoMath.offsetPoint(origin, 0.0, 10.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 15.0, showMinorJunctions = false)
    val visibleWayIds = res.features.flatMap { it.branches }.map { it.wayId }.toSet()
    for (idx in dists.indices) {
      assertTrue("Unnamed residential side street ${100L + idx} within 600m must stay visible", visibleWayIds.contains(100L + idx))
    }
  }

  @Test
  fun scenario5_dividedRoadPreservesFarSideStreetsAcrossMedianAsIndirect() {
    val a0 = GeoPoint(30.0000, 31.0000)
    val a1 = GeoMath.offsetPoint(a0, 0.0, 200.0)
    val a2 = GeoMath.offsetPoint(a0, 0.0, 500.0)
    val b0 = GeoMath.offsetPoint(a0, 270.0, 16.0)
    val b1 = GeoMath.offsetPoint(a1, 270.0, 16.0)
    val b2 = GeoMath.offsetPoint(a2, 270.0, 16.0)
    val farSideEnd = GeoMath.offsetPoint(b1, 270.0, 120.0)

    val g = graphFrom(
      listOf(
        OsmWay(1L, listOf(1L, 2L, 3L), listOf(a0, a1, a2), OsmTags(highway = "primary", oneway = "yes", nameEn = "Corridor NB")),
        OsmWay(2L, listOf(13L, 12L, 11L), listOf(b2, b1, b0), OsmTags(highway = "primary", oneway = "yes", nameEn = "Corridor SB")),
        OsmWay(3L, listOf(12L, 21L), listOf(b1, farSideEnd), OsmTags(highway = "secondary", nameEn = "Far Side St")),
      ),
    )
    val m = MapMatcher.match(g, GeoMath.offsetPoint(a0, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 15.0)
    val acrossBranch = res.features.flatMap { it.branches }.firstOrNull { it.wayId == 3L }
    assertNotNull("Far-side street on opposite carriageway must not be dropped", acrossBranch)
    assertTrue("Far-side street must be flagged isAcrossMedian", acrossBranch!!.isAcrossMedian)
    assertEquals(RelativeDirection.LEFT, acrossBranch.relativeDirection)
  }

  @Test
  fun scenario6_interchangeExcludesUnreachableFarLinkAndLogsIt() {
    val p0 = GeoPoint(30.0000, 31.0000)
    val p1 = GeoMath.offsetPoint(p0, 0.0, 200.0)
    val p2 = GeoMath.offsetPoint(p0, 0.0, 300.0)
    val p3 = GeoMath.offsetPoint(p0, 0.0, 500.0)
    val ramp1End = GeoMath.offsetPoint(p1, 55.0, 160.0)
    val ramp2End = GeoMath.offsetPoint(p2, 305.0, 160.0)
    // Unreachable disconnected link ~120 m East of corridor
    val farLinkStart = GeoMath.offsetPoint(p1, 90.0, 120.0)
    val farLinkEnd = GeoMath.offsetPoint(farLinkStart, 0.0, 140.0)

    val g = graphFrom(
      listOf(
        OsmWay(1L, listOf(1L, 2L), listOf(p0, p1), OsmTags(highway = "motorway")),
        OsmWay(2L, listOf(2L, 3L), listOf(p1, p2), OsmTags(highway = "motorway", bridge = "yes", layer = "1")),
        OsmWay(3L, listOf(3L, 4L), listOf(p2, p3), OsmTags(highway = "motorway")),
        OsmWay(10L, listOf(2L, 10L), listOf(p1, ramp1End), OsmTags(highway = "motorway_link", oneway = "yes")),
        OsmWay(11L, listOf(3L, 11L), listOf(p2, ramp2End), OsmTags(highway = "motorway_link", oneway = "yes")),
        OsmWay(999L, listOf(90L, 91L), listOf(farLinkStart, farLinkEnd), OsmTags(highway = "motorway_link")),
      ),
    )
    val m = MapMatcher.match(g, GeoMath.offsetPoint(p0, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 20.0)
    val ic = res.features.firstOrNull { it.isInterchangeGroup() }
    assertNotNull(ic)
    assertFalse("Unreachable link 999 must not enter interchange branches", ic!!.branches.any { it.wayId == 999L })
    assertTrue("Expected unreachable link log entry", res.nearbyUnreachableLinks.any { it.contains("999") && it.contains("nearby, not reachable") })
  }

  @Test
  fun scenario7_frontageRoadStaysFrontageNotInterchange() {
    val m1 = GeoPoint(30.0000, 31.0000)
    val m2 = GeoMath.offsetPoint(m1, 0.0, 120.0)
    val m3 = GeoMath.offsetPoint(m1, 0.0, 450.0)
    val f1 = GeoMath.offsetPoint(m1, 90.0, 15.0)
    val f2 = GeoMath.offsetPoint(m2, 90.0, 15.0)
    val f3 = GeoMath.offsetPoint(m3, 90.0, 15.0)
    val g = graphFrom(
      listOf(
        OsmWay(1L, listOf(1L, 2L, 3L), listOf(m1, m2, m3), OsmTags(highway = "primary", nameEn = "Ring Road")),
        OsmWay(2L, listOf(11L, 12L, 13L), listOf(f1, f2, f3), OsmTags(highway = "service")),
        OsmWay(3L, listOf(2L, 12L), listOf(m2, f2), OsmTags(highway = "service")),
      ),
    )
    val m = MapMatcher.match(g, GeoMath.offsetPoint(m1, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 18.0)
    assertTrue(res.features.any { it.type == FeatureType.FRONTAGE_ROAD })
    assertFalse(res.features.any { it.isInterchangeGroup() })
  }

  @Test
  fun scenario8_allInterchangeBranchPinsStartWithin5mOfMainCorridor() {
    val p0 = GeoPoint(30.0000, 31.0000)
    val p1 = GeoMath.offsetPoint(p0, 0.0, 200.0)
    val p2 = GeoMath.offsetPoint(p0, 0.0, 320.0)
    val p3 = GeoMath.offsetPoint(p0, 0.0, 500.0)
    val r1 = GeoMath.offsetPoint(p1, 50.0, 120.0)
    val r2 = GeoMath.offsetPoint(p2, 310.0, 120.0)
    val g = graphFrom(
      listOf(
        OsmWay(1L, listOf(1L, 2L), listOf(p0, p1), OsmTags(highway = "motorway")),
        OsmWay(2L, listOf(2L, 3L), listOf(p1, p2), OsmTags(highway = "motorway", bridge = "yes", layer = "1")),
        OsmWay(3L, listOf(3L, 4L), listOf(p2, p3), OsmTags(highway = "motorway")),
        OsmWay(10L, listOf(2L, 10L), listOf(p1, r1), OsmTags(highway = "motorway_link", oneway = "yes")),
        OsmWay(11L, listOf(3L, 11L), listOf(p2, r2), OsmTags(highway = "motorway_link", oneway = "yes")),
      ),
    )
    val m = MapMatcher.match(g, GeoMath.offsetPoint(p0, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 20.0)
    val ic = res.features.first { it.isInterchangeGroup() }
    for (br in ic.branches) {
      val dep = br.departurePoint ?: br.worldPoints.first()
      val minDist = GeoMath.snapToPolyline(dep, res.corridorWorldPoints).distanceMeters
      assertTrue("Branch ${br.badgeId} departure pin $minDist m must lie within 5m of corridor", minDist <= 5.0)
    }
  }

  @Test
  fun scenario9_bridgeAndFourWayJunctionInsideInterchangeAreNeverHidden() {
    val p0 = GeoPoint(30.0000, 31.0000)
    val p1 = GeoMath.offsetPoint(p0, 0.0, 200.0)
    val p2 = GeoMath.offsetPoint(p0, 0.0, 300.0)
    val p3 = GeoMath.offsetPoint(p0, 0.0, 420.0)
    val p4 = GeoMath.offsetPoint(p0, 0.0, 600.0)
    val g = graphFrom(
      listOf(
        OsmWay(1L, listOf(1L, 2L), listOf(p0, p1), OsmTags(highway = "trunk")),
        OsmWay(2L, listOf(2L, 3L), listOf(p1, p2), OsmTags(highway = "trunk", bridge = "yes", layer = "1")),
        OsmWay(3L, listOf(3L, 4L, 5L), listOf(p2, p3, p4), OsmTags(highway = "trunk")),
        OsmWay(10L, listOf(2L, 10L), listOf(p1, GeoMath.offsetPoint(p1, 55.0, 120.0)), OsmTags(highway = "trunk_link", oneway = "yes")),
        OsmWay(11L, listOf(4L, 11L), listOf(p3, GeoMath.offsetPoint(p3, 305.0, 120.0)), OsmTags(highway = "trunk_link", oneway = "yes")),
        // 4-way at p2
        OsmWay(20L, listOf(20L, 3L, 21L), listOf(GeoMath.offsetPoint(p2, 270.0, 140.0), p2, GeoMath.offsetPoint(p2, 90.0, 140.0)), OsmTags(highway = "secondary", nameEn = "Cross Blvd")),
      ),
    )
    val m = MapMatcher.match(g, GeoMath.offsetPoint(p0, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 20.0)
    assertTrue(res.features.any { it.isInterchangeGroup() })
    assertTrue("BRIDGE inside interchange span must stay visible", res.features.any { it.type == FeatureType.BRIDGE })
    assertTrue("FOUR_WAY junction inside interchange span must stay visible", res.features.any { it.type == FeatureType.FOUR_WAY })
  }

  @Test
  fun scenario10_eightRampInterchangeUsesGenericInterchangeSortedWithinCaps() {
    val p0 = GeoPoint(30.0000, 31.0000)
    val pts = mutableListOf(p0)
    val ids = mutableListOf(1L)
    val ways = mutableListOf<OsmWay>()
    for (i in 1..8) {
      val dist = 120.0 + (i - 1) * 45.0
      val nodePt = GeoMath.offsetPoint(p0, 0.0, dist)
      val nid = 10L + i
      pts += nodePt
      ids += nid
      val dir = if (i % 2 == 0) 60.0 else 300.0
      ways += OsmWay(100L + i, listOf(nid, 500L + i), listOf(nodePt, GeoMath.offsetPoint(nodePt, dir, 130.0)), OsmTags(highway = "motorway_link", oneway = "yes"))
    }
    val pEnd = GeoMath.offsetPoint(p0, 0.0, 700.0)
    val middleBridgeStart = pts[4]
    val middleBridgeEnd = pts[5]
    ways += OsmWay(1L, ids.subList(0, 5), pts.subList(0, 5), OsmTags(highway = "motorway"))
    ways += OsmWay(2L, listOf(ids[4], ids[5]), listOf(middleBridgeStart, middleBridgeEnd), OsmTags(highway = "motorway", bridge = "yes", layer = "1"))
    ways += OsmWay(3L, ids.subList(5, ids.size) + listOf(99L), pts.subList(5, pts.size) + listOf(pEnd), OsmTags(highway = "motorway"))

    val g = graphFrom(ways)
    val m = MapMatcher.match(g, GeoMath.offsetPoint(p0, 0.0, 10.0), 0.0, TravelMode.DRIVING, null).primary!!
    AheadGraphAnalyzer.clearCompositeCache()
    val res = AheadGraphAnalyzer.analyzeAheadDetailed(g, m, TravelMode.DRIVING, 20.0)
    val ic = res.features.firstOrNull { it.isInterchangeGroup() }
    assertNotNull(ic)
    assertEquals("8-ramp (>6) interchange must classify as generic INTERCHANGE", FeatureType.INTERCHANGE, ic!!.effectiveDisplayType())
    assertTrue(ic.branches.size > 6)
    assertNotNull(ic.rangeEndDistanceMeters)
    assertTrue(ic.rangeEndDistanceMeters!! > ic.distanceMeters)
    // Verify branches are sorted ascending by distance
    val dists = ic.branches.map { it.distanceMeters }
    assertEquals(dists.sorted(), dists)
    // Schematic 250 m filter and map 400 m / 4-pin filter checks
    val schematicBranches = ic.branches.filter { (it.distanceMeters - ic.distanceMeters) <= 250.0 }
    val mapCompactPins = ic.branches.sortedBy { it.distanceMeters }.filter { it.distanceMeters <= 400.0 }.take(4)
    assertTrue(schematicBranches.size < ic.branches.size)
    assertEquals(4, mapCompactPins.size)
  }
}
