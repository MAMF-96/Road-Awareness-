package com.example.data.remote

import com.example.config.AppConfig
import com.example.data.model.BilingualName
import com.example.data.model.DecodedTileArtifact
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LoadedTileBounds
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PoiCategory
import com.example.data.model.PoiItem
import com.example.data.model.RoadGraph
import com.example.data.model.TileNameLabelRecord
import com.example.data.model.TravelMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Binary disk serializer for pre-decoded & stitched z14 tiles (stitched_z14 .bin files).
 * Avoids repeating GZIP + protobuf MVT parsing across app restarts, enforces 30-day TTL and
 * 200 MB LRU disk budget, and writes atomically via temp file rename.
 */
class TileBinaryCodec(
  baseDir: File,
  private val maxBytes: Long = AppConfig.MAX_DISK_TILE_CACHE_BYTES,
  private val ttlMs: Long = AppConfig.CACHE_TTL_MS,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
  private val cacheDir = File(baseDir, "stitched_z14")

  data class DiskStats(
    val fileCount: Int,
    val totalBytes: Long,
  )

  fun getDiskStats(): DiskStats {
    val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return DiskStats(0, 0L)
    return DiskStats(
      fileCount = files.size,
      totalBytes = files.sumOf { it.length() },
    )
  }

  fun readIfValid(
    tileKey: String,
    mode: TravelMode,
    ignoreTtl: Boolean = false,
  ): DecodedTileArtifact? {
    val file = fileFor(tileKey, mode)
    if (!file.exists() || !file.isFile) return null
    val res =
      runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file), 8192)).use { dis ->
          val magic = dis.readInt()
          val version = dis.readInt()
          if (magic != MAGIC || version != VERSION) return null
          val savedKey = dis.readUTF()
          if (savedKey != tileKey) return null
          val z = dis.readInt()
          val x = dis.readInt()
          val y = dis.readInt()
          val south = dis.readDouble()
          val west = dis.readDouble()
          val north = dis.readDouble()
          val east = dis.readDouble()
          val fetchedAtMs = dis.readLong()
          val isEmpty = dis.readBoolean()
          if (!ignoreTtl && (clockMs() - fetchedAtMs) >= ttlMs) {
            return null
          }
          val extent = dis.readInt()

          val layerCountSize = dis.readInt()
          val layerCounts = LinkedHashMap<String, Int>(layerCountSize)
          repeat(layerCountSize) {
            val lk = dis.readUTF()
            val lv = dis.readInt()
            layerCounts[lk] = lv
          }

          val sampleSize = dis.readInt()
          val samples = ArrayList<String>(sampleSize)
          repeat(sampleSize) {
            samples += dis.readUTF()
          }

          val nodeCount = dis.readInt()
          val nodes = HashMap<Long, OsmNode>(nodeCount)
          repeat(nodeCount) {
            val nid = dis.readLong()
            val lat = dis.readDouble()
            val lon = dis.readDouble()
            nodes[nid] = OsmNode(id = nid, point = GeoPoint(lat, lon))
          }

          val wayCount = dis.readInt()
          val ways = HashMap<Long, OsmWay>(wayCount)
          val nodeToWays = HashMap<Long, MutableList<Long>>(nodeCount)
          repeat(wayCount) {
            val wid = dis.readLong()
            val ptCount = dis.readInt()
            val ndIds = ArrayList<Long>(ptCount)
            val pts = ArrayList<GeoPoint>(ptCount)
            repeat(ptCount) {
              val nid = dis.readLong()
              val lat = dis.readDouble()
              val lon = dis.readDouble()
              ndIds += nid
              pts += GeoPoint(lat, lon)
              if (!nodes.containsKey(nid)) {
                nodes[nid] = OsmNode(nid, GeoPoint(lat, lon))
              }
            }
            val tags = readOsmTags(dis)
            val way =
              OsmWay(
                id = wid,
                nodeIds = ndIds,
                points = pts,
                tags = tags,
                source = GraphDataSource.TILES,
              )
            ways[wid] = way
            for (nid in ndIds.distinct()) {
              nodeToWays.getOrPut(nid) { ArrayList(3) }.add(wid)
            }
          }

          val labelCount = dis.readInt()
          val labels = ArrayList<TileNameLabelRecord>(labelCount)
          repeat(labelCount) {
            val ptCount = dis.readInt()
            val lPts = ArrayList<GeoPoint>(ptCount)
            repeat(ptCount) {
              lPts += GeoPoint(dis.readDouble(), dis.readDouble())
            }
            val lblCls = readNullableString(dis)
            val tagCount = dis.readInt()
            val tagMap = LinkedHashMap<String, String>(tagCount)
            repeat(tagCount) {
              tagMap[dis.readUTF()] = dis.readUTF()
            }
            labels += TileNameLabelRecord(lPts, lblCls, tagMap)
          }

          val poiCount = dis.readInt()
          val pois = ArrayList<PoiItem>(poiCount)
          repeat(poiCount) {
            val pid = dis.readLong()
            val ar = readNullableString(dis)
            val en = readNullableString(dis)
            val ref = readNullableString(dis)
            val localP = readNullableString(dis)
            val secL = readNullableString(dis)
            val catOrd = dis.readInt()
            val category = PoiCategory.entries.getOrElse(catOrd) { PoiCategory.OTHER }
            val pLat = dis.readDouble()
            val pLon = dis.readDouble()
            val tagCount = dis.readInt()
            val tagMap = LinkedHashMap<String, String>(tagCount)
            repeat(tagCount) {
              tagMap[dis.readUTF()] = dis.readUTF()
            }
            pois +=
              PoiItem(
                id = pid,
                name = BilingualName(ar, en, ref, localP, secL),
                category = category,
                point = GeoPoint(pLat, pLon),
                osmTags = tagMap,
                source = GraphDataSource.TILES,
              )
          }

          val bounds = LoadedTileBounds(z, x, y, south, west, north, east)
          val graph =
            RoadGraph(
              nodes = nodes,
              ways = ways,
              nodeToWays = nodeToWays,
              fetchedAtMs = fetchedAtMs,
              fromCache = true,
              source = GraphDataSource.TILES,
              loadedTiles = listOf(bounds),
            )
          file.setLastModified(clockMs())
          DecodedTileArtifact(
            tileKey = tileKey,
            z = z,
            x = x,
            y = y,
            bounds = bounds,
            graph = graph,
            labels = labels,
            pois = pois,
            fetchedAtMs = fetchedAtMs,
            isEmptyDecoded = isEmpty,
            extent = extent,
            layerCounts = layerCounts,
            sampleCoords = samples,
          )
        }
      }
    if (res.isFailure) {
      runCatching { file.delete() }
      return null
    }
    return res.getOrNull()
  }

  fun write(artifact: DecodedTileArtifact, mode: TravelMode) {
    runCatching {
      cacheDir.mkdirs()
      val target = fileFor(artifact.tileKey, mode)
      val tmp = File(cacheDir, "${target.name}.tmp")
      DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 8192)).use { dos ->
        dos.writeInt(MAGIC)
        dos.writeInt(VERSION)
        dos.writeUTF(artifact.tileKey)
        dos.writeInt(artifact.z)
        dos.writeInt(artifact.x)
        dos.writeInt(artifact.y)
        dos.writeDouble(artifact.bounds.south)
        dos.writeDouble(artifact.bounds.west)
        dos.writeDouble(artifact.bounds.north)
        dos.writeDouble(artifact.bounds.east)
        dos.writeLong(artifact.fetchedAtMs)
        dos.writeBoolean(artifact.isEmptyDecoded)
        dos.writeInt(artifact.extent)

        dos.writeInt(artifact.layerCounts.size)
        for ((k, v) in artifact.layerCounts) {
          dos.writeUTF(k)
          dos.writeInt(v)
        }

        dos.writeInt(artifact.sampleCoords.size)
        for (s in artifact.sampleCoords) {
          dos.writeUTF(s)
        }

        val nodes = artifact.graph.nodes
        dos.writeInt(nodes.size)
        for (node in nodes.values) {
          dos.writeLong(node.id)
          dos.writeDouble(node.point.lat)
          dos.writeDouble(node.point.lon)
        }

        val ways = artifact.graph.ways.values
        dos.writeInt(ways.size)
        for (way in ways) {
          dos.writeLong(way.id)
          val count = minOf(way.nodeIds.size, way.points.size)
          dos.writeInt(count)
          for (i in 0 until count) {
            dos.writeLong(way.nodeIds[i])
            dos.writeDouble(way.points[i].lat)
            dos.writeDouble(way.points[i].lon)
          }
          writeOsmTags(dos, way.tags)
        }

        dos.writeInt(artifact.labels.size)
        for (lbl in artifact.labels) {
          dos.writeInt(lbl.points.size)
          for (p in lbl.points) {
            dos.writeDouble(p.lat)
            dos.writeDouble(p.lon)
          }
          writeNullableString(dos, lbl.labelClass)
          dos.writeInt(lbl.tagMap.size)
          for ((k, v) in lbl.tagMap) {
            dos.writeUTF(k)
            dos.writeUTF(v)
          }
        }

        dos.writeInt(artifact.pois.size)
        for (poi in artifact.pois) {
          dos.writeLong(poi.id)
          writeNullableString(dos, poi.name.arabic)
          writeNullableString(dos, poi.name.english)
          writeNullableString(dos, poi.name.ref)
          writeNullableString(dos, poi.name.localPrimary)
          writeNullableString(dos, poi.name.secondaryLocal)
          dos.writeInt(poi.category.ordinal)
          dos.writeDouble(poi.point.lat)
          dos.writeDouble(poi.point.lon)
          dos.writeInt(poi.osmTags.size)
          for ((k, v) in poi.osmTags) {
            dos.writeUTF(k)
            dos.writeUTF(v)
          }
        }
      }
      if (target.exists()) target.delete()
      tmp.renameTo(target)
      evictOverBudgetLocked()
    }
  }

  fun clearAll() {
    runCatching {
      cacheDir.listFiles()?.forEach { it.delete() }
    }
  }

  private fun evictOverBudgetLocked() {
    val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return
    val now = clockMs()
    var total = 0L
    val survivors = ArrayList<File>(files.size)
    for (f in files) {
      if ((now - f.lastModified()) > ttlMs) {
        f.delete()
      } else {
        total += f.length()
        survivors += f
      }
    }
    if (total <= maxBytes) return
    survivors.sortBy { it.lastModified() }
    for (f in survivors) {
      if (total <= maxBytes) break
      val len = f.length()
      if (f.delete()) {
        total -= len
      }
    }
  }

  private fun fileFor(tileKey: String, @Suppress("UNUSED_PARAMETER") mode: TravelMode): File =
    File(cacheDir, "${tileKey}.bin")

  private fun writeNullableString(dos: DataOutputStream, s: String?) {
    if (s == null) {
      dos.writeBoolean(false)
    } else {
      dos.writeBoolean(true)
      dos.writeUTF(s)
    }
  }

  private fun readNullableString(dis: DataInputStream): String? =
    if (dis.readBoolean()) dis.readUTF() else null

  private fun writeOsmTags(dos: DataOutputStream, tags: OsmTags) {
    writeNullableString(dos, tags.name)
    writeNullableString(dos, tags.nameAr)
    writeNullableString(dos, tags.nameEn)
    writeNullableString(dos, tags.nameLocalized)
    writeNullableString(dos, tags.nameNonLatin)
    writeNullableString(dos, tags.nameLatin)
    writeNullableString(dos, tags.ref)
    writeNullableString(dos, tags.oneway)
    writeNullableString(dos, tags.junction)
    writeNullableString(dos, tags.highway)
    writeNullableString(dos, tags.service)
    writeNullableString(dos, tags.bridge)
    writeNullableString(dos, tags.tunnel)
    writeNullableString(dos, tags.layer)
    writeNullableString(dos, tags.lanes)
    writeNullableString(dos, tags.access)
    writeNullableString(dos, tags.construction)
    writeNullableString(dos, tags.barrier)
    writeNullableString(dos, tags.noexit)
  }

  private fun readOsmTags(dis: DataInputStream): OsmTags =
    OsmTags(
      name = readNullableString(dis),
      nameAr = readNullableString(dis),
      nameEn = readNullableString(dis),
      nameLocalized = readNullableString(dis),
      nameNonLatin = readNullableString(dis),
      nameLatin = readNullableString(dis),
      ref = readNullableString(dis),
      oneway = readNullableString(dis),
      junction = readNullableString(dis),
      highway = readNullableString(dis),
      service = readNullableString(dis),
      bridge = readNullableString(dis),
      tunnel = readNullableString(dis),
      layer = readNullableString(dis),
      lanes = readNullableString(dis),
      access = readNullableString(dis),
      construction = readNullableString(dis),
      barrier = readNullableString(dis),
      noexit = readNullableString(dis),
    )

  companion object {
    private const val MAGIC = 0x52415431 // "RAT1"
    private const val VERSION = 2
  }
}
