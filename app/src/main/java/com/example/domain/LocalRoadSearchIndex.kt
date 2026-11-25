package com.example.domain

import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DistanceKind
import com.example.data.model.EntryLegalityStatus
import com.example.data.model.FoundRoadMatch
import com.example.data.model.FoundRoadRelation
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.OsmWay
import com.example.data.model.PoiCategory
import com.example.data.model.PoiItem
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import java.text.Normalizer
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs

object LocalRoadSearchIndex {

  private val ARABIC_DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
  private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")
  private val PUNCTUATION_REGEX = Regex("[\\p{Punct}\\u060C\\u061B\\u061F]+")
  private val WHITESPACE_REGEX = Regex("\\s+")

  private val STREET_STOPWORDS =
    setOf(
      "street",
      "st",
      "avenue",
      "ave",
      "road",
      "rd",
      "highway",
      "hwy",
      "boulevard",
      "blvd",
      "lane",
      "ln",
      "drive",
      "dr",
      "way",
      "place",
      "pl",
      "شارع",
      "ش",
      "طريق",
      "ميدان",
      "محور",
      "كوبري",
    )

  /**
   * Worldwide search normalization:
   * - NFKD decomposition + stripping diacritics/combining marks and Arabic tatweel
   * - Arabic alef variants -> ا, ى -> ي, ة -> ه
   * - Arabic-Indic / Eastern Arabic-Indic digits -> ASCII digits
   * - Lowercase Latin, strip punctuation & street-type words (with non-stopword fallback)
   */
  fun normalizeSearchText(raw: String): String {
    val base = normalizeBase(raw)
    if (base.isEmpty()) return ""
    val tokens = base.split(' ').filter { it.isNotBlank() }
    val filtered = tokens.filter { it !in STREET_STOPWORDS }
    return if (filtered.isNotEmpty()) {
      filtered.joinToString(" ")
    } else {
      tokens.joinToString(" ")
    }
  }

  private fun normalizeBase(raw: String): String {
    if (raw.isBlank()) return ""
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
      val mapped =
        when (ch) {
          in '\u0660'..'\u0669' -> ('0'.code + (ch.code - 0x0660)).toChar()
          in '\u06F0'..'\u06F9' -> ('0'.code + (ch.code - 0x06F0)).toChar()
          'أ', 'إ', 'آ', 'ٱ' -> 'ا'
          'ى' -> 'ي'
          'ة' -> 'ه'
          '\u0640' -> ' '
          else -> ch
        }
      sb.append(mapped)
    }
    var step = ARABIC_DIACRITICS_REGEX.replace(sb.toString(), "")
    step = Normalizer.normalize(step, Normalizer.Form.NFKD)
    step = COMBINING_MARKS_REGEX.replace(step, "")
    step = step.lowercase(Locale.US)
    step = PUNCTUATION_REGEX.replace(step, " ")
    step = WHITESPACE_REGEX.replace(step, " ").trim()
    return step
  }

  private data class SearchCandidateGroup(
    val id: String,
    val name: BilingualName,
    val isStreet: Boolean,
    val placeType: String,
    val primaryWayId: Long?,
    val poiId: Long?,
    val ways: List<OsmWay>,
    val poi: PoiItem?,
    val searchTexts: List<String>,
    val anchorPoint: GeoPoint,
  )

  private data class CorridorWalkResult(
    val corridorNodeDist: Map<Long, Double>,
    val corridorWayIds: Set<Long>,
    val legalNodeDist: Map<Long, Double>,
    val legalNodeDirect: Map<Long, Boolean>,
    val legalNodeUnverified: Map<Long, Boolean>,
    val behindNodeDist: Map<Long, Double>,
    val behindWayIds: Set<Long>,
  )

  fun searchLocal(
    query: String,
    graph: RoadGraph,
    pois: List<PoiItem>,
    matched: RoadCandidate?,
    locationPoint: GeoPoint? = matched?.snappedPoint,
    headingDeg: Double? = null,
    trailingBreadcrumbs: List<GeoPoint> = emptyList(),
    mode: TravelMode = TravelMode.DRIVING,
    timelineEntries: List<AheadTimelineEntry> = emptyList(),
    maxResults: Int = 15,
  ): List<FoundRoadMatch> {
    val normQuery = normalizeSearchText(query)
    val baseQuery = normalizeBase(query)
    if (normQuery.isEmpty() && baseQuery.isEmpty()) return emptyList()

    val effectivePoint = matched?.snappedPoint ?: locationPoint ?: GeoPoint(0.0, 0.0)
    val effectiveHeading =
      headingDeg
        ?: matched?.let {
          val p1 = it.way.points[it.segmentIndex]
          val p2 = it.way.points[(it.segmentIndex + 1).coerceAtMost(it.way.points.lastIndex)]
          if (it.forwardAlongWay) GeoMath.bearingDeg(p1, p2) else GeoMath.bearingDeg(p2, p1)
        }
        ?: 0.0

    val walk =
      if (matched != null && graph.ways.isNotEmpty()) {
        computeCorridorAndGraphWalk(
          graph = graph,
          matched = matched,
          headingDeg = effectiveHeading,
          trailingBreadcrumbs = trailingBreadcrumbs,
          mode = mode,
        )
      } else {
        null
      }

    // Build searchable street groups & POI groups
    val candidates = mutableListOf<Pair<Int, SearchCandidateGroup>>()

    // 1) Group named ways by normalized display key
    val waysByGroupKey = LinkedHashMap<String, MutableList<OsmWay>>()
    for (way in graph.ways.values) {
      if (way.points.size < 2) continue
      if (way.id != matched?.way?.id && !AheadGraphAnalyzer.isRelevantForMode(way, mode)) continue
      val bName = BilingualName.fromOsmTags(way.tags)
      if (!bName.hasAny) continue
      val rawKey =
        listOfNotNull(bName.arabic, bName.english, bName.ref)
          .map { normalizeSearchText(it) }
          .firstOrNull { it.isNotEmpty() }
          ?: normalizeBase(bName.primaryText ?: bName.ref ?: "")
      if (rawKey.isEmpty()) continue
      waysByGroupKey.getOrPut("st_$rawKey") { mutableListOf() }.add(way)
    }

    for ((groupKey, wayList) in waysByGroupKey) {
      val repWay =
        wayList.maxByOrNull { w ->
          val b = BilingualName.fromOsmTags(w.tags)
          (if (b.arabic != null) 2 else 0) +
            (if (b.english != null) 2 else 0) +
            (if (w.source == GraphDataSource.OVERPASS) 1 else 0)
        } ?: wayList.first()
      val mergedName =
        BilingualName(
          arabic = wayList.firstNotNullOfOrNull { BilingualName.fromOsmTags(it.tags).arabic },
          english = wayList.firstNotNullOfOrNull { BilingualName.fromOsmTags(it.tags).english },
          ref = wayList.firstNotNullOfOrNull { BilingualName.fromOsmTags(it.tags).ref },
        )
      val texts =
        wayList
          .flatMap { w ->
            val bn = BilingualName.fromOsmTags(w.tags)
            listOfNotNull(bn.arabic, bn.english, bn.ref, w.tags.name, w.tags.nameEn, w.tags.nameAr)
          }
          .distinct()
      val score = scoreMatch(normQuery, baseQuery, texts)
      if (score <= 0) continue

      val nearestSnap =
        wayList
          .map { w -> w to GeoMath.snapToPolyline(effectivePoint, w.points) }
          .minByOrNull { it.second.distanceMeters }
      val anchor = nearestSnap?.second?.snappedPoint ?: repWay.points.first()
      val hw = repWay.tags.highway?.replace('_', ' ')?.replaceFirstChar { it.uppercase(Locale.US) } ?: "Street"

      candidates +=
        score to
          SearchCandidateGroup(
            id = groupKey,
            name = mergedName,
            isStreet = true,
            placeType = hw,
            primaryWayId = nearestSnap?.first?.id ?: repWay.id,
            poiId = null,
            ways = wayList,
            poi = null,
            searchTexts = texts,
            anchorPoint = anchor,
          )
    }

    // 2) Add matching POIs
    for (poi in pois) {
      if (!poi.name.hasAny) continue
      val texts = listOfNotNull(poi.name.arabic, poi.name.english, poi.name.ref, poi.osmTags["name"])
      val score = scoreMatch(normQuery, baseQuery, texts)
      if (score <= 0) continue
      val catLabel = poi.category.name.lowercase(Locale.US).replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
      candidates +=
        score to
          SearchCandidateGroup(
            id = "poi_${poi.id}",
            name = poi.name,
            isStreet = false,
            placeType = catLabel,
            primaryWayId = null,
            poiId = poi.id,
            ways = emptyList(),
            poi = poi,
            searchTexts = texts,
            anchorPoint = poi.point,
          )
    }

    val evaluated =
      candidates
        .sortedByDescending { it.first }
        .take(maxResults * 3)
        .map { (score, group) ->
          val match =
            evaluateGroup(
              group = group,
              graph = graph,
              matched = matched,
              originPoint = effectivePoint,
              headingDeg = effectiveHeading,
              trailingBreadcrumbs = trailingBreadcrumbs,
              mode = mode,
              timelineEntries = timelineEntries,
              walk = walk,
            )
          score to match
        }

    return evaluated
      .sortedWith(
        compareByDescending<Pair<Int, FoundRoadMatch>> { it.first }
          .thenBy { if (it.second.entryStatus == EntryLegalityStatus.BLOCKED) 1 else 0 }
          .thenBy { it.second.alongRoadDistanceMeters ?: Double.MAX_VALUE },
      )
      .take(maxResults)
      .map { it.second }
  }

  /**
   * Re-evaluates an already-selected [FoundRoadMatch] dynamically as GPS position / road graph updates.
   */
  fun refreshFoundMatch(
    existing: FoundRoadMatch,
    graph: RoadGraph,
    pois: List<PoiItem>,
    matched: RoadCandidate?,
    locationPoint: GeoPoint? = matched?.snappedPoint,
    headingDeg: Double? = null,
    trailingBreadcrumbs: List<GeoPoint> = emptyList(),
    mode: TravelMode = TravelMode.DRIVING,
    timelineEntries: List<AheadTimelineEntry> = emptyList(),
  ): FoundRoadMatch {
    if (graph.ways.isEmpty()) return existing
    val effectivePoint = matched?.snappedPoint ?: locationPoint ?: return existing
    val effectiveHeading =
      headingDeg
        ?: matched?.let {
          val p1 = it.way.points[it.segmentIndex]
          val p2 = it.way.points[(it.segmentIndex + 1).coerceAtMost(it.way.points.lastIndex)]
          if (it.forwardAlongWay) GeoMath.bearingDeg(p1, p2) else GeoMath.bearingDeg(p2, p1)
        }
        ?: 0.0

    val walk =
      if (matched != null) {
        computeCorridorAndGraphWalk(graph, matched, effectiveHeading, trailingBreadcrumbs, mode)
      } else {
        null
      }

    if (existing.isStreet) {
      val matchingWays =
        if (existing.wayId != null && graph.ways.containsKey(existing.wayId)) {
          val refName = existing.name.primaryText?.let { normalizeSearchText(it) }
          graph.ways.values.filter { w ->
            w.id == existing.wayId ||
              (refName != null && refName.isNotEmpty() &&
                listOfNotNull(
                  BilingualName.fromOsmTags(w.tags).arabic,
                  BilingualName.fromOsmTags(w.tags).english,
                  BilingualName.fromOsmTags(w.tags).ref,
                ).any { normalizeSearchText(it) == refName })
          }
        } else {
          emptyList()
        }
      if (matchingWays.isEmpty()) return existing
      val group =
        SearchCandidateGroup(
          id = existing.id,
          name = existing.name,
          isStreet = true,
          placeType = existing.placeType,
          primaryWayId = existing.wayId ?: matchingWays.first().id,
          poiId = null,
          ways = matchingWays,
          poi = null,
          searchTexts = emptyList(),
          anchorPoint = existing.targetPoint,
        )
      return evaluateGroup(
        group = group,
        graph = graph,
        matched = matched,
        originPoint = effectivePoint,
        headingDeg = effectiveHeading,
        trailingBreadcrumbs = trailingBreadcrumbs,
        mode = mode,
        timelineEntries = timelineEntries,
        walk = walk,
      )
    } else {
      val poi =
        existing.poiId?.let { id -> pois.firstOrNull { it.id == id } }
          ?: PoiItem(
            id = existing.poiId ?: -1L,
            name = existing.name,
            category = PoiCategory.OTHER,
            point = existing.targetPoint,
          )
      val group =
        SearchCandidateGroup(
          id = existing.id,
          name = existing.name,
          isStreet = false,
          placeType = existing.placeType,
          primaryWayId = null,
          poiId = existing.poiId,
          ways = emptyList(),
          poi = poi,
          searchTexts = emptyList(),
          anchorPoint = poi.point,
        )
      return evaluateGroup(
        group = group,
        graph = graph,
        matched = matched,
        originPoint = effectivePoint,
        headingDeg = effectiveHeading,
        trailingBreadcrumbs = trailingBreadcrumbs,
        mode = mode,
        timelineEntries = timelineEntries,
        walk = walk,
      )
    }
  }

  private fun scoreMatch(normQuery: String, baseQuery: String, candidateTexts: List<String>): Int {
    val qTokens = normQuery.split(' ').filter { it.isNotBlank() }
    var best = 0
    for (rawText in candidateTexts) {
      val normText = normalizeSearchText(rawText)
      val baseText = normalizeBase(rawText)
      if (normQuery.isNotEmpty() && normText.isNotEmpty()) {
        if (normText == normQuery) return 100
        if (normText.startsWith(normQuery)) best = maxOf(best, 85)
        else {
          val tTokens = normText.split(' ').filter { it.isNotBlank() }
          if (qTokens.isNotEmpty() && qTokens.all { qt -> tTokens.any { tt -> tt.startsWith(qt) } }) {
            best = maxOf(best, 68)
          } else if (normText.contains(normQuery)) {
            best = maxOf(best, 50)
          } else if (qTokens.size > 1 && qTokens.count { qt -> normText.contains(qt) } >= qTokens.size - 1) {
            best = maxOf(best, 30)
          }
        }
      }
      if (baseQuery.isNotEmpty() && baseText.isNotEmpty()) {
        if (baseText == baseQuery) best = maxOf(best, 95)
        else if (baseText.startsWith(baseQuery)) best = maxOf(best, 80)
        else if (baseText.contains(baseQuery)) best = maxOf(best, 42)
      }
    }
    return best
  }

  private fun computeCorridorAndGraphWalk(
    graph: RoadGraph,
    matched: RoadCandidate,
    headingDeg: Double,
    trailingBreadcrumbs: List<GeoPoint>,
    mode: TravelMode,
  ): CorridorWalkResult {
    val corridorNodeDist = HashMap<Long, Double>()
    val corridorWayIds = HashSet<Long>()
    val behindNodeDist = HashMap<Long, Double>()
    val behindWayIds = HashSet<Long>()

    // 1) Trace forward main corridor (up to ~6 km) for direct-entry reference
    run {
      var curWay = matched.way
      var fwd = matched.forwardAlongWay
      var startIdx = if (fwd) matched.segmentIndex + 1 else matched.segmentIndex
      var prevPt = matched.snappedPoint
      var dist = 0.0
      val visited = HashSet<Long>()
      var guard = 0
      while (dist < 6_000.0 && guard++ < 120) {
        visited += curWay.id
        corridorWayIds += curWay.id
        val indices = if (fwd) (startIdx until curWay.points.size) else (startIdx downTo 0)
        var lastNodeId: Long? = null
        var lastBearing = headingDeg
        for (idx in indices) {
          val pt = curWay.points[idx]
          val d = GeoMath.haversineMeters(prevPt, pt)
          dist += d
          if (d > 0.5) lastBearing = GeoMath.bearingDeg(prevPt, pt)
          val nid = curWay.nodeIds.getOrNull(idx)
          if (nid != null) {
            corridorNodeDist[nid] = minOf(corridorNodeDist[nid] ?: Double.MAX_VALUE, dist)
            lastNodeId = nid
          }
          prevPt = pt
          if (dist >= 6_000.0) break
        }
        if (lastNodeId == null || dist >= 6_000.0) break
        var nextHop: Triple<OsmWay, Int, Boolean>? = null
        var bestDiff = 52.0
        for (wId in graph.nodeToWays[lastNodeId].orEmpty()) {
          if (wId in visited) continue
          val w = graph.ways[wId] ?: continue
          if (!AheadGraphAnalyzer.isRelevantForMode(w, mode)) continue
          if (AheadGraphAnalyzer.evaluateBranchStatus(w, true, mode) == BranchStatus.BLOCKED &&
            AheadGraphAnalyzer.evaluateBranchStatus(w, false, mode) == BranchStatus.BLOCKED
          ) {
            continue
          }
          val idx = w.nodeIds.indexOf(lastNodeId)
          if (idx < 0) continue
          if (idx + 1 < w.points.size && AheadGraphAnalyzer.evaluateBranchStatus(w, true, mode) != BranchStatus.BLOCKED) {
            val diff = GeoMath.absAngleDiff(lastBearing, GeoMath.bearingDeg(w.points[idx], w.points[idx + 1]))
            if (diff < bestDiff) {
              bestDiff = diff
              nextHop = Triple(w, idx, true)
            }
          }
          if (idx - 1 >= 0 && AheadGraphAnalyzer.evaluateBranchStatus(w, false, mode) != BranchStatus.BLOCKED) {
            val diff = GeoMath.absAngleDiff(lastBearing, GeoMath.bearingDeg(w.points[idx], w.points[idx - 1]))
            if (diff < bestDiff) {
              bestDiff = diff
              nextHop = Triple(w, idx, false)
            }
          }
        }
        if (nextHop == null) break
        curWay = nextHop.first
        fwd = nextHop.third
        startIdx = if (fwd) nextHop.second + 1 else nextHop.second - 1
      }
    }

    // 2) Trace backward along matched way + upstream corridor ways (up to ~4.5 km) for "Passed X ago"
    run {
      var curWay = matched.way
      var backFwd = !matched.forwardAlongWay
      var startIdx = if (backFwd) matched.segmentIndex + 1 else matched.segmentIndex
      var prevPt = matched.snappedPoint
      var dist = 0.0
      val visited = HashSet<Long>()
      var guard = 0
      var backBearing = (headingDeg + 180.0) % 360.0
      while (dist < 4_500.0 && guard++ < 100) {
        visited += curWay.id
        behindWayIds += curWay.id
        val indices = if (backFwd) (startIdx until curWay.points.size) else (startIdx downTo 0)
        var lastNodeId: Long? = null
        for (idx in indices) {
          val pt = curWay.points[idx]
          val d = GeoMath.haversineMeters(prevPt, pt)
          dist += d
          if (d > 0.5) backBearing = GeoMath.bearingDeg(prevPt, pt)
          val nid = curWay.nodeIds.getOrNull(idx)
          if (nid != null) {
            behindNodeDist[nid] = minOf(behindNodeDist[nid] ?: Double.MAX_VALUE, dist)
            lastNodeId = nid
          }
          prevPt = pt
          if (dist >= 4_500.0) break
        }
        if (lastNodeId == null || dist >= 4_500.0) break
        var nextHop: Triple<OsmWay, Int, Boolean>? = null
        var bestScore = Double.MAX_VALUE
        for (wId in graph.nodeToWays[lastNodeId].orEmpty()) {
          if (wId in visited) continue
          val w = graph.ways[wId] ?: continue
          if (!AheadGraphAnalyzer.isRelevantForMode(w, mode)) continue
          val idx = w.nodeIds.indexOf(lastNodeId)
          if (idx < 0) continue
          if (idx + 1 < w.points.size) {
            val diff = GeoMath.absAngleDiff(backBearing, GeoMath.bearingDeg(w.points[idx], w.points[idx + 1]))
            if (diff < 65.0) {
              val crumbBias =
                if (trailingBreadcrumbs.isNotEmpty()) {
                  trailingBreadcrumbs.minOf { GeoMath.haversineMeters(w.points[idx + 1], it) } * 0.05
                } else {
                  0.0
                }
              val sc = diff + crumbBias
              if (sc < bestScore) {
                bestScore = sc
                nextHop = Triple(w, idx, true)
              }
            }
          }
          if (idx - 1 >= 0) {
            val diff = GeoMath.absAngleDiff(backBearing, GeoMath.bearingDeg(w.points[idx], w.points[idx - 1]))
            if (diff < 65.0) {
              val crumbBias =
                if (trailingBreadcrumbs.isNotEmpty()) {
                  trailingBreadcrumbs.minOf { GeoMath.haversineMeters(w.points[idx - 1], it) } * 0.05
                } else {
                  0.0
                }
              val sc = diff + crumbBias
              if (sc < bestScore) {
                bestScore = sc
                nextHop = Triple(w, idx, false)
              }
            }
          }
        }
        if (nextHop == null) break
        curWay = nextHop.first
        backFwd = nextHop.third
        startIdx = if (backFwd) nextHop.second + 1 else nextHop.second - 1
      }
    }

    // 3) Directed Legal Dijkstra along road graph up to 6 km
    // State: (nodeId, dist, offCorridorTurns, hasUnverifiedEdge)
    data class DState(
      val nodeId: Long,
      val dist: Double,
      val turnsOffCorridor: Int,
      val hasUnverified: Boolean,
    ) : Comparable<DState> {
      override fun compareTo(other: DState): Int = dist.compareTo(other.dist)
    }

    val legalDist = HashMap<Long, Double>()
    val legalDirect = HashMap<Long, Boolean>()
    val legalUnverified = HashMap<Long, Boolean>()
    val pq = PriorityQueue<DState>()

    // Seed from ahead vertex of current segment along matched.way if legal
    val aheadIdx = if (matched.forwardAlongWay) matched.segmentIndex + 1 else matched.segmentIndex
    val seedAheadNodeId = matched.way.nodeIds.getOrNull(aheadIdx)
    var seedAheadDist = Double.MAX_VALUE
    val mStatus = AheadGraphAnalyzer.evaluateBranchStatus(matched.way, matched.forwardAlongWay, mode)
    if (mStatus != BranchStatus.BLOCKED && seedAheadNodeId != null && aheadIdx in matched.way.points.indices) {
      val seedDist = GeoMath.haversineMeters(matched.snappedPoint, matched.way.points[aheadIdx])
      seedAheadDist = seedDist
      val isUnv = mStatus == BranchStatus.UNVERIFIED
      legalDist[seedAheadNodeId] = seedDist
      legalDirect[seedAheadNodeId] = true
      legalUnverified[seedAheadNodeId] = isUnv
      pq.add(DState(seedAheadNodeId, seedDist, turnsOffCorridor = 0, hasUnverified = isUnv))
    }
    // In walking or two-way road scenarios, also seed the opposite vertex of current segment if legal
    val backStatus = AheadGraphAnalyzer.evaluateBranchStatus(matched.way, !matched.forwardAlongWay, mode)
    if (backStatus != BranchStatus.BLOCKED && mode == TravelMode.WALKING) {
      val backIdx = if (matched.forwardAlongWay) matched.segmentIndex else matched.segmentIndex + 1
      val seedNodeId = matched.way.nodeIds.getOrNull(backIdx)
      if (seedNodeId != null && backIdx in matched.way.points.indices) {
        val seedDist = GeoMath.haversineMeters(matched.snappedPoint, matched.way.points[backIdx])
        legalDist[seedNodeId] = minOf(legalDist[seedNodeId] ?: Double.MAX_VALUE, seedDist)
        legalDirect.putIfAbsent(seedNodeId, false)
        legalUnverified.putIfAbsent(seedNodeId, backStatus == BranchStatus.UNVERIFIED)
        pq.add(DState(seedNodeId, seedDist, turnsOffCorridor = 1, hasUnverified = backStatus == BranchStatus.UNVERIFIED))
      }
    }

    val maxDijkstraMeters = 6_500.0
    while (pq.isNotEmpty()) {
      val cur = pq.poll() ?: break
      if (cur.dist > (legalDist[cur.nodeId] ?: Double.MAX_VALUE) + 0.01) continue
      if (cur.dist > maxDijkstraMeters) break

      val curOnCorridor = corridorWayIds.containsAnyNode(graph, cur.nodeId) && cur.turnsOffCorridor == 0
      for (wId in graph.nodeToWays[cur.nodeId].orEmpty()) {
        val way = graph.ways[wId] ?: continue
        if (way.id != matched.way.id && !AheadGraphAnalyzer.isRelevantForMode(way, mode)) continue
        val idx = way.nodeIds.indexOf(cur.nodeId)
        if (idx < 0) continue

        // Explore forward along way
        val skipReverseOriginFwd =
          mode == TravelMode.DRIVING &&
            cur.turnsOffCorridor == 0 &&
            cur.nodeId == seedAheadNodeId &&
            cur.dist <= seedAheadDist + 0.01 &&
            way.id == matched.way.id &&
            !matched.forwardAlongWay
        if (idx + 1 < way.points.size && !skipReverseOriginFwd) {
          val st = AheadGraphAnalyzer.evaluateBranchStatus(way, outgoingForward = true, mode = mode)
          if (st != BranchStatus.BLOCKED) {
            val edgeLen = GeoMath.haversineMeters(way.points[idx], way.points[idx + 1])
            val nxtNode = way.nodeIds[idx + 1]
            val nd = cur.dist + edgeLen
            val nextTurns =
              if (curOnCorridor && way.id in corridorWayIds) 0 else cur.turnsOffCorridor + 1
            val nextUnv = cur.hasUnverified || (st == BranchStatus.UNVERIFIED)
            if (nd <= maxDijkstraMeters && nd + 0.01 < (legalDist[nxtNode] ?: Double.MAX_VALUE)) {
              legalDist[nxtNode] = nd
              legalDirect[nxtNode] = (nextTurns <= 1)
              legalUnverified[nxtNode] = nextUnv
              pq.add(DState(nxtNode, nd, nextTurns, nextUnv))
            }
          }
        }
        // Explore backward along way
        val skipReverseOriginBack =
          mode == TravelMode.DRIVING &&
            cur.turnsOffCorridor == 0 &&
            cur.nodeId == seedAheadNodeId &&
            cur.dist <= seedAheadDist + 0.01 &&
            way.id == matched.way.id &&
            matched.forwardAlongWay
        if (idx - 1 >= 0 && !skipReverseOriginBack) {
          val st = AheadGraphAnalyzer.evaluateBranchStatus(way, outgoingForward = false, mode = mode)
          if (st != BranchStatus.BLOCKED) {
            val edgeLen = GeoMath.haversineMeters(way.points[idx], way.points[idx - 1])
            val nxtNode = way.nodeIds[idx - 1]
            val nd = cur.dist + edgeLen
            val nextTurns =
              if (curOnCorridor && way.id in corridorWayIds) 0 else cur.turnsOffCorridor + 1
            val nextUnv = cur.hasUnverified || (st == BranchStatus.UNVERIFIED)
            if (nd <= maxDijkstraMeters && nd + 0.01 < (legalDist[nxtNode] ?: Double.MAX_VALUE)) {
              legalDist[nxtNode] = nd
              legalDirect[nxtNode] = (nextTurns <= 1)
              legalUnverified[nxtNode] = nextUnv
              pq.add(DState(nxtNode, nd, nextTurns, nextUnv))
            }
          }
        }
      }
    }

    return CorridorWalkResult(
      corridorNodeDist = corridorNodeDist,
      corridorWayIds = corridorWayIds,
      legalNodeDist = legalDist,
      legalNodeDirect = legalDirect,
      legalNodeUnverified = legalUnverified,
      behindNodeDist = behindNodeDist,
      behindWayIds = behindWayIds,
    )
  }

  private fun Set<Long>.containsAnyNode(graph: RoadGraph, nodeId: Long): Boolean {
    for (wId in graph.nodeToWays[nodeId].orEmpty()) {
      if (wId in this) return true
    }
    return false
  }

  private fun evaluateGroup(
    group: SearchCandidateGroup,
    graph: RoadGraph,
    matched: RoadCandidate?,
    originPoint: GeoPoint,
    headingDeg: Double,
    trailingBreadcrumbs: List<GeoPoint>,
    mode: TravelMode,
    timelineEntries: List<AheadTimelineEntry>,
    walk: CorridorWalkResult?,
  ): FoundRoadMatch {
    if (group.isStreet) {
      return evaluateStreetGroup(
        group = group,
        matched = matched,
        originPoint = originPoint,
        headingDeg = headingDeg,
        mode = mode,
        timelineEntries = timelineEntries,
        walk = walk,
      )
    } else {
      return evaluatePoiGroup(
        group = group,
        graph = graph,
        matched = matched,
        originPoint = originPoint,
        headingDeg = headingDeg,
        trailingBreadcrumbs = trailingBreadcrumbs,
        mode = mode,
        timelineEntries = timelineEntries,
        walk = walk,
      )
    }
  }

  private fun evaluateStreetGroup(
    group: SearchCandidateGroup,
    matched: RoadCandidate?,
    originPoint: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    timelineEntries: List<AheadTimelineEntry>,
    walk: CorridorWalkResult?,
  ): FoundRoadMatch {
    val wayIds = group.ways.mapTo(HashSet()) { it.id }
    val matchedTimelineFeature =
      timelineEntries.firstOrNull { e ->
        e.featureIndex != null &&
          (e.primaryWayId in wayIds || e.branchWayIds.any { it in wayIds })
      }?.featureIndex

    // Gather up to 220 world points for purple highlight on map
    val polyline =
      group.ways
        .sortedBy { GeoMath.snapToPolyline(originPoint, it.points).distanceMeters }
        .firstOrNull()?.points
        .orEmpty()

    if (matched == null || walk == null) {
      val relation = inferGeometricRelation(originPoint, headingDeg, group.anchorPoint, group.ways.firstOrNull())
      return FoundRoadMatch(
        id = group.id,
        name = group.name,
        wayId = group.primaryWayId,
        poiId = null,
        isStreet = true,
        placeType = group.placeType,
        targetPoint = group.anchorPoint,
        entryPoint = group.anchorPoint,
        polyline = polyline,
        relation = relation,
        alongRoadDistanceMeters = null,
        distanceKind = DistanceKind.UNAVAILABLE,
        entryStatus = EntryLegalityStatus.UNVERIFIED,
        matchedFeatureIndex = matchedTimelineFeature,
      )
    }

    // Special case: user is CURRENTLY on this street
    if (matched.way.id in wayIds) {
      val st = AheadGraphAnalyzer.evaluateBranchStatus(matched.way, matched.forwardAlongWay, mode)
      val entrySt =
        when (st) {
          BranchStatus.BLOCKED -> EntryLegalityStatus.BLOCKED
          BranchStatus.UNVERIFIED -> EntryLegalityStatus.UNVERIFIED
          BranchStatus.OPEN -> EntryLegalityStatus.OPEN
        }
      return FoundRoadMatch(
        id = group.id,
        name = group.name,
        wayId = matched.way.id,
        poiId = null,
        isStreet = true,
        placeType = group.placeType,
        targetPoint = matched.snappedPoint,
        entryPoint = matched.snappedPoint,
        polyline = polyline,
        relation = FoundRoadRelation.AHEAD,
        alongRoadDistanceMeters = 0.0,
        distanceKind = DistanceKind.ROAD_SHORTEST,
        entryStatus = entrySt,
        matchedFeatureIndex = matchedTimelineFeature,
      )
    }

    // 1) Check legal entry into any node on target ways
    var bestLegalDist = Double.MAX_VALUE
    var bestLegalPoint: GeoPoint? = null
    var bestLegalWay: OsmWay? = null
    var bestIsDirect = false
    var bestIsUnverified = false

    // 2) Check behind entry along user's backward trace
    var bestBehindDist = Double.MAX_VALUE
    var bestBehindPoint: GeoPoint? = null
    var bestBehindWay: OsmWay? = null

    // 3) Check direct corridor connection (especially to catch BLOCKED one-way against user!)
    var bestCorrTouchDist = Double.MAX_VALUE
    var bestCorrTouchPoint: GeoPoint? = null
    var bestCorrTouchWay: OsmWay? = null
    var corrTouchHasOpen = false
    var corrTouchHasUnverified = false
    var corrTouchHasBlocked = false

    for (way in group.ways) {
      for (i in way.nodeIds.indices) {
        val nid = way.nodeIds[i]
        val pt = way.points.getOrNull(i) ?: continue

        // Evaluate legal status entering `way` at index `i`
        val canEnterFwd = i + 1 < way.points.size
        val canEnterBack = i - 1 >= 0
        val stFwd = if (canEnterFwd) AheadGraphAnalyzer.evaluateBranchStatus(way, true, mode) else null
        val stBack = if (canEnterBack) AheadGraphAnalyzer.evaluateBranchStatus(way, false, mode) else null
        val entryStatuses = listOfNotNull(stFwd, stBack)
        val canEnterLegally = entryStatuses.any { it != BranchStatus.BLOCKED }
        val entryAllBlocked = entryStatuses.isNotEmpty() && entryStatuses.all { it == BranchStatus.BLOCKED }
        val entryUnverified = entryStatuses.any { it == BranchStatus.UNVERIFIED }

        val corrDist = walk.corridorNodeDist[nid]
        if (corrDist != null) {
          if (corrDist < bestCorrTouchDist) {
            bestCorrTouchDist = corrDist
            bestCorrTouchPoint = pt
            bestCorrTouchWay = way
          }
          if (entryAllBlocked) corrTouchHasBlocked = true
          if (entryStatuses.any { it == BranchStatus.OPEN }) corrTouchHasOpen = true
          else if (entryUnverified) corrTouchHasUnverified = true

          // Directly attached to forward corridor and legally enterable
          if (canEnterLegally && corrDist < bestLegalDist) {
            bestLegalDist = corrDist
            bestLegalPoint = pt
            bestLegalWay = way
            bestIsDirect = true
            bestIsUnverified = entryUnverified && entryStatuses.none { it == BranchStatus.OPEN }
          }
        }

        val lDist = walk.legalNodeDist[nid]
        if (lDist != null && canEnterLegally) {
          val isDirectNode = walk.legalNodeDirect[nid] == true
          val isUnv = (walk.legalNodeUnverified[nid] == true) ||
            (entryUnverified && entryStatuses.none { it == BranchStatus.OPEN })
          if (lDist < bestLegalDist) {
            bestLegalDist = lDist
            bestLegalPoint = pt
            bestLegalWay = way
            bestIsDirect = isDirectNode && !wayIds.contains(matched.way.id).not().let { false }
            bestIsUnverified = isUnv
          }
        }

        val bDist = walk.behindNodeDist[nid]
        if (bDist != null && bDist < bestBehindDist) {
          bestBehindDist = bDist
          bestBehindPoint = pt
          bestBehindWay = way
        }
      }
    }

    // Decide primary relation & legality:
    // If behind on trace AND either no legal forward path OR closer behind than looping forward:
    val preferBehind =
      bestBehindPoint != null &&
        (bestLegalPoint == null || bestBehindDist <= bestLegalDist + 25.0)

    if (preferBehind) {
      val behindWay = bestBehindWay ?: group.ways.first()
      val behindIdx = behindWay.nodeIds.indexOfFirst { walk.behindNodeDist.containsKey(it) }.coerceAtLeast(0)
      val fwdSt = if (behindIdx + 1 < behindWay.points.size) AheadGraphAnalyzer.evaluateBranchStatus(behindWay, true, mode) else null
      val backSt = if (behindIdx - 1 >= 0) AheadGraphAnalyzer.evaluateBranchStatus(behindWay, false, mode) else null
      val statuses = listOfNotNull(fwdSt, backSt)
      val legality =
        when {
          statuses.isNotEmpty() && statuses.all { it == BranchStatus.BLOCKED } -> EntryLegalityStatus.BLOCKED
          bestLegalPoint != null -> EntryLegalityStatus.INDIRECT
          statuses.any { it == BranchStatus.UNVERIFIED } -> EntryLegalityStatus.UNVERIFIED
          else -> EntryLegalityStatus.INDIRECT
        }
      return FoundRoadMatch(
        id = group.id,
        name = group.name,
        wayId = behindWay.id,
        poiId = null,
        isStreet = true,
        placeType = group.placeType,
        targetPoint = bestBehindPoint!!,
        entryPoint = bestBehindPoint,
        polyline = polyline,
        relation = FoundRoadRelation.BEHIND,
        alongRoadDistanceMeters = bestBehindDist,
        distanceKind = DistanceKind.ROAD_SHORTEST,
        entryStatus = legality,
        matchedFeatureIndex = matchedTimelineFeature,
      )
    }

    if (bestLegalPoint != null) {
      val legality =
        when {
          bestIsUnverified -> EntryLegalityStatus.UNVERIFIED
          bestIsDirect && bestCorrTouchPoint != null -> EntryLegalityStatus.OPEN
          bestIsDirect -> EntryLegalityStatus.OPEN
          else -> EntryLegalityStatus.INDIRECT
        }
      val rel =
        if (isParallelCorridorRoad(originPoint, headingDeg, group.ways, bestLegalPoint)) {
          if (bestIsDirect) FoundRoadRelation.AHEAD else FoundRoadRelation.PARALLEL
        } else {
          FoundRoadRelation.AHEAD
        }
      return FoundRoadMatch(
        id = group.id,
        name = group.name,
        wayId = bestLegalWay?.id ?: group.primaryWayId,
        poiId = null,
        isStreet = true,
        placeType = group.placeType,
        targetPoint = bestLegalPoint,
        entryPoint = bestLegalPoint,
        polyline = polyline,
        relation = rel,
        alongRoadDistanceMeters = bestLegalDist,
        distanceKind = DistanceKind.ROAD_SHORTEST,
        entryStatus = legality,
        matchedFeatureIndex = matchedTimelineFeature,
      )
    }

    // Touches forward corridor ONLY via BLOCKED entrance (e.g. one-way against user or road works/construction)
    if (bestCorrTouchPoint != null && corrTouchHasBlocked && !corrTouchHasOpen) {
      return FoundRoadMatch(
        id = group.id,
        name = group.name,
        wayId = bestCorrTouchWay?.id ?: group.primaryWayId,
        poiId = null,
        isStreet = true,
        placeType = group.placeType,
        targetPoint = bestCorrTouchPoint,
        entryPoint = bestCorrTouchPoint,
        polyline = polyline,
        relation = FoundRoadRelation.AHEAD,
        alongRoadDistanceMeters = bestCorrTouchDist,
        distanceKind = DistanceKind.ROAD_SHORTEST,
        entryStatus = EntryLegalityStatus.BLOCKED,
        matchedFeatureIndex = matchedTimelineFeature,
      )
    }

    // Disconnected from local routed corridor -> unavailable road distance, unverified entry
    val geomRel = inferGeometricRelation(originPoint, headingDeg, group.anchorPoint, group.ways.firstOrNull())
    return FoundRoadMatch(
      id = group.id,
      name = group.name,
      wayId = group.primaryWayId,
      poiId = null,
      isStreet = true,
      placeType = group.placeType,
      targetPoint = group.anchorPoint,
      entryPoint = group.anchorPoint,
      polyline = polyline,
      relation = geomRel,
      alongRoadDistanceMeters = null,
      distanceKind = DistanceKind.UNAVAILABLE,
      entryStatus = EntryLegalityStatus.UNVERIFIED,
      matchedFeatureIndex = matchedTimelineFeature,
    )
  }

  private fun evaluatePoiGroup(
    group: SearchCandidateGroup,
    graph: RoadGraph,
    matched: RoadCandidate?,
    originPoint: GeoPoint,
    headingDeg: Double,
    trailingBreadcrumbs: List<GeoPoint>,
    mode: TravelMode,
    timelineEntries: List<AheadTimelineEntry>,
    walk: CorridorWalkResult?,
  ): FoundRoadMatch {
    val poi = group.poi!!
    val lateralMax = if (mode == TravelMode.DRIVING) 80.0 else 45.0
    val nearTimeline =
      timelineEntries.firstOrNull { e ->
        e.featureIndex != null && GeoMath.haversineMeters(e.anchor, poi.point) < 60.0
      }?.featureIndex

    if (matched == null || walk == null) {
      val rel = inferGeometricRelation(originPoint, headingDeg, poi.point, null)
      return FoundRoadMatch(
        id = group.id,
        name = group.name,
        wayId = null,
        poiId = poi.id,
        isStreet = false,
        placeType = group.placeType,
        targetPoint = poi.point,
        entryPoint = poi.point,
        polyline = emptyList(),
        relation = rel,
        alongRoadDistanceMeters = null,
        distanceKind = DistanceKind.UNAVAILABLE,
        entryStatus = EntryLegalityStatus.UNVERIFIED,
        matchedFeatureIndex = nearTimeline,
      )
    }

    // Find nearest graph road segment to POI to compute exact along-road distance
    var bestWay: OsmWay? = null
    var bestSnap: GeoMath.PolylineSnap? = null
    for (w in graph.ways.values) {
      if (w.points.size < 2) continue
      if (w.id != matched.way.id && !AheadGraphAnalyzer.isRelevantForMode(w, mode)) continue
      val snap = GeoMath.snapToPolyline(poi.point, w.points)
      if (snap.distanceMeters <= lateralMax && (bestSnap == null || snap.distanceMeters < bestSnap.distanceMeters)) {
        bestSnap = snap
        bestWay = w
      }
    }

    if (bestWay != null && bestSnap != null) {
      val nidA = bestWay.nodeIds.getOrNull(bestSnap.segmentIndex)
      val nidB = bestWay.nodeIds.getOrNull(bestSnap.segmentIndex + 1)
      val ptA = bestWay.points[bestSnap.segmentIndex]
      val ptB = bestWay.points[(bestSnap.segmentIndex + 1).coerceAtMost(bestWay.points.lastIndex)]

      // Check behind first if on matched.way behind user or backward node trace
      val behindCandidates = mutableListOf<Double>()
      if (bestWay.id == matched.way.id) {
        val segSigned =
          if (matched.forwardAlongWay) bestSnap.segmentIndex - matched.segmentIndex
          else matched.segmentIndex - bestSnap.segmentIndex
        if (segSigned < 0) {
          val dA = nidA?.let { walk.behindNodeDist[it] }
          val dB = nidB?.let { walk.behindNodeDist[it] }
          listOfNotNull(
            dA?.plus(GeoMath.haversineMeters(ptA, bestSnap.snappedPoint)),
            dB?.plus(GeoMath.haversineMeters(ptB, bestSnap.snappedPoint)),
          ).minOrNull()?.let { behindCandidates += it }
        }
      } else {
        nidA?.let { walk.behindNodeDist[it]?.plus(GeoMath.haversineMeters(ptA, bestSnap.snappedPoint)) }
          ?.let { behindCandidates += it }
        nidB?.let { walk.behindNodeDist[it]?.plus(GeoMath.haversineMeters(ptB, bestSnap.snappedPoint)) }
          ?.let { behindCandidates += it }
      }
      val minBehind = behindCandidates.minOrNull()

      // Check legal ahead along graph
      val aheadDistA = nidA?.let { walk.legalNodeDist[it]?.plus(GeoMath.haversineMeters(ptA, bestSnap.snappedPoint)) }
      val aheadDistB = nidB?.let { walk.legalNodeDist[it]?.plus(GeoMath.haversineMeters(ptB, bestSnap.snappedPoint)) }
      val bestLegalPair =
        listOfNotNull(
          aheadDistA?.let { it to nidA },
          aheadDistB?.let { it to nidB },
        ).minByOrNull { it.first }

      val onMatchedWayAhead =
        bestWay.id == matched.way.id &&
          ((matched.forwardAlongWay && bestSnap.segmentIndex >= matched.segmentIndex) ||
            (!matched.forwardAlongWay && bestSnap.segmentIndex <= matched.segmentIndex))

      if (minBehind != null && !onMatchedWayAhead && (bestLegalPair == null || minBehind <= bestLegalPair.first + 25.0)) {
        return FoundRoadMatch(
          id = group.id,
          name = group.name,
          wayId = bestWay.id,
          poiId = poi.id,
          isStreet = false,
          placeType = group.placeType,
          targetPoint = poi.point,
          entryPoint = bestSnap.snappedPoint,
          polyline = emptyList(),
          relation = FoundRoadRelation.BEHIND,
          alongRoadDistanceMeters = minBehind,
          distanceKind = DistanceKind.ROAD_SHORTEST,
          entryStatus = if (bestLegalPair != null) EntryLegalityStatus.INDIRECT else EntryLegalityStatus.UNVERIFIED,
          matchedFeatureIndex = nearTimeline,
        )
      }

      if (onMatchedWayAhead) {
        val dAlong =
          bestLegalPair?.first
            ?: run {
              // On exact same segment ahead of snappedPoint
              val p1 = matched.way.points[matched.segmentIndex]
              val p2 = matched.way.points[(matched.segmentIndex + 1).coerceAtMost(matched.way.points.lastIndex)]
              val segBearing = if (matched.forwardAlongWay) GeoMath.bearingDeg(p1, p2) else GeoMath.bearingDeg(p2, p1)
              val delta = GeoMath.absAngleDiff(segBearing, GeoMath.bearingDeg(matched.snappedPoint, bestSnap.snappedPoint))
              if (delta <= 90.0) GeoMath.haversineMeters(matched.snappedPoint, bestSnap.snappedPoint) else null
            }
        if (dAlong != null) {
          return FoundRoadMatch(
            id = group.id,
            name = group.name,
            wayId = bestWay.id,
            poiId = poi.id,
            isStreet = false,
            placeType = group.placeType,
            targetPoint = poi.point,
            entryPoint = bestSnap.snappedPoint,
            polyline = emptyList(),
            relation = FoundRoadRelation.AHEAD,
            alongRoadDistanceMeters = dAlong,
            distanceKind = DistanceKind.ROAD_SHORTEST,
            entryStatus = EntryLegalityStatus.OPEN,
            matchedFeatureIndex = nearTimeline,
          )
        }
      }

      if (bestLegalPair != null) {
        val nodeId = bestLegalPair.second
        val isDirect = walk.legalNodeDirect[nodeId] == true || bestWay.id in walk.corridorWayIds
        val isUnv = walk.legalNodeUnverified[nodeId] == true
        val status =
          when {
            isUnv -> EntryLegalityStatus.UNVERIFIED
            isDirect -> EntryLegalityStatus.OPEN
            else -> EntryLegalityStatus.INDIRECT
          }
        return FoundRoadMatch(
          id = group.id,
          name = group.name,
          wayId = bestWay.id,
          poiId = poi.id,
          isStreet = false,
          placeType = group.placeType,
          targetPoint = poi.point,
          entryPoint = bestSnap.snappedPoint,
          polyline = emptyList(),
          relation = FoundRoadRelation.AHEAD,
          alongRoadDistanceMeters = bestLegalPair.first,
          distanceKind = DistanceKind.ROAD_SHORTEST,
          entryStatus = status,
          matchedFeatureIndex = nearTimeline,
        )
      }
    }

    // Fallback: check trailing breadcrumbs if road behind isn't fully bridged in current window
    if (trailingBreadcrumbs.size >= 2) {
      val trailPts = trailingBreadcrumbs + originPoint
      val snap = GeoMath.snapToPolyline(poi.point, trailPts)
      if (snap.distanceMeters <= lateralMax) {
        var behindM = GeoMath.haversineMeters(snap.snappedPoint, trailPts[snap.segmentIndex + 1])
        for (i in (snap.segmentIndex + 1) until trailPts.lastIndex) {
          behindM += GeoMath.haversineMeters(trailPts[i], trailPts[i + 1])
        }
        return FoundRoadMatch(
          id = group.id,
          name = group.name,
          wayId = null,
          poiId = poi.id,
          isStreet = false,
          placeType = group.placeType,
          targetPoint = poi.point,
          entryPoint = snap.snappedPoint,
          polyline = emptyList(),
          relation = FoundRoadRelation.BEHIND,
          alongRoadDistanceMeters = behindM,
          distanceKind = DistanceKind.ROAD_SHORTEST,
          entryStatus = EntryLegalityStatus.UNVERIFIED,
          matchedFeatureIndex = nearTimeline,
        )
      }
    }

    val rel = inferGeometricRelation(originPoint, headingDeg, poi.point, bestWay)
    return FoundRoadMatch(
      id = group.id,
      name = group.name,
      wayId = bestWay?.id,
      poiId = poi.id,
      isStreet = false,
      placeType = group.placeType,
      targetPoint = poi.point,
      entryPoint = bestSnap?.snappedPoint ?: poi.point,
      polyline = emptyList(),
      relation = rel,
      alongRoadDistanceMeters = null,
      distanceKind = DistanceKind.UNAVAILABLE,
      entryStatus = EntryLegalityStatus.UNVERIFIED,
      matchedFeatureIndex = nearTimeline,
    )
  }

  private fun isParallelCorridorRoad(
    originPoint: GeoPoint,
    headingDeg: Double,
    ways: List<OsmWay>,
    entryPt: GeoPoint,
  ): Boolean {
    val w = ways.firstOrNull { it.points.size >= 2 } ?: return false
    val snap = GeoMath.snapToPolyline(originPoint, w.points)
    val parallelAngle =
      minOf(
        GeoMath.absAngleDiff(headingDeg, snap.segmentBearingDeg),
        GeoMath.absAngleDiff((headingDeg + 180.0) % 360.0, snap.segmentBearingDeg),
      )
    val lateral = snap.distanceMeters
    val brngToEntry = GeoMath.bearingDeg(originPoint, entryPt)
    val angleToEntry = GeoMath.absAngleDiff(headingDeg, brngToEntry)
    return parallelAngle <= 30.0 && lateral > 25.0 && angleToEntry in 45.0..135.0
  }

  private fun inferGeometricRelation(
    originPoint: GeoPoint,
    headingDeg: Double,
    targetPoint: GeoPoint,
    targetWay: OsmWay?,
  ): FoundRoadRelation {
    val brng = GeoMath.bearingDeg(originPoint, targetPoint)
    val absDelta = GeoMath.absAngleDiff(headingDeg, brng)
    if (absDelta >= 115.0) return FoundRoadRelation.BEHIND
    if (targetWay != null && targetWay.points.size >= 2) {
      val snap = GeoMath.snapToPolyline(originPoint, targetWay.points)
      val parallelDiff =
        minOf(
          GeoMath.absAngleDiff(headingDeg, snap.segmentBearingDeg),
          GeoMath.absAngleDiff((headingDeg + 180.0) % 360.0, snap.segmentBearingDeg),
        )
      if (parallelDiff <= 30.0 && absDelta in 55.0..120.0) {
        return FoundRoadRelation.PARALLEL
      }
    }
    return FoundRoadRelation.AHEAD
  }
}
