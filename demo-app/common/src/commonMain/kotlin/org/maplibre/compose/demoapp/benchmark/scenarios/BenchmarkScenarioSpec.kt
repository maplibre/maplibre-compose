package org.maplibre.compose.demoapp.benchmark.scenarios

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import kotlin.time.TimeMark
import kotlinx.coroutines.delay
import org.maplibre.compose.demoapp.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

/** Per-run Compose state that scenarios may drive from their workload. */
@Stable
internal class BenchmarkSceneState {
  /** Viewport insets for the map; only the padding scenario changes them. */
  var viewportInsets by mutableStateOf(PaddingValues(0.dp))

  /** Map extent as a fraction of the window; only the resize scenario changes it. */
  var sizeFraction by mutableStateOf(1f)
}

/** Everything the measurement scaffold needs for one [BenchmarkScenario]. */
internal interface BenchmarkScenarioSpec {
  val scenario: BenchmarkScenario

  /** Builds the base style for [config]. Called once per run. */
  fun style(config: BenchmarkConfig): BaseStyle

  /** Composes scenario style content inside the map; the scaffold calls this once per run. */
  @Composable
  @MaplibreComposable
  fun Content(state: MapState, config: BenchmarkConfig, scene: BenchmarkSceneState) {}

  /** Runs the measured workload; the scaffold times everything until this returns. */
  suspend fun workload(state: MapState, config: BenchmarkConfig, scene: BenchmarkSceneState)
}

/** Returns a fresh spec for [scenario] so a run cannot share scenario state with another run. */
internal fun benchmarkScenarioSpec(scenario: BenchmarkScenario): BenchmarkScenarioSpec =
  when (scenario) {
    BenchmarkScenario.Animation -> CameraAnimationScenario()
    BenchmarkScenario.Setters -> CameraSettersScenario()
    BenchmarkScenario.Input -> InputScenario()
    BenchmarkScenario.StyleComplex -> StyleComplexScenario()
    BenchmarkScenario.StyleSwap -> StyleSwapScenario()
    BenchmarkScenario.StyleMutate -> StyleMutateScenario()
    BenchmarkScenario.GeojsonUpdate -> GeojsonUpdateScenario()
    BenchmarkScenario.Padding -> PaddingScenario()
    BenchmarkScenario.Images -> ImagesScenario()
    BenchmarkScenario.Resize -> ResizeScenario()
  }

/** The measured workload interval every scenario must produce. */
internal const val MeasurementMillis = 12_000L

/** Calls [block] once per frame for the measured interval. */
internal suspend fun frameLoop(block: (seconds: Double) -> Unit) {
  val start = withFrameNanos { it }
  while (true) {
    val seconds = (withFrameNanos { it } - start) / 1e9
    if (seconds >= MeasurementMillis / 1000.0) break
    block(seconds)
  }
}

/** Milliseconds left in the measurement interval, never negative. */
internal fun TimeMark.remainingMeasurementMs(): Long =
  (MeasurementMillis - elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)

/** Sleeps for [millis], but never past the measurement deadline. */
internal suspend fun TimeMark.delayInMeasurement(millis: Long) {
  val remaining = remainingMeasurementMs()
  if (remaining > 0) delay(minOf(millis, remaining))
}
