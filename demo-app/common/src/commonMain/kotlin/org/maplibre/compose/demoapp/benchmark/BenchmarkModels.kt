package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.compose.map.MapUiOptions

/** One scenario's typed workload parameters. Every field has a default, so `{}` is a valid run. */
@Serializable sealed interface BenchmarkParams

/** The empty parameters of the camera scenarios. */
@Serializable object NoParams : BenchmarkParams

/** [StyleComplexScenario]: generated style size and generated GeoJSON size. */
@Serializable
data class StyleComplexParams(
  /** Number of generated layers over the generated sources. */
  val layers: Int = 8,
  /** Number of generated features per source. */
  val features: Int = 2000,
  /** Number of generated GeoJSON sources. */
  val sources: Int = 2,
) : BenchmarkParams {
  init {
    require(layers in 1..64) { "layers must be in 1..64" }
    require(features in 1..50000) { "features must be in 1..50000" }
    require(sources in 1..8) { "sources must be in 1..8" }
    require(features * sources <= 200_000) {
      "features * sources must be at most 200000 to bound style build work"
    }
  }
}

/** [StyleSwapScenario]: how often and how many times the whole style is replaced. */
@Serializable
data class StyleSwapParams(
  /** Delay between consecutive style replacements. */
  val intervalMs: Int = 1500,
  /** Maximum replacements attempted before the measurement interval ends. */
  val count: Int = 8,
  /** Number of generated layers in each variant. */
  val layers: Int = 6,
  /** Number of generated features per source in each variant. */
  val features: Int = 1500,
) : BenchmarkParams {
  init {
    require(intervalMs in 100..10000) { "intervalMs must be in 100..10000" }
    require(count in 1..60) { "count must be in 1..60" }
    require(layers in 1..64) { "layers must be in 1..64" }
    require(features in 1..50000) { "features must be in 1..50000" }
  }
}

/** [StyleMutateScenario]: cadence and size of composition-driven style mutations. */
@Serializable
data class StyleMutateParams(
  /** Mutations per second. */
  val rateHz: Double = 8.0,
  /** Maximum number of addable layer and source pairs. */
  val pairs: Int = 4,
) : BenchmarkParams {
  init {
    require(rateHz in 0.1..240.0) { "rateHz must be in 0.1..240" }
    require(pairs in 1..16) { "pairs must be in 1..16" }
  }
}

/** [GeojsonUpdateScenario]: rate and size of GeoJSON source data updates. */
@Serializable
data class GeojsonUpdateParams(
  /** Data updates per second. */
  val rateHz: Double = 4.0,
  /** Features in each generated replacement. */
  val features: Int = 5000,
) : BenchmarkParams {
  init {
    require(rateHz in 0.1..240.0) { "rateHz must be in 0.1..240" }
    require(features in 1..50000) { "features must be in 1..50000" }
    require(features * rateHz <= 100_000.0) {
      "features * rateHz must be at most 100000 to bound per-tick parse work"
    }
  }
}

/** [PaddingScenario]: viewport padding oscillation. */
@Serializable
data class PaddingParams(
  /**
   * Peak padding in dp; the horizontal target shift is half of it, so 100 clears the 40 dp floor.
   */
  val amplitudeDp: Double = 120.0,
  /** Full oscillation period in milliseconds. */
  val periodMs: Int = 2000,
) : BenchmarkParams {
  init {
    require(amplitudeDp in 100.0..300.0) { "amplitudeDp must be in 100..300" }
    require(periodMs in 100..10000) { "periodMs must be in 100..10000" }
  }
}

/** [ImagesScenario]: style-image pool size and update cadence. */
@Serializable
data class ImagesParams(
  /** Number of live images and referencing symbol layers. */
  val count: Int = 8,
  /** Delay between image replacement ticks. */
  val intervalMs: Int = 500,
  /** Edge length of each generated bitmap. */
  val sizePx: Int = 32,
) : BenchmarkParams {
  init {
    require(count in 1..64) { "count must be in 1..64" }
    require(intervalMs in 50..10000) { "intervalMs must be in 50..10000" }
    require(sizePx in 4..256) { "sizePx must be in 4..256" }
  }
}

/** [ResizeScenario]: in-app composition resize oscillation. */
@Serializable
data class ResizeParams(
  /** Full oscillation period in milliseconds. */
  val periodMs: Int = 2000,
  /** Smallest map extent as a percentage of the window. */
  val minPercent: Int = 50,
) : BenchmarkParams {
  init {
    require(periodMs in 100..10000) { "periodMs must be in 100..10000" }
    require(minPercent in 10..99) { "minPercent must be in 10..99" }
  }
}

/**
 * One benchmark workload. Ids are the first field of the launch configuration, and the params type
 * is the decoded form of its optional fifth field.
 */
enum class BenchmarkScenario(
  val id: String,
  val title: String,
  val description: String,
  internal val defaultParams: BenchmarkParams,
  internal val paramsSerializer: KSerializer<out BenchmarkParams>,
) {
  Animation(
    "animation",
    "Camera animation",
    "Compare map and overlay pixels during native camera animation.",
    NoParams,
    NoParams.serializer(),
  ),
  Setters(
    "setters",
    "Camera setters",
    "Compare map and overlay pixels while Compose drives the camera.",
    NoParams,
    NoParams.serializer(),
  ),
  Input(
    "input",
    "Input response",
    "Tap the map to alternate camera positions and measure the visible response.",
    NoParams,
    NoParams.serializer(),
  ),
  StyleComplex(
    "style-complex",
    "Complex style",
    "Render a generated style with many layers over generated GeoJSON under slow camera drift.",
    StyleComplexParams(),
    StyleComplexParams.serializer(),
  ),
  StyleSwap(
    "style-swap",
    "Style swap",
    "Replace the whole style between two generated variants on an interval.",
    StyleSwapParams(),
    StyleSwapParams.serializer(),
  ),
  StyleMutate(
    "style-mutate",
    "Style mutation",
    "Add and remove layers and mutate paint and layout properties on an interval.",
    StyleMutateParams(),
    StyleMutateParams.serializer(),
  ),
  GeojsonUpdate(
    "geojson-update",
    "GeoJSON updates",
    "Replace GeoJSON source data at a fixed rate.",
    GeojsonUpdateParams(),
    GeojsonUpdateParams.serializer(),
  ),
  Padding(
    "padding",
    "Camera padding",
    "Oscillate viewport padding each frame to drive relayout and recentering.",
    PaddingParams(),
    PaddingParams.serializer(),
  ),
  Images(
    "images",
    "Style images",
    "Add and remove generated bitmap style images on an interval.",
    ImagesParams(),
    ImagesParams.serializer(),
  ),
  Resize(
    "resize",
    "In-app resize",
    "Resize the map composition between two extents on a timer.",
    ResizeParams(),
    ResizeParams.serializer(),
  ),
}

val allBenchmarkScenarios = BenchmarkScenario.entries

/** The wire format rejects unknown keys and bad types. */
internal val BenchmarkJson = Json

/** The same format, with every field spelled out. */
internal val BenchmarkJsonWithDefaults = Json { encodeDefaults = true }

/** The scenario's default parameters as the panel text field shows them. */
val BenchmarkScenario.defaultParamsJson: String
  get() = encodeParams(defaultParams)

/**
 * Renders [params] as the wire JSON object. Every field is emitted, so a START line always states
 * the full parameter set the run decoded; the runner's metadata may state less.
 */
@Suppress("UNCHECKED_CAST")
private fun BenchmarkScenario.encodeParams(params: BenchmarkParams): String =
  BenchmarkJsonWithDefaults.encodeToString(paramsSerializer as KSerializer<BenchmarkParams>, params)

@Suppress("UNCHECKED_CAST")
private fun BenchmarkScenario.decodeParams(value: String): BenchmarkParams =
  BenchmarkJson.decodeFromString(paramsSerializer as KSerializer<BenchmarkParams>, value)

/**
 * Launch format: scenario,surface,maximumFps,load[,params]. The params field is a JSON object of
 * the scenario's fields; a missing field means the scenario's defaults.
 */
data class BenchmarkConfig(
  val scenario: BenchmarkScenario = BenchmarkScenario.Animation,
  val surface: String = "surface",
  val maximumFps: Int? = null,
  val load: Int = 0,
  val params: BenchmarkParams = scenario.defaultParams,
) {
  init {
    require(surface in setOf("surface", "texture"))
    require(maximumFps == null || maximumFps in 1..240)
    require(load in 0..10000)
    require(params::class == scenario.defaultParams::class) {
      "Scenario ${scenario.id} does not accept ${params::class.simpleName} parameters"
    }
  }

  fun encode(): String =
    "${scenario.id},$surface,${maximumFps ?: "default"},$load,${scenario.encodeParams(params)}"

  companion object {
    /** Builds a configuration from a params JSON object without the launch error logging. */
    fun of(
      scenario: BenchmarkScenario,
      surface: String,
      maximumFps: Int?,
      load: Int,
      paramsJson: String,
    ): BenchmarkConfig =
      BenchmarkConfig(scenario, surface, maximumFps, load, scenario.decodeParams(paramsJson))

    /** Parses [value], printing a MAP_BENCHMARK ERROR line before rejecting bad parameters. */
    fun parse(value: String?): BenchmarkConfig? {
      if (value == null) return null
      try {
        val parts = value.split(',', limit = 5)
        require(parts.size == 4 || parts.size == 5) {
          "Expected scenario,surface,maximumFps,load[,params]"
        }
        val scenario =
          requireNotNull(BenchmarkScenario.entries.firstOrNull { it.id == parts[0] }) {
            "Unknown scenario '${parts[0]}'"
          }
        return BenchmarkConfig(
          scenario = scenario,
          surface = parts[1],
          maximumFps = if (parts[2] == "default") null else parts[2].toInt(),
          load = parts[3].toInt(),
          params = if (parts.size == 5) scenario.decodeParams(parts[4]) else scenario.defaultParams,
        )
      } catch (e: Exception) {
        println("MAP_BENCHMARK ERROR ${e.message}")
        throw e
      }
    }
  }
}

class BenchmarkUiState {
  private var nextRunId = 0
  var runId by mutableStateOf(0)
    private set

  var running by mutableStateOf(false)
  var status by mutableStateOf("Ready")
  var surface by mutableStateOf("surface")
  var maximumFps by mutableStateOf<Int?>(null)
  var load by mutableStateOf(0)
  var paramsJson by mutableStateOf(BenchmarkScenario.Animation.defaultParamsJson)
  var config by mutableStateOf<BenchmarkConfig?>(null)
    private set

  fun requestRun(config: BenchmarkConfig) {
    if (running) return
    running = true
    status = "Starting"
    this.config = config
    runId = ++nextRunId
  }

  fun abandonRun() {
    runId = 0
    running = false
    status = "Ready"
    config = null
  }
}

@Composable internal expect fun benchmarkLaunchConfig(): BenchmarkConfig?

internal expect fun benchmarkMapOptions(config: BenchmarkConfig): MapUiOptions

/** Platform tracing uses the same measurement interval as the visible green gate. */
internal expect fun benchmarkTrace(active: Boolean)

/** Records input event time in the platform clock used by its capture adapter, when available. */
internal expect fun benchmarkInput(sequence: Int, uptimeMillis: Long)

@Composable internal expect fun BenchmarkPlatformMetrics(active: Boolean)
