package org.maplibre.compose.benchmark

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

@Serializable
enum class BenchmarkImplementation(val id: String) {
  @SerialName("compose-imperative") Imperative("compose-imperative"),
  @SerialName("compose-declarative") Declarative("compose-declarative"),
  @SerialName("classic-android") ClassicAndroid("classic-android"),
  @SerialName("classic-ios") ClassicIos("classic-ios"),
}

@Serializable
enum class BenchmarkScene(val id: String) {
  @SerialName("minimal") Minimal("minimal"),
  @SerialName("points-100") Points100("points-100"),
  @SerialName("points-1000") Points1000("points-1000"),
  @SerialName("points-10000") Points10000("points-10000"),
  @SerialName("route-2000") Route("route-2000"),
  @SerialName("basemap-sf") Basemap("basemap-sf"),
}

/** Workloads declare only implementations that the public API can actually express. */
@Serializable
enum class BenchmarkScenario(
  val id: String,
  val title: String,
  val description: String,
  val implementations: Set<BenchmarkImplementation> = BenchmarkImplementation.entries.toSet(),
) {
  @SerialName("idle") Idle("idle", "Idle map", "Measure a settled map without updates."),
  @SerialName("camera")
  Camera(
    "camera",
    "Camera tour",
    "A repeatable pan, zoom, bearing, and pitch path.",
    setOf(
      BenchmarkImplementation.Imperative,
      BenchmarkImplementation.ClassicAndroid,
      BenchmarkImplementation.ClassicIos,
    ),
  ),
  @SerialName("overlays")
  Overlays(
    "overlays",
    "Map overlays",
    "The camera tour with the default Compose map controls.",
    setOf(BenchmarkImplementation.Imperative),
  ),
  @SerialName("animation")
  Animation(
    "animation",
    "Camera animation",
    "Engine-driven camera animation.",
    setOf(
      BenchmarkImplementation.Imperative,
      BenchmarkImplementation.ClassicAndroid,
      BenchmarkImplementation.ClassicIos,
    ),
  ),
  @SerialName("paint")
  Paint("paint", "Paint mutation", "Change layer colors without changing layout or data."),
  @SerialName("layout")
  Layout("layout", "Visibility mutation", "Toggle existing layers without changing source data."),
  @SerialName("layers")
  Layers(
    "layers",
    "Layer structure",
    "Add and remove declared layers over one stable source.",
    setOf(
      BenchmarkImplementation.Declarative,
      BenchmarkImplementation.ClassicAndroid,
      BenchmarkImplementation.ClassicIos,
    ),
  ),
  @SerialName("source")
  Source("source", "Source replacement", "Replace prepared GeoJSON data at a fixed rate."),
  @SerialName("source-latency")
  SourceLatency(
    "source-latency",
    "Source completion",
    "Wait until a rendered-feature query observes the submitted revision.",
  ),
  @SerialName("style")
  Style("style", "Style replacement", "Measure replacement through style-ready completion."),
  @SerialName("resize")
  Resize(
    "resize",
    "Map resize",
    "Resize map height like an expanding panel.",
    setOf(
      BenchmarkImplementation.Declarative,
      BenchmarkImplementation.ClassicAndroid,
      BenchmarkImplementation.ClassicIos,
    ),
  ),
  @SerialName("padding")
  Padding(
    "padding",
    "Viewport padding",
    "Animate bottom padding without resizing the surface.",
    setOf(
      BenchmarkImplementation.Declarative,
      BenchmarkImplementation.ClassicAndroid,
      BenchmarkImplementation.ClassicIos,
    ),
  ),
  @SerialName("recompose")
  Recompose(
    "recompose",
    "Unchanged recomposition",
    "Recompose declared content while leaving map properties unchanged.",
    setOf(BenchmarkImplementation.Declarative),
  ),
  @SerialName("images")
  Images(
    "images",
    "Image registration",
    "Replace a prepared bitmap used by symbol layers.",
    setOf(
      BenchmarkImplementation.Imperative,
      BenchmarkImplementation.ClassicAndroid,
      BenchmarkImplementation.ClassicIos,
    ),
  ),
}

val allBenchmarkScenarios = BenchmarkScenario.entries
val BenchmarkJson = Json
val BenchmarkJsonWithDefaults = Json { encodeDefaults = true }

/** Run configuration shared by the demo and command-line runner. */
@Serializable
data class BenchmarkConfig(
  @SerialName("workload") val scenario: BenchmarkScenario = BenchmarkScenario.Camera,
  val scene: BenchmarkScene = BenchmarkScene.Points1000,
  val implementation: BenchmarkImplementation = BenchmarkImplementation.Imperative,
  val surface: String = "surface",
  val maximumFps: Int? = null,
  val layers: Int = 1,
  val rateHz: Double = 4.0,
  val durationMs: Long = 12000,
) {
  init {
    require(surface in setOf("surface", "texture"))
    require(maximumFps == null || maximumFps in 1..240)
    require(layers in 1..32)
    require(rateHz in 0.1..120.0)
    require(durationMs in 3000..30000)
    require(implementation in scenario.implementations) {
      "${scenario.id} does not support ${implementation.id}"
    }
    if (
      scenario in
        setOf(
          BenchmarkScenario.Paint,
          BenchmarkScenario.Layout,
          BenchmarkScenario.Layers,
          BenchmarkScenario.Source,
          BenchmarkScenario.SourceLatency,
          BenchmarkScenario.Recompose,
        )
    ) {
      require(
        scene in
          setOf(
            BenchmarkScene.Points100,
            BenchmarkScene.Points1000,
            BenchmarkScene.Points10000,
            BenchmarkScene.Route,
          )
      ) {
        "${scenario.id} requires a points or route scene"
      }
    }
    if (scenario == BenchmarkScenario.Images)
      require(
        scene in
          setOf(BenchmarkScene.Points100, BenchmarkScene.Points1000, BenchmarkScene.Points10000)
      )
    if (scenario == BenchmarkScenario.SourceLatency)
      require(scene != BenchmarkScene.Route) {
        "Source completion requires a point fixture with a center probe"
      }
  }

  fun encode(): String = BenchmarkJsonWithDefaults.encodeToString(this)

  companion object {
    fun forScenario(scenario: BenchmarkScenario): BenchmarkConfig =
      BenchmarkConfig(
        scenario = scenario,
        implementation = scenario.implementations.first(),
      )

    fun parse(value: String?): BenchmarkConfig? {
      if (value == null) return null
      try {
        return BenchmarkJson.decodeFromString<BenchmarkConfig>(value)
      } catch (e: Exception) {
        println("MAP_BENCHMARK ERROR ${e.message}")
        throw e
      }
    }
  }
}
