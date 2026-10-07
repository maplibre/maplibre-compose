package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.map.MapSnapshotter
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Position

/** The canonical XYZ coordinate of one Web Mercator tile. */
@Immutable
public data class TileCoordinate(
  public val zoomLevel: Int,
  public val x: Long,
  public val y: Long,
) {

  init {
    require(zoomLevel in MinZoom..MaxZoom) {
      "zoomLevel must be within $MinZoom..$MaxZoom, was $zoomLevel"
    }
    val tileCount = 1L shl zoomLevel
    require(x in 0 until tileCount) {
      "x must be within 0 until $tileCount at zoom $zoomLevel, was $x"
    }
    require(y in 0 until tileCount) {
      "y must be within 0 until $tileCount at zoom $zoomLevel, was $y"
    }
  }

  /** The geographic bounds of this tile. */
  public val bounds: BoundingBox
    get() {
      val tileCount = 2.0.pow(zoomLevel)
      return BoundingBox(
        southwest =
          Position(longitude = longitudeAt(x, tileCount), latitude = latitudeAt(y + 1, tileCount)),
        northeast =
          Position(longitude = longitudeAt(x + 1, tileCount), latitude = latitudeAt(y, tileCount)),
      )
    }

  private companion object {
    const val MinZoom = 0
    const val MaxZoom = 32
  }
}

/**
 * Supplies geographic features for one tile.
 *
 * On MapLibre Native, calls run on background threads where blocking work, such as reading a file
 * or a database, is safe, and calls for different tiles can run at the same time. On the browser,
 * calls run on the page's main thread and overlap only where they suspend.
 *
 * A tile can be requested again while a call for it is still running. The library may cancel a call
 * that it no longer needs, or share one call between requests for the same tile, so do not expect
 * exactly one call for each request.
 */
public fun interface GeometryTileProvider {
  /**
   * Returns the features of [tile].
   *
   * The library cancels a call when MapLibre no longer needs the tile or the source leaves the
   * style. Any other exception, including a cancellation that the provider causes itself, such as
   * its own timeout, is handled differently on each platform:
   * - On the browser, the exception is logged as a warning, and the tile fails to load. MapLibre
   *   reports a tile error with the exception message, shows lower-zoom data in place of the tile
   *   where available, and requests the tile again when it is needed again.
   * - On MapLibre Native, the exception is logged as an error, and the tile loads with no features.
   *   MapLibre does not report a tile error, but a [MapSnapshotter] capture that needs the tile
   *   fails.
   */
  public suspend fun loadTile(tile: TileCoordinate): FeatureCollection<*, *>
}

/**
 * Supplies encoded vector data for one tile.
 *
 * Calls run on the same threads, overlap the same way, and are cancelled or shared the same way as
 * [GeometryTileProvider] calls.
 */
public fun interface VectorTileProvider {
  /**
   * Returns an uncompressed MVT protobuf document for [tile]. An empty array represents an empty
   * tile.
   *
   * The library cancels a call when MapLibre no longer needs the tile or the source leaves the
   * style. Any other exception, including a cancellation that the provider causes itself, such as
   * its own timeout, is logged as a warning, and the tile fails to load. MapLibre reports a tile
   * error with the exception message, shows lower-zoom data in place of the tile where available,
   * and requests the tile again when it is needed again.
   */
  public suspend fun loadTile(tile: TileCoordinate): ByteArray
}

/**
 * A source whose tiles contain geographic features that the application supplies.
 *
 * MapLibre clips and simplifies the returned features using [CustomGeometrySourceOptions].
 *
 * On the browser, feature IDs are converted to integers, array and object property values become
 * JSON strings, and null-valued properties are omitted. Native preserves these values.
 *
 * Layers read the source's single feature layer regardless of their `source-layer` setting. On the
 * browser, a layer handle reads back the source id as its `source-layer`.
 *
 * When the provider fails, the tile fails to load on the browser and loads with no features on
 * MapLibre Native. See [GeometryTileProvider.loadTile].
 */
public class CustomGeometrySource(
  id: String,
  private val options: CustomGeometrySourceOptions = CustomGeometrySourceOptions(),
  private val provider: GeometryTileProvider,
) : VectorSource(id) {

  override fun definition(): SourceDefinition =
    SourceDefinition.CustomGeometry(id, options, provider)

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "custom-geometry")
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
    put("buffer", options.buffer)
    put("tolerance", options.tolerance)
    put("clip", options.clip)
    put("wrap", options.wrap)
  }
}

/**
 * A source whose tiles contain uncompressed MVT protobuf documents that the application supplies.
 *
 * Layers that use this source specify a source layer that exists in the returned MVT document.
 *
 * When the provider fails, the tile fails to load. See [VectorTileProvider.loadTile].
 */
public class CustomVectorTileSource(
  id: String,
  private val options: CustomVectorTileSourceOptions = CustomVectorTileSourceOptions(),
  private val provider: VectorTileProvider,
) : VectorSource(id) {

  override fun definition(): SourceDefinition = SourceDefinition.CustomVector(id, options, provider)

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "vector")
    putJsonArray("tiles") {}
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
  }
}

/**
 * Controls the feature tiles that MapLibre creates.
 *
 * @param minZoom Minimum zoom level at which MapLibre creates tiles. Defaults to 0.
 * @param maxZoom Maximum zoom level at which MapLibre creates tiles. Defaults to 18, the MapLibre
 *   default for a custom geometry source. MapLibre overzooms the highest tiles beyond it.
 * @param buffer Tile buffer size on each side. Zero disables the buffer, and 512 adds a buffer as
 *   wide as the tile. Larger values reduce rendering artifacts near tile edges and increase
 *   processing time.
 * @param tolerance Douglas-Peucker simplification tolerance. Larger values create simpler geometry
 *   and reduce processing time.
 * @param clip Whether MapLibre clips geometry to the tile bounds.
 * @param wrap Whether MapLibre unwraps wrapped coordinates.
 */
@Immutable
public data class CustomGeometrySourceOptions(
  val minZoom: Int = 0,
  val maxZoom: Int = 18,
  val buffer: Int = 128,
  val tolerance: Float = 0.375f,
  val clip: Boolean = false,
  val wrap: Boolean = false,
) {
  init {
    validateZoomRange(minZoom, maxZoom)
  }
}

/**
 * Options for application-supplied MVT tiles.
 *
 * @param minZoom Minimum zoom level at which MapLibre requests tiles. Defaults to 0.
 * @param maxZoom Maximum zoom level at which MapLibre requests tiles. Defaults to 22, the style
 *   spec default for a vector source. MapLibre overzooms the highest tiles beyond it.
 */
@Immutable
public data class CustomVectorTileSourceOptions(
  val minZoom: Int = 0,
  val maxZoom: Int = 22,
) {
  init {
    validateZoomRange(minZoom, maxZoom)
  }
}

/** Remembers a [CustomGeometrySource] that uses [provider]. */
@Composable
public fun rememberCustomGeometrySource(
  options: CustomGeometrySourceOptions = CustomGeometrySourceOptions(),
  provider: GeometryTileProvider,
): CustomGeometrySource {
  return key(options) {
    rememberUserSource { CustomGeometrySource(id = it, options = options, provider = provider) }
  }
}

/** Remembers a [CustomVectorTileSource] that uses [provider]. */
@Composable
public fun rememberCustomVectorTileSource(
  options: CustomVectorTileSourceOptions = CustomVectorTileSourceOptions(),
  provider: VectorTileProvider,
): CustomVectorTileSource {
  return key(options) {
    rememberUserSource { CustomVectorTileSource(id = it, options = options, provider = provider) }
  }
}

private fun validateZoomRange(minZoom: Int, maxZoom: Int) {
  require(minZoom in 0..32) { "minZoom must be within 0..32, was $minZoom" }
  require(maxZoom in 0..32) { "maxZoom must be within 0..32, was $maxZoom" }
  require(minZoom <= maxZoom) {
    "minZoom must be less than or equal to maxZoom, was $minZoom and $maxZoom"
  }
}

private fun longitudeAt(column: Long, tileCount: Double): Double =
  column / tileCount * 360.0 - 180.0

private fun latitudeAt(row: Long, tileCount: Double): Double =
  atan(sinh(PI * (1.0 - 2.0 * row / tileCount))) * 180.0 / PI
