package com.example.data.remote

import com.example.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

data class DebugNetworkSimState(
  val offline: Boolean = false,
  val blockOverpass: Boolean = false,
  val blockTiles: Boolean = false,
  val slowNetwork: Boolean = false,
  val forceOverpassErrorCode: Int? = null,
) {
  val hasAnyActive: Boolean
    get() = offline || blockOverpass || blockTiles || slowNetwork || forceOverpassErrorCode != null
}

/**
 * Debug-only in-app network simulator applied as an OkHttp Interceptor across all app HTTP clients
 * (Overpass, vector/raster tiles, style JSON, search, routing, and MapLibre internal HTTP stack).
 * Completely inactive in release builds.
 */
object DebugNetworkSimulator {
  private val _state = MutableStateFlow(DebugNetworkSimState())
  val state: StateFlow<DebugNetworkSimState> = _state.asStateFlow()

  @Volatile var delayRangeMsOverride: LongRange? = null

  fun update(transform: (DebugNetworkSimState) -> DebugNetworkSimState) {
    if (!BuildConfig.DEBUG) return
    _state.value = transform(_state.value)
  }

  fun setOffline(enabled: Boolean) {
    update { it.copy(offline = enabled) }
  }

  fun setBlockOverpass(enabled: Boolean) {
    update { it.copy(blockOverpass = enabled) }
  }

  fun setBlockTiles(enabled: Boolean) {
    update { it.copy(blockTiles = enabled) }
  }

  fun setSlowNetwork(enabled: Boolean) {
    update { it.copy(slowNetwork = enabled) }
  }

  fun setForceOverpassError(code: Int?) {
    val sanitized = if (code in setOf(406, 429, 502, 504)) code else null
    update { it.copy(forceOverpassErrorCode = sanitized) }
  }

  fun reset() {
    if (!BuildConfig.DEBUG) return
    _state.value = DebugNetworkSimState()
  }

  fun isOfflineBlocked(): Boolean = BuildConfig.DEBUG && _state.value.offline

  fun isTilesBlocked(): Boolean = BuildConfig.DEBUG && (_state.value.offline || _state.value.blockTiles)

  val interceptor: Interceptor = Interceptor { chain ->
    val req = chain.request()
    if (!BuildConfig.DEBUG) {
      return@Interceptor chain.proceed(req)
    }

    val snapshot = _state.value

    if (snapshot.slowNetwork) {
      val range = delayRangeMsOverride ?: (3_000L..8_000L)
      val targetDelay =
        if (range.first >= range.last) range.first
        else Random.nextLong(range.first, range.last + 1L)
      var remaining = targetDelay
      while (remaining > 0L) {
        if (chain.call().isCanceled()) {
          throw IOException("Canceled during slow network simulation")
        }
        val step = minOf(remaining, 100L)
        try {
          Thread.sleep(step)
        } catch (ie: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("Interrupted during slow network simulation", ie)
        }
        remaining -= step
      }
    }

    // Re-read after potential slow delay in case user toggled flags while sleeping
    val current = _state.value
    if (current.offline) {
      throw UnknownHostException("Simulated offline mode (debug override)")
    }

    val isOverpass = isOverpassRequest(req)
    val isTile = isTileOrStyleRequest(req, isOverpass)

    if (current.blockOverpass && isOverpass) {
      throw SocketTimeoutException("Simulated Overpass timeout (debug override)")
    }

    if (current.blockTiles && isTile) {
      throw IOException("Simulated tile source failure (debug override)")
    }

    val forcedCode = current.forceOverpassErrorCode
    if (forcedCode != null && isOverpass) {
      val builder =
        Response.Builder()
          .request(req)
          .protocol(Protocol.HTTP_1_1)
          .code(forcedCode)
          .message("Simulated HTTP $forcedCode")
          .body(
            "Simulated Overpass HTTP $forcedCode error response for diagnostics verification"
              .toResponseBody("text/plain; charset=utf-8".toMediaType())
          )
      if (forcedCode == 429) {
        builder.header("Retry-After", "2")
      }
      return@Interceptor builder.build()
    }

    chain.proceed(req)
  }

  private fun isOverpassRequest(req: Request): Boolean {
    val host = req.url.host.lowercase()
    val path = req.url.encodedPath.lowercase()
    return path.contains("interpreter") ||
      host.contains("overpass")
  }

  private fun isTileOrStyleRequest(req: Request, isOverpass: Boolean): Boolean {
    if (isOverpass) return false
    val host = req.url.host.lowercase()
    val path = req.url.encodedPath.lowercase()
    if (host.contains("nominatim") || host.contains("valhalla")) return false
    return req.header("X-Road-Tile-Request") != null ||
      host.contains("openfreemap") ||
      host.contains("tile.openstreetmap.org") ||
      path.endsWith(".pbf") ||
      path.endsWith(".mvt") ||
      path.endsWith(".png") ||
      path.contains("/styles/") ||
      path.contains("tiles.json") ||
      host.contains("tiles.")
  }
}
