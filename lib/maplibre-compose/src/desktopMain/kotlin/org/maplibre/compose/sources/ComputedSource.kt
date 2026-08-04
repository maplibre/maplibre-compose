@file:JvmName("DesktopComputedSourceKt")

package org.maplibre.compose.sources

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 * A source whose tiles this application generates.
 *
 * MapLibre drives it rather than the other way around: it decides which tiles it needs, asks
 * [getFeatures] for each, and asks again whenever one is invalidated. Everything interesting here
 * is about which thread that conversation happens on, because the two ends of it belong to threads
 * that must not wait for each other. See [worker].
 */
public actual class ComputedSource : Source {

  private val options: ComputedSourceOptions
  private val getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>

  /**
   * Tiles MapLibre has asked for and has not cancelled.
   *
   * Three threads touch it — a MapLibre worker adds, another cancels, and the map's owner thread
   * takes the entry as it writes the data — so it is the one piece of state here that has to be
   * concurrent.
   */
  private val requestedTiles = ConcurrentHashMap.newKeySet<CanonicalTileId>()

  /**
   * The one thread this source computes and delivers tiles on.
   *
   * Neither half of the work can run where MapLibre offers it. The callback arrives on a MapLibre
   * worker holding a lease that `CallbackGate.close` spins on, and the map's owner thread is what
   * closes that gate — from `removeStyleSource` and from `setStyleJson` — so blocking the callback
   * thread on the owner thread is a deadlock, and merely computing there stalls the next style
   * change for as long as [getFeatures] runs. Delivering is no better placed: every `MapHandle`
   * call is owner-thread-affine and enforced natively.
   *
   * So this thread does both, and spends its own time waiting for the owner-thread hop. One thread,
   * so a source's tiles are computed in the order they were asked for. It is a daemon with a zero
   * core size, which means it exists only while there is work and never holds a process open: a
   * computed source is not reliably detached before it is dropped — a style swap unloads the
   * binding instead of removing the source — so a thread that had to be shut down explicitly would
   * be a thread left running.
   */
  private val worker =
    ThreadPoolExecutor(0, 1, WORKER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
      Thread(task, "maplibre-computed-source-$id").also { it.isDaemon = true }
    }

  private val callback =
    object : CustomGeometrySourceCallback {
      override fun fetchTile(tileId: CanonicalTileId) {
        requestedTiles.add(tileId)
        // A queue insertion is all that happens under MapLibre's callback lease; see [worker].
        worker.execute { answer(tileId) }
      }

      override fun cancelTile(tileId: CanonicalTileId) {
        // Forgetting the tile is what stops it: [answer] checks before it computes and again on the
        // owner thread before it writes, so a cancel arriving any time up to the write is honored.
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
   * Nothing adds a source from this.
   *
   * MapLibre Native accepts only `vector`, `raster`, `raster-dem`, `geojson`, and `image` from
   * source JSON, so a custom geometry source has no style-spec form to emit; [addTo] creates it
   * through the typed adder instead. This exists because a descriptor has to be able to describe
   * itself — [attributionHtml] reads it — and the tiling options are what there is to say.
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
  private fun answer(tileId: CanonicalTileId) {
    if (tileId !in requestedTiles) return
    val data =
      try {
        getFeatures(tileId.toBoundingBox(), tileId.z).toFfiGeoJson()
      } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        requestedTiles.remove(tileId)
        // Reported rather than propagated: this thread is ours, and MapLibre has no way to hear
        // about it. The tile simply stays blank until something invalidates it.
        binding.logger?.e(error) { "Computing tile $tileId of source '$id' failed" }
        return
      }
    // The cancellation check runs inside the same hop as the write, on the owner thread, so nothing
    // can cancel in the window between deciding to write and writing.
    binding.withMap { map ->
      if (requestedTiles.remove(tileId)) map.setCustomGeometrySourceTileData(id, tileId, data)
    }
  }

  public actual fun invalidateBounds(bounds: BoundingBox) {
    mutate { map -> map.invalidateCustomGeometrySourceRegion(id, bounds.toLatLngBounds()) }
  }

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {
    mutate { map -> map.invalidateCustomGeometrySourceTile(id, tileId(zoomLevel, x, y)) }
  }

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {
    // Converted before the hop, as the other source setters do it: the map's owner thread should
    // not be walking caller data while a frame waits on it.
    val geoJson = data.toFfiGeoJson()
    val tileId = tileId(zoomLevel, x, y)
    // Forgotten first, so an answer still in flight does not overwrite what was just supplied:
    // data given here is newer than anything getFeatures was asked for earlier.
    requestedTiles.remove(tileId)
    mutate { map -> map.setCustomGeometrySourceTileData(id, tileId, geoJson) }
  }

  private fun tileId(zoomLevel: Int, x: Int, y: Int): CanonicalTileId =
    CanonicalTileId(z = zoomLevel, x = x.toLong(), y = y.toLong())
}

/**
 * The geographic bounds of a Web Mercator tile.
 *
 * MapLibre asks for a tile and [ComputedSource] hands its caller a bounding box, so this is the
 * whole of the translation between the two. Neither the FFI nor mbgl exposes the conversion, and
 * both mobile SDKs do it in their own language, so it is transcribed here from the slippy-map
 * definition rather than filed as a gap.
 */
internal fun CanonicalTileId.toBoundingBox(): BoundingBox {
  val tiles = 2.0.pow(z)
  return BoundingBox(
    southwest = Position(longitude = longitudeAt(x, tiles), latitude = latitudeAt(y + 1, tiles)),
    northeast = Position(longitude = longitudeAt(x + 1, tiles), latitude = latitudeAt(y, tiles)),
  )
}

private fun longitudeAt(column: Long, tiles: Double): Double = column / tiles * 360.0 - 180.0

/**
 * The latitude at a tile row boundary.
 *
 * Rows run north to south, so row zero is the top of the world rather than the equator, and the
 * spacing between them is the inverse Mercator rather than anything linear.
 */
private fun latitudeAt(row: Long, tiles: Double): Double =
  atan(sinh(PI * (1.0 - 2.0 * row / tiles))) * 180.0 / PI
