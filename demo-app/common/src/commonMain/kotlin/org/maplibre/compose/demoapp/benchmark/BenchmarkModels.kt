package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.maplibre.compose.map.MapUiOptions

/** Camera workloads share the same scene and measurement protocol. */
enum class BenchmarkScenario(val id: String, val title: String, val description: String) {
  Animation(
    "animation",
    "Camera animation",
    "Compare map and overlay pixels during native camera animation.",
  ),
  Setters(
    "setters",
    "Camera setters",
    "Compare map and overlay pixels while Compose drives the camera.",
  ),
  Input(
    "input",
    "Input response",
    "Tap the map to alternate camera positions and measure the visible response.",
  ),
}

val allBenchmarkScenarios = BenchmarkScenario.entries

/** Launch format: scenario,surface,maximumFps,load. An unset FPS cap is written as default. */
data class BenchmarkConfig(
  val scenario: BenchmarkScenario = BenchmarkScenario.Animation,
  val surface: String = "surface",
  val maximumFps: Int? = null,
  val load: Int = 0,
) {
  init {
    require(surface in setOf("surface", "texture"))
    require(maximumFps == null || maximumFps in 1..240)
    require(load in 0..10000)
  }

  fun encode() = "${scenario.id},$surface,${maximumFps ?: "default"},$load"

  companion object {
    fun parse(value: String?): BenchmarkConfig? {
      if (value == null) return null
      val parts = value.split(',')
      require(parts.size == 4) { "Expected scenario,surface,maximumFps,load" }
      return BenchmarkConfig(
        scenario =
          requireNotNull(BenchmarkScenario.entries.firstOrNull { it.id == parts[0] }) {
            "Unknown scenario"
          },
        surface = parts[1],
        maximumFps = if (parts[2] == "default") null else parts[2].toInt(),
        load = parts[3].toInt(),
      )
    }
  }
}

class BenchmarkUiState {
  var runId by mutableStateOf(0)
    private set

  var running by mutableStateOf(false)
  var status by mutableStateOf("Ready")
  var surface by mutableStateOf("surface")
  var maximumFps by mutableStateOf<Int?>(null)
  var load by mutableStateOf(0)

  fun requestRun() {
    if (running) return
    running = true
    status = "Starting"
    runId++
  }

  fun abandonRun() {
    runId = 0
    running = false
    status = "Ready"
  }
}

@Composable internal expect fun benchmarkLaunchConfig(): BenchmarkConfig?

internal expect fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions

/** Platform tracing uses the same measurement interval as the visible green gate. */
internal expect fun benchmarkTrace(active: Boolean)

/** Records input event time in the platform clock used by its capture adapter, when available. */
internal expect fun benchmarkInput(sequence: Int, uptimeMillis: Long)

@Composable internal expect fun BenchmarkPlatformMetrics(active: Boolean)
