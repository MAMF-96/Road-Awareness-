package com.example

import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.TravelMode
import com.example.data.remote.OverpassMapDataSource
import com.example.data.remote.ValhallaRoutingService
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoadGraphEngineTest {

  @Test
  fun overpassParser_filtersStrictAllowedTagsOnly() {
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0010, "lon": 31.0000, "tags": {"highway": "crossing", "crossing": "marked", "secret_custom": "ignore"}},
          {"type": "way", "id": 100, "nodes": [1, 2], "tags": {"name": "Nile St", "name:ar": "شارع النيل", "name:en": "Nile St", "ref": "M12", "highway": "primary", "oneway": "yes", "lanes": "3", "maxspeed": "80"}}
        ]
      }
      """.trimIndent()

    val graph = OverpassMapDataSource.parseOverpassJson(json, fetchedAtMs = 1000L, fromCache = false)
    val way = graph.ways[100L]
    assertNotNull(way)
    val tagMap = way!!.tags.toDisplayMap()
    assertEquals("شارع النيل", tagMap["name:ar"])
    assertEquals("M12", tagMap["ref"])
    assertEquals("yes", tagMap["oneway"])
    assertFalse(tagMap.containsKey("maxspeed"))
    assertEquals("crossing", graph.nodes[2L]?.tags?.highway)
  }

  @Test
  fun mapMatcher_detectsParallelRoadUncertainty() {
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0020, "lon": 31.0000},
          {"type": "node", "id": 3, "lat": 30.0000, "lon": 31.00006},
          {"type": "node", "id": 4, "lat": 30.0020, "lon": 31.00006},
          {"type": "way", "id": 10, "nodes": [1, 2], "tags": {"highway": "primary", "name:en": "Main Hwy"}},
          {"type": "way", "id": 11, "nodes": [3, 4], "tags": {"highway": "service", "name:en": "Frontage Rd"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val posBetween = GeoPoint(30.0010, 31.00003)
    val match = MapMatcher.match(graph, posBetween, headingDeg = 0.0, TravelMode.DRIVING, previousWayId = null)

    assertNotNull(match.primary)
    assertNotNull(match.secondary)
    assertTrue(match.isUncertain)
  }

  @Test
  fun aheadAnalyzer_classifiesJunctionBranchesOnewayAndMissingDestination() {
    // Northbound road (1->2) meeting 3 branches at node 2:
    // Left (2->3), Straight (2->4), Right oneway AGAINST user (5->2)
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0020, "lon": 31.0000},
          {"type": "node", "id": 3, "lat": 30.0026, "lon": 30.9992},
          {"type": "node", "id": 4, "lat": 30.0032, "lon": 31.0000},
          {"type": "node", "id": 5, "lat": 30.0026, "lon": 31.0008},
          {"type": "way", "id": 10, "nodes": [1, 2, 4], "tags": {"highway": "primary", "name:ar": "الطريق المركزي", "name:en": "Central Highway", "ref": "M12"}},
          {"type": "way", "id": 20, "nodes": [2, 3], "tags": {"highway": "secondary", "name:en": "West Port Rd", "destination": "West Port"}},
          {"type": "way", "id": 30, "nodes": [5, 2], "tags": {"highway": "secondary", "name:en": "Old Quarry Rd", "oneway": "yes", "destination": "East Hills"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val pos = GeoPoint(30.0005, 31.0000)
    val match = MapMatcher.match(graph, pos, headingDeg = 0.0, TravelMode.DRIVING, null)
    assertNotNull(match.primary)

    val features = AheadGraphAnalyzer.analyzeAhead(graph, match.primary!!, TravelMode.DRIVING, speedMps = 12.0)
    assertTrue(features.isNotEmpty())
    val junction = features.first()
    assertEquals(FeatureType.FOUR_WAY, junction.type)
    assertEquals(3, junction.branches.size)

    val ids = junction.branches.map { it.badgeId }
    assertEquals(listOf("1A", "1B", "1C"), ids)

    val west = junction.branches.first { it.name.english == "West Port Rd" }
    assertEquals(BranchStatus.OPEN, west.status)
    assertEquals("West Port", west.leadsTo)

    val straight = junction.branches.first { it.name.english == "Central Highway" }
    assertNull(straight.leadsTo) // Missing destination must remain null -> UI shows unknown

    val quarry = junction.branches.first { it.name.english == "Old Quarry Rd" }
    assertEquals(BranchStatus.BLOCKED, quarry.status)
  }

  @Test
  fun aheadAnalyzer_detectsCrosswalkAndFootpathInWalkingMode() {
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0004, "lon": 31.0000, "tags": {"highway": "crossing"}},
          {"type": "node", "id": 3, "lat": 30.0008, "lon": 31.0000},
          {"type": "node", "id": 4, "lat": 30.0010, "lon": 31.0005},
          {"type": "way", "id": 10, "nodes": [1, 2, 3], "tags": {"highway": "pedestrian", "name:en": "Al-Andalus Walkway"}},
          {"type": "way", "id": 11, "nodes": [3, 4], "tags": {"highway": "footway", "name:en": "Garden Footpath", "destination": "River Park"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val match = MapMatcher.match(graph, GeoPoint(30.0001, 31.0000), 0.0, TravelMode.WALKING, null)
    assertNotNull(match.primary)

    val features = AheadGraphAnalyzer.analyzeAhead(graph, match.primary!!, TravelMode.WALKING, 1.4)
    assertTrue(features.any { it.type == FeatureType.CROSSWALK })
    assertTrue(features.any { it.type == FeatureType.FOOTPATH })
  }

  @Test
  fun geoMathAndPolyline6_computeExpectedValues() {
    val (angle, radius) =
      GeoMath.turningAngleAndRadius(
        GeoPoint(30.0000, 31.0000),
        GeoPoint(30.0003, 31.0000),
        GeoPoint(30.0004, 31.0003),
      )
    assertTrue(angle > 45.0)
    assertTrue(radius < 100.0)

    val decoded = ValhallaRoutingService.decodePolyline6("_izlhA~rlgdF_{geC~ywl@")
    assertEquals(2, decoded.size)
  }

  @Test
  fun aheadAnalyzer_5wayAndServiceDrivewayFilteringByMode() {
    // Center node 2 with 4 outgoing branches + 1 driveway service branch
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0010, "lon": 31.0000},
          {"type": "node", "id": 3, "lat": 30.0020, "lon": 31.0000},
          {"type": "node", "id": 4, "lat": 30.0010, "lon": 30.9990},
          {"type": "node", "id": 5, "lat": 30.0010, "lon": 31.0010},
          {"type": "node", "id": 6, "lat": 30.0016, "lon": 30.9993},
          {"type": "node", "id": 7, "lat": 30.0016, "lon": 31.0007},
          {"type": "way", "id": 10, "nodes": [1, 2, 3], "tags": {"highway": "primary"}},
          {"type": "way", "id": 20, "nodes": [2, 4], "tags": {"highway": "secondary"}},
          {"type": "way", "id": 30, "nodes": [2, 5], "tags": {"highway": "secondary"}},
          {"type": "way", "id": 40, "nodes": [2, 6], "tags": {"highway": "service", "service": "driveway"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val pos = GeoPoint(30.0002, 31.0000)

    val drivingMatch = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val drivingFeatures = AheadGraphAnalyzer.analyzeAhead(graph, drivingMatch, TravelMode.DRIVING, 12.0)
    // In Driving: driveway ignored -> 3 outgoing edges -> FOUR_WAY
    assertEquals(FeatureType.FOUR_WAY, drivingFeatures.first().type)
    assertEquals(3, drivingFeatures.first().branches.size)

    val walkingMatch = MapMatcher.match(graph, pos, 0.0, TravelMode.WALKING, null).primary!!
    val walkingFeatures = AheadGraphAnalyzer.analyzeAhead(graph, walkingMatch, TravelMode.WALKING, 1.4)
    // In Walking: driveway counted -> 4 outgoing edges -> MULTI_WAY
    assertEquals(FeatureType.MULTI_WAY, walkingFeatures.first().type)
    assertEquals(4, walkingFeatures.first().branches.size)
  }

  @Test
  fun aheadAnalyzer_detectsTileRoundaboutGeometrically() {
    // Approach south->north on way 1 to node 10, which enters a 4-edge clockwise ring (~20m radius, ~125m total) with 3 external roads
    val nodes =
      mapOf(
        1L to com.example.data.model.OsmNode(1L, GeoPoint(30.0000, 31.0000)),
        10L to com.example.data.model.OsmNode(10L, GeoPoint(30.0010, 31.0000)),
        11L to com.example.data.model.OsmNode(11L, GeoPoint(30.0012, 31.00022)),
        12L to com.example.data.model.OsmNode(12L, GeoPoint(30.0014, 31.0000)),
        13L to com.example.data.model.OsmNode(13L, GeoPoint(30.0012, 30.99978)),
        20L to com.example.data.model.OsmNode(20L, GeoPoint(30.0020, 31.0000)),
        21L to com.example.data.model.OsmNode(21L, GeoPoint(30.0012, 31.0010)),
      )
    val ways =
      mapOf(
        1L to com.example.data.model.OsmWay(1L, listOf(1L, 10L), listOf(nodes[1L]!!.point, nodes[10L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
        2L to com.example.data.model.OsmWay(2L, listOf(10L, 11L), listOf(nodes[10L]!!.point, nodes[11L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
        3L to com.example.data.model.OsmWay(3L, listOf(11L, 12L), listOf(nodes[11L]!!.point, nodes[12L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
        4L to com.example.data.model.OsmWay(4L, listOf(12L, 13L), listOf(nodes[12L]!!.point, nodes[13L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
        5L to com.example.data.model.OsmWay(5L, listOf(13L, 10L), listOf(nodes[13L]!!.point, nodes[10L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
        6L to com.example.data.model.OsmWay(6L, listOf(12L, 20L), listOf(nodes[12L]!!.point, nodes[20L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
        7L to com.example.data.model.OsmWay(7L, listOf(11L, 21L), listOf(nodes[11L]!!.point, nodes[21L]!!.point), com.example.data.model.OsmTags(highway = "secondary"), com.example.data.model.GraphDataSource.TILES),
      )
    val nodeToWays =
      mapOf(
        1L to listOf(1L),
        10L to listOf(1L, 2L, 5L),
        11L to listOf(2L, 3L, 7L),
        12L to listOf(3L, 4L, 6L),
        13L to listOf(4L, 5L),
        20L to listOf(6L),
        21L to listOf(7L),
      )
    val graph = com.example.data.model.RoadGraph(nodes, ways, nodeToWays, 0L, false, com.example.data.model.GraphDataSource.TILES)
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 12.0)
    assertTrue(features.any { it.type == FeatureType.ROUNDABOUT && it.ruleId == "geom_tile_roundabout" })
  }

  @Test
  fun aheadAnalyzer_rampsImpliedOnewayAndLinkDirection() {
    // Main road 1->2->3; motorway_link starting at 2->4 (OFF_RAMP) and trunk_link without oneway at 2->5 (UNVERIFIED)
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0010, "lon": 31.0000},
          {"type": "node", "id": 3, "lat": 30.0020, "lon": 31.0000},
          {"type": "node", "id": 4, "lat": 30.0015, "lon": 31.0005},
          {"type": "node", "id": 5, "lat": 30.0005, "lon": 30.9995},
          {"type": "way", "id": 10, "nodes": [1, 2, 3], "tags": {"highway": "motorway", "name:en": "Ring Rd"}},
          {"type": "way", "id": 20, "nodes": [2, 4], "tags": {"highway": "motorway_link", "destination": "Airport"}},
          {"type": "way", "id": 30, "nodes": [5, 2], "tags": {"highway": "motorway_link"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 20.0)
    assertEquals(FeatureType.OFF_RAMP, features.first().type)
    // Incoming way 30 ends at node 2 and implies oneway=yes -> blocked when going against it (2->5)
    val onRampLink = features.first().branches.first { it.wayId == 30L }
    assertEquals(BranchStatus.BLOCKED, onRampLink.status)
    val offRampLink = features.first().branches.first { it.wayId == 20L }
    assertEquals(BranchStatus.OPEN, offRampLink.status)
  }

  @Test
  fun aheadAnalyzer_suppressesFalseDeadEndNearUnloadedTileBorder() {
    val p1 = GeoPoint(30.0000, 31.0000)
    val p2 = GeoPoint(30.0010, 31.0000)
    val nodes =
      mapOf(
        1L to com.example.data.model.OsmNode(1L, p1),
        2L to com.example.data.model.OsmNode(2L, p2),
      )
    val ways =
      mapOf(
        1L to com.example.data.model.OsmWay(1L, listOf(1L, 2L), listOf(p1, p2), com.example.data.model.OsmTags(highway = "residential"), com.example.data.model.GraphDataSource.TILES),
      )
    val boundsOnlyNorth =
      listOf(
        com.example.data.model.LoadedTileBounds(
          z = 14,
          x = 1,
          y = 1,
          south = 29.9900,
          west = 30.9900,
          north = 30.00101, // ~1.1m from p2 -> within 3m of north border, north neighbor NOT loaded
          east = 31.0100,
        ),
      )
    val graph = com.example.data.model.RoadGraph(nodes, ways, mapOf(1L to listOf(1L), 2L to listOf(1L)), 0L, false, com.example.data.model.GraphDataSource.TILES, boundsOnlyNorth)
    val primary = MapMatcher.match(graph, GeoPoint(30.0002, 31.0000), 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 12.0)
    assertFalse(features.any { it.type == FeatureType.DEAD_END })
  }

  @Test
  fun aheadAnalyzer_detectsOverpassAboveUnderpassBelowTollAndRoadWorks() {
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0010, "lon": 31.0000, "tags": {"barrier": "toll_booth"}},
          {"type": "node", "id": 3, "lat": 30.0020, "lon": 31.0000},
          {"type": "node", "id": 4, "lat": 30.0030, "lon": 31.0000},
          {"type": "node", "id": 10, "lat": 30.0015, "lon": 30.9992},
          {"type": "node", "id": 11, "lat": 30.0015, "lon": 31.0008},
          {"type": "node", "id": 12, "lat": 30.0025, "lon": 30.9992},
          {"type": "node", "id": 13, "lat": 30.0025, "lon": 31.0008},
          {"type": "way", "id": 100, "nodes": [1, 2, 3], "tags": {"highway": "primary", "name:en": "Main"}},
          {"type": "way", "id": 101, "nodes": [3, 4], "tags": {"highway": "construction", "name:en": "Closed Section"}},
          {"type": "way", "id": 200, "nodes": [10, 11], "tags": {"highway": "secondary", "bridge": "yes", "layer": "1"}},
          {"type": "way", "id": 201, "nodes": [12, 13], "tags": {"highway": "secondary", "tunnel": "yes", "layer": "-1"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val pos = GeoPoint(30.0002, 31.0000)
    val primary = MapMatcher.match(graph, pos, 0.0, TravelMode.DRIVING, null).primary!!
    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, 12.0)

    assertTrue(features.any { it.type == FeatureType.TOLL_PLAZA })
    assertTrue(features.any { it.type == FeatureType.OVERPASS_ABOVE })
    assertTrue(features.any { it.type == FeatureType.ROAD_WORKS })

    val census = AheadGraphAnalyzer.computeFeatureCensus(graph, pos, TravelMode.DRIVING, 1500.0)
    assertTrue(census.any { it.type == FeatureType.TOLL_PLAZA })
    assertTrue(census.any { it.type == FeatureType.OVERPASS_ABOVE })
  }
}
