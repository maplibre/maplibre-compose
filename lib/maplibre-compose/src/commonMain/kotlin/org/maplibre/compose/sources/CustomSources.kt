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
    require(zoomLevel in MIN_ZOOM..MAX_ZOOM) {
      "zoomLevel must be within $MIN_ZOOM..$MAX_ZOOM"
    }
    val tileCount = 1L shl zoomLevel
    require(x in 0 until tileCount) { "x must be within 0 until $tileCount at zoom $zoomLevel" }
    require(y in 0 until tileCount) { "y must be within 0 until $tileCount at zoom $zoomLevel" }
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
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 32
  }
}

/**
 * Supplies geographic features for one tile.
 *
 * Calls for different tiles can overlap. Cancellation means MapLibre no longer needs that request.
 */
public fun interface GeometryTileProvider {
  public suspend fun loadTile(tile: TileCoordinate): FeatureCollection<*, *>
}

/**
 * Supplies encoded vector data for one tile.
 *
 * Calls for different tiles can overlap. Cancellation means MapLibre no longer needs that request.
 */
public fun interface VectorTileProvider {
  /** Returns an uncompressed MVT protobuf document. An empty array represents an empty tile. */
  public suspend fun loadTile(tile: TileCoordinate): ByteArray
}

/**
 * A source whose tiles contain geographic features that the application supplies.
 *
 * MapLibre clips, simplifies, and encodes the returned features for rendering. This source is not
 * available on the browser platform: adding it to a style there throws
 * [UnsupportedOperationException].
 */
public class CustomGeometrySource(
  id: String,
  private val options: CustomGeometrySourceOptions = CustomGeometrySourceOptions(),
  private var provider: GeometryTileProvider,
) : Source(id) {

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

  internal fun setDesiredProvider(provider: GeometryTileProvider) {
    this.provider = provider
  }
}

/**
 * A source whose tiles contain uncompressed MVT protobuf documents that the application supplies.
 *
 * Layers that use this source specify a source layer that exists in the returned MVT document.
 */
public class CustomVectorSource(
  id: String,
  private val options: CustomVectorSourceOptions = CustomVectorSourceOptions(),
  private var provider: VectorTileProvider,
) : Source(id) {

  override fun definition(): SourceDefinition = SourceDefinition.CustomVector(id, options, provider)

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "vector")
    putJsonArray("tiles") {}
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
  }

  internal fun setDesiredProvider(provider: VectorTileProvider) {
    this.provider = provider
  }
}

/**
 * Controls the feature tiles that MapLibre creates.
 *
 * @param minZoom Minimum zoom level at which MapLibre creates tiles.
 * @param maxZoom Maximum zoom level at which MapLibre creates tiles.
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
  val minZoom: Int = SourceDefaults.MIN_ZOOM,
  val maxZoom: Int = SourceDefaults.MAX_ZOOM,
  val buffer: Int = 128,
  val tolerance: Float = 0.375f,
  val clip: Boolean = false,
  val wrap: Boolean = false,
) {
  init {
    validateZoomRange(minZoom, maxZoom)
  }
}

/** Options for application-supplied MVT tiles. */
@Immutable
public data class CustomVectorSourceOptions(
  val minZoom: Int = SourceDefaults.MIN_ZOOM,
  val maxZoom: Int = SourceDefaults.MAX_ZOOM,
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
    rememberUserSource(
      factory = { CustomGeometrySource(id = it, options = options, provider = provider) },
      update = { setDesiredProvider(provider) },
    )
  }
}

/** Remembers a [CustomVectorSource] that uses [provider]. */
@Composable
public fun rememberCustomVectorSource(
  options: CustomVectorSourceOptions = CustomVectorSourceOptions(),
  provider: VectorTileProvider,
): CustomVectorSource {
  return key(options) {
    rememberUserSource(
      factory = { CustomVectorSource(id = it, options = options, provider = provider) },
      update = { setDesiredProvider(provider) },
    )
  }
}

private fun validateZoomRange(minZoom: Int, maxZoom: Int) {
  require(minZoom in 0..32) { "minZoom must be within 0..32" }
  require(maxZoom in 0..32) { "maxZoom must be within 0..32" }
  require(minZoom <= maxZoom) { "minZoom must be less than or equal to maxZoom" }
}

private fun longitudeAt(column: Long, tileCount: Double): Double =
  column / tileCount * 360.0 - 180.0

private fun latitudeAt(row: Long, tileCount: Double): Double =
  atan(sinh(PI * (1.0 - 2.0 * row / tileCount))) * 180.0 / PI
