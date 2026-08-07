@file:JvmName("MlnFfiComputedSourceKt")

package org.maplibre.compose.sources

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.CustomGeometrySourceCallback
import org.maplibre.nativeffi.style.CustomGeometrySourceOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Position

/** How long the tile thread waits for more work before it goes away. */
private const val WORKER_IDLE_SECONDS = 30L

/**
 * A source whose tiles this application generates: MapLibre decides which tiles it needs, asks
 * [getFeatures] for each, and asks again whenever one is invalidated.
 */
public actual class ComputedSource : Source {

  private val options: ComputedSourceOptions
  private val getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>

  /**
   * Tiles MapLibre has asked for and has not cancelled. Concurrent because MapLibre worker threads
   * add and cancel entries while the map's owner thread takes them.
   */
  private val requestedTiles = ConcurrentHashMap<CanonicalTileId, Long>()
  private val nextRequest = AtomicLong()

  /**
   * The one thread this source computes and delivers tiles on. Blocking MapLibre's callback thread
   * on the owner thread would deadlock against `CallbackGate.close`, and every `MapHandle` call is
   * owner-thread-affine, so both halves happen here instead. Daemon with a zero core size: a
   * computed source is not reliably detached before it is dropped, so it must never need an
   * explicit shutdown.
   */
  private val worker =
    ThreadPoolExecutor(0, 1, WORKER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
      Thread(task, "maplibre-computed-source-$id").also { it.isDaemon = true }
    }

  private val callback =
    object : CustomGeometrySourceCallback {
      override fun fetchTile(tileId: CanonicalTileId) {
        val request = nextRequest.incrementAndGet()
        requestedTiles[tileId] = request
        // A queue insertion is all that happens under MapLibre's callback lease; see [worker].
        worker.execute { answer(tileId, request) }
      }

      override fun cancelTile(tileId: CanonicalTileId) {
        // Forgetting the tile is what stops it; [answer] rechecks on the owner thread before it
        // writes, so a cancel arriving any time up to the write is honored.
        requestedTiles.remove(tileId)
      }
    }

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
  ) : super(id) {
    this.options = options
    this.getFeatures = getFeatures
  }

  override fun addTo(map: MapHandle) {
    map.addCustomGeometrySource(
      id,
      CustomGeometrySourceOptions(callback).also {
        it.minZoom = options.minZoom.toDouble()
        it.maxZoom = options.maxZoom.toDouble()
        it.buffer = options.buffer
        it.tolerance = options.tolerance.toDouble()
        it.clip = options.clip
        it.wrap = options.wrap
      },
    )
  }

  /**
   * Nothing adds a source from this: a custom geometry source has no style-spec form, so [addTo]
   * uses the typed adder. This exists only so the descriptor can describe itself.
   */
  override fun toJson(): JsonObject = buildJsonObject {
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
    put("buffer", options.buffer)
    put("tolerance", options.tolerance)
    put("clip", options.clip)
    put("wrap", options.wrap)
  }

  /** Computes one tile and delivers it. Runs on [worker]. */
  private fun answer(tileId: CanonicalTileId, request: Long) {
    if (requestedTiles[tileId] != request) return
    val data =
      try {
        getFeatures(tileId.toBoundingBox(), tileId.z).toFfiGeoJson()
      } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        requestedTiles.remove(tileId, request)
        // Reported rather than propagated; the tile stays blank until something invalidates it.
        binding.logger?.e(error) { "Computing tile $tileId of source '$id' failed" }
        return
      }
    // The cancellation check shares the owner-thread hop with the write, so nothing can cancel
    // between deciding to write and writing.
    binding.withMap { map ->
      if (requestedTiles.remove(tileId, request)) {
        map.setCustomGeometrySourceTileData(id, tileId, data)
      }
    }
  }

  public actual fun invalidateBounds(bounds: BoundingBox) {
    mutate { map -> map.invalidateCustomGeometrySourceRegion(id, bounds.toLatLngBounds()) }
  }

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {
    mutate { map -> map.invalidateCustomGeometrySourceTile(id, tileId(zoomLevel, x, y)) }
  }

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {
    // Converted before the hop, so the owner thread does not walk caller data while a frame waits.
    val geoJson = data.toFfiGeoJson()
    val tileId = tileId(zoomLevel, x, y)
    // Forgotten first, so an answer still in flight does not overwrite what was just supplied.
    requestedTiles.remove(tileId)
    mutate { map -> map.setCustomGeometrySourceTileData(id, tileId, geoJson) }
  }

  private fun tileId(zoomLevel: Int, x: Int, y: Int): CanonicalTileId =
    CanonicalTileId(z = zoomLevel, x = x.toLong(), y = y.toLong())
}

/**
 * The geographic bounds of a Web Mercator tile. Neither the FFI nor mbgl exposes this conversion,
 * so it is transcribed from the slippy-map definition.
 */
internal fun CanonicalTileId.toBoundingBox(): BoundingBox {
  val tiles = 2.0.pow(z)
  return BoundingBox(
    southwest = Position(longitude = longitudeAt(x, tiles), latitude = latitudeAt(y + 1, tiles)),
    northeast = Position(longitude = longitudeAt(x + 1, tiles), latitude = latitudeAt(y, tiles)),
  )
}

private fun longitudeAt(column: Long, tiles: Double): Double = column / tiles * 360.0 - 180.0

/** The latitude at a tile row boundary; rows run north to south from row zero at the top. */
private fun latitudeAt(row: Long, tiles: Double): Double =
  atan(sinh(PI * (1.0 - 2.0 * row / tiles))) * 180.0 / PI
