package com.example

import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LoadedTileBounds
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.MapMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadClassificationPureTest {

  private fun graphFrom(
    nodes: List<OsmNode>,
    ways: List<OsmWay>,
    source: GraphDataSource = GraphDataSource.OVERPASS,
    loadedTiles: List<LoadedTileBounds> = emptyList(),
    hasBeenPlanarized: Boolean = false,
  ): RoadGraph {
    val nodeMap = nodes.associateBy { it.id }
    val wayMap = ways.associateBy { it.id }
    val nodeToWays = mutableMapOf<Long, MutableList<Long>>()
    for (w in ways) {
      for (nid in w.nodeIds.distinct()) {
        nodeToWays.getOrPut(nid) { mutableListOf() }.add(w.id)
      }
    }
    return RoadGraph(
      nodes = nodeMap,
      ways = wayMap,
      nodeToWays = nodeToWays,
      fetchedAtMs = 0L,
      fromCache = false,
      source = source,
      loadedTiles = loadedTiles,
      hasBeenPlanarized = hasBeenPlanarized,
    )
  }

  @Test
  fun multiWayAndDrivewayFilteringByMode() {
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0010, 31.0000))
    val n3 = OsmNode(3L, GeoPoint(30.0020, 31.0000))
    val n4 = OsmNode(4L, GeoPoint(30.0010, 30.9990))
    val n5 = OsmNode(5L, GeoPoint(30.0010, 31.0010))
    val n6 = OsmNode(6L, GeoPoint(30.0016, 30.9993))

    val w10 = OsmWay(10L, listOf(1L, 2L, 3L), listOf(n1.point, n2.point, n3.point), OsmTags(highway = "primary"))
    val w20 = OsmWay(20L, listOf(2L, 4L), listOf(n2.point, n4.point), OsmTags(highway = "secondary"))
    val w30 = OsmWay(30L, listOf(2L, 5L), listOf(n2.point, n5.point), OsmTags(highway = "secondary"))
    val w40 = OsmWay(40L, listOf(2L, 6L), listOf(n2.point, n6.point), OsmTags(highway = "service", service = "driveway"))

    val graph = graphFrom(listOf(n1, n2, n3, n4, n5, n6), listOf(w10, w20, w30, w40))
    val pos = GeoPoint(30.0002, 31.0000)

    val drivingMatch = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary
    assertNotNull(drivingMatch)
    val drivingFeatures = AheadGraphAnalyzer.analyzeAhead(graph, drivingMatch!!, TravelMode.DRIVING, 12.0)
    assertEquals(FeatureType.FOUR_WAY, drivingFeatures.first().type)
    assertEquals(2, drivingFeatures.first().branches.size)

    val walkingMatch = MapMatcher.match(graph, pos, 0.0, TravelMode.WALKING, null).primary
    assertNotNull(walkingMatch)
    val walkingFeatures = AheadGraphAnalyzer.analyzeAhead(graph, walkingMatch!!, TravelMode.WALKING, 1.4)
    assertEquals(FeatureType.MULTI_WAY, walkingFeatures.first().type)
    assertEquals(3, walkingFeatures.first().branches.size)
  }

  @Test
  fun geometricTileRoundaboutDetection() {
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n10 = OsmNode(10L, GeoPoint(30.0010, 31.0000))
    val n11 = OsmNode(11L, GeoPoint(30.0012, 31.00022))
    val n12 = OsmNode(12L, GeoPoint(30.0014, 31.0000))
    val n13 = OsmNode(13L, GeoPoint(30.0012, 30.99978))
    val n20 = OsmNode(20L, GeoPoint(30.0020, 31.0000))
    val n21 = OsmNode(21L, GeoPoint(30.0012, 31.0010))

    val ways = listOf(
      OsmWay(1L, listOf(1L, 10L), listOf(n1.point, n10.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
      OsmWay(2L, listOf(10L, 11L), listOf(n10.point, n11.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
      OsmWay(3L, listOf(11L, 12L), listOf(n11.point, n12.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
      OsmWay(4L, listOf(12L, 13L), listOf(n12.point, n13.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
      OsmWay(5L, listOf(13L, 10L), listOf(n13.point, n10.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
      OsmWay(6L, listOf(12L, 20L), listOf(n12.point, n20.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
      OsmWay(7L, listOf(11L, 21L), listOf(n11.point, n21.point), OsmTags(highway = "secondary"), GraphDataSource.TILES),
    )
    val graph = graphFrom(listOf(n1, n10, n11, n12, n13, n20, n21), ways, GraphDataSource.TILES)
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 12.0)
    assertTrue(features.any { it.type == FeatureType.ROUNDABOUT && it.ruleId == "geom_tile_roundabout" })
  }

  @Test
  fun rampsImpliedOnewayAndLinkMerge() {
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0010, 31.0000))
    val n3 = OsmNode(3L, GeoPoint(30.0020, 31.0000))
    val n4 = OsmNode(4L, GeoPoint(30.0015, 31.0005))
    val n5 = OsmNode(5L, GeoPoint(30.0005, 30.9995))

    val wMain = OsmWay(10L, listOf(1L, 2L, 3L), listOf(n1.point, n2.point, n3.point), OsmTags(highway = "motorway", nameEn = "Ring Rd"))
    val wOff = OsmWay(20L, listOf(2L, 4L), listOf(n2.point, n4.point), OsmTags(highway = "motorway_link", destination = "Airport"))
    val wOn = OsmWay(30L, listOf(5L, 2L), listOf(n5.point, n2.point), OsmTags(highway = "motorway_link"))

    val graph = graphFrom(listOf(n1, n2, n3, n4, n5), listOf(wMain, wOff, wOn))
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 20.0)
    assertEquals(FeatureType.OFF_RAMP, features.first().type)
    assertEquals(BranchStatus.BLOCKED, features.first().branches.first { it.wayId == 30L }.status)
    assertEquals(BranchStatus.OPEN, features.first().branches.first { it.wayId == 20L }.status)

    // Test user on trunk_link without oneway -> UNVERIFIED, or ending at main road -> MERGE
    val nA = OsmNode(101L, GeoPoint(30.0100, 31.0000))
    val nB = OsmNode(102L, GeoPoint(30.0110, 31.0000))
    val nC = OsmNode(103L, GeoPoint(30.0120, 31.0000))
    val wRamp = OsmWay(40L, listOf(101L, 102L), listOf(nA.point, nB.point), OsmTags(highway = "motorway_link"))
    val wTarget = OsmWay(41L, listOf(102L, 103L), listOf(nB.point, nC.point), OsmTags(highway = "motorway", nameEn = "Highway 55"))
    val mergeGraph = graphFrom(listOf(nA, nB, nC), listOf(wRamp, wTarget))
    val mergePrimary = MapMatcher.match(mergeGraph, GeoPoint(30.0102, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val mergeFeatures = AheadGraphAnalyzer.analyzeAhead(mergeGraph, mergePrimary, TravelMode.DRIVING, 20.0)
    assertEquals(FeatureType.MERGE, mergeFeatures.first().type)
  }

  @Test
  fun deadEndTileBorderVsCulDeSacVsNoexit() {
    val p1 = GeoPoint(30.0000, 31.0000)
    val p2 = GeoPoint(30.0010, 31.0000)
    val n1 = OsmNode(1L, p1)
    val n2 = OsmNode(2L, p2)
    val w1 = OsmWay(1L, listOf(1L, 2L), listOf(p1, p2), OsmTags(highway = "residential"), GraphDataSource.TILES)
    val bounds = listOf(LoadedTileBounds(14, 1, 1, south = 29.9900, west = 30.9900, north = 30.00101, east = 31.0100))

    val tileBorderGraph = graphFrom(listOf(n1, n2), listOf(w1), GraphDataSource.TILES, bounds)
    val pMatch = MapMatcher.match(tileBorderGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val borderFeatures = AheadGraphAnalyzer.analyzeAhead(tileBorderGraph, pMatch, TravelMode.DRIVING, 12.0)
    assertFalse(borderFeatures.any { it.type == FeatureType.DEAD_END })

    // 3x3 surrounding tiles loaded + planarized -> true DEAD_END
    val boundsWithNeighbor =
      listOf(
        LoadedTileBounds(14, 1, 1, south = 29.9900, west = 30.9900, north = 30.00101, east = 31.0100),
        LoadedTileBounds(14, 0, 1, south = 29.9900, west = 30.9700, north = 30.00101, east = 30.9900),
        LoadedTileBounds(14, 2, 1, south = 29.9900, west = 31.0100, north = 30.00101, east = 31.0300),
        LoadedTileBounds(14, 0, 0, south = 30.00101, west = 30.9700, north = 30.0200, east = 30.9900),
        LoadedTileBounds(14, 1, 0, south = 30.00101, west = 30.9900, north = 30.0200, east = 31.0100),
        LoadedTileBounds(14, 2, 0, south = 30.00101, west = 31.0100, north = 30.0200, east = 31.0300),
        LoadedTileBounds(14, 0, 2, south = 29.9700, west = 30.9700, north = 29.9900, east = 30.9900),
        LoadedTileBounds(14, 1, 2, south = 29.9700, west = 30.9900, north = 29.9900, east = 31.0100),
        LoadedTileBounds(14, 2, 2, south = 29.9700, west = 31.0100, north = 29.9900, east = 31.0300),
      )
    val loadedNeighborGraph =
      graphFrom(listOf(n1, n2), listOf(w1), GraphDataSource.TILES, boundsWithNeighbor, hasBeenPlanarized = true)
    val nMatch = MapMatcher.match(loadedNeighborGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val deadFeatures = AheadGraphAnalyzer.analyzeAhead(loadedNeighborGraph, nMatch, TravelMode.DRIVING, 12.0)
    assertTrue(deadFeatures.any { it.type == FeatureType.DEAD_END })

    // Turning circle -> CUL_DE_SAC
    val n2Circle = OsmNode(2L, p2, OsmTags(highway = "turning_circle"))
    val circleGraph = graphFrom(listOf(n1, n2Circle), listOf(w1.copy(source = GraphDataSource.OVERPASS)))
    val cMatch = MapMatcher.match(circleGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val circleFeatures = AheadGraphAnalyzer.analyzeAhead(circleGraph, cMatch, TravelMode.DRIVING, 12.0)
    assertTrue(circleFeatures.any { it.type == FeatureType.CUL_DE_SAC })

    // noexit=yes -> DEAD_END with confidence 1.0
    val n2NoExit = OsmNode(2L, p2, OsmTags(noexit = "yes"))
    val noExitGraph = graphFrom(listOf(n1, n2NoExit), listOf(w1.copy(source = GraphDataSource.OVERPASS)))
    val neMatch = MapMatcher.match(noExitGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val neFeatures = AheadGraphAnalyzer.analyzeAhead(noExitGraph, neMatch, TravelMode.DRIVING, 12.0)
    val deadEnd = neFeatures.firstOrNull { it.type == FeatureType.DEAD_END }
    assertNotNull(deadEnd)
    assertEquals(1.0, deadEnd!!.confidence, 0.001)
  }

  @Test
  fun overpassAboveUnderpassBelowTollAndRoadWorksAndCensus() {
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0008, 31.0000), OsmTags(barrier = "toll_booth"))
    val n3 = OsmNode(3L, GeoPoint(30.0018, 31.0000))
    val n4 = OsmNode(4L, GeoPoint(30.0028, 31.0000))
    val n10 = OsmNode(10L, GeoPoint(30.0011, 30.9992))
    val n11 = OsmNode(11L, GeoPoint(30.0011, 31.0008))
    val n12 = OsmNode(12L, GeoPoint(30.0015, 30.9992))
    val n13 = OsmNode(13L, GeoPoint(30.0015, 31.0008))

    val wMain = OsmWay(100L, listOf(1L, 2L, 3L), listOf(n1.point, n2.point, n3.point), OsmTags(highway = "primary", nameEn = "Main"))
    val wConst = OsmWay(101L, listOf(3L, 4L), listOf(n3.point, n4.point), OsmTags(highway = "construction", nameEn = "Closed Section"))
    val wBridgeOver = OsmWay(200L, listOf(10L, 11L), listOf(n10.point, n11.point), OsmTags(highway = "secondary", bridge = "yes", layer = "1"))
    val wTunnelUnder = OsmWay(201L, listOf(12L, 13L), listOf(n12.point, n13.point), OsmTags(highway = "secondary", tunnel = "yes", layer = "-1"))

    val graph = graphFrom(listOf(n1, n2, n3, n4, n10, n11, n12, n13), listOf(wMain, wConst, wBridgeOver, wTunnelUnder))
    val pos = GeoPoint(30.0002, 31.0000)
    val primary = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 15.0)

    assertTrue(features.any { it.type == FeatureType.TOLL_PLAZA })
    assertTrue(features.any { it.type == FeatureType.OVERPASS_ABOVE })
    assertTrue(features.any { it.type == FeatureType.UNDERPASS_BELOW })
    assertTrue(features.any { it.type == FeatureType.ROAD_WORKS })

    val census = AheadGraphAnalyzer.computeFeatureCensus(graph, pos, TravelMode.DRIVING, 1500.0)
    assertTrue(census.any { it.type == FeatureType.TOLL_PLAZA })
    assertTrue(census.any { it.type == FeatureType.OVERPASS_ABOVE })
    assertTrue(census.any { it.type == FeatureType.UNDERPASS_BELOW })

    // Tile-only graph must NEVER emit TOLL_PLAZA / CHECKPOINT / BARRIER
    val tileGraph = graphFrom(listOf(n1, n2, n3), listOf(wMain.copy(source = GraphDataSource.TILES)), GraphDataSource.TILES)
    val tPrimary = MapMatcher.match(tileGraph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val tFeatures = AheadGraphAnalyzer.analyzeAhead(tileGraph, tPrimary, TravelMode.DRIVING, 15.0)
    assertFalse(tFeatures.any { it.type == FeatureType.TOLL_PLAZA })
    assertNull(tFeatures.firstOrNull { it.type == FeatureType.CHECKPOINT })
  }

  @Test
  fun staggeredJunctionDrivingAndWalkingLimits() {
    // Straight South->North main road with 2 opposite collinear T-junctions 55m apart (between 40m walking and 80m driving)
    // 1 deg lat ~ 111,195 m -> 0.0005 deg lat ~ 55.6 m
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0008, 31.0000))
    val n3 = OsmNode(3L, GeoPoint(30.0013, 31.0000))
    val n4 = OsmNode(4L, GeoPoint(30.0022, 31.0000))
    val nL = OsmNode(5L, GeoPoint(30.0008, 30.9990)) // due West from n2
    val nR = OsmNode(6L, GeoPoint(30.0013, 31.0010)) // due East from n3

    val wMain = OsmWay(10L, listOf(1L, 2L, 3L, 4L), listOf(n1.point, n2.point, n3.point, n4.point), OsmTags(highway = "secondary", nameEn = "Main Rd"))
    val wLeft = OsmWay(11L, listOf(2L, 5L), listOf(n2.point, nL.point), OsmTags(highway = "residential", nameEn = "West St"))
    val wRight = OsmWay(12L, listOf(3L, 6L), listOf(n3.point, nR.point), OsmTags(highway = "residential", nameEn = "East St"))

    val graph = graphFrom(listOf(n1, n2, n3, n4, nL, nR), listOf(wMain, wLeft, wRight))
    val pos = GeoPoint(30.0002, 31.0000)

    AheadGraphAnalyzer.clearCompositeCache()
    val dPrimary = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val dFeatures = AheadGraphAnalyzer.analyzeAhead(graph, dPrimary, TravelMode.DRIVING, 15.0)
    assertEquals(1, dFeatures.count { it.type == FeatureType.STAGGERED_JUNCTION })
    assertEquals(0, dFeatures.count { it.type == FeatureType.T_JUNCTION })
    val staggered = dFeatures.first { it.type == FeatureType.STAGGERED_JUNCTION }
    assertEquals(2, staggered.branches.size)
    assertEquals("1A", staggered.branches[0].badgeId)
    assertEquals("1B", staggered.branches[1].badgeId)
    assertEquals(com.example.data.model.RelativeDirection.LEFT, staggered.branches[0].relativeDirection)
    assertEquals(com.example.data.model.RelativeDirection.RIGHT, staggered.branches[1].relativeDirection)
    assertNotNull(staggered.staggerOffsetMeters)
    assertTrue(staggered.staggerOffsetMeters!! in 50.0..62.0)

    // In WALKING mode (limit 40m), 55.6m offset stays 2 separate T-junctions
    val wPrimary = MapMatcher.match(graph, pos, 0.0, TravelMode.WALKING, null).primary!!
    val wFeatures = AheadGraphAnalyzer.analyzeAhead(graph, wPrimary, TravelMode.WALKING, 1.4)
    assertEquals(0, wFeatures.count { it.type == FeatureType.STAGGERED_JUNCTION })
    assertEquals(2, wFeatures.count { it.type == FeatureType.T_JUNCTION })
  }

  @Test
  fun dividedRoadAndMedianOpening() {
    // Two parallel opposite oneway carriageways 20m apart, > 150m overlap, same name, with a median opening connector 90m ahead
    val u1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val u2 = OsmNode(2L, GeoPoint(30.0010, 31.0000))
    val u3 = OsmNode(3L, GeoPoint(30.0022, 31.0000))
    // Partner shifted ~20m West (-0.00021 deg lon) running North->South (oneway=yes)
    val o1 = OsmNode(11L, GeoPoint(30.0022, 30.99979))
    val o2 = OsmNode(12L, GeoPoint(30.0010, 30.99979))
    val o3 = OsmNode(13L, GeoPoint(30.0000, 30.99979))

    val wUp = OsmWay(100L, listOf(1L, 2L, 3L), listOf(u1.point, u2.point, u3.point), OsmTags(highway = "primary", nameEn = "Corniche", oneway = "yes"))
    val wDown = OsmWay(101L, listOf(11L, 12L, 13L), listOf(o1.point, o2.point, o3.point), OsmTags(highway = "primary", nameEn = "Corniche", oneway = "yes"))
    val wConn = OsmWay(102L, listOf(2L, 12L), listOf(u2.point, o2.point), OsmTags(highway = "unclassified", oneway = "yes"))

    val graph = graphFrom(listOf(u1, u2, u3, o1, o2, o3), listOf(wUp, wDown, wConn))
    val pos = GeoPoint(30.0002, 31.0000)
    AheadGraphAnalyzer.clearCompositeCache()
    val primary = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val result = AheadGraphAnalyzer.analyzeAheadDetailed(graph, primary, TravelMode.DRIVING, 15.0)

    val divided = result.dividedRoad!!
    assertTrue(divided.isDivided)
    assertTrue(divided.separationMeters in 15.0..28.0)
    assertTrue(divided.partnerSideOffsetMeters < 0.0)
    assertTrue(result.features.any { it.type == FeatureType.MEDIAN_OPENING })
    // Verify road name itself is untouched (still pure "Corniche")
    assertEquals("Corniche", primary.name.english)
  }

  @Test
  fun frontageRoadParallelWithConnector() {
    val m1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val m2 = OsmNode(2L, GeoPoint(30.0010, 31.0000))
    val m3 = OsmNode(3L, GeoPoint(30.0022, 31.0000))
    // Parallel service road ~18m to the right (+0.00019 deg lon), overlapping ~244m
    val f1 = OsmNode(11L, GeoPoint(30.0000, 31.00019))
    val f2 = OsmNode(12L, GeoPoint(30.0010, 31.00019))
    val f3 = OsmNode(13L, GeoPoint(30.0022, 31.00019))

    val wMain = OsmWay(100L, listOf(1L, 2L, 3L), listOf(m1.point, m2.point, m3.point), OsmTags(highway = "primary", nameEn = "Ring Road"))
    val wFrontage = OsmWay(101L, listOf(11L, 12L, 13L), listOf(f1.point, f2.point, f3.point), OsmTags(highway = "service"))
    val wConn = OsmWay(102L, listOf(2L, 12L), listOf(m2.point, f2.point), OsmTags(highway = "service"))

    val graph = graphFrom(listOf(m1, m2, m3, f1, f2, f3), listOf(wMain, wFrontage, wConn))
    AheadGraphAnalyzer.clearCompositeCache()
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 15.0)
    val frontage = features.firstOrNull { it.type == FeatureType.FRONTAGE_ROAD }
    assertNotNull(frontage)
    assertEquals(com.example.data.model.RelativeDirection.RIGHT, frontage!!.frontageSide)
    // Must not invent a road name for untagged frontage
    assertNull(frontage.osmTags.name)
    assertNull(frontage.osmTags.nameEn)
  }

  @Test
  fun interchangeCloverleafDiamondTrumpetDirectionalAndHiddenFeatures() {
    // 1. CLOVERLEAF (4 loop ramps + bridge)
    val nodes = mutableListOf<OsmNode>()
    val ways = mutableListOf<OsmWay>()
    val m1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val m2 = OsmNode(2L, GeoPoint(30.0012, 31.0000))
    val m3 = OsmNode(3L, GeoPoint(30.0020, 31.0000))
    val m4 = OsmNode(4L, GeoPoint(30.0032, 31.0000))
    nodes += listOf(m1, m2, m3, m4)
    ways += OsmWay(10L, listOf(1L, 2L), listOf(m1.point, m2.point), OsmTags(highway = "motorway", nameEn = "North-South Fwy"))
    ways += OsmWay(11L, listOf(2L, 3L), listOf(m2.point, m3.point), OsmTags(highway = "motorway", nameEn = "North-South Fwy", bridge = "yes", layer = "1"))
    ways += OsmWay(12L, listOf(3L, 4L), listOf(m3.point, m4.point), OsmTags(highway = "motorway", nameEn = "North-South Fwy"))

    val cL = OsmNode(20L, GeoPoint(30.0016, 30.9985))
    val cR = OsmNode(21L, GeoPoint(30.0016, 31.0015))
    nodes += listOf(cL, cR)
    ways += OsmWay(20L, listOf(20L, 21L), listOf(cL.point, cR.point), OsmTags(highway = "trunk", nameEn = "East-West Hwy"))

    // 4 quadrant 270-deg loop ramps of ~40m radius attached near m2/m3
    fun addLoop(startId: Long, anchorNode: OsmNode, dLatSign: Int, dLonSign: Int) {
      val pts = (0..6).map { step ->
        val angle = step * (Math.PI * 1.5 / 6.0)
        val lat = anchorNode.point.lat + dLatSign * (0.00035 * (1.0 - kotlin.math.cos(angle)))
        val lon = anchorNode.point.lon + dLonSign * (0.00038 * kotlin.math.sin(angle))
        GeoPoint(lat, lon)
      }
      val nIds = listOf(anchorNode.id) + (1 until pts.size).map { idx -> startId * 10 + idx }
      for (k in 1 until pts.size) nodes += OsmNode(nIds[k], pts[k])
      ways += OsmWay(startId, nIds, pts, OsmTags(highway = "motorway_link"))
    }
    addLoop(30L, m2, 1, 1)
    addLoop(31L, m2, -1, 1)
    addLoop(32L, m3, 1, -1)
    addLoop(33L, m3, -1, -1)

    val cloverGraph = graphFrom(nodes, ways)
    AheadGraphAnalyzer.clearCompositeCache()
    val cPrimary = MapMatcher.match(cloverGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val cFeatures = AheadGraphAnalyzer.analyzeAhead(cloverGraph, cPrimary, TravelMode.DRIVING, 22.0)
    val clover = cFeatures.firstOrNull { it.type == FeatureType.CLOVERLEAF }
    assertNotNull(clover)
    assertEquals(0.90, clover!!.confidence, 0.001)
    assertEquals(FeatureType.CLOVERLEAF, clover.effectiveDisplayType())
    assertEquals(4, clover.branches.size)
    // Bridges and junctions inside the interchange are never hidden (Rule 13); merged ramps remain in hiddenSubFeatures
    assertTrue(clover.hiddenSubFeatures.isNotEmpty())
    assertTrue(cFeatures.any { it.type == FeatureType.BRIDGE && it.anchorNodeId == m2.id })

    // 2. DIAMOND (4 straight ramps <60 deg meeting crossing road at-grade on both sides)
    val dNodes = mutableListOf(m1, m2, m3, m4)
    val dCrossL = OsmNode(200L, GeoPoint(30.0016, 30.9985))
    val dCrossJL = OsmNode(201L, GeoPoint(30.0016, 30.9993))
    val dCrossJR = OsmNode(202L, GeoPoint(30.0016, 31.0007))
    val dCrossR = OsmNode(203L, GeoPoint(30.0016, 31.0015))
    dNodes += listOf(dCrossL, dCrossJL, dCrossJR, dCrossR)
    val dWays = mutableListOf(
      OsmWay(10L, listOf(1L, 2L), listOf(m1.point, m2.point), OsmTags(highway = "motorway")),
      OsmWay(11L, listOf(2L, 3L), listOf(m2.point, m3.point), OsmTags(highway = "motorway", bridge = "yes", layer = "1")),
      OsmWay(12L, listOf(3L, 4L), listOf(m3.point, m4.point), OsmTags(highway = "motorway")),
      OsmWay(20L, listOf(200L, 201L, 202L, 203L), listOf(dCrossL.point, dCrossJL.point, dCrossJR.point, dCrossR.point), OsmTags(highway = "secondary")),
      OsmWay(30L, listOf(2L, 201L), listOf(m2.point, dCrossJL.point), OsmTags(highway = "motorway_link")),
      OsmWay(31L, listOf(201L, 3L), listOf(dCrossJL.point, m3.point), OsmTags(highway = "motorway_link")),
      OsmWay(32L, listOf(2L, 202L), listOf(m2.point, dCrossJR.point), OsmTags(highway = "motorway_link")),
      OsmWay(33L, listOf(202L, 3L), listOf(dCrossJR.point, m3.point), OsmTags(highway = "motorway_link")),
    )
    val diamondGraph = graphFrom(dNodes, dWays)
    AheadGraphAnalyzer.clearCompositeCache()
    val dPrimary = MapMatcher.match(diamondGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val diamondFeatures = AheadGraphAnalyzer.analyzeAhead(diamondGraph, dPrimary, TravelMode.DRIVING, 22.0)
    val diamond = diamondFeatures.firstOrNull { it.type == FeatureType.DIAMOND }
    assertNotNull(diamond)
    assertEquals(0.85, diamond!!.confidence, 0.001)

    // 3. TRUMPET (1 loop + 2 non-loop ramps, crossing road ends at interchange)
    val tNodes = mutableListOf(m1, m2, m3, m4)
    val tCrossStart = OsmNode(300L, GeoPoint(30.0016, 31.0006))
    val tCrossEnd = OsmNode(301L, GeoPoint(30.0016, 31.0020))
    tNodes += listOf(tCrossStart, tCrossEnd)
    val tWays = mutableListOf(
      OsmWay(10L, listOf(1L, 2L), listOf(m1.point, m2.point), OsmTags(highway = "motorway")),
      OsmWay(11L, listOf(2L, 3L), listOf(m2.point, m3.point), OsmTags(highway = "motorway", bridge = "yes", layer = "1")),
      OsmWay(12L, listOf(3L, 4L), listOf(m3.point, m4.point), OsmTags(highway = "motorway")),
      OsmWay(20L, listOf(300L, 301L), listOf(tCrossStart.point, tCrossEnd.point), OsmTags(highway = "trunk")),
      OsmWay(30L, listOf(2L, 300L), listOf(m2.point, tCrossStart.point), OsmTags(highway = "motorway_link")),
      OsmWay(31L, listOf(300L, 3L), listOf(tCrossStart.point, m3.point), OsmTags(highway = "motorway_link")),
    )
    // 1 loop ramp
    val loopPts = (0..6).map { step ->
      val angle = step * (Math.PI * 1.5 / 6.0)
      GeoPoint(m2.point.lat + 0.00035 * (1.0 - kotlin.math.cos(angle)), m2.point.lon + 0.00038 * kotlin.math.sin(angle))
    }
    val loopIds = listOf(2L) + (1 until loopPts.size).map { 400L + it }
    for (k in 1 until loopPts.size) tNodes += OsmNode(loopIds[k], loopPts[k])
    tWays += OsmWay(32L, loopIds, loopPts, OsmTags(highway = "motorway_link"))

    val trumpetGraph = graphFrom(tNodes, tWays)
    AheadGraphAnalyzer.clearCompositeCache()
    val tPrimary = MapMatcher.match(trumpetGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val trumpetFeatures = AheadGraphAnalyzer.analyzeAhead(trumpetGraph, tPrimary, TravelMode.DRIVING, 22.0)
    val trumpet = trumpetFeatures.firstOrNull { it.type == FeatureType.TRUMPET }
    assertNotNull(trumpet)
    assertEquals(0.80, trumpet!!.confidence, 0.001)

    // 4. DIRECTIONAL (>= 2 flyover bridges >120m radius -> conf 0.7 -> effectiveDisplayType == INTERCHANGE)
    val dirNodes = mutableListOf(m1, m2, m3, m4)
    val f1Mid = OsmNode(501L, GeoPoint(30.0015, 31.0012))
    val f1End = OsmNode(502L, GeoPoint(30.0024, 31.0030))
    val f2Mid = OsmNode(503L, GeoPoint(30.0018, 30.9988))
    val f2End = OsmNode(504L, GeoPoint(30.0028, 30.9970))
    dirNodes += listOf(f1Mid, f1End, f2Mid, f2End)
    val dirWays = listOf(
      OsmWay(10L, listOf(1L, 2L), listOf(m1.point, m2.point), OsmTags(highway = "motorway")),
      OsmWay(11L, listOf(2L, 3L), listOf(m2.point, m3.point), OsmTags(highway = "motorway", bridge = "yes", layer = "1")),
      OsmWay(12L, listOf(3L, 4L), listOf(m3.point, m4.point), OsmTags(highway = "motorway")),
      OsmWay(40L, listOf(2L, 501L, 502L), listOf(m2.point, f1Mid.point, f1End.point), OsmTags(highway = "motorway_link", bridge = "yes", layer = "2")),
      OsmWay(41L, listOf(3L, 503L, 504L), listOf(m3.point, f2Mid.point, f2End.point), OsmTags(highway = "motorway_link", bridge = "yes", layer = "2")),
    )
    val dirGraph = graphFrom(dirNodes, dirWays)
    AheadGraphAnalyzer.clearCompositeCache()
    val dirPrimary = MapMatcher.match(dirGraph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val dirFeatures = AheadGraphAnalyzer.analyzeAhead(dirGraph, dirPrimary, TravelMode.DRIVING, 22.0)
    val dirIc = dirFeatures.firstOrNull { it.type == FeatureType.DIRECTIONAL }
    assertNotNull(dirIc)
    assertTrue(dirIc!!.confidence < 0.8)
    assertEquals(FeatureType.INTERCHANGE, dirIc.effectiveDisplayType())
  }

  @Test
  fun compositeCorridorLimitCacheAndCensus() {
    // Same staggered setup beyond 800m ahead -> NOT grouped within corridor
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0080, 31.0000)) // ~889m ahead
    val n3 = OsmNode(3L, GeoPoint(30.0085, 31.0000))
    val n4 = OsmNode(4L, GeoPoint(30.0095, 31.0000))
    val nL = OsmNode(5L, GeoPoint(30.0080, 30.9990))
    val nR = OsmNode(6L, GeoPoint(30.0085, 31.0010))
    val ways = listOf(
      OsmWay(10L, listOf(1L, 2L, 3L, 4L), listOf(n1.point, n2.point, n3.point, n4.point), OsmTags(highway = "primary")),
      OsmWay(11L, listOf(2L, 5L), listOf(n2.point, nL.point), OsmTags(highway = "residential")),
      OsmWay(12L, listOf(3L, 6L), listOf(n3.point, nR.point), OsmTags(highway = "residential")),
    )
    val graph = graphFrom(listOf(n1, n2, n3, n4, nL, nR), ways)
    AheadGraphAnalyzer.clearCompositeCache()
    val pos = GeoPoint(30.0001, 31.0000)
    val primary = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val res1 = AheadGraphAnalyzer.analyzeAheadDetailed(graph, primary, TravelMode.DRIVING, 30.0)
    val res2 = AheadGraphAnalyzer.analyzeAheadDetailed(graph, primary, TravelMode.DRIVING, 30.0)
    assertSame(res1, res2) // Cache hit returns exact cached instance for same cell group
    assertFalse(res1.features.any { it.type == FeatureType.STAGGERED_JUNCTION })

    // Census over 1.5 km includes STAGGERED_JUNCTION with Jump point
    val census = AheadGraphAnalyzer.computeFeatureCensus(graph, pos, TravelMode.DRIVING, 1500.0)
    assertTrue(census.any { it.type == FeatureType.STAGGERED_JUNCTION })
  }
}
