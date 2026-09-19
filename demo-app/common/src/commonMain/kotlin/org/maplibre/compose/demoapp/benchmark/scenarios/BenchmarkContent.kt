package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

private const val ReferencePoint =
  """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}"""

/**
 * The red map-rendered marker the pixel analysis tracks. Content scenarios compose it last so no
 * generated geometry can cover it.
 */
@Composable
@MaplibreComposable
internal fun ReferenceMarkerLayer() {
  val source = rememberGeoJsonSource(GeoJsonData.JsonString(ReferencePoint))
  CircleLayer(
    id = "reference-point",
    source = source,
    color = const(Color(0xFFFF0000)),
    radius = const(10.dp),
  )
}
