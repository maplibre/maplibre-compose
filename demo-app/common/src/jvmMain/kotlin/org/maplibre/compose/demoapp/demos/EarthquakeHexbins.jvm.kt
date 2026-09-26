package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.demoapp.demos.ngon.NgonLayer
import org.maplibre.compose.demoapp.demos.ngon.NgonPlugin
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

internal actual fun earthquakeHexbins(): EarthquakeHexbins? =
  if (NgonPlugin.isRegistered) NgonEarthquakeHexbins else null

/** One [NgonLayer] hexagon per bin, on a screen-space grid rebuilt at each whole zoom level. */
internal object NgonEarthquakeHexbins : EarthquakeHexbins {
  private var quakes by mutableStateOf<List<Position>?>(null)

  @Composable
  override fun MapContent(feedUri: String) {
    LaunchedEffect(feedUri) {
      if (quakes == null) quakes = runCatching { fetchQuakes(feedUri) }.getOrNull()
    }
    val loaded = quakes ?: return
    val mapState = LocalMapState.current ?: return
    // Derived so that camera motion within a level does not recompose.
    val level by
      remember(mapState) { derivedStateOf { round(mapState.cameraPosition.zoom).toInt() } }
    val bins = remember(loaded, level) { hexbin(loaded, level, CELL_RADIUS_DP) }
    val source = rememberGeoJsonSource(GeoJsonData.Features(bins.cells))

    NgonLayer(
      id = "earthquake-hexbins",
      source = source,
      corners = const(6f),
      // TODO: a zoom ramp, once the plugin layer re-evaluates zoom-dependent paint while zooming.
      radius = const(bins.radiusDp.dp),
      color =
        interpolate(
          linear(),
          feature["share"].asNumber(),
          0 to const(Color(0xFFFFF176)),
          0.5 to const(Color(0xFFFF9800)),
          1 to const(Color(0xFFB71C1C)),
        ),
      colorTransition = TransitionOptions(400.milliseconds),
      opacity = const(0.8f),
      strokeWidth = const(1.dp),
      strokeColor = const(Color.White),
      strokeOpacity = const(0.7f),
      pitchAlignment = const(CirclePitchAlignment.Map),
      pitchScale = const(CirclePitchScale.Map),
    )
  }
}

private suspend fun fetchQuakes(feedUri: String): List<Position> {
  val json = HttpClient().use { client -> client.get(feedUri).bodyAsText() }
  val collection =
    FeatureCollection.fromJsonOrNull<Geometry?, JsonObject?>(json) ?: return emptyList()
  return collection.mapNotNull { (it.geometry as? Point)?.coordinates }
}

private class Hexbins(val cells: FeatureCollection<Point, JsonObject>, val radiusDp: Float)

/**
 * Pointy-top hexagons of about [cellRadiusDp] dp at zoom [level] in Web Mercator. The radius is
 * nudged so a whole number of columns spans the world and cells merge across the antimeridian.
 */
private fun hexbin(quakes: List<Position>, level: Int, cellRadiusDp: Float): Hexbins {
  val columns =
    (WORLD_SIZE / (sqrt(3.0) * cellRadiusDp / 2.0.pow(level))).roundToInt().coerceAtLeast(1)
  val radius = WORLD_SIZE / (sqrt(3.0) * columns)
  val counts = HashMap<Pair<Int, Int>, Int>()
  for (quake in quakes) {
    val (x, y) = project(quake)
    val (q, r) = hexAt(x, y, radius)
    counts.merge(q.mod(columns) to r, 1, Int::plus)
  }
  val busiest = counts.values.maxOrNull() ?: 1
  val features = counts.map { (cell, count) ->
    val (x, y) = hexCenter(cell, radius)
    Feature(
      geometry = Point(unproject(x.mod(WORLD_SIZE), y)),
      properties = buildJsonObject { put("share", ln(1.0 + count) / ln(1.0 + busiest)) },
    )
  }
  return Hexbins(FeatureCollection(features), (radius * 2.0.pow(level)).toFloat())
}

private fun hexAt(x: Double, y: Double, radius: Double): Pair<Int, Int> {
  val q = (sqrt(3.0) / 3 * x - y / 3) / radius
  val r = (2.0 / 3 * y) / radius
  // Cube rounding.
  var rq = round(q)
  var rr = round(r)
  val rs = round(-q - r)
  val dq = abs(rq - q)
  val dr = abs(rr - r)
  val ds = abs(rs - (-q - r))
  if (dq > dr && dq > ds) rq = -rr - rs else if (dr > ds) rr = -rq - rs
  return rq.toInt() to rr.toInt()
}

private fun hexCenter(cell: Pair<Int, Int>, radius: Double): Pair<Double, Double> {
  val (q, r) = cell
  return radius * (sqrt(3.0) * q + sqrt(3.0) / 2 * r) to radius * 1.5 * r
}

private const val CELL_RADIUS_DP = 24f

private const val WORLD_SIZE = 512.0

private fun project(position: Position): Pair<Double, Double> {
  val x = (position.longitude + 180) / 360 * WORLD_SIZE
  val latitude = position.latitude.coerceIn(-85.05, 85.05) * PI / 180
  val y = (1 - ln(tan(PI / 4 + latitude / 2)) / PI) / 2 * WORLD_SIZE
  return x to y
}

private fun unproject(x: Double, y: Double): Position =
  Position(
    longitude = x / WORLD_SIZE * 360 - 180,
    latitude = atan(sinh(PI * (1 - 2 * y / WORLD_SIZE))) * 180 / PI,
  )
