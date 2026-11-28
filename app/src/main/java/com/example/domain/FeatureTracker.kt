package com.example.domain

import com.example.data.model.AheadTimelineEntry
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.LandmarkAheadItem
import com.example.data.model.RoadFeature
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stabilizes upcoming road features across incremental/partial tile loads, budget-capped
 * analyses, and single-frame transient misses while dead-reckoning distances between fixes.
 */
class FeatureTracker {
  private data class TrackedFeature(
    val key: String,
    var feature: RoadFeature,
    var signedDistanceMeters: Double = feature.distanceMeters,
    var missingPasses: Int = 0,
    var firstMissingAtMs: Long = 0L,
    var lastSeenAtMs: Long = 0L,
  )

  private data class TrackedPassBy(
    val key: String,
    var entry: AheadTimelineEntry,
    var signedDistanceMeters: Double = entry.distanceMeters,
    var missingPasses: Int = 0,
    var firstMissingAtMs: Long = 0L,
  )

  private val trackedFeatures = LinkedHashMap<String, TrackedFeature>()
  private val trackedPassBys = LinkedHashMap<String, TrackedPassBy>()
  private var trackedLandmarks: List<LandmarkAheadItem> = emptyList()

  private var lastMatchedWayId: Long? = null
  private var lastTracedWayIds: Set<Long> = emptySet()
  private var lastCorridorPoints: List<GeoPoint> = emptyList()
  private var lastPathProgressMeters: Double = 0.0

  private var consecutiveEmptyFullPasses: Int = 0
  private var emptyWindowStartMs: Long = 0L
  private var pathSwitchPendingWayId: Long? = null
  private var pathSwitchConfirmCount: Int = 0

  var keptCount: Int = 0
    private set
  var addedCount: Int = 0
    private set
  var droppedCount: Int = 0
    private set
  var lastDropReason: String = "none"
    private set

  data class ReconcileOutput(
    val features: List<RoadFeature>,
    val timelineEntries: List<AheadTimelineEntry>,
    val landmarksAhead: List<LandmarkAheadItem>,
    val hasConfirmedEmptyAhead: Boolean,
    val statsSummary: String,
  )

  fun reset() {
    trackedFeatures.clear()
    trackedPassBys.clear()
    trackedLandmarks = emptyList()
    lastMatchedWayId = null
    lastTracedWayIds = emptySet()
    lastCorridorPoints = emptyList()
    lastPathProgressMeters = 0.0
    consecutiveEmptyFullPasses = 0
    emptyWindowStartMs = 0L
    pathSwitchPendingWayId = null
    pathSwitchConfirmCount = 0
    keptCount = 0
    addedCount = 0
    droppedCount = 0
    lastDropReason = "reset"
  }

  /**
   * Shifts all currently tracked distances forward as the user moves along the active corridor,
   * dropping anything that has clearly fallen more than 25 m behind the user.
   */
  fun deadReckon(
    position: GeoPoint,
    matchedWayId: Long?,
  ): ReconcileOutput? {
    if (lastCorridorPoints.size < 2 || (trackedFeatures.isEmpty() && trackedPassBys.isEmpty())) {
      return null
    }
    if (matchedWayId != null && lastMatchedWayId != null && matchedWayId != lastMatchedWayId && matchedWayId !in lastTracedWayIds) {
      return null
    }
    val progress = projectAlongPolyline(position, lastCorridorPoints) ?: return null
    val delta = (progress - lastPathProgressMeters).coerceIn(-10.0, 200.0)
    if (abs(delta) < 0.5) return null
    lastPathProgressMeters = progress

    val featIter = trackedFeatures.entries.iterator()
    var dropped = 0
    while (featIter.hasNext()) {
      val tracked = featIter.next().value
      val newDist = tracked.signedDistanceMeters - delta
      if (newDist < -25.0) {
        featIter.remove()
        dropped++
      } else {
        tracked.signedDistanceMeters = newDist
        tracked.feature = tracked.feature.copy(distanceMeters = newDist.coerceAtLeast(0.0))
      }
    }
    val passIter = trackedPassBys.entries.iterator()
    while (passIter.hasNext()) {
      val tracked = passIter.next().value
      val newDist = tracked.signedDistanceMeters - delta
      if (newDist < -25.0) {
        passIter.remove()
      } else {
        tracked.signedDistanceMeters = newDist
        tracked.entry = tracked.entry.copy(distanceMeters = newDist.coerceAtLeast(0.0))
      }
    }
    if (dropped > 0) {
      droppedCount += dropped
      lastDropReason = "passed"
    }
    return buildOutput(hasConfirmedEmptyAhead = false, baseTimeline = emptyList())
  }

  fun reconcile(
    nowMs: Long,
    matchedWayId: Long?,
    isOffRoadUnselected: Boolean,
    tracedWayIds: Set<Long>,
    corridorWorldPoints: List<GeoPoint>,
    userPosition: GeoPoint?,
    newFeatures: List<RoadFeature>,
    newTimelineEntries: List<AheadTimelineEntry>,
    newLandmarks: List<LandmarkAheadItem>,
    loadedCorridorMeters: Double,
    configuredHorizonMeters: Double,
    isPartialBudgetResult: Boolean,
    horizonStoppedAtUnloadedBorder: Boolean,
  ): ReconcileOutput {
    if (isOffRoadUnselected) {
      val prevSize = trackedFeatures.size
      trackedFeatures.clear()
      trackedPassBys.clear()
      trackedLandmarks = emptyList()
      lastMatchedWayId = null
      lastTracedWayIds = emptySet()
      lastCorridorPoints = emptyList()
      lastPathProgressMeters = 0.0
      consecutiveEmptyFullPasses = 0
      emptyWindowStartMs = 0L
      keptCount = 0
      addedCount = 0
      droppedCount = prevSize
      lastDropReason = if (prevSize > 0) "off_road" else "none"
      return ReconcileOutput(
        features = emptyList(),
        timelineEntries = emptyList(),
        landmarksAhead = emptyList(),
        hasConfirmedEmptyAhead = true,
        statsSummary = statsLine(),
      )
    }

    val sameWay = matchedWayId != null && matchedWayId == lastMatchedWayId
    val connectedContinuation =
      matchedWayId != null &&
        lastMatchedWayId != null &&
        matchedWayId != lastMatchedWayId &&
        (matchedWayId in lastTracedWayIds || setOverlapRatio(tracedWayIds, lastTracedWayIds) >= 0.25)

    val pathChanged =
      when {
        lastMatchedWayId == null && matchedWayId != null -> false
        sameWay || connectedContinuation -> {
          pathSwitchPendingWayId = null
          pathSwitchConfirmCount = 0
          false
        }
        matchedWayId != null && lastMatchedWayId != null && matchedWayId != lastMatchedWayId -> {
          if (pathSwitchPendingWayId == matchedWayId) {
            pathSwitchConfirmCount++
          } else {
            pathSwitchPendingWayId = matchedWayId
            pathSwitchConfirmCount = 1
          }
          // Immediate switch if we already have new features on the different road; or after 2 confirming passes
          newFeatures.isNotEmpty() || pathSwitchConfirmCount >= 2
        }
        else -> false
      }

    if (pathChanged) {
      val prevSize = trackedFeatures.size
      trackedFeatures.clear()
      trackedPassBys.clear()
      consecutiveEmptyFullPasses = 0
      emptyWindowStartMs = 0L
      newFeatures.forEach { feat ->
        val key = stableKey(feat)
        trackedFeatures[key] = TrackedFeature(key = key, feature = feat, lastSeenAtMs = nowMs)
      }
      newTimelineEntries.filter { it.isPassByStreet }.forEach { entry ->
        val key = stablePassByKey(entry)
        trackedPassBys[key] = TrackedPassBy(key = key, entry = entry)
      }
      trackedLandmarks = newLandmarks
      updatePathAnchor(matchedWayId, tracedWayIds, corridorWorldPoints, userPosition)
      keptCount = 0
      addedCount = trackedFeatures.size
      droppedCount = prevSize
      lastDropReason = if (prevSize > 0) "path_changed" else "none"
      return buildOutput(hasConfirmedEmptyAhead = newFeatures.isEmpty() && loadedCorridorMeters >= configuredHorizonMeters, baseTimeline = newTimelineEntries)
    }

    val isPartial = isPartialBudgetResult || horizonStoppedAtUnloadedBorder
    val fullRangeLoaded = loadedCorridorMeters + 25.0 >= configuredHorizonMeters && !isPartial

    // Handle empty new results carefully to prevent flicker
    if (newFeatures.isEmpty()) {
      if (fullRangeLoaded) {
        if (consecutiveEmptyFullPasses == 0) {
          emptyWindowStartMs = nowMs
        }
        consecutiveEmptyFullPasses++
      } else {
        consecutiveEmptyFullPasses = 0
        emptyWindowStartMs = 0L
      }

      val confirmedEmpty =
        fullRangeLoaded &&
          consecutiveEmptyFullPasses >= 2 &&
          (nowMs - emptyWindowStartMs) >= 1_500L

      return if (confirmedEmpty) {
        val prevSize = trackedFeatures.size
        trackedFeatures.clear()
        trackedPassBys.clear()
        trackedLandmarks = emptyList()
        updatePathAnchor(matchedWayId, tracedWayIds, corridorWorldPoints, userPosition)
        keptCount = 0
        addedCount = 0
        droppedCount = prevSize
        lastDropReason = if (prevSize > 0) "confirmed_empty" else "none"
        ReconcileOutput(
          features = emptyList(),
          timelineEntries = emptyList(),
          landmarksAhead = emptyList(),
          hasConfirmedEmptyAhead = true,
          statsSummary = statsLine(),
        )
      } else {
        // Keep previous features and dead-reckon them along updated path if available
        if (corridorWorldPoints.size >= 2) {
          updatePathAnchor(matchedWayId, tracedWayIds, corridorWorldPoints, userPosition)
        }
        keptCount = trackedFeatures.size
        addedCount = 0
        droppedCount = 0
        lastDropReason = "empty_suppressed"
        buildOutput(hasConfirmedEmptyAhead = false, baseTimeline = newTimelineEntries)
      }
    }

    // Non-empty results
    consecutiveEmptyFullPasses = 0
    emptyWindowStartMs = 0L

    var kept = 0
    var added = 0
    var dropped = 0
    val newKeys = HashSet<String>(newFeatures.size * 2)
    val maxNewDist =
      maxOf(
        loadedCorridorMeters,
        newFeatures.maxOfOrNull { it.distanceMeters } ?: 0.0,
      )

    for (feat in newFeatures) {
      val key = stableKey(feat)
      newKeys += key
      val existing = trackedFeatures[key]
      if (existing != null) {
        existing.feature = feat
        existing.signedDistanceMeters = feat.distanceMeters
        existing.missingPasses = 0
        existing.firstMissingAtMs = 0L
        existing.lastSeenAtMs = nowMs
        kept++
      } else {
        trackedFeatures[key] =
          TrackedFeature(
            key = key,
            feature = feat,
            signedDistanceMeters = feat.distanceMeters,
            missingPasses = 0,
            firstMissingAtMs = 0L,
            lastSeenAtMs = nowMs,
          )
        added++
      }
    }

    val featIter = trackedFeatures.entries.iterator()
    while (featIter.hasNext()) {
      val tracked = featIter.next().value
      if (tracked.key in newKeys) continue

      if (isPartial && tracked.feature.distanceMeters > maxNewDist - 5.0) {
        // Beyond currently loaded/budgeted horizon during partial pass -> preserve as-is
        kept++
        continue
      }

      if (tracked.signedDistanceMeters < -25.0) {
        featIter.remove()
        dropped++
        lastDropReason = "passed"
        continue
      }

      if (tracked.firstMissingAtMs == 0L) {
        tracked.firstMissingAtMs = nowMs
      }
      tracked.missingPasses++
      if (tracked.missingPasses >= 2 && (nowMs - tracked.firstMissingAtMs) >= 2_000L) {
        featIter.remove()
        dropped++
        lastDropReason = "missing_2_passes"
      } else {
        kept++
      }
    }

    // Reconcile pass-by timeline entries similarly
    val newPassKeys = HashSet<String>()
    for (entry in newTimelineEntries) {
      if (!entry.isPassByStreet) continue
      val key = stablePassByKey(entry)
      newPassKeys += key
      val ex = trackedPassBys[key]
      if (ex != null) {
        ex.entry = entry
        ex.signedDistanceMeters = entry.distanceMeters
        ex.missingPasses = 0
        ex.firstMissingAtMs = 0L
      } else {
        trackedPassBys[key] = TrackedPassBy(key = key, entry = entry, signedDistanceMeters = entry.distanceMeters)
      }
    }
    val passIter = trackedPassBys.entries.iterator()
    while (passIter.hasNext()) {
      val tracked = passIter.next().value
      if (tracked.key in newPassKeys) continue
      if (tracked.signedDistanceMeters < -25.0) {
        passIter.remove()
        continue
      }
      if (isPartial && tracked.entry.distanceMeters > maxNewDist - 5.0) continue
      if (tracked.firstMissingAtMs == 0L) tracked.firstMissingAtMs = nowMs
      tracked.missingPasses++
      if (tracked.missingPasses >= 2 && (nowMs - tracked.firstMissingAtMs) >= 2_000L) {
        passIter.remove()
      }
    }

    if (newLandmarks.isNotEmpty() || !isPartial) {
      trackedLandmarks = newLandmarks
    }

    updatePathAnchor(matchedWayId, tracedWayIds, corridorWorldPoints, userPosition)
    keptCount = kept
    addedCount = added
    droppedCount = dropped
    if (dropped == 0) {
      lastDropReason = if (isPartial) "partial_preserve" else "none"
    }

    return buildOutput(hasConfirmedEmptyAhead = false, baseTimeline = newTimelineEntries)
  }

  private fun updatePathAnchor(
    matchedWayId: Long?,
    tracedWayIds: Set<Long>,
    corridorWorldPoints: List<GeoPoint>,
    userPosition: GeoPoint?,
  ) {
    lastMatchedWayId = matchedWayId
    if (tracedWayIds.isNotEmpty()) {
      lastTracedWayIds = tracedWayIds
    } else if (matchedWayId != null) {
      lastTracedWayIds = setOf(matchedWayId)
    }
    if (corridorWorldPoints.size >= 2) {
      lastCorridorPoints = corridorWorldPoints
      lastPathProgressMeters =
        if (userPosition != null) projectAlongPolyline(userPosition, corridorWorldPoints) ?: 0.0
        else 0.0
    }
  }

  private fun buildOutput(
    hasConfirmedEmptyAhead: Boolean,
    baseTimeline: List<AheadTimelineEntry>,
  ): ReconcileOutput {
    val sortedFeatures =
      trackedFeatures.values
        .map { it.feature }
        .sortedBy { it.distanceMeters }
        .mapIndexed { idx, feat -> feat.copy(index = idx + 1) }

    val indexByKey = HashMap<String, Int>(sortedFeatures.size * 2)
    sortedFeatures.forEach { feat ->
      indexByKey[stableKey(feat)] = feat.index
    }

    val baseNonPassBy =
      sortedFeatures.map { feat ->
        val existingEntry = baseTimeline.firstOrNull { !it.isPassByStreet && it.featureIndex == feat.index }
        existingEntry?.copy(
          distanceMeters = feat.distanceMeters,
          continuesAsName = existingEntry.continuesAsName ?: feat.continuesAsName,
          forkMainName = existingEntry.forkMainName ?: feat.forkMainName,
        )
          ?: AheadTimelineEntry(
            distanceMeters = feat.distanceMeters,
            type = feat.effectiveDisplayType(),
            title = feat.continuesAsName
              ?: feat.branches.firstOrNull()?.name
              ?: com.example.data.model.BilingualName.fromOsmTags(feat.osmTags),
            branchSummary = feat.continuesAsName?.primaryText ?: feat.branches.firstOrNull()?.name?.primaryText,
            status = feat.branches.firstOrNull()?.status,
            featureIndex = feat.index,
            primaryWayId = feat.branches.firstOrNull()?.wayId,
            branchWayIds = feat.branches.map { it.wayId },
            anchor = feat.anchor,
            continuesAsName = feat.continuesAsName,
            forkMainName = feat.forkMainName,
          )
      }
    val passByList = trackedPassBys.values.map { it.entry }
    val mergedTimeline = (baseNonPassBy + passByList).sortedBy { it.distanceMeters }

    return ReconcileOutput(
      features = sortedFeatures,
      timelineEntries = mergedTimeline,
      landmarksAhead = trackedLandmarks,
      hasConfirmedEmptyAhead = hasConfirmedEmptyAhead,
      statsSummary = statsLine(),
    )
  }

  private fun statsLine(): String =
    "kept=$keptCount add=$addedCount drop=$droppedCount($lastDropReason) empty=${consecutiveEmptyFullPasses}/2"

  companion object {
    fun stableKey(feature: RoadFeature): String {
      val typeKey = feature.effectiveDisplayType().name
      val nodeId = feature.anchorNodeId
      if (nodeId != null) return "${typeKey}_n_$nodeId"
      val latCell = (feature.anchor.lat * 100_000.0).roundToInt()
      val lonCell = (feature.anchor.lon * 100_000.0).roundToInt()
      val brSig = feature.branches.firstOrNull()?.wayId ?: 0L
      return "${typeKey}_p_${latCell}_${lonCell}_$brSig"
    }

    private fun stablePassByKey(entry: AheadTimelineEntry): String {
      val wayId = entry.primaryWayId
      if (wayId != null) return "pb_w_${wayId}_${entry.passBySide?.name ?: "NONE"}"
      val latCell = entry.anchor?.let { (it.lat * 10_000.0).roundToInt() } ?: 0
      val lonCell = entry.anchor?.let { (it.lon * 10_000.0).roundToInt() } ?: 0
      return "pb_p_${entry.type.name}_${latCell}_$lonCell"
    }

    private fun setOverlapRatio(a: Set<Long>, b: Set<Long>): Double {
      if (a.isEmpty() || b.isEmpty()) return 0.0
      val shared = a.count { it in b }
      return shared.toDouble() / minOf(a.size, b.size).toDouble()
    }

    private fun projectAlongPolyline(point: GeoPoint, polyline: List<GeoPoint>): Double? {
      if (polyline.size < 2) return null
      var bestDist = Double.MAX_VALUE
      var bestAlong = 0.0
      var cumMeters = 0.0
      for (i in 0 until polyline.lastIndex) {
        val a = polyline[i]
        val b = polyline[i + 1]
        val segLen = GeoMath.haversineMeters(a, b)
        val proj = GeoMath.projectPointOnSegment(point, a, b)
        if (proj.distanceMeters < bestDist) {
          bestDist = proj.distanceMeters
          val alongSeg = GeoMath.haversineMeters(a, proj.snappedPoint).coerceIn(0.0, segLen)
          bestAlong = cumMeters + alongSeg
        }
        cumMeters += segLen
      }
      return bestAlong
    }
  }
}
