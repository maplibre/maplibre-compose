package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.*
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/** Only this adapter knows whether a workload step is a handle write or a Compose state change. */
internal class ComposeBenchmarkDriver(val fixture: BenchmarkFixture) {
  private val config = fixture.config
  private val declared = config.implementation == BenchmarkImplementation.Declarative
  var baseStyle by mutableStateOf(fixture.baseStyles[0])
    private set

  var heightFraction by mutableStateOf(1f)
    private set

  var viewportInsets by mutableStateOf(PaddingValues(0.dp))
    private set

  private var revision by mutableStateOf(0)
  private var colorIndex by mutableStateOf(0)
  private var visible by mutableStateOf(true)
  private var layerCount by mutableStateOf(config.layers)
  private var recomposeTick by mutableStateOf(0)
  private var styleIndex = 0

  fun prepare(state: MapState) {
    if (fixture.images.isNotEmpty()) replaceImage(state, 0)
  }

  private fun replaceImage(state: MapState, index: Int) {
    state.style.images.set("workload-image", fixture.images[index])
  }

  @Composable
  @MaplibreComposable
  fun Content() {
    if (!declared || config.scenario == BenchmarkScenario.Style || fixture.data.isEmpty()) return
    // Reading an otherwise unused tick deliberately invalidates this scope, without changing
    // inputs.
    @Suppress("UNUSED_VARIABLE") val tick = recomposeTick
    val source = rememberGeoJsonSource(fixture.data[revision])
    // Declared sources are reference-counted by layers. Keep a hidden reference so
    // structural layer work does not also remove and reparse the source.
    if (config.scenario == BenchmarkScenario.Layers) {
      if (fixture.line) LineLayer("source-anchor", source, visible = false)
      else CircleLayer("source-anchor", source, visible = false)
    }
    repeat(layerCount) { index ->
      if (fixture.line)
        LineLayer(
          "workload-$index",
          source,
          color = const(BenchmarkColors[colorIndex]),
          width = const(3.dp),
          visible = visible,
        )
      else
        CircleLayer(
          "workload-$index",
          source,
          color = const(BenchmarkColors[colorIndex]),
          radius = const(5.dp),
          visible = visible,
        )
    }
  }

  suspend fun run(state: MapState, clock: BenchmarkWorkload) {
    when (config.scenario) {
      BenchmarkScenario.Idle -> clock.idle()
      BenchmarkScenario.Camera,
      BenchmarkScenario.Overlays ->
        clock.frames { progress -> state.setCameraPosition(tourCamera(progress)) }
      BenchmarkScenario.Animation -> {
        repeat(4) { index ->
          state.animateCamera(
            benchmarkCamera(if (index % 2 == 0) 1.0 else -1.0).toCameraUpdate(),
            CameraAnimation.Fly((clock.durationMillis / 4).milliseconds),
          )
          clock.submitted()
        }
        clock.idle()
      }
      BenchmarkScenario.Resize ->
        clock.frames { progress ->
          heightFraction = (0.75 + 0.25 * kotlin.math.cos(progress * 4 * PI)).toFloat()
        }
      BenchmarkScenario.Padding ->
        clock.frames { progress ->
          viewportInsets =
            PaddingValues(bottom = ((1 - kotlin.math.cos(progress * 4 * PI)) * 100).dp)
        }
      BenchmarkScenario.Recompose -> clock.frames { recomposeTick++ }
      else ->
        clock.scheduled(config.rateHz) { tick ->
          val started = TimeSource.Monotonic.markNow()
          var completion: Double? = null
          when (config.scenario) {
            BenchmarkScenario.Images -> replaceImage(state, (tick + 1) % 2)
            BenchmarkScenario.Paint -> {
              val color = (tick + 1) % 2
              if (declared) colorIndex = color
              else
                repeat(config.layers) { index ->
                  checkNotNull(state.style.layers["workload-$index"]?.asMutable)
                    .setPaintProperty(
                      if (fixture.line) "line-color" else "circle-color",
                      JsonPrimitive(BenchmarkColorStrings[color]),
                    )
                }
            }
            BenchmarkScenario.Layout -> {
              val show = tick % 2 != 0
              if (declared) visible = show
              else
                repeat(config.layers) { index ->
                  checkNotNull(state.style.layers["workload-$index"]?.asMutable)
                    .setLayoutProperty("visibility", JsonPrimitive(if (show) "visible" else "none"))
                }
            }
            BenchmarkScenario.Layers -> layerCount = if (tick % 2 == 0) 0 else config.layers
            BenchmarkScenario.Source,
            BenchmarkScenario.SourceLatency -> {
              revision = 1 - revision
              if (!declared)
                checkNotNull((state.style.sources["data"] as? GeoJsonSourceHandle)?.asMutable)
                  .setData(fixture.data[revision])
            }
            BenchmarkScenario.Style -> {
              styleIndex = 1 - styleIndex
              if (declared) baseStyle = fixture.baseStyles[styleIndex]
              else checkNotNull(state.style.asMutable).baseStyle = fixture.baseStyles[styleIndex]
            }
            else -> error("Unexpected scheduled workload")
          }
          val submitted = started.elapsedNow().inWholeNanoseconds / 1e6
          if (config.scenario == BenchmarkScenario.Style) {
            clock.completionSignal = "style-ready"
            withTimeout(10000) {
              snapshotFlow {
                state.style.baseStyle == fixture.baseStyles[styleIndex] &&
                  state.style.loadState is StyleLoadState.Ready
              }
                .first { it }
            }
            completion = started.elapsedNow().inWholeNanoseconds / 1e6
          } else if (config.scenario == BenchmarkScenario.SourceLatency) {
            clock.completionSignal = "rendered-feature-revision"
            withTimeout(10000) {
              while (true) {
                withFrameNanos {}
                val offset = checkNotNull(state.screenLocationFromPosition(BenchmarkOrigin))
                val features = state.queryRenderedFeatures(offset, layerIds = setOf("workload-0"))
                if (
                  features.any {
                    it.properties?.get("revision")?.jsonPrimitive?.intOrNull == revision
                  }
                )
                  break
              }
            }
            completion = started.elapsedNow().inWholeNanoseconds / 1e6
          }
          clock.submitted(submitted, completion)
        }
    }
  }

  /** Restore the same visible state after warming, without discarding renderer/resource caches. */
  suspend fun reset(state: MapState) {
    heightFraction = 1f
    viewportInsets = PaddingValues(0.dp)
    revision = 0
    colorIndex = 0
    visible = true
    layerCount = config.layers
    if (config.scenario == BenchmarkScenario.Style) {
      styleIndex = 0
      if (declared) baseStyle = fixture.baseStyles[0]
      else checkNotNull(state.style.asMutable).baseStyle = fixture.baseStyles[0]
      withTimeout(10000) {
        snapshotFlow {
          state.style.baseStyle == fixture.baseStyles[0] &&
            state.style.loadState is StyleLoadState.Ready
        }
          .first { it }
      }
    }
    if (config.scenario == BenchmarkScenario.Images) replaceImage(state, 0)
    if (!declared && fixture.data.isNotEmpty() && config.scenario != BenchmarkScenario.Images) {
      checkNotNull((state.style.sources["data"] as? GeoJsonSourceHandle)?.asMutable)
        .setData(fixture.data[0])
      repeat(config.layers) { index ->
        checkNotNull(state.style.layers["workload-$index"]?.asMutable).apply {
          setPaintProperty(
            if (fixture.line) "line-color" else "circle-color",
            JsonPrimitive(BenchmarkColorStrings[0]),
          )
          setLayoutProperty("visibility", JsonPrimitive("visible"))
        }
      }
    }
    if (declared) {
      // Frame callbacks run before recomposition. Cross a second frame boundary so the reset's
      // declarations and layout have been applied before requesting camera/render settlement.
      repeat(2) { withFrameNanos {} }
    }
    state.setCameraPosition(benchmarkCamera(-1.0))
    // Allow pending declarations and asynchronous source preparation to settle before measurement.
    awaitSettled(state)
  }
}

/** Readiness is based on engine events, with a bounded wait; style-ready alone is insufficient. */
internal suspend fun awaitSettled(state: MapState) {
  withTimeout(15000) {
    val settled =
      async(start = CoroutineStart.UNDISPATCHED) {
        state.events.first { event ->
          when (event) {
            is MapEvent.StyleLoadFailed -> error(event.reason)
            is MapEvent.SourceDataFailed -> throw event.cause
            MapEvent.Idle -> state.style.loadState is StyleLoadState.Ready
            is MapEvent.FrameRendered ->
              event.stats?.let { it.mode == RenderStats.Mode.Full && !it.needsRepaint } == true &&
                state.style.loadState is StyleLoadState.Ready
            else -> false
          }
        }
      }
    // A settled map may have emitted its last frame before this subscription. Request one
    // after subscribing; this handshake happens outside the measured workload.
    benchmarkRequestRepaint(state)
    settled.await()
  }
}

internal expect suspend fun benchmarkRequestRepaint(state: MapState)
