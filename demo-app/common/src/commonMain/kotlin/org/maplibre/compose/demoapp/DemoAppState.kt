package org.maplibre.compose.demoapp

import androidx.compose.foundation.isSystemInDarkTheme
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

/** New York City at a metro-area zoom, so every demo's fly-in has somewhere to go. */
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
  /**
   * The demo shown on the map. Select demos through [selectDemo] so the panel can align its
   * destination.
   */
  var selectedDemo: Demo?
    get() = mapConfiguration.selectedDemo
    set(value) {
      mapConfiguration.selectedDemo = value
    }

  /**
   * Selects [demo] in the Demos shell. The one selection path for both the panel's demo list and
   * the agent driver; the panel observes [selectedDemo] and navigates to the demo's controls.
   */
  fun selectDemo(demo: Demo) {
    selectedDemo = demo
    shell = DemoShell.Demos
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
    @Composable get() = mapConfiguration.appliedStyle(settings)

  /**
   * The style the map composition most recently applied. Kept as plain state because [appliedStyle]
   * is composable, so non-composition readers like the agent driver cannot call it. Goes stale
   * while the Benchmarks shell is showing, where the demo map is not composed.
   */
  internal var appliedStyleSnapshot by mutableStateOf<DemoStyle?>(null)

  var shell by mutableStateOf(DemoShell.Demos)

  var selectedScenario by mutableStateOf<BenchmarkScenario>(allBenchmarkScenarios.first())
  val benchmark = BenchmarkUiState()

  /** The most recent base style load to finish or fail. Read its count before a reload. */
  internal var lastStyleLoad by mutableStateOf(StyleLoad(count = 0, base = null))
    private set

  /**
   * The base style the map has applied but not yet finished or failed loading, if any. Set by the
   * map composition and cleared by [noteStyleLoad].
   */
  internal var pendingStyleLoad by mutableStateOf<BaseStyle?>(null)

  internal fun noteStyleLoad(base: BaseStyle) {
    lastStyleLoad = StyleLoad(lastStyleLoad.count + 1, base)
    if (pendingStyleLoad == base) pendingStyleLoad = null
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

  @Composable
  fun appliedStyle(settings: DemoSettings): DemoStyle {
    selectedDemo?.preferredStyle?.let {
      return it
    }
    val systemDark = isSystemInDarkTheme()
    return when (settings.mapStyleMode) {
      MapStyleMode.System -> if (systemDark) chosenDarkStyle else chosenLightStyle
      MapStyleMode.Light -> chosenLightStyle
      MapStyleMode.Dark -> chosenDarkStyle
    }
  }
}

internal data class StyleLoad(val count: Int, val base: BaseStyle?)

// Pin the composition target to UI. Without it, the @MaplibreComposable content lambda below lets
// the compiler's target inference mark this function as map content. The compiler then propagates
// that target to target-inferred calling scopes (the Nucleus window host) and rejects their UI
// composables.
@UiComposable
@Composable
fun rememberDemoAppState(): DemoAppState {
  val mapRuntime = DefaultMapRuntime.instance
  val settings = rememberDemoSettings()
  val mapConfiguration = remember { DemoMapConfiguration() }
  val appliedStyle = mapConfiguration.appliedStyle(settings)
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
