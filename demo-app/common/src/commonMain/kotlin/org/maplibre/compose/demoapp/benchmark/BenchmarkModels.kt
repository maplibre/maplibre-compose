package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.GeoJsonObject
import org.maplibre.spatialk.geojson.Position

/** Lower Manhattan; small enough that an offline pack of zooms 12–16 stays modest. */
internal val BenchmarkRegion =
  BoundingBox(west = -74.020, south = 40.700, east = -73.993, north = 40.722)

internal val BenchmarkCenter =
  Position(
    longitude = (BenchmarkRegion.west + BenchmarkRegion.east) / 2,
    latitude = (BenchmarkRegion.south + BenchmarkRegion.north) / 2,
  )

/** A scripted or interactive measurement that owns its camera path and map content. */
interface BenchmarkScenario {
  val id: String
  val title: String
  val description: String
  val region: BoundingBox
  val minZoom: Int
  val maxZoom: Int
  val camera: CameraPosition
  val usesGestures: Boolean
    get() = false

  val style: DemoStyle
    get() = OpenFreeMap.Liberty

  @MaplibreComposable @Composable fun MapContent(session: BenchmarkSession) {}

  suspend fun run(session: BenchmarkSession)
}

/** Mutable state one scenario run reads and writes while the isolated map is live. */
@Stable
class BenchmarkSession(
  val map: MapState,
  val ui: BenchmarkUiState,
  val prefetcher: TilePrefetcher,
  val frames: FrameTimeCollector = FrameTimeCollector(),
  val gestures: GestureLatencyTracker = GestureLatencyTracker(),
) {
  var geoJson by mutableStateOf<GeoJsonObject?>(null)
  var pin by mutableStateOf<Position?>(null)
  var pointerPx by mutableStateOf<Offset?>(null)
}

/** Status the panel shows; the map owns the collectors and the run coroutine. */
@Stable
class BenchmarkUiState {
  var runId by mutableStateOf(0)
  var running by mutableStateOf(false)
  var status by mutableStateOf("Pick a scenario and run it.")
  var report by mutableStateOf<BenchmarkReport?>(null)

  fun requestRun() {
    if (running) return
    report = null
    status = "Starting"
    runId++
  }

  /** Drops a leftover run token so a new scenario waits for Run. */
  fun abandonRun() {
    runId = 0
    running = false
    report = null
    status = "Pick a scenario and run it."
  }
}

data class BenchmarkReport(
  val schema: String = Schema,
  val scenario: String,
  val platform: String,
  val prefetch: String,
  val durationMs: Double,
  val frames: FrameTimeStats,
  val gesture: GestureLatencyStats? = null,
) {
  fun toJsonLine(): String {
    val gestureJson =
      if (gesture == null) "null"
      else
        """{"samples":${gesture.samples},"medianTrailPx":${gesture.medianTrailPx.fmt()},"p95TrailPx":${gesture.p95TrailPx.fmt()},"medianLatencyMs":${gesture.medianLatencyMs.fmt()},"p95LatencyMs":${gesture.p95LatencyMs.fmt()}}"""
    return """{"schema":"$schema","scenario":"$scenario","platform":"$platform","prefetch":"$prefetch","durationMs":${durationMs.fmt()},"frames":${frames.frames},"avgFrameMs":${frames.avgMs.fmt()},"p50FrameMs":${frames.p50Ms.fmt()},"p95FrameMs":${frames.p95Ms.fmt()},"maxFrameMs":${frames.maxMs.fmt()},"droppedFrames":${frames.droppedFrames},"vsyncMs":${frames.vsyncMs.fmt()},"gesture":$gestureJson}"""
  }

  companion object {
    const val Schema = "maplibre-compose-bench/v1"
    const val LogPrefix = "maplibre-compose-bench"
  }
}

internal fun Double.fmt(): String {
  if (this.isNaN() || this.isInfinite()) return "null"
  val scaled = (this * 1000.0).toLong()
  val whole = scaled / 1000
  val frac = kotlin.math.abs(scaled % 1000).toString().padStart(3, '0')
  return "$whole.$frac"
}

val allBenchmarkScenarios: List<BenchmarkScenario> =
  listOf(
    ZoomPumpScenario,
    FlyAroundScenario,
    GeoJsonLoadScenario(synchronousUpdate = true),
    GeoJsonLoadScenario(synchronousUpdate = false),
    GestureTrailScenario,
  )
