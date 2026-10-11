@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

@Composable
fun Images() {
  // #region icon-painter
  val position = Position(longitude = -74.0120, latitude = 40.7067)
  val state =
    rememberMapState(initialCameraPosition = CameraPosition(center = position, zoom = 15.5)) {
      val source =
        rememberGeoJsonSource(GeoJsonData.Features(Feature(Point(position), properties = null)))
      SymbolLayer(
        id = "badge",
        source = source,
        iconImage = image(rememberBadgePainter("12 min")),
        iconAllowOverlap = const(true),
      )
    }
  MaplibreMap(state = state)
  // #endregion icon-painter

  val imageState = rememberMapState {
    // #region image-source
    val corners =
      PositionQuad(
        topLeft = Position(longitude = -74.0178, latitude = 40.7040),
        topRight = Position(longitude = -74.0118, latitude = 40.7100),
        bottomRight = Position(longitude = -74.0060, latitude = 40.7067),
        bottomLeft = Position(longitude = -74.0120, latitude = 40.7006),
      )
    val plan = rememberImageSource(position = corners, uri = Res.getUri("files/castello-plan.jpg"))
    RasterLayer(id = "castello-plan", source = plan, opacity = const(0.8f))
    // #endregion image-source
  }
  MaplibreMap(state = imageState)
}

// #region badge-painter
@Composable
private fun rememberBadgePainter(label: String): Painter {
  val colors = MaterialTheme.colorScheme
  val style = MaterialTheme.typography.labelLarge.copy(color = colors.onPrimaryContainer)
  val text = rememberTextMeasurer().measure(label, style = style)
  val padding = with(LocalDensity.current) { 12.dp.toPx() }
  return remember(colors.primaryContainer, text, padding) {
    object : Painter() {
      override val intrinsicSize = Size(text.size.width + padding * 2, text.size.height + padding)

      override fun DrawScope.onDraw() {
        drawRoundRect(colors.primaryContainer, cornerRadius = CornerRadius(size.height / 2))
        drawText(
          text,
          topLeft =
            Offset((size.width - text.size.width) / 2, (size.height - text.size.height) / 2),
        )
      }
    }
  }
}

// #endregion badge-painter

@Composable
fun MissingImages(fallback: Painter) {
  // #region missing-image
  val mapState = rememberMapState()
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  DisposableEffect(mapState, fallback, density, layoutDirection) {
    mapState.missingImageResolver = {
      ResolvedStyleImage.fromPainter(fallback, density, layoutDirection)
    }
    onDispose { mapState.missingImageResolver = null }
  }
  MaplibreMap(state = mapState)
  // #endregion missing-image
}
