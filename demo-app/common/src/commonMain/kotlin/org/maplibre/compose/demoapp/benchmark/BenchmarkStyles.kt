package org.maplibre.compose.demoapp.benchmark

import androidx.compose.ui.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.PreparedImage
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
    pitch = pitch,
  )

internal fun benchmarkCamera(x: Double) =
  org.maplibre.compose.benchmark.benchmarkCamera(x).toCompose()

internal fun tourCamera(progress: Double) =
  org.maplibre.compose.benchmark.tourCamera(progress).toCompose()

internal class BenchmarkFixture(
  val prepared: PreparedBenchmarkFixture,
  val images: List<ResolvedStyleImage>,
  val bitmaps: List<ImageBitmap>,
) {
  val config = prepared.config
  val data = prepared.data.map(GeoJsonData::JsonString)
  val baseStyles = prepared.baseStyles.map(BaseStyle::Json)
  val line = prepared.line
  val partitioned = prepared.partitioned
}

internal suspend fun loadBenchmarkFixture(config: BenchmarkConfig): BenchmarkFixture {
  val prepared =
    org.maplibre.compose.benchmark.loadBenchmarkFixture(
      config,
      read = { Res.readBytes("files/benchmarks/$it").decodeToString() },
      uri = { Res.getUri("files/benchmarks/$it") },
    )
  val bitmaps =
    if (prepared.usesImages)
      BenchmarkColors.map { color ->
        val size = prepared.imageSize
        ImageBitmap(size, size).also {
          Canvas(it)
            .drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { this.color = color })
        }
      }
    else emptyList()
  val images =
    withContext(Dispatchers.Default) {
      bitmaps.map { ResolvedStyleImage(PreparedImage.fromBitmap(it)) }
    }
  return BenchmarkFixture(prepared, images, bitmaps)
}
