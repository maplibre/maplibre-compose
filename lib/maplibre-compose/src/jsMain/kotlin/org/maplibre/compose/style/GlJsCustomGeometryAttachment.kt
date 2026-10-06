package org.maplibre.compose.style

import js.objects.unsafeJso
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.maplibre.compose.gljs.GeoJsonToTileOptions
import org.maplibre.compose.gljs.VectorTileEncodingOptions
import org.maplibre.compose.gljs.fromGeojsonVt
import org.maplibre.compose.gljs.geoJSONToTile
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.toJson

/** Encodes provider features as MVT for GL JS's vector source. */
internal class GlJsCustomGeometryAttachment(
  sourceId: String,
  private val options: CustomGeometrySourceOptions,
  private val provider: GeometryTileProvider,
) {
  private val attachment =
    GlJsProtocolTileAttachment(
      name = "custom-geometry-$sourceId",
      loadTile = { tile ->
        try {
          encodeTile(provider.loadTile(tile), tile)
        } catch (error: Throwable) {
          // A cancelled job means the request ended. The provider's own cancellation, such as a
          // timeout, leaves the job active and fails like any other exception.
          if (error is CancellationException) currentCoroutineContext().ensureActive()
          // Match native and keep the tile reloadable: GL JS cannot refetch an errored tile.
          MapLog.e(error) { "Custom geometry source '$sourceId' failed to load $tile" }
          byteArrayOf()
        }
      },
    )

  /** The MVT layer name carried by every tile; GL JS matches layers to it by name. */
  val sourceLayerName: String = sourceId

  val tileUrlTemplate: String
    get() = attachment.tileUrlTemplate

  fun invalidate(): String = attachment.invalidate()

  fun close() = attachment.close()

  private fun encodeTile(features: FeatureCollection<*, *>, tile: TileCoordinate): ByteArray {
    val data: dynamic = JSON.parse(features.toJson())
    val encoded =
      geoJSONToTile(
        data = data,
        z = tile.zoomLevel,
        x = tile.x.toInt(),
        y = tile.y.toInt(),
        options =
          unsafeJso<GeoJsonToTileOptions> {
            extent = Extent.toDouble()
            buffer = (Scale * options.buffer).toDouble()
            tolerance = Scale * options.tolerance.toDouble()
            maxZoom = MaxZoom
            wrap = options.wrap
            clip = options.clip
          },
      )
    if (encoded == null || encoded.features.length == 0) return byteArrayOf()
    val layers: dynamic = js("Object.create(null)")
    layers[sourceLayerName] = encoded
    val bytes =
      fromGeojsonVt(
        layers,
        unsafeJso<VectorTileEncodingOptions> {
          version = 2.0
          extent = Extent.toDouble()
        },
      )
    return ByteArray(bytes.length) { bytes[it].toByte() }
  }

  private companion object {
    /** MapLibre Native's `util::EXTENT`, the tile extent it encodes custom geometry at. */
    const val Extent = 8192

    /** The tile size the source's options are measured against. */
    const val TileSize = 512

    /** Converts buffer and tolerance from tile-size units to extent units. */
    const val Scale = Extent / TileSize

    // Keep the conversion pass precise; geojson-vt applies per-tile simplification below this zoom.
    const val MaxZoom = 24
  }
}
