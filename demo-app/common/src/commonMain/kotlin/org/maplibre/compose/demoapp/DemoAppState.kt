package org.maplibre.compose.demoapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.BenchmarkUiState
import org.maplibre.compose.demoapp.benchmark.allBenchmarkScenarios
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState
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
class DemoAppState(
  val cameraState: CameraState,
  val styleState: StyleState,
  val settings: DemoSettings,
  val frameRateState: FrameRateState,
) {
  /**
   * The demo shown on the map. Select demos through [selectDemo] so the panel can align its
   * destination.
   */
  var selectedDemo by mutableStateOf<Demo?>(null)

  /**
   * Selects [demo] in the Demos shell. The one selection path for both the panel's demo list and
   * the agent driver; the panel observes [selectedDemo] and navigates to the demo's controls.
   */
  fun selectDemo(demo: Demo) {
    selectedDemo = demo
    shell = DemoShell.Demos
  }

  /** The style applied when [MapStyleMode] resolves to light. */
  var chosenLightStyle by mutableStateOf<DemoStyle>(Protomaps.Light)

  /** The style applied when [MapStyleMode] resolves to dark. */
  var chosenDarkStyle by mutableStateOf<DemoStyle>(Protomaps.Dark)

  /**
   * The style applied to the map: the selected demo's if it provides one, else the chosen style for
   * the current [MapStyleMode].
   */
  val appliedStyle: DemoStyle
    @Composable
    get() {
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

internal data class StyleLoad(val count: Int, val base: BaseStyle?)

@Composable
fun rememberDemoAppState(): DemoAppState {
  val cameraState = rememberCameraState(firstPosition = StartPosition)
  val styleState = rememberStyleState()
  val settings = rememberDemoSettings()
  val frameRateState = remember { FrameRateState() }
  return remember { DemoAppState(cameraState, styleState, settings, frameRateState) }
}
