package com.example

import com.example.data.model.DeveloperOrientation
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LocationSnapshot
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.OverpassRequestLog
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import com.example.domain.RoadGeometryStepper
import com.example.location.LocationTracker
import com.example.ui.RoadHudUiState
import com.example.ui.components.buildInspectorPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockControlAndOrientationTest {

  private fun graphFrom(nodes: List<OsmNode>, ways: List<OsmWay>): RoadGraph {
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
      source = GraphDataSource.OVERPASS,
    )
  }

  @Test
  fun liveJumpFilterRejectsLargeFastJumpOnly() {
    val start = GeoPoint(30.0444, 31.2357)
    val far = GeoMath.offsetPoint(start, 90.0, 650.0)
    val near = GeoMath.offsetPoint(start, 90.0, 120.0)

    val rejected = LocationTracker.checkLiveJump(start, 1_000L, far, 2_500L)
    assertTrue(rejected.isRejected)
    assertTrue(rejected.distanceMeters > 500.0)
    assertEquals(1_500L, rejected.deltaMs)

    val slowFarAccepted = LocationTracker.checkLiveJump(start, 1_000L, far, 3_000L)
    assertFalse(slowFarAccepted.isRejected)

    val fastNearAccepted = LocationTracker.checkLiveJump(start, 1_000L, near, 1_500L)
    assertFalse(fastNearAccepted.isRejected)

    val initialAccepted = LocationTracker.checkLiveJump(null, 0L, far, 1_500L)
    assertFalse(initialAccepted.isRejected)
  }

  @Test
  fun roadGeometryStepperPicksStraightestOrPreferredAndDetectsDeadEnd() {
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0004, 31.0000)) // ~44.4m north of n1
    val n3 = OsmNode(3L, GeoPoint(30.0010, 31.0000)) // straight north continuation (~66.7m)
    val n4 = OsmNode(4L, GeoPoint(30.0004, 31.0008)) // right turn east (~77m)

    val wMain = OsmWay(10L, listOf(1L, 2L), listOf(n1.point, n2.point), OsmTags(highway = "primary"))
    val wStraight = OsmWay(20L, listOf(2L, 3L), listOf(n2.point, n3.point), OsmTags(highway = "primary"))
    val wRight = OsmWay(30L, listOf(2L, 4L), listOf(n2.point, n4.point), OsmTags(highway = "secondary"))
    val graph = graphFrom(listOf(n1, n2, n3, n4), listOf(wMain, wStraight, wRight))

    val startPt = GeoPoint(30.0002, 31.0000) // ~22.2m from n1 and n2
    val matched = MapMatcher.match(graph, startPt, 0.0, TravelMode.DRIVING, null).primary!!

    // +50m forward picks straight continuation onto way 20
    val straightStep =
      RoadGeometryStepper.stepAlongRoad(
        graph = graph,
        matched = matched,
        currentPoint = startPt,
        currentHeadingDeg = 0.0,
        deltaMeters = 50.0,
        mode = TravelMode.DRIVING,
      )
    assertEquals(20L, straightStep.wayId)
    assertFalse(straightStep.hitDeadEnd)

    // +50m with preferredBranchWayId = 30 turns right onto way 30
    val rightStep =
      RoadGeometryStepper.stepAlongRoad(
        graph = graph,
        matched = matched,
        currentPoint = startPt,
        currentHeadingDeg = 0.0,
        deltaMeters = 50.0,
        mode = TravelMode.DRIVING,
        preferredBranchWayId = 30L,
      )
    assertEquals(30L, rightStep.wayId)
    assertTrue(rightStep.consumedPreferredBranch)
    assertTrue(rightStep.headingDeg in 75.0..105.0)

    // -10m backward stays on way 10 keeping forward heading (~0 deg)
    val backStep =
      RoadGeometryStepper.stepAlongRoad(
        graph = graph,
        matched = matched,
        currentPoint = startPt,
        currentHeadingDeg = 0.0,
        deltaMeters = -10.0,
        mode = TravelMode.DRIVING,
      )
    assertEquals(10L, backStep.wayId)
    assertTrue(backStep.point.lat < startPt.lat)

    // +500m reaches end of wStraight -> hitDeadEnd = true
    val deadStep =
      RoadGeometryStepper.stepAlongRoad(
        graph = graph,
        matched = matched,
        currentPoint = startPt,
        currentHeadingDeg = 0.0,
        deltaMeters = 500.0,
        mode = TravelMode.DRIVING,
      )
    assertTrue(deadStep.hitDeadEnd)
    assertEquals(20L, deadStep.wayId)
  }

  @Test
  fun roadGeometryStepperHandlesInteriorPreferredBranchAndInterpolation() {
    val n1 = OsmNode(1L, GeoPoint(30.0000, 31.0000))
    val n2 = OsmNode(2L, GeoPoint(30.0003, 31.0000))
    val n3 = OsmNode(3L, GeoPoint(30.0008, 31.0000))
    val n4 = OsmNode(4L, GeoPoint(30.0003, 31.0006))

    // Interior node n2 on wMain splits to wBranch (way 200)
    val wMain = OsmWay(100L, listOf(1L, 2L, 3L), listOf(n1.point, n2.point, n3.point), OsmTags(highway = "primary"))
    val wBranch = OsmWay(200L, listOf(2L, 4L), listOf(n2.point, n4.point), OsmTags(highway = "secondary"))
    val graph = graphFrom(listOf(n1, n2, n3, n4), listOf(wMain, wBranch))

    val startPt = GeoPoint(30.0001, 31.0000)
    val matched = MapMatcher.match(graph, startPt, 0.0, TravelMode.DRIVING, null).primary!!
    val step =
      RoadGeometryStepper.stepAlongRoad(
        graph = graph,
        matched = matched,
        currentPoint = startPt,
        currentHeadingDeg = 0.0,
        deltaMeters = 50.0,
        mode = TravelMode.DRIVING,
        preferredBranchWayId = 200L,
      )
    assertEquals(200L, step.wayId)
    assertTrue(step.consumedPreferredBranch)

    // Heading wrap-around across 350 -> 10 passes through 0 deg
    val midHeading = RoadGeometryStepper.interpolateHeadingDeg(350.0, 10.0, 0.5)
    assertEquals(0.0, midHeading, 0.001)
    val midPt = RoadGeometryStepper.interpolatePoint(GeoPoint(30.0, 31.0), GeoPoint(31.0, 32.0), 0.5)
    assertEquals(30.5, midPt.lat, 0.0001)
    assertEquals(31.5, midPt.lon, 0.0001)
  }

  @Test
  fun mockSpeedLookaheadAndInspectorReportFormat() {
    val look5 = AheadGraphAnalyzer.computeLookaheadMeters(TravelMode.DRIVING, 5.0 / 3.6)
    val look90 = AheadGraphAnalyzer.computeLookaheadMeters(TravelMode.DRIVING, 90.0 / 3.6)
    assertTrue(look90 > look5)

    val state =
      RoadHudUiState(
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 90.0, 50.0 / 3.6, 12f, isMock = true, timestampMs = 1000L),
        isMockModeActive = true,
        mockSpeedKmh = 50.0,
        mockAccuracyMeters = 12.0f,
        mockJitterEnabled = true,
        isSimulatingDrive = true,
        cachedCellCount = 4,
        cachedTileCount = 2,
        recentPositionSources = listOf("MOCK(sim)", "MOCK(step)", "MOCK(tap)"),
        recentOverpassLogs =
          listOf(
            OverpassRequestLog(
              timestampMs = 1000L,
              endpoint = "https://overpass-api.de/api/interpreter",
              method = "POST",
              protocol = "HTTP/2",
              status = "406",
              durationMs = 120L,
              responseSizeKb = 0.1,
              cellsFetched = 0,
              cellsCached = 2,
              queryText = "[out:json][timeout:8];way(30,31,30.1,31.1);",
              responseSnippet = "Not Acceptable test body",
              source = "tiles",
            ),
          ),
      )

    val report = buildInspectorPlainText(state)
    val lines = report.lines()
    assertTrue(lines.size <= 60)
    assertTrue(report.contains("MOCK(sim)"))
    assertTrue(report.contains("mockSpd=50km/h"))
    assertTrue(report.contains("mockAcc=12m"))
    assertTrue(report.contains("jitter=on"))
    assertTrue(report.contains("[LAST_5_POS_SOURCES]"))
    assertTrue(report.contains("Cache: 4 cells · 2 tiles"))
    assertTrue(report.contains("status=406"))
    assertTrue(report.contains("Not Acceptable test body"))
    assertTrue(report.contains("QUERY: [out:json][timeout:8];"))
    assertTrue(report.contains("[tiles]"))
    assertEquals(DeveloperOrientation.AUTO, state.settings.developerOrientation)
  }
}
