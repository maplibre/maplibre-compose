package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.cos
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters

@Composable
@MaplibreComposable
internal actual fun PlatformLocationIndicator(properties: LocationIndicatorProperties) {
  val data =
    remember(properties.location) {
      GeoJsonData.Features(
        FeatureCollection(
          Feature(
            Point(properties.location),
            buildJsonObject {
              put(
                "latitude",
                properties.location.latitude.coerceIn(-85.0511287798066, 85.0511287798066),
              )
            },
            id = JsonPrimitive(0),
          )
        )
      )
    }
  val source = rememberGeoJsonSource(data)
  val handle = LocalMapState.current?.style?.sources?.get(source)
  LaunchedEffect(handle, properties.accuracyRadius) {
    handle?.setFeatureState(
      "0",
      buildJsonObject { put("accuracy", properties.accuracyRadius?.inMeters ?: 0.0) },
    )
  }
  if (properties.accuracyRadius != null) {
    CircleLayer(
      id = "${properties.id}-accuracy",
      source = source,
      minZoom = properties.minZoom,
      maxZoom = properties.maxZoom,
      visible = properties.visible,
      radius = accuracyRadiusExpression,
      color = const(properties.accuracyRadiusColor),
      strokeColor = const(properties.accuracyRadiusBorderColor),
      strokeWidth = const(1.dp),
      pitchAlignment = const(CirclePitchAlignment.Map),
    )
  }
  val images =
    listOf(
      Triple("shadow", properties.shadowImage, properties.shadowImageSize),
      Triple(
        "bearing",
        properties.bearingImage.takeIf { properties.bearing != null },
        properties.bearingImageSize,
      ),
      Triple("top", properties.topImage, properties.topImageSize),
    )
  val clickGroup = remember { Any() }
  CompositionLocalProvider(LocalLayerClickGroup provides clickGroup) {
    for ((suffix, image, size) in images) {
      key(suffix) {
        if (image != null)
          SymbolLayer(
            id = "${properties.id}-$suffix",
            source = source,
            minZoom = properties.minZoom,
            maxZoom = properties.maxZoom,
            visible = properties.visible,
            iconImage = image,
            iconSize = const(size),
            iconRotate =
              const(
                if (suffix == "bearing")
                  properties.bearing?.let { (it - Bearing.North).inDegrees.toFloat() } ?: 0f
                else 0f
              ),
            iconRotationAlignment = const(IconRotationAlignment.Map),
            iconPitchAlignment = const(IconPitchAlignment.Map),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            onClick = properties.onClick?.takeIf { suffix != "shadow" }?.asFeaturesClickHandler(),
            onLongClick =
              properties.onLongClick?.takeIf { suffix != "shadow" }?.asFeaturesClickHandler(),
            onDoubleClick =
              properties.onDoubleClick?.takeIf { suffix != "shadow" }?.asFeaturesClickHandler(),
            hitPadding = properties.hitPadding,
          )
      }
    }
  }
}

private val accuracyRadiusExpression = run {
  // Mercator meters per logical pixel at zoom zero. Zoom must be the outer interpolation in a
  // composite camera/feature expression. Location updates carry latitude; accuracy uses state.
  val metersPerPixel =
    const((2 * PI * 6378137 / 512).toFloat()) *
      cos(feature["latitude"].asNumber() * const((PI / 180).toFloat()))
  val radiusAtZoomZero = feature.state("accuracy").asNumber(const(0f)) / metersPerPixel
  interpolate(
    exponential(2f),
    zoom(),
    0 to radiusAtZoomZero.dp,
    24 to (radiusAtZoomZero * const((1 shl 24).toFloat())).dp,
  )
}
