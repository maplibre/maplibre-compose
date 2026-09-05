package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.UiComposable
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.BenchmarkUiState
import org.maplibre.compose.demoapp.benchmark.allBenchmarkScenarios
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** New York City, zoomed out before selecting a demo. */
private val StartPosition =
  CameraPosition(target = Position(longitude = -74.006, latitude = 40.7128), zoom = 9.5)

/** Whether the shell is showing the shared demo map or the isolated benchmark map. */
enum class DemoShell {
  Demos,
  Benchmarks,
}

/** The state the shell owns: the shared map, the selection, and the settings. */
@Stable
class DemoAppState
internal constructor(
  val mapRuntime: MapRuntime,
  val mapState: MapState,
  val settings: DemoSettings,
  val frameRateState: FrameRateState,
  private val mapConfiguration: DemoMapConfiguration,
) {
  /** The demo shown on the map. */
  var selectedDemo: Demo?
    get() = mapConfiguration.selectedDemo
    set(value) {
      mapConfiguration.selectedDemo = value
    }

  /**
   * Selects [demo] and flies the camera to its destination. [dark] is the resolved map style mode,
   * so the flight can wait for the demo's matching base style to load. [reveal] runs after the
   * selection and before the flight, so a shell can uncover the map and let the settled viewport
   * insets reach the camera first.
   */
  suspend fun openDemo(demo: Demo, dark: Boolean, reveal: suspend () -> Unit = {}) {
    val newBase =
      mapConfiguration.appliedStyle(dark, demo).base.takeIf { it != mapState.style.baseStyle }
    val styleLoadsSeen = lastStyleLoad.count
    selectedDemo = demo
    shell = DemoShell.Demos
    reveal()
    if (newBase != null) awaitStyleLoad(seen = styleLoadsSeen, base = newBase)
    mapState.flyTo(demo.destination)
  }

  /** The style applied when [MapStyleMode] resolves to light. */
  var chosenLightStyle: DemoStyle
    get() = mapConfiguration.chosenLightStyle
    set(value) {
      mapConfiguration.chosenLightStyle = value
    }

  /** The style applied when [MapStyleMode] resolves to dark. */
  var chosenDarkStyle: DemoStyle
    get() = mapConfiguration.chosenDarkStyle
    set(value) {
      mapConfiguration.chosenDarkStyle = value
    }

  /**
   * The style applied to the map: the selected demo's if it provides one, else the chosen style for
   * the current [MapStyleMode].
   */
  val appliedStyle: DemoStyle
    @Composable get() = mapConfiguration.appliedStyle(settings.mapStyleMode.isDark)

  var shell by mutableStateOf(DemoShell.Demos)

  var selectedScenario by mutableStateOf<BenchmarkScenario>(allBenchmarkScenarios.first())
  val benchmark = BenchmarkUiState()

  /** The most recent base style load to finish or fail. Read its count before a reload. */
  internal var lastStyleLoad by mutableStateOf(StyleLoad(count = 0, base = null))
    private set

  internal fun noteStyleLoad(base: BaseStyle) {
    lastStyleLoad = StyleLoad(lastStyleLoad.count + 1, base)
  }

  /**
   * Suspends until a load of [base] completes beyond the count captured in [seen]. The base match
   * keeps a stale in-flight load from releasing a newer selection's wait.
   */
  internal suspend fun awaitStyleLoad(seen: Int, base: BaseStyle) {
    snapshotFlow { lastStyleLoad }.first { it.count > seen && it.base == base }
  }
}

@Stable
internal class DemoMapConfiguration {
  var selectedDemo by mutableStateOf<Demo?>(null)
  var chosenLightStyle by mutableStateOf<DemoStyle>(Protomaps.Light)
  var chosenDarkStyle by mutableStateOf<DemoStyle>(Protomaps.Dark)

  fun appliedStyle(dark: Boolean, demo: Demo? = selectedDemo): DemoStyle =
    if (dark) demo?.preferredDarkStyle ?: chosenDarkStyle
    else demo?.preferredLightStyle ?: chosenLightStyle
}

internal data class StyleLoad(val count: Int, val base: BaseStyle?)

// Prevent the map-content lambda from making callers infer a map composition target.
// The Nucleus window host needs a UI target.
@UiComposable
@Composable
fun rememberDemoAppState(): DemoAppState {
  val mapRuntime = DefaultMapRuntime.instance
  val settings = rememberDemoSettings()
  val mapConfiguration = remember { DemoMapConfiguration() }
  val appliedStyle = mapConfiguration.appliedStyle(settings.mapStyleMode.isDark)
  val mapState =
    rememberMapState(
      runtime = mapRuntime,
      baseStyle = appliedStyle.base,
      initialCameraPosition = StartPosition,
    ) {
      mapConfiguration.selectedDemo?.let { demo -> key(demo) { demo.MapContent() } }
    }
  val frameRateState = remember { FrameRateState() }
  return remember {
    DemoAppState(mapRuntime, mapState, settings, frameRateState, mapConfiguration)
  }
}
