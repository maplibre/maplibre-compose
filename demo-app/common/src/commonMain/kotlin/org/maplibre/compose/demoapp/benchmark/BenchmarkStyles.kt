package org.maplibre.compose.demoapp.benchmark

import androidx.compose.ui.graphics.*
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

internal val BenchmarkOrigin = Position(BenchmarkLongitude, BenchmarkLatitude)
internal val BenchmarkColors = BenchmarkColorStrings.map {
  Color(0xff000000L or it.drop(1).toLong(16))
}

internal fun BenchmarkCamera.toCompose() =
  CameraPosition(
    target = Position(longitude, latitude),
    zoom = zoom,
    bearing = bearing,
    tilt = tilt,
  )

internal fun benchmarkCamera(x: Double) =
  org.maplibre.compose.benchmark.benchmarkCamera(x).toCompose()

internal fun tourCamera(progress: Double) =
  org.maplibre.compose.benchmark.tourCamera(progress).toCompose()

internal class BenchmarkFixture(prepared: PreparedBenchmarkFixture) {
  val config = prepared.config
  val data = prepared.data.map(GeoJsonData::JsonString)
  val baseStyles = prepared.baseStyles.map(BaseStyle::Json)
  val line = prepared.line
  val images =
    if (config.scenario == BenchmarkScenario.Images)
      BenchmarkColors.map { color ->
        ImageBitmap(32, 32).also {
          Canvas(it).drawRect(0f, 0f, 32f, 32f, Paint().apply { this.color = color })
        }
      }
    else emptyList()
}

internal suspend fun loadBenchmarkFixture(config: BenchmarkConfig) =
  BenchmarkFixture(
    org.maplibre.compose.benchmark.loadBenchmarkFixture(
      config,
      read = { Res.readBytes("files/benchmarks/$it").decodeToString() },
      uri = { Res.getUri("files/benchmarks/$it") },
    )
  )
