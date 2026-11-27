package com.example

import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.RoadBranch
import com.example.data.model.ChaseCameraDistance
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LadderOrder
import com.example.data.model.LadderTextSize
import com.example.data.model.NameOrder
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.domain.FeatureTracker
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import com.example.domain.WayMatchHysteresis
import com.example.location.TrackingServicePolicy
import com.example.ui.ChaseCameraSmoother
import com.example.ui.FrameQualityStateMachine
import com.example.ui.components.RoadLadderBuilder
import com.example.ui.components.resolveDisplayLines
import com.example.util.CrashDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullFixPassTest {
  private fun graphFromWays(ways: List<OsmWay>): RoadGraph {
    val nodeMap = LinkedHashMap<Long, OsmNode>()
    val wayMap = LinkedHashMap<Long, OsmWay>()
    val n2w = HashMap<Long, MutableList<Long>>()
    for (way in ways) {
      wayMap[way.id] = way
      way.nodeIds.forEachIndexed { idx, nid ->
        nodeMap.putIfAbsent(nid, OsmNode(nid, way.points[idx]))
        n2w.getOrPut(nid) { mutableListOf() }.add(way.id)
      }
    }
    return RoadGraph(
      nodes = nodeMap,
      ways = wayMap,
      nodeToWays = n2w,
      fetchedAtMs = 1_000L,
      fromCache = false,
      source = GraphDataSource.TILES,
    )
  }

  private fun sampleFeature(
    index: Int,
    wayId: Long,
    dist: Double,
    type: FeatureType = FeatureType.T_JUNCTION,
    anchor: GeoPoint = GeoMath.offsetPoint(GeoPoint(30.0, 31.0), 0.0, dist),
  ): RoadFeature =
    RoadFeature(
      index = index,
      type = type,
      distanceMeters = dist,
      anchor = anchor,
      anchorNodeId = wayId * 10 + index,
      incomingLocalPoints = emptyList(),
      incomingWorldPoints = emptyList(),
      outgoingMainLocalPoints = emptyList(),
      outgoingMainWorldPoints = emptyList(),
      branches =
        listOf(
          RoadBranch(
            badgeId = "${index}R",
            wayId = wayId + 100L + index,
            name = BilingualName.fromOsmTags(OsmTags(nameEn = "Side $index")),
            leadsTo = "East $index",
            distanceMeters = dist,
            status = BranchStatus.OPEN,
            relativeDirection = RelativeDirection.RIGHT,
            bearingDeltaDeg = 90.0,
            worldPoints = listOf(anchor, GeoMath.offsetPoint(anchor, 90.0, 40.0)),
            localPoints = emptyList(),
            osmTags = OsmTags(highway = "residential"),
          ),
        ),
      osmTags = OsmTags(highway = "primary"),
      confidence = 0.9,
      ruleId = "test_rule",
      source = GraphDataSource.TILES,
    )

  // Part 1: FeatureTracker preserves features during partial/empty flicker, dead-reckons, and only clears after confirmed empty window or off-road
  @Test
  fun testPart1_featureTrackerStabilityAndDeadReckoning() {
    val tracker = FeatureTracker()
    val p0 = GeoPoint(30.0000, 31.0000)
    val pEnd = GeoMath.offsetPoint(p0, 0.0, 1000.0)
    val corridor = listOf(p0, pEnd)
    val f1 = sampleFeature(1, wayId = 10L, dist = 120.0)

    val initial =
      tracker.reconcile(
        nowMs = 1_000L,
        matchedWayId = 10L,
        isOffRoadUnselected = false,
        tracedWayIds = setOf(10L),
        corridorWorldPoints = corridor,
        userPosition = p0,
        newFeatures = listOf(f1),
        newTimelineEntries = emptyList(),
        newLandmarks = emptyList(),
        loadedCorridorMeters = 3000.0,
        configuredHorizonMeters = 3000.0,
        isPartialBudgetResult = false,
        horizonStoppedAtUnloadedBorder = false,
      )
    assertEquals(1, initial.features.size)
    assertFalse(initial.hasConfirmedEmptyAhead)

    // Partial/border-stopped empty result must preserve existing features
    val partialEmpty =
      tracker.reconcile(
        nowMs = 1_500L,
        matchedWayId = 10L,
        isOffRoadUnselected = false,
        tracedWayIds = setOf(10L),
        corridorWorldPoints = corridor,
        userPosition = p0,
        newFeatures = emptyList(),
        newTimelineEntries = emptyList(),
        newLandmarks = emptyList(),
        loadedCorridorMeters = 400.0,
        configuredHorizonMeters = 3000.0,
        isPartialBudgetResult = true,
        horizonStoppedAtUnloadedBorder = true,
      )
    assertEquals(1, partialEmpty.features.size)
    assertFalse(partialEmpty.hasConfirmedEmptyAhead)

    // Dead-reckoning advances user 30 m forward -> distance decreases ~30 m
    val p30 = GeoMath.offsetPoint(p0, 0.0, 30.0)
    val dr = tracker.deadReckon(p30, matchedWayId = 10L)
    assertNotNull(dr)
    assertEquals(90.0, dr!!.features.first().distanceMeters, 2.0)

    // Single full-horizon empty pass must NOT clear yet
    val emptyPass1 =
      tracker.reconcile(
        nowMs = 2_000L,
        matchedWayId = 10L,
        isOffRoadUnselected = false,
        tracedWayIds = setOf(10L),
        corridorWorldPoints = corridor,
        userPosition = p30,
        newFeatures = emptyList(),
        newTimelineEntries = emptyList(),
        newLandmarks = emptyList(),
        loadedCorridorMeters = 3000.0,
        configuredHorizonMeters = 3000.0,
        isPartialBudgetResult = false,
        horizonStoppedAtUnloadedBorder = false,
      )
    assertEquals(1, emptyPass1.features.size)
    assertFalse(emptyPass1.hasConfirmedEmptyAhead)

    // Second full-horizon empty pass >= 1.5s later confirms empty ahead
    val emptyPass2 =
      tracker.reconcile(
        nowMs = 3_600L,
        matchedWayId = 10L,
        isOffRoadUnselected = false,
        tracedWayIds = setOf(10L),
        corridorWorldPoints = corridor,
        userPosition = p30,
        newFeatures = emptyList(),
        newTimelineEntries = emptyList(),
        newLandmarks = emptyList(),
        loadedCorridorMeters = 3000.0,
        configuredHorizonMeters = 3000.0,
        isPartialBudgetResult = false,
        horizonStoppedAtUnloadedBorder = false,
      )
    assertTrue(emptyPass2.features.isEmpty())
    assertTrue(emptyPass2.hasConfirmedEmptyAhead)
  }

  // Part 2: Primary arterial beats nearby 26m service spur; WayMatchHysteresis requires >=30% over 3 fixes unless connected
  @Test
  fun testPart2_mapMatcherClassPriorSpurPenaltyAndHysteresis() {
    val origin = GeoPoint(30.0000, 31.0000)
    // Primary road running north at x = 0m, 500m long
    val pStart = origin
    val pEnd = GeoMath.offsetPoint(origin, 0.0, 500.0)
    val primaryWay =
      OsmWay(
        id = 100L,
        nodeIds = listOf(1L, 2L),
        points = listOf(pStart, pEnd),
        tags = OsmTags(highway = "primary", nameEn = "Main Arterial"),
        source = GraphDataSource.TILES,
      )

    // Short 26m unnamed service spur 6m to the east, also running north
    val sStart = GeoMath.offsetPoint(GeoMath.offsetPoint(origin, 0.0, 40.0), 90.0, 6.0)
    val sEnd = GeoMath.offsetPoint(sStart, 0.0, 26.0)
    val spurWay =
      OsmWay(
        id = 200L,
        nodeIds = listOf(3L, 4L),
        points = listOf(sStart, sEnd),
        tags = OsmTags(highway = "service"),
        source = GraphDataSource.TILES,
      )

    val graph = graphFromWays(listOf(primaryWay, spurWay))
    val userPos = GeoMath.offsetPoint(GeoMath.offsetPoint(origin, 0.0, 50.0), 90.0, 3.0)
    val res = MapMatcher.match(graph, userPos, headingDeg = 0.0, mode = TravelMode.DRIVING, previousWayId = null)
    assertNotNull(res.primary)
    assertEquals(100L, res.primary!!.way.id)
    assertTrue("Expected top candidates diagnostics populated", res.diagnostics.topCandidates.size >= 2)

    // Hysteresis: unrelated way with +10% margin is held; >=30% switches only on 3rd consecutive fix
    val hyst = WayMatchHysteresis()
    data class Dummy(val id: Long, val raw: Double, val conn: Boolean = false)
    val a = Dummy(10L, 1.0)
    val bWeak = Dummy(20L, 1.15)
    val bStrong = Dummy(20L, 1.40)

    assertEquals(10L, hyst.select(a, listOf(a, bWeak), { it.id }, { it.raw }, { it.conn }).chosen.id)
    assertEquals(10L, hyst.select(bWeak, listOf(a, bWeak), { it.id }, { it.raw }, { it.conn }).chosen.id)
    assertEquals(10L, hyst.select(bStrong, listOf(a, bStrong), { it.id }, { it.raw }, { it.conn }).chosen.id)
    assertEquals(10L, hyst.select(bStrong, listOf(a, bStrong), { it.id }, { it.raw }, { it.conn }).chosen.id)
    assertEquals(20L, hyst.select(bStrong, listOf(a, bStrong), { it.id }, { it.raw }, { it.conn }).chosen.id)

    // Connected continuation switches immediately
    val cConn = Dummy(30L, 1.01, conn = true)
    assertEquals(30L, hyst.select(cConn, listOf(bStrong, cConn), { it.id }, { it.raw }, { it.conn }).chosen.id)
  }

  // Part 3: Foreground tracking policy & 10-minute idle auto-stop
  @Test
  fun testPart3_trackingServicePolicyAndIdleStop() {
    assertTrue(
      TrackingServicePolicy.shouldRunInBackground(
        hasLocationPermission = true,
        isMockMode = false,
        backgroundTrackingWhileDriving = true,
        travelMode = TravelMode.DRIVING,
      ),
    )
    assertFalse(
      TrackingServicePolicy.shouldRunInBackground(
        hasLocationPermission = true,
        isMockMode = false,
        backgroundTrackingWhileDriving = false,
        travelMode = TravelMode.DRIVING,
      ),
    )
    assertFalse(
      TrackingServicePolicy.shouldRunInBackground(
        hasLocationPermission = true,
        isMockMode = false,
        backgroundTrackingWhileDriving = true,
        travelMode = TravelMode.WALKING,
      ),
    )
    assertFalse(
      TrackingServicePolicy.shouldAutoStopIdle(
        nowMs = 1_000_000L,
        idleSinceMs = 100_000L,
        isDriving = true,
        isFollowingUser = true,
      ),
    )
    assertTrue(
      TrackingServicePolicy.shouldAutoStopIdle(
        nowMs = 700_000L,
        idleSinceMs = 100_000L,
        isDriving = false,
        isFollowingUser = true,
      ),
    )
  }

  // Part 4 & 5: RoadLadderBuilder, LadderTextSize scale, NameOrder ordering & case-insensitive dedup
  @Test
  fun testPart4And5_ladderOrderingTextSizesAndBilingualNameOrder() {
    val identical = BilingualName(arabic = null, english = "Ring Road", ref = null, localPrimary = "Ring Road", secondaryLocal = "ring road")
    val autoLines = resolveDisplayLines(identical, NameOrder.AUTO)
    assertEquals("Ring Road", autoLines.first)
    assertNull("Identical case-insensitive secondary name should be dropped", autoLines.second)

    val bi = BilingualName(arabic = "الطريق الدائري", english = "Ring Road", ref = "M1")
    val arFirst = resolveDisplayLines(bi, NameOrder.ARABIC_FIRST)
    val enFirst = resolveDisplayLines(bi, NameOrder.ENGLISH_FIRST)
    assertTrue(arFirst.first!!.contains("الطريق الدائري"))
    assertTrue(arFirst.second!!.contains("Ring Road"))
    assertTrue(enFirst.first!!.contains("Ring Road"))
    assertTrue(enFirst.second!!.contains("الطريق الدائري"))

    assertTrue(LadderTextSize.EXTRA_LARGE.primarySp > LadderTextSize.LARGE.primarySp)
    assertTrue(LadderTextSize.LARGE.primarySp > LadderTextSize.NORMAL.primarySp)

    val fNear = sampleFeature(1, 10L, 120.0)
    val fFar = sampleFeature(2, 10L, 480.0)
    val rowsBottom = RoadLadderBuilder.buildRows(listOf(fNear, fFar), emptyList(), 1, LadderOrder.NEAREST_AT_BOTTOM)
    assertEquals(480.0, rowsBottom.first().distanceMeters, 0.1)
    assertEquals(120.0, rowsBottom.last().distanceMeters, 0.1)
  }

  // Part 6: FrameQualityStateMachine slow-frame drop + 60s recovery & CrashDiagnostics formatting
  @Test
  fun testPart6_frameQualityMonitorAndCrashDiagnostics() {
    val machine = FrameQualityStateMachine()
    assertFalse(machine.isLowQuality)

    // 30 consecutive slow frames (>32ms) trigger low quality
    var t = 1_000L
    for (i in 1..29) {
      assertFalse(machine.onFrame(t, 36.0))
      t += 36L
    }
    assertTrue(machine.onFrame(t, 36.0))

    // Good frames before 60s hold must stay in low quality
    t += 1_000L
    assertTrue(machine.onFrame(t, 16.0))
    t += 30_000L
    assertTrue(machine.onFrame(t, 16.0))

    // Good frame at >= 60s after healthy start recovers to high quality
    t += 31_000L
    assertFalse(machine.onFrame(t, 16.0))

    // p95 > 40ms over >=30 samples in 5s window triggers low quality
    val p95Machine = FrameQualityStateMachine()
    var pt = 10_000L
    for (i in 0 until 27) {
      p95Machine.onFrame(pt, 16.0)
      pt += 20L
    }
    for (i in 0 until 3) {
      p95Machine.onFrame(pt, 48.0)
      pt += 50L
    }
    assertTrue(p95Machine.isLowQuality)

    val summary = CrashDiagnostics.formatUncaughtException("main", IllegalStateException("Boom test"), 1_700_000_000_000L)
    assertTrue(summary.contains("IllegalStateException"))
    assertTrue(summary.contains("Boom test"))
  }

  // Part 7: Tunable chase camera presets & 512px tile maxPitchedStubMeters
  @Test
  fun testPart7_chaseCameraPresetsTuningAnd512TileClamp() {
    val smoother = ChaseCameraSmoother()
    val pt = GeoPoint(30.0, 31.0)

    val veryClose =
      smoother.update(
        nowMs = 1_000L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.VERY_CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 30.0 / 3.6,
        userPoint = pt,
        isMatchedToRoad = true,
        corridorWorldPoints = emptyList(),
      )
    assertEquals(19.0, veryClose.zoom, 0.01)
    assertEquals(68.0, veryClose.tiltDeg, 0.01)
    assertEquals(0.72, veryClose.topRatio, 0.01) // 2 * 0.86 - 1

    // Custom overrides: zoomTrim = -1.0, tilt = 72°, vehiclePos = 0.70, curveLean = 0%
    val p30 = GeoMath.offsetPoint(pt, 0.0, 30.0)
    val pTurn = GeoMath.offsetPoint(p30, 90.0, 40.0)
    val tuned =
      smoother.update(
        nowMs = 2_000L,
        chaseCameraEnabled = true,
        distancePreset = ChaseCameraDistance.CLOSE,
        travelMode = TravelMode.DRIVING,
        isLandscape = false,
        gpsHeadingDeg = 0.0,
        speedMps = 20.0,
        userPoint = pt,
        isMatchedToRoad = true,
        corridorWorldPoints = listOf(pt, p30, pTurn),
        forceEmit = true,
        zoomTrim = -1.0,
        tiltOverrideDeg = 72.0,
        vehiclePosFraction = 0.70,
        curveLeanPercent = 0,
      )
    assertEquals(72.0, tuned.tiltDeg, 0.01)
    assertEquals(0.40, tuned.topRatio, 0.01)
    assertEquals(0.0, tuned.bearingDeg, 0.5)

    // 512px tile scale at lat 0, zoom 18, pitch 60 -> 78_271.517 * 1 / 2^18 / 0.5 * 90dp ~= 53.7m
    val stubMax = ChaseCameraSmoother.maxPitchedStubMeters(0.0, 18.0, 60.0)!!
    assertEquals(53.75, stubMax, 1.0)
  }
}
