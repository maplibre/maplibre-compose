package org.maplibre.compose.sources

import io.github.dellisd.spatialk.geojson.Feature
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource as MLNVectorSource
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.util.correctedAndroidUri
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.compose.util.toMLNExpression

public actual class VectorSource : Source {
  override val impl: MLNVectorSource

  internal constructor(source: MLNVectorSource) {
    impl = source
  }

  public actual constructor(id: String, uri: String) {
    impl = MLNVectorSource(id, uri.correctedAndroidUri())
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions) {
    impl =
      MLNVectorSource(
        id,
        TileSet("{\"type\": \"vector\"}", *tiles.map { it.correctedAndroidUri() }.toTypedArray())
          .apply {
            minZoom = options.minZoom.toFloat()
            maxZoom = options.maxZoom.toFloat()
            scheme =
              when (options.tileCoordinateSystem) {
                TileCoordinateSystem.XYZ -> "xyz"
                TileCoordinateSystem.TMS -> "tms"
              }
            options.boundingBox?.let { setBounds(it.toLatLngBounds()) }
            attribution = options.attributionHtml
          },
      )
  }

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature> {
    return impl
      .querySourceFeatures(
        sourceLayerIds = sourceLayerIds.toTypedArray(),
        filter =
          predicate
            .takeUnless { it == const(true) }
            ?.compile(ExpressionContext.None)
            ?.toMLNExpression(),
      )
      .map { Feature.fromJson(it.toJson()) }
  }
}
