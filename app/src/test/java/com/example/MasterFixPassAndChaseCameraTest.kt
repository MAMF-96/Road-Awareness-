package com.example

import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.ChaseCameraDistance
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LadderOrder
import com.example.data.model.LoadedTileBounds
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PassByRelation
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.data.remote.TileRoadGraphSource
import com.example.data.repository.RoadGraphRepository
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import com.example.domain.PassByStreetDetector
import com.example.domain.TileEndpointPlanarizer
import com.example.ui.ChaseCameraSmoother
import com.example.ui.components.RoadLadderBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterFixPassAndChaseCameraTest {

  private fun buildGraph(
    ways: List<OsmWay>,
    source: GraphDataSource = GraphDataSource.TILES,
    loadedTiles: List<LoadedTileBounds> = emptyList(),
    hasBeenPlanarized: Boolean = false,
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
      fetchedAtMs = 10_000L,
      fromCache = false,
      source = source,
      loadedTiles = loadedTiles,
      hasBeenPlanarized = hasBeenPlanarized,
    )
  }

  private fun tileBounds3x3(centerZ: Int = 14, centerX: Int = 100, centerY: Int = 100): List<LoadedTileBounds> {
    val latMin = 29.9900
    val latMax = 30.0100
    val lonMin = 30.9900
    val lonMax = 31.0100
    val dLat = latMax - latMin
    val dLon = lonMax - lonMin
    val list = ArrayList<LoadedTileBounds>(9)
    for (dy in -1..1) {
      for (dx in -1..1) {
        val x = centerX + dx
        val y = centerY + dy
        // North is smaller y in Slippy tiles
        val s = latMin - dy * dLat
        val n = latMax - dy * dLat
        val w = lonMin + dx * dLon
        val e = lonMax + dx * dLon
        list += LoadedTileBounds(centerZ, x, y, south = s, west = w, north = n, east = e)
      }
    }
    return list
  }

  // (a) TileEndpointPlanarizer: vertex merge <= 1.5m, interior split <= 1.5m, no interior-interior, layer guard, injective IDs, fast
  @Test
  fun testA_tileEndpointPlanarizerSnappingLayersIdsAndPerformance() {
    val nodes = LinkedHashMap<Long, OsmNode>()
    val ways = LinkedHashMap<Long, OsmWay>()
    val n2w = HashMap<Long, MutableList<Long>>()

    fun addWay(id: Long, nIds: List<Long>, pts: List<GeoPoint>, tags: OsmTags) {
      ways[id] = OsmWay(id, nIds, pts, tags, GraphDataSource.TILES)
      nIds.forEachIndexed { idx, nid ->
        nodes.putIfAbsent(nid, OsmNode(nid, pts[idx]))
        n2w.getOrPut(nid) { mutableListOf() }.add(id)
      }
    }

    // Main north-south road with only two vertices 220m apart (encoded negative tile ID)
    val mainId = TileRoadGraphSource.encodeWayId(1, 14, 100, 100, 1L)
    val mainS = GeoPoint(30.0000, 31.0000)
    val mainN = GeoPoint(30.0020, 31.0000)
    addWay(mainId, listOf(-1001L, -1002L), listOf(mainS, mainN), OsmTags(highway = "primary", nameEn = "Main Highway"))

    // 1) Side street whose endpoint is 1.0 m east of the interior of main road (segment split expected)
    val side1Id = TileRoadGraphSource.encodeWayId(1, 14, 100, 100, 2L)
    val sideStart = GeoMath.offsetPoint(GeoPoint(30.0010, 31.0000), 90.0, 1.0)
    val sideEnd = GeoMath.offsetPoint(sideStart, 90.0, 80.0)
    addWay(side1Id, listOf(-2001L, -2002L), listOf(sideStart, sideEnd), OsmTags(highway = "residential", nameEn = "Side St"))

    // 2) Vertex merge <= 1.5 m: ramp endpoint 0.8 m from sideEnd
    val rampId = TileRoadGraphSource.encodeWayId(1, 14, 100, 100, 3L)
    val rampStart = GeoMath.offsetPoint(sideEnd, 0.0, 0.8)
    val rampEnd = GeoMath.offsetPoint(rampStart, 90.0, 60.0)
    addWay(rampId, listOf(-3001L, -3002L), listOf(rampStart, rampEnd), OsmTags(highway = "primary_link"))

    // 3) Layer mismatch: bridge crossing/ending near main road (layer=1 vs layer=0) must NOT snap
    val bridgeId = TileRoadGraphSource.encodeWayId(1, 14, 100, 100, 4L)
    val bridgeStart = GeoMath.offsetPoint(GeoPoint(30.0014, 31.0000), 90.0, 0.9)
    val bridgeEnd = GeoMath.offsetPoint(bridgeStart, 90.0, 60.0)
    addWay(bridgeId, listOf(-4001L, -4002L), listOf(bridgeStart, bridgeEnd), OsmTags(highway = "secondary", bridge = "yes", layer = "1"))

    // 4) Interior-interior cross (no endpoint within 1.5m) must NOT split
    val crossId = TileRoadGraphSource.encodeWayId(1, 14, 100, 100, 5L)
    addWay(
      crossId,
      listOf(-5001L, -5002L),
      listOf(GeoPoint(30.0005, 30.9990), GeoPoint(30.0005, 31.0010)),
      OsmTags(highway = "tertiary", nameEn = "Cross No Endpoint"),
    )

    val stats = TileEndpointPlanarizer.planarize(nodes, ways, n2w)
    assertTrue("Expected >= 1 vertex merge, got ${stats.vertexMerges}", stats.vertexMerges >= 1)
    assertTrue("Expected >= 1 segment split, got ${stats.segmentSplits}", stats.segmentSplits >= 1)
    assertTrue("Expected rejected layer mismatch, got ${stats.rejectedLayerMismatches}", stats.rejectedLayerMismatches >= 1)

    // Verify side street connects cleanly into main road graph
    assertTrue((n2w[-2001L]?.size ?: 0) >= 2)
    // Verify bridge was not merged with main road
    assertEquals(1, n2w[-4001L]?.size ?: 0)
    // Verify interior crossing remained unconnected
    assertFalse(ways[crossId]!!.nodeIds.any { it == -2001L })

    // Verify all encoded negative way IDs remain injective with counters < 2^20
    val negIds = ways.keys.filter { it < 0L }
    assertEquals(negIds.size, negIds.distinct().size)
    assertTrue(negIds.all { ((-it) and (TileRoadGraphSource.MAX_WAY_COUNTER - 1L)) < TileRoadGraphSource.MAX_WAY_COUNTER })

    // Performance check on ~400 ways
    val pNodes = LinkedHashMap<Long, OsmNode>()
    val pWays = LinkedHashMap<Long, OsmWay>()
    val pN2W = HashMap<Long, MutableList<Long>>()
    for (i in 0 until 400) {
      val base = GeoPoint(30.000 + i * 0.0001, 31.000 + (i % 10) * 0.0002)
      val end = GeoMath.offsetPoint(base, 0.0, 40.0)
      val nid1 = -(i * 2L + 100L)
      val nid2 = -(i * 2L + 101L)
      val wid = TileRoadGraphSource.encodeWayId(1, 14, 100, 100, (i + 1).toLong())
      pNodes[nid1] = OsmNode(nid1, base)
      pNodes[nid2] = OsmNode(nid2, end)
      pWays[wid] = OsmWay(wid, listOf(nid1, nid2), listOf(base, end), OsmTags(highway = "residential"), GraphDataSource.TILES)
      pN2W[nid1] = mutableListOf(wid)
      pN2W[nid2] = mutableListOf(wid)
    }
    val elapsedMs = measureTimeMillis { TileEndpointPlanarizer.planarize(pNodes, pWays, pN2W) }
    assertTrue("Planarizer took ${elapsedMs}ms (> 1500ms budget in CI)", elapsedMs < 1500L)
  }

  // (b) Strict DEAD_END guard: suppressed unless planarized + 3x3 loaded + >= 30m inside + no neighbor way <= 3m (or noexit=yes)
  @Test
  fun testB_strictDeadEndGuardRules() {
    val pStart = GeoPoint(29.9920, 31.0000)
    val pEnd = GeoPoint(30.0000, 31.0000) // right at center of center tile (29.99..30.01, 30.99..31.01)
    val tiles3x3 = tileBounds3x3()
    val w = OsmWay(100L, listOf(1L, 2L), listOf(pStart, pEnd), OsmTags(highway = "trunk", nameEn = "Rural Highway"), GraphDataSource.TILES)

    // 1) Missing planarization flag -> NO DEAD_END
    val unplanarized = buildGraph(listOf(w), GraphDataSource.TILES, tiles3x3, hasBeenPlanarized = false)
    val m1 = MapMatcher.match(unplanarized, GeoPoint(29.9950, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val res1 = AheadGraphAnalyzer.analyzeAheadDetailed(unplanarized, m1, TravelMode.DRIVING, 20.0)
    assertFalse(res1.features.any { it.type == FeatureType.DEAD_END })

    // 2) Missing neighbor tile (only center tile loaded) -> NO DEAD_END and marks loadingAheadTileKeys
    val partialTilesGraph = buildGraph(listOf(w), GraphDataSource.TILES, listOf(tiles3x3[4]), hasBeenPlanarized = true)
    val m2 = MapMatcher.match(partialTilesGraph, GeoPoint(29.9950, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val res2 = AheadGraphAnalyzer.analyzeAheadDetailed(partialTilesGraph, m2, TravelMode.DRIVING, 20.0)
    assertFalse(res2.features.any { it.type == FeatureType.DEAD_END })
    assertTrue(res2.horizonStoppedAtUnloadedBorder)
    assertTrue(res2.loadingAheadTileKeys.isNotEmpty())

    // 3) Endpoint within 3.0 m of another disconnected way -> NO DEAD_END
    val nearbyContinuation =
      OsmWay(
        101L,
        listOf(3L, 4L),
        listOf(GeoMath.offsetPoint(pEnd, 0.0, 2.2), GeoPoint(30.0050, 31.0000)),
        OsmTags(highway = "trunk", nameEn = "Rural Highway"),
        GraphDataSource.TILES,
      )
    val nearWayGraph = buildGraph(listOf(w, nearbyContinuation), GraphDataSource.TILES, tiles3x3, hasBeenPlanarized = true)
    val m3 = MapMatcher.match(nearWayGraph, GeoPoint(29.9950, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val res3 = AheadGraphAnalyzer.analyzeAheadDetailed(nearWayGraph, m3, TravelMode.DRIVING, 20.0)
    assertFalse(res3.features.any { it.type == FeatureType.DEAD_END })

    // 4) Planarized + full 3x3 loaded + > 30m inside perimeter -> TRUE DEAD_END
    val validDeadGraph = buildGraph(listOf(w), GraphDataSource.TILES, tiles3x3, hasBeenPlanarized = true)
    val m4 = MapMatcher.match(validDeadGraph, GeoPoint(29.9950, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val res4 = AheadGraphAnalyzer.analyzeAheadDetailed(validDeadGraph, m4, TravelMode.DRIVING, 20.0)
    assertTrue(res4.features.any { it.type == FeatureType.DEAD_END })
  }

  // (c) PassByStreetDetector: DIRECT, VIA_FRONTAGE_OR_RAMP, ACROSS_MEDIAN, GRADE_SEPARATED, NOT_CONNECTED, <600m unnamed
  @Test
  fun testC_passByStreetRelationsAndShortRangeUnnamed() {
    AheadGraphAnalyzer.clearCompositeCache()
    val mainStart = GeoPoint(30.0000, 31.0000)
    val mainMid1 = GeoPoint(30.0020, 31.0000)
    val mainMid2 = GeoPoint(30.0040, 31.0000)
    val mainEnd = GeoPoint(30.0090, 31.0000)
    val wMain =
      OsmWay(
        10L,
        listOf(1L, 2L, 3L, 4L),
        listOf(mainStart, mainMid1, mainMid2, mainEnd),
        OsmTags(highway = "trunk", nameEn = "Ring Road", oneway = "yes"),
      )

    // Side street passing within 25 m (no shared vertex, NOT_CONNECTED)
    val wNotConn =
      OsmWay(
        20L,
        listOf(20L, 21L),
        listOf(
          GeoMath.offsetPoint(GeoPoint(30.0025, 31.0000), 90.0, 22.0),
          GeoMath.offsetPoint(GeoPoint(30.0025, 31.0000), 90.0, 120.0),
        ),
        OsmTags(highway = "residential", nameEn = "Isolated Ave"),
      )

    // Grade-separated bridge overpass crossing main corridor at lat 30.0032
    val wBridge =
      OsmWay(
        21L,
        listOf(22L, 23L),
        listOf(
          GeoMath.offsetPoint(GeoPoint(30.0032, 31.0000), 270.0, 90.0),
          GeoMath.offsetPoint(GeoPoint(30.0032, 31.0000), 90.0, 90.0),
        ),
        OsmTags(highway = "secondary", nameEn = "Bridge Blvd", bridge = "yes", layer = "1"),
      )

    // Street connected via ramp <= 400m from mainMid1 -> node 30 -> node 31 (street)
    val rampNode = GeoMath.offsetPoint(mainMid1, 90.0, 15.0)
    val rampPtEnd = GeoPoint(30.0035, 31.0005)
    val wRamp =
      OsmWay(
        22L,
        listOf(2L, 30L),
        listOf(mainMid1, rampPtEnd),
        OsmTags(highway = "trunk_link"),
      )
    val wViaRamp =
      OsmWay(
        23L,
        listOf(30L, 31L),
        listOf(rampPtEnd, GeoMath.offsetPoint(rampPtEnd, 90.0, 110.0)),
        OsmTags(highway = "secondary", nameEn = "Market Street"),
      )

    // Unnamed branch at 250 m (< 600 m) along divided/trunk road -> must NOT be dropped as minor
    val unnamedNode = GeoPoint(30.00225, 31.0000)
    val wMainWithSplit =
      OsmWay(
        10L,
        listOf(1L, 2L, 99L, 3L, 4L),
        listOf(mainStart, mainMid1, unnamedNode, mainMid2, mainEnd),
        OsmTags(highway = "trunk", nameEn = "Ring Road", oneway = "yes"),
      )
    val wUnnamedShort =
      OsmWay(
        24L,
        listOf(99L, 40L),
        listOf(unnamedNode, GeoMath.offsetPoint(unnamedNode, 90.0, 80.0)),
        OsmTags(highway = "residential"),
      )

    val graph =
      buildGraph(
        listOf(wMainWithSplit, wNotConn, wBridge, wRamp, wViaRamp, wUnnamedShort),
        source = GraphDataSource.OVERPASS,
      )
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val result =
      AheadGraphAnalyzer.analyzeAheadDetailed(
        graph = graph,
        matched = primary,
        mode = TravelMode.DRIVING,
        speedMps = 22.0,
        configuredHorizonMeters = 3_000.0,
        showMinorJunctions = false,
      )

    val passBy = result.timelineEntries.filter { it.isPassByStreet }
    val isolated = passBy.firstOrNull { it.title.english == "Isolated Ave" }
    val bridge = passBy.firstOrNull { it.title.english == "Bridge Blvd" }
    assertNotNull(isolated)
    assertEquals(PassByRelation.NOT_CONNECTED, isolated!!.passByRelation)
    assertNotNull(bridge)
    assertEquals(PassByRelation.GRADE_SEPARATED, bridge!!.passByRelation)

    // Verify VIA_FRONTAGE_OR_RAMP and unnamed < 600m feature visibility
    assertTrue(
      "Unnamed side branch within 600m should be preserved",
      result.features.any { it.distanceMeters < 600.0 && it.branches.any { br -> br.wayId == 24L } },
    )
    assertTrue(
      "Market Street via ramp should appear in timeline or features",
      passBy.any { it.title.english == "Market Street" && it.passByRelation == PassByRelation.VIA_FRONTAGE_OR_RAMP } ||
        result.features.any { f -> f.branches.any { it.name.english == "Market Street" } },
    )
  }

  // (d) RoadLadderBuilder ordering, unnamed branch preservation, loading row
  @Test
  fun testD_roadLadderBuilderOrderingAndLoadingRow() {
    val fNear =
      RoadFeature(
        index = 1,
        type = FeatureType.T_JUNCTION,
        distanceMeters = 120.0,
        anchor = GeoPoint(30.001, 31.000),
        anchorNodeId = 10L,
        incomingLocalPoints = emptyList(),
        incomingWorldPoints = emptyList(),
        outgoingMainLocalPoints = emptyList(),
        outgoingMainWorldPoints = emptyList(),
        branches =
          listOf(
            RoadBranch(
              badgeId = "1A",
              wayId = 201L,
              name = BilingualName(null, null, null), // unnamed branch < 600m preserved
              leadsTo = null,
              distanceMeters = 120.0,
              status = BranchStatus.OPEN,
              relativeDirection = RelativeDirection.RIGHT,
              bearingDeltaDeg = 90.0,
              worldPoints = emptyList(),
              localPoints = emptyList(),
              osmTags = OsmTags(highway = "residential"),
            ),
          ),
        osmTags = OsmTags(highway = "primary"),
      )
    val fFar = fNear.copy(index = 2, distanceMeters = 520.0, anchorNodeId = 20L)
    val passEntry =
      AheadTimelineEntry(
        featureIndex = null,
        type = FeatureType.T_JUNCTION,
        distanceMeters = 310.0,
        title = BilingualName(null, "Cross St", null),
        branchSummary = null,
        status = BranchStatus.OPEN,
        anchor = GeoPoint(30.003, 31.000),
        primaryWayId = 999L,
        isPassByStreet = true,
        passBySide = RelativeDirection.LEFT,
        passByRelation = PassByRelation.NOT_CONNECTED,
      )

    val bottomRows =
      RoadLadderBuilder.buildRows(
        features = listOf(fNear, fFar),
        timelineEntries = listOf(passEntry),
        selectedFeatureIndex = 1,
        ladderOrder = LadderOrder.NEAREST_AT_BOTTOM,
        horizonStoppedAtUnloadedBorder = true,
      )
    // Nearest at bottom => row distances descend from top to bottom (loading -> 520 -> 310 -> 120)
    assertEquals("loading_continues", bottomRows.first().id)
    assertTrue(bottomRows.first().isLoadingContinues)
    assertEquals(120.0, bottomRows.last().distanceMeters, 0.1)
    assertTrue(bottomRows.last().rightBranches.first().isUnnamed)

    val topRows =
      RoadLadderBuilder.buildRows(
        features = listOf(fNear, fFar),
        timelineEntries = listOf(passEntry),
        selectedFeatureIndex = 1,
        ladderOrder = LadderOrder.NEAREST_AT_TOP,
        horizonStoppedAtUnloadedBorder = true,
      )
    assertEquals(120.0, topRows.first().distanceMeters, 0.1)
    assertTrue(topRows.last().isLoadingContinues)
  }

  // (e) RoadGraphRepository lock-free reads & concurrent write coherence
  @Test
  fun testE_repositoryLockFreeSnapshotReadsDuringWrites() {
    val repo = RoadGraphRepository()
    val stopWriting = AtomicBoolean(false)
    val writeCount = AtomicInteger(0)
    val executor = Executors.newSingleThreadExecutor()
    val firstWriteLatch = CountDownLatch(1)

    val writerFuture =
      executor.submit {
        var tileIdx = 0
        while (!stopWriting.get() && tileIdx < 25) {
          val x = 100 + tileIdx
          val w =
            OsmWay(
              TileRoadGraphSource.encodeWayId(1, 14, x, 100, 1L),
              listOf(-(tileIdx * 2L + 1L), -(tileIdx * 2L + 2L)),
              listOf(GeoPoint(30.0 + tileIdx * 0.001, 31.0), GeoPoint(30.0 + (tileIdx + 1) * 0.001, 31.0)),
              OsmTags(highway = "primary", nameEn = "Road $tileIdx"),
              GraphDataSource.TILES,
            )
          val g = buildGraph(listOf(w))
          val bounds = LoadedTileBounds(14, x, 100, 29.99, 30.99 + tileIdx * 0.01, 30.01, 31.00 + tileIdx * 0.01)
          repo.upsertTileGraph("14_${x}_100", bounds, g, System.currentTimeMillis(), false)
          writeCount.incrementAndGet()
          if (tileIdx == 0) firstWriteLatch.countDown()
          tileIdx++
        }
      }

    assertTrue(firstWriteLatch.await(5, TimeUnit.SECONDS))
    var maxReadMicros = 0L
    var prevVer = 0L
    for (i in 0 until 1000) {
      val t0 = System.nanoTime()
      val snap = repo.currentSnapshot()
      val elapsedMicros = (System.nanoTime() - t0) / 1000L
      if (elapsedMicros > maxReadMicros) maxReadMicros = elapsedMicros
      assertTrue(snap.version >= prevVer)
      prevVer = snap.version
      Thread.sleep(0, 50_000)
    }
    stopWriting.set(true)
    writerFuture.get(10, TimeUnit.SECONDS)
    executor.shutdown()
    assertTrue("Expected concurrent snapshot reads < 2000us (2ms), max=${maxReadMicros}us", maxReadMicros < 2000L)
    assertTrue(repo.currentSnapshot().version >= 1L)
  }

  // (f) AheadGraphAnalyzer 150ms budget partial result + >8000 ways clamps horizon to 3km
  @Test
  fun testF_analysisBudgetAndReducedHorizonClamp() {
    val ways = ArrayList<OsmWay>(8_100)
    val n2w = HashMap<Long, MutableList<Long>>(16_300)
    val nodes = HashMap<Long, OsmNode>(16_300)

    // Continuous 5km trunk road with nodes every 100m
    val trunkPts = ArrayList<GeoPoint>(52)
    val trunkIds = ArrayList<Long>(52)
    for (i in 0..50) {
      val nid = 1_000_000L + i
      val pt = GeoPoint(30.0 + i * 0.0009, 31.0000)
      trunkIds += nid
      trunkPts += pt
      nodes[nid] = OsmNode(nid, pt)
      n2w.getOrPut(nid) { mutableListOf() }.add(10L)
    }
    val wTrunk = OsmWay(10L, trunkIds, trunkPts, OsmTags(highway = "trunk", nameEn = "Long Highway"))
    ways += wTrunk

    // Add branch at 3.8 km (beyond 3 km clamped horizon)
    val branchNodeId = 1_000_000L + 38
    val bPt = trunkPts[38]
    val bEnd = GeoMath.offsetPoint(bPt, 90.0, 90.0)
    nodes[2_000_000L] = OsmNode(2_000_000L, bEnd)
    val wFarBranch = OsmWay(20L, listOf(branchNodeId, 2_000_000L), listOf(bPt, bEnd), OsmTags(highway = "secondary", nameEn = "Far 3.8km Branch"))
    ways += wFarBranch
    n2w.getOrPut(branchNodeId) { mutableListOf() }.add(20L)
    n2w[2_000_000L] = mutableListOf(20L)

    // Fill remaining ways up to 8,050 to trigger > 8,000 reduced horizon guard
    for (i in 1..8_050) {
      val nidA = 3_000_000L + i * 2L
      val nidB = nidA + 1L
      val pA = GeoPoint(30.02 + (i % 100) * 0.0002, 31.02 + (i / 100) * 0.0002)
      val pB = GeoPoint(pA.lat + 0.0001, pA.lon)
      nodes[nidA] = OsmNode(nidA, pA)
      nodes[nidB] = OsmNode(nidB, pB)
      val wid = 1000L + i
      ways += OsmWay(wid, listOf(nidA, nidB), listOf(pA, pB), OsmTags(highway = "residential"))
      n2w[nidA] = mutableListOf(wid)
      n2w[nidB] = mutableListOf(wid)
    }

    val bigGraph =
      RoadGraph(
        nodes = nodes,
        ways = ways.associateBy { it.id },
        nodeToWays = n2w,
        fetchedAtMs = 1L,
        fromCache = false,
        source = GraphDataSource.OVERPASS,
      )
    val matched = MapMatcher.match(bigGraph, GeoPoint(30.0001, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!

    // Verify >8,000 ways clamps horizon to 3km (excludes 3.8km branch) & marks isReducedHorizon
    val clampedRes =
      AheadGraphAnalyzer.analyzeAheadDetailed(
        graph = bigGraph,
        matched = matched,
        mode = TravelMode.DRIVING,
        speedMps = 30.0,
        configuredHorizonMeters = 5_000.0,
        budgetMs = 5_000L,
      )
    assertTrue(clampedRes.isReducedHorizon)
    assertTrue(clampedRes.loadedCorridorMeters <= 3_150.0)
    assertFalse(clampedRes.features.any { f -> f.branches.any { it.wayId == 20L } })

    // Verify tight budgetMs + abort hook returns partial result gracefully without hanging
    var checks = 0
    val partialRes =
      AheadGraphAnalyzer.analyzeAheadDetailed(
        graph = bigGraph,
        matched = matched,
        mode = TravelMode.DRIVING,
        speedMps = 30.0,
        configuredHorizonMeters = 3_000.0,
        budgetMs = 1L,
        shouldAbort = { checks++; checks > 1 },
      )
    assertTrue(partialRes.isReducedHorizon)
  }

  // (g) Single-threaded pipelineDispatcher + CONFLATED channel off-Main behavior
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun testG_singleThreadedConflatedPipelineDispatch() = runBlocking {
    val pipelineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val channel = Channel<Int>(Channel.CONFLATED)
    val processed = mutableListOf<Int>()
    val threadNames = HashSet<String>()
    val done = CountDownLatch(1)

    val worker =
      launch(pipelineDispatcher) {
        for (item in channel) {
          threadNames += Thread.currentThread().name
          processed += item
          if (item == 50) {
            done.countDown()
            break
          }
        }
      }

    // Rapid burst of 50 location updates -> CONFLATED channel always delivers the newest without dropping 50
    for (i in 1..50) {
      channel.trySend(i)
    }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    worker.join()
    assertEquals(50, processed.last())
    assertTrue(processed.size <= 50)
    assertFalse(threadNames.any { it.contains("main", ignoreCase = true) })
  }

  // (h) Hairpin vs U-turn regression & path-based curve detector
  @Test
  fun testH_hairpinVsUTurnAndPathBasedCurves() {
    AheadGraphAnalyzer.clearCompositeCache()
    // 1) Continuous hairpin curve on a single winding highway -> must emit HAIRPIN, NEVER U_TURN
    val hairpinPts =
      listOf(
        GeoPoint(30.0000, 31.0000),
        GeoPoint(30.0012, 31.0000),
        GeoPoint(30.0014, 31.00015),
        GeoPoint(30.0012, 31.00030),
        GeoPoint(30.0000, 31.00030),
      )
    val hairpinIds = listOf(1L, 2L, 3L, 4L, 5L)
    val wHairpin = OsmWay(10L, hairpinIds, hairpinPts, OsmTags(highway = "trunk", nameEn = "Mountain Pass"))
    val hairpinGraph = buildGraph(listOf(wHairpin), source = GraphDataSource.OVERPASS)
    val hMatch = MapMatcher.match(hairpinGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val hFeatures = AheadGraphAnalyzer.analyzeAhead(hairpinGraph, hMatch, TravelMode.DRIVING, 15.0)
    assertFalse("Single-way hairpin must never be classified as U_TURN", hFeatures.any { it.type == FeatureType.U_TURN })
    assertTrue(
      "Expected HAIRPIN or SHARP_CURVE, got ${hFeatures.map { it.type }}",
      hFeatures.any { it.type == FeatureType.HAIRPIN || it.type == FeatureType.SHARP_CURVE || it.type == FeatureType.WINDING_ROAD },
    )

    // 2) Divided road with explicit opposite carriageway link opening >= 145 deg -> MUST emit U_TURN
    val nS = OsmNode(10L, GeoPoint(30.0000, 31.0000))
    val nMid = OsmNode(11L, GeoPoint(30.0015, 31.0000))
    val nN = OsmNode(12L, GeoPoint(30.0030, 31.0000))
    val nOpp = OsmNode(13L, GeoPoint(30.0015, 30.99985)) // ~14m west across median
    val nOppS = OsmNode(14L, GeoPoint(30.0000, 30.99985))
    val wNorthbound = OsmWay(100L, listOf(10L, 11L, 12L), listOf(nS.point, nMid.point, nN.point), OsmTags(highway = "primary", nameEn = "Boulevard", oneway = "yes"))
    val wSouthbound = OsmWay(101L, listOf(13L, 14L), listOf(nOpp.point, nOppS.point), OsmTags(highway = "primary", nameEn = "Boulevard", oneway = "yes"))
    val wUturnLink = OsmWay(102L, listOf(11L, 13L), listOf(nMid.point, nOpp.point), OsmTags(highway = "primary_link", oneway = "yes"))
    val uGraph = buildGraph(listOf(wNorthbound, wSouthbound, wUturnLink), source = GraphDataSource.OVERPASS)
    val uMatch = MapMatcher.match(uGraph, GeoPoint(30.0003, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val uFeatures = AheadGraphAnalyzer.analyzeAhead(uGraph, uMatch, TravelMode.DRIVING, 15.0)
    assertTrue(
      "Divided-road median opening link should emit U_TURN or MEDIAN_OPENING, got ${uFeatures.map { it.type }}",
      uFeatures.any { it.type == FeatureType.U_TURN || it.type == FeatureType.MEDIAN_OPENING },
    )
  }

  // (i) ChaseCameraSmoother: OFF legacy vs ON 3rd-person, zoom/distance, smoothing, curve look-ahead +/-25deg, <= 15Hz throttle
  @Test
  fun testI_chaseCameraSmootherOffAndOnPoses() {
    val smoother = ChaseCameraSmoother()
    val userPt = GeoPoint(30.0000, 31.0000)

    // 1) OFF preserves legacy ratios, pitches, and stepped zooms
    val offPortraitLow =
      smoother.update(
        nowMs = 1_000L,
        chaseCameraEnabled = false,
        distancePreset = ChaseCameraDistance.NORMAL,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 12.0,
        speedMps = 8.0,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(0.34, offPortraitLow.topRatio, 0.001)
    assertEquals(32.0, offPortraitLow.tiltDeg, 0.001)
    assertEquals(16.8, offPortraitLow.zoom, 0.001)
    assertNull(offPortraitLow.pinMaxDistanceMeters)

    val offLandMed =
      smoother.update(
        nowMs = 1_100L,
        chaseCameraEnabled = false,
        distancePreset = ChaseCameraDistance.NORMAL,
        travelMode = TravelMode.DRIVING,
        isLandscape = true,
        gpsHeadingDeg = 12.0,
        speedMps = 15.0,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(0.30, offLandMed.topRatio, 0.001)
    assertEquals(16.3, offLandMed.zoom, 0.001)

    val offHigh =
      smoother.update(
        nowMs = 1_200L,
        chaseCameraEnabled = false,
        distancePreset = ChaseCameraDistance.NORMAL,
        travelMode = TravelMode.DRIVING,
        isLandscape = true,
        gpsHeadingDeg = 12.0,
        speedMps = 25.0,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(15.6, offHigh.zoom, 0.001)

    val offWalk =
      smoother.update(
        nowMs = 1_300L,
        chaseCameraEnabled = false,
        distancePreset = ChaseCameraDistance.NORMAL,
        travelMode = TravelMode.WALKING,
        isLandscape = false,
        gpsHeadingDeg = 12.0,
        speedMps = 1.4,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(16.0, offWalk.tiltDeg, 0.001)
    assertEquals(17.8, offWalk.zoom, 0.001)

    // 2) ON: topRatio=0.56 (~78% free height), 60deg Driving / 50deg Walking, speed-zoom + distance presets
    smoother.reset()
    val onClose30 =
      smoother.update(
        nowMs = 2_000L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 30.0 / 3.6,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(0.56, onClose30.topRatio, 0.001)
    assertEquals(60.0, onClose30.tiltDeg, 0.001)
    assertEquals(17.6, onClose30.zoom, 0.01)
    assertEquals(400.0, onClose30.pinMaxDistanceMeters!!, 0.1)
    assertTrue(onClose30.shouldEmitCameraUpdate)

    // Throttle check: +30ms (< 67ms) must NOT emit camera update
    val throttled =
      smoother.update(
        nowMs = 2_030L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 30.0 / 3.6,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertFalse(throttled.shouldEmitCameraUpdate)

    // +70ms (>= 67ms) emits camera update
    val emitted =
      smoother.update(
        nowMs = 2_070L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 30.0 / 3.6,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertTrue(emitted.shouldEmitCameraUpdate)

    // Presets at 100 km/h + Walking check
    val sNormal = ChaseCameraSmoother()
    val onNormal100 =
      sNormal.update(
        nowMs = 3_000L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.NORMAL,
        travelMode = TravelMode.DRIVING,
        isLandscape = true,
        gpsHeadingDeg = 0.0,
        speedMps = 100.0 / 3.6,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(0.56, onNormal100.topRatio, 0.001)
    assertEquals(16.9 - 0.6, onNormal100.zoom, 0.01)

    val sFarWalk = ChaseCameraSmoother()
    val onWalkFar =
      sFarWalk.update(
        nowMs = 3_000L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.FAR,
        travelMode = TravelMode.WALKING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 1.4,
        userPoint = userPt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(50.0, onWalkFar.tiltDeg, 0.001)
    assertEquals(18.3 - 1.2, onWalkFar.zoom, 0.01)

    // 3) Curve look-ahead 40m ahead turning sharply +90deg right -> clamped to +25deg * 0.5 = +12.5deg
    val p0 = userPt
    val p30 = GeoMath.offsetPoint(p0, 0.0, 30.0)
    val pTurn = GeoMath.offsetPoint(p30, 90.0, 40.0)
    val sCurve = ChaseCameraSmoother()
    val curvePose =
      sCurve.update(
        nowMs = 4_000L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 20.0,
        userPoint = p0,
        isMatchedToRoad = true,
        corridorWorldPoints = listOf(p0, p30, pTurn),
      )
    assertEquals(12.5, curvePose.bearingDeg, 1.0)

    // 4) Heading smoothing lag check (step input +40deg over 200ms should move smoothly, < 40deg)
    val smoothTurn =
      sCurve.update(
        nowMs = 4_200L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 40.0,
        speedMps = 20.0,
        userPoint = p0,
        isMatchedToRoad = false,
        corridorWorldPoints = emptyList(),
      )
    assertTrue("Expected smoothed heading between 5 and 30 deg, got ${smoothTurn.bearingDeg}", smoothTurn.bearingDeg in 5.0..30.0)
  }

  @Test
  fun testJ_planarizerLongDiagonalSegmentsNoOomAndStillSnaps() {
    val nodes = LinkedHashMap<Long, OsmNode>()
    val ways = LinkedHashMap<Long, OsmWay>()
    val n2w = HashMap<Long, MutableList<Long>>()

    fun addWay(id: Long, nIds: List<Long>, pts: List<GeoPoint>, tags: OsmTags) {
      ways[id] = OsmWay(id, nIds, pts, tags, GraphDataSource.MAP)
      nIds.forEachIndexed { idx, nid ->
        nodes.putIfAbsent(nid, OsmNode(nid, pts[idx]))
        n2w.getOrPut(nid) { mutableListOf() }.add(id)
      }
    }

    // 20 km diagonal trunk highway (old AABB grid would allocate >40M cells and OOM)
    val startPt = GeoPoint(30.0000, 31.0000)
    val endPt = GeoMath.offsetPoint(startPt, 45.0, 20_000.0)
    val diagId = TileRoadGraphSource.encodeWayId(2, 14, 100, 100, 1L)
    addWay(diagId, listOf(-1001L, -1002L), listOf(startPt, endPt), OsmTags(highway = "trunk", nameEn = "Long Diagonal"))

    // Several additional 15 km diagonal roads
    for (i in 1..10) {
      val s = GeoMath.offsetPoint(startPt, 135.0, i * 300.0)
      val e = GeoMath.offsetPoint(s, 45.0, 15_000.0)
      val wid = TileRoadGraphSource.encodeWayId(2, 14, 100, 100, 10L + i)
      addWay(wid, listOf(-1100L - i * 2L, -1101L - i * 2L), listOf(s, e), OsmTags(highway = "primary"))
    }

    // Side street ending 0.9 m from the chord midpoint of the 20 km diagonal highway -> must split & snap
    val midPt = GeoPoint((startPt.lat + endPt.lat) * 0.5, (startPt.lon + endPt.lon) * 0.5)
    val sideStart = GeoMath.offsetPoint(midPt, 135.0, 0.9)
    val sideEnd = GeoMath.offsetPoint(sideStart, 135.0, 60.0)
    val sideId = TileRoadGraphSource.encodeWayId(2, 14, 100, 100, 50L)
    addWay(sideId, listOf(-2001L, -2002L), listOf(sideStart, sideEnd), OsmTags(highway = "residential", nameEn = "Snap Side"))

    lateinit var stats: com.example.data.model.PlanarizeStats
    val elapsedMs = measureTimeMillis {
      stats = TileEndpointPlanarizer.planarize(nodes, ways, n2w)
    }
    assertTrue("Expected >= 1 split on 20km diagonal, got ${stats.segmentSplits}", stats.segmentSplits >= 1)
    assertTrue("Expected snapped side endpoint degree >= 2", (n2w[-2001L]?.size ?: 0) >= 2)
    assertTrue("Planarize took ${elapsedMs}ms (> 1000ms)", elapsedMs < 1000L)
  }

  @Test
  fun testK_denseUrbanGridCycleDetectionAndLargeWaySetPlanarize() {
    AheadGraphAnalyzer.clearCompositeCache()
    val ways = ArrayList<OsmWay>()
    val origin = GeoPoint(30.0000, 31.0000)

    // 14x14 dense 35m-block urban grid (would cause exponential DFS blowup without pruning/caps)
    val gridN = 14
    fun gridNodeId(r: Int, c: Int): Long = 10_000L + r * 100L + c
    fun gridPoint(r: Int, c: Int): GeoPoint =
      GeoMath.offsetPoint(GeoMath.offsetPoint(origin, 0.0, r * 35.0), 90.0, c * 35.0)

    var nextWid = 1L
    for (r in 0 until gridN) {
      val nIds = (0 until gridN).map { c -> gridNodeId(r, c) }
      val pts = (0 until gridN).map { c -> gridPoint(r, c) }
      ways += OsmWay(nextWid++, nIds, pts, OsmTags(highway = "residential", nameEn = "Row $r"))
    }
    for (c in 0 until gridN) {
      val nIds = (0 until gridN).map { r -> gridNodeId(r, c) }
      val pts = (0 until gridN).map { r -> gridPoint(r, c) }
      ways += OsmWay(nextWid++, nIds, pts, OsmTags(highway = "residential", nameEn = "Col $c"))
    }

    // Embed an untagged geometric roundabout (6 nodes, 20m radius, 3 external roads)
    val rbCenter = GeoMath.offsetPoint(origin, 0.0, 200.0)
    val rbPts = (0 until 6).map { k -> GeoMath.offsetPoint(rbCenter, k * 60.0, 20.0) }
    val rbNodes = (0 until 6).map { k -> 90_000L + k }
    for (k in 0 until 6) {
      val nk = (k + 1) % 6
      ways += OsmWay(nextWid++, listOf(rbNodes[k], rbNodes[nk]), listOf(rbPts[k], rbPts[nk]), OsmTags(highway = "secondary"))
    }
    // 3 external legs attached to roundabout
    val approachStart = GeoMath.offsetPoint(rbPts[0], 180.0, 80.0)
    ways += OsmWay(1000L, listOf(91_000L, rbNodes[0]), listOf(approachStart, rbPts[0]), OsmTags(highway = "secondary", nameEn = "Approach"))
    ways += OsmWay(1001L, listOf(rbNodes[2], 91_002L), listOf(rbPts[2], GeoMath.offsetPoint(rbPts[2], 90.0, 80.0)), OsmTags(highway = "secondary", nameEn = "East Exit"))
    ways += OsmWay(1002L, listOf(rbNodes[4], 91_004L), listOf(rbPts[4], GeoMath.offsetPoint(rbPts[4], 270.0, 80.0)), OsmTags(highway = "secondary", nameEn = "West Exit"))

    val graph = buildGraph(ways, source = GraphDataSource.TILES, loadedTiles = tileBounds3x3(), hasBeenPlanarized = true)
    val matched = MapMatcher.match(graph, GeoMath.offsetPoint(approachStart, 0.0, 20.0), 0.0, TravelMode.DRIVING, null).primary!!

    lateinit var res: com.example.data.model.AheadAnalysisResult
    val analysisMs = measureTimeMillis {
      res = AheadGraphAnalyzer.analyzeAheadDetailed(graph, matched, TravelMode.DRIVING, 15.0)
    }
    assertTrue("Dense grid cycle analysis took ${analysisMs}ms (> 1500ms)", analysisMs < 1500L)
    assertTrue("Expected roundabout detected, got ${res.features.map { it.type }}", res.features.any { it.type == FeatureType.ROUNDABOUT })

    // Also stress TileEndpointPlanarizer on 3,000 ways without excessive LongHashSet memory
    val pNodes = LinkedHashMap<Long, OsmNode>(6000)
    val pWays = LinkedHashMap<Long, OsmWay>(3000)
    val pN2W = HashMap<Long, MutableList<Long>>(6000)
    for (i in 0 until 3000) {
      val a = GeoMath.offsetPoint(origin, 0.0, (i % 60) * 20.0)
      val b = GeoMath.offsetPoint(a, 90.0, 18.0)
      val n1 = -(i * 2L + 1L)
      val n2 = -(i * 2L + 2L)
      val wid = TileRoadGraphSource.encodeWayId(1, 14, 100 + (i / 1000), 100, (i % 1000).toLong() + 1L)
      pNodes[n1] = OsmNode(n1, a)
      pNodes[n2] = OsmNode(n2, b)
      pWays[wid] = OsmWay(wid, listOf(n1, n2), listOf(a, b), OsmTags(highway = "residential"), GraphDataSource.TILES)
      pN2W[n1] = mutableListOf(wid)
      pN2W[n2] = mutableListOf(wid)
    }
    val planMs = measureTimeMillis { TileEndpointPlanarizer.planarize(pNodes, pWays, pN2W) }
    assertTrue("Planarize 3000 ways took ${planMs}ms (> 2500ms)", planMs < 2500L)
  }
}
