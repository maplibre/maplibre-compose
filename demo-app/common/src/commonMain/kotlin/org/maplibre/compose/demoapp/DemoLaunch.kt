package org.maplibre.compose.demoapp

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.maplibre.compose.benchmark.BenchmarkScenario
import org.maplibre.compose.benchmark.allBenchmarkScenarios
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

/**
 * The state a launcher opens the demo in, so a script or a bug report can reach a screen without
 * tapping through the menu. Each launcher reads its own argument source; AGENTS.md lists them.
 */
data class DemoLaunch(
  /** The panel screen to open. Null opens the demo list. */
  val route: DemoLaunchRoute? = null,
  /** The initial camera. A demo route with a camera does not fly to the demo's destination. */
  val camera: CameraPosition? = null,
  /** The window size in dp. Only windowed launchers read it. */
  val windowSize: DpSize? = null,
) {
  companion object {
    val None = DemoLaunch()

    /**
     * Reads the `route`, `camera`, and `extent` arguments through [argument], which returns the
     * value for a name or null when absent. Throws [IllegalArgumentException] for a value it cannot
     * read.
     */
    fun parse(argument: (name: String) -> String?): DemoLaunch =
      DemoLaunch(
        route = argument("route")?.let(DemoLaunchRoute::parse),
        camera = argument("camera")?.let(::parseCamera),
        windowSize = argument("extent")?.let(::parseExtent),
      )

    /** Reads `--name=value` and `--name value` pairs from command-line [arguments]. */
    fun parse(arguments: List<String>): DemoLaunch = parse { name ->
      val prefix = "--$name"
      arguments.withIndex().firstNotNullOfOrNull { (index, argument) ->
        when {
          argument.startsWith("$prefix=") -> argument.removePrefix("$prefix=")
          argument == prefix -> arguments.getOrNull(index + 1)
          else -> null
        }
      }
    }

    /** `zoom/latitude/longitude[/bearing[/pitch]]`, the MapLibre GL JS map hash without `#`. */
    private fun parseCamera(value: String): CameraPosition {
      val parts = value.removePrefix("#").split('/')
      require(parts.size in 3..5) { "Expected camera zoom/latitude/longitude[/bearing[/pitch]]" }
      val numbers = parts.map { requireNotNull(it.toDoubleOrNull()) { "Camera value '$it'" } }
      return CameraPosition(
        zoom = numbers[0],
        target = Position(latitude = numbers[1], longitude = numbers[2]),
        bearing = numbers.getOrElse(3) { 0.0 },
        tilt = numbers.getOrElse(4) { 0.0 },
      )
    }

    /** `WIDTHxHEIGHT` in dp. */
    private fun parseExtent(value: String): DpSize {
      val parts = value.lowercase().split('x')
      require(parts.size == 2) { "Expected extent WIDTHxHEIGHT" }
      val (width, height) = parts.map { requireNotNull(it.toIntOrNull()) { "Extent value '$it'" } }
      require(width > 0 && height > 0) { "Extent must be positive" }
      return DpSize(width.dp, height.dp)
    }
  }
}

/** A panel screen a launcher can open. The parsed forms mirror [DemoRoute]. */
sealed interface DemoLaunchRoute {
  /** `demo/<id>`, where the id is [Demo.id]. */
  data class Demo(val demo: org.maplibre.compose.demoapp.Demo) : DemoLaunchRoute

  /** `benchmarks`. */
  data object Benchmarks : DemoLaunchRoute

  /** `benchmark/<id>`, where the id is [BenchmarkScenario.id]. */
  data class Benchmark(val scenario: BenchmarkScenario) : DemoLaunchRoute

  /** `settings`, or `settings/<page>` for `location`, `input`, `camera`, or `rendering`. */
  data class Settings(val page: String?) : DemoLaunchRoute

  companion object {
    /** Parses a route, or returns null for `demos`, the default screen. */
    fun parse(value: String): DemoLaunchRoute? {
      val segments = value.trim('/').split('/')
      val head = segments[0]
      val tail = segments.getOrNull(1)
      require(segments.size <= 2) { "Unknown route '$value'" }
      return when {
        head == DemoRoute.Demos && tail == null -> null
        head == DemoRoute.Demo && tail != null ->
          Demo(requireNotNull(allDemos.firstOrNull { it.id == tail }) { "Unknown demo '$tail'" })
        head == DemoRoute.Benchmarks && tail == null -> Benchmarks
        head == DemoRoute.Benchmark && tail != null ->
          Benchmark(
            requireNotNull(allBenchmarkScenarios.firstOrNull { it.id == tail }) {
              "Unknown benchmark '$tail'"
            }
          )
        head == DemoRoute.Settings && (tail == null || "$head/$tail" in settingsPages) ->
          Settings(tail)
        else -> throw IllegalArgumentException("Unknown route '$value'")
      }
    }

    private val settingsPages =
      setOf(
        DemoRoute.LocationSettings,
        DemoRoute.InputSettings,
        DemoRoute.CameraSettings,
        DemoRoute.RenderingSettings,
      )
  }
}
