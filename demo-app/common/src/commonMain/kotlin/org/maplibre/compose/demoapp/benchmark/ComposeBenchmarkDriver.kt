package org.maplibre.compose.demoapp.benchmark

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.benchmark.*
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.expressions.dsl.*
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.*
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.PreparedImage

/**
 * The Compose host of the shared workloads. Only this adapter knows whether a workload step is a
 * handle write or a Compose state change. The presentation assigns [state] and [density] once it
 * has created the map, before the run starts.
 */
internal class ComposeBenchmarkDriver(private val resources: BenchmarkFixture) :
  BenchmarkDriver(resources.prepared, nextFrame = { withFrameNanos { it } }) {
  private val declared = config.implementation == BenchmarkImplementation.Declarative
  lateinit var state: MapState
  var density = 1f
  private lateinit var scope: CoroutineScope
  private var recorder: BenchmarkFrameRecorder? = null
  private var frames: Job? = null

  var baseStyle by mutableStateOf(resources.baseStyles[0])
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
  private var overlayShown by mutableStateOf(true)

  // A query must not accept the previous style's rendered overlay after the new style is ready.
  private val metadataOverlayId: String
    get() =
      if (config.scenario == BenchmarkScenario.StyleOverlay)
        "metadata-overlay-${resources.baseStyles.indexOf(baseStyle)}"
      else "metadata-overlay"

  @Composable
  @MaplibreComposable
  fun Content() {
    if (!declared || config.scenario == BenchmarkScenario.Style || resources.data.isEmpty()) return
    if (config.scenario in setOf(BenchmarkScenario.StyleOverlay, BenchmarkScenario.OverlayUpdate)) {
      if (config.scenario == BenchmarkScenario.StyleOverlay || overlayShown) {
        val source = checkNotNull(getBaseSource<GeoJsonSource>("data"))
        Anchor.Above(predicate = { it.id == "workload-0" }) {
          CircleLayer(
            metadataOverlayId,
            source,
            radius = const(8.dp),
            color = const(BenchmarkColors[1]),
          )
        }
      }
      return
    }
    // Reading an otherwise unused tick deliberately invalidates this scope, without changing
    // inputs.
    @Suppress("UNUSED_VARIABLE") val tick = recomposeTick
    val source = rememberGeoJsonSource(resources.data[revision])
    // Declared sources are reference-counted by layers. Keep a hidden reference so
    // structural layer work does not also remove and reparse the source.
    if (config.scenario == BenchmarkScenario.Layers) {
      if (resources.line) LineLayer("source-anchor", source, visible = false)
      else CircleLayer("source-anchor", source, visible = false)
    }
    val partitioned = resources.partitioned
    repeat(layerCount) { index ->
      // A sparse update recolors only the first layer.
      val color =
        if (config.scenario == BenchmarkScenario.SparsePaint && index != 0) 0 else colorIndex
      if (resources.line)
        LineLayer(
          "workload-$index",
          source,
          color = const(BenchmarkColors[color]),
          width = const(3.dp),
          visible = visible,
        )
      else
        CircleLayer(
          "workload-$index",
          source,
          color = const(BenchmarkColors[color]),
          radius =
            if (partitioned)
              interpolate(linear(), zoom(), 0 to const(2.dp), 12 to const(5.dp), 20 to const(9.dp))
            else const(5.dp),
          filter =
            if (partitioned) (feature.id().asNumber() % const(config.layers)) eq const(index)
            else null,
          visible = visible,
        )
    }
  }

  override suspend fun prepare(scope: CoroutineScope): StartupReport {
    this.scope = scope
    collectFrames()
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      state.events.collect { event ->
        val failure =
          when (event) {
            is MapEvent.StyleLoadFailed -> event.reason
            is MapEvent.SourceDataFailed -> event.cause.message ?: "Source load failed"
            else -> null
          }
        if (failure != null) {
          println("MAP_BENCHMARK ERROR $failure")
          error(failure)
        }
      }
    }
    // The browser reports no frame completeness, so its first complete frame is the idle event.
    val firstFrame =
      scope.async(start = CoroutineStart.UNDISPATCHED) {
        state.events.first { event ->
          val complete =
            when (event) {
              is MapEvent.FrameRendered -> event.stats?.mode == RenderStats.Mode.Full
              MapEvent.Idle -> true
              else -> false
            }
          complete && state.style.loadState is StyleLoadState.Ready
        }
        sinceCreation()
      }
    var styleReady = 0.0
    try {
      withTimeout(15000) {
        snapshotFlow { state.style.loadState }
          .first { it is StyleLoadState.Ready || it is StyleLoadState.Failed }
        check(state.style.loadState is StyleLoadState.Ready) { "Benchmark style failed to load" }
        styleReady = sinceCreation()
        snapshotFlow { state.viewport }.first { it != null }
      }
    } catch (e: TimeoutCancellationException) {
      // A timeout is a workload failure, not caller cancellation; report it as an error.
      error("Timed out waiting for the style and viewport")
    }
    if (config.scenario in setOf(BenchmarkScenario.Images, BenchmarkScenario.ImagePreparation))
      image(0)
    if (config.scenario == BenchmarkScenario.MapReturn) registerImages()
    settled {}
    return StartupReport(styleReady, withTimeout(15000) { firstFrame.await() })
  }

  override fun camera(value: BenchmarkCamera) {
    state.setCameraPosition(value.toCompose())
  }

  override suspend fun animate(value: BenchmarkCamera, durationMs: Long) {
    state.animateCamera(
      value.toCompose().toCameraUpdate(),
      CameraAnimation.Fly(durationMs.milliseconds),
    )
  }

  override fun style(index: Int): Deferred<Unit> {
    val style = resources.baseStyles[index]
    if (declared) baseStyle = style else checkNotNull(state.style.asMutable).baseStyle = style
    return scope.async {
      snapshotFlow {
        state.style.baseStyle == style && state.style.loadState is StyleLoadState.Ready
      }
        .first { it }
    }
  }

  override suspend fun prepareImage(index: Int) {
    val image =
      withContext(Dispatchers.Default) { PreparedImage.fromBitmap(resources.bitmaps[index]) }
    state.style.images.set("workload-image", ResolvedStyleImage(image))
  }

  override fun overlay(show: Boolean) {
    overlayShown = show
  }

  override suspend fun overlayVisible(): Boolean {
    val offset = checkNotNull(state.screenLocationFromPosition(BenchmarkOrigin))
    return state.queryRenderedFeatures(offset, layerIds = setOf(metadataOverlayId)).isNotEmpty()
  }

  override fun image(index: Int) {
    state.style.images.set("workload-image", resources.images[index])
  }

  /** Imperative styles declare their layers in the style JSON, so they have nothing to restore. */
  override fun layers(show: Boolean) {
    if (declared) layerCount = if (show) config.layers else 0
  }

  override fun paint(index: Int) {
    if (declared) colorIndex = index
    else
      repeat(config.layers) { layer ->
        checkNotNull(state.style.layers["workload-$layer"]?.asMutable)
          .setPaintProperty(
            if (resources.line) "line-color" else "circle-color",
            JsonPrimitive(BenchmarkColorStrings[index]),
          )
      }
  }

  override fun sparsePaint(index: Int) {
    if (declared) colorIndex = index
    else
      checkNotNull(state.style.layers["workload-0"]?.asMutable)
        .setPaintProperty("circle-color", JsonPrimitive(BenchmarkColorStrings[index]))
  }

  override fun registerImages() {
    state.style.images.setAll(
      (0 until config.imageCount).associate { index ->
        "burst-$index" to resources.images[index % resources.images.size]
      }
    )
  }

  override fun removeImages() {
    repeat(config.imageCount) { state.style.images.remove("burst-$it") }
  }

  override fun visible(show: Boolean) {
    if (declared) visible = show
    else
      repeat(config.layers) { index ->
        checkNotNull(state.style.layers["workload-$index"]?.asMutable)
          .setLayoutProperty("visibility", JsonPrimitive(if (show) "visible" else "none"))
      }
  }

  override fun source(index: Int) {
    revision = index
    if (!declared)
      checkNotNull((state.style.sources["data"] as? GeoJsonSourceHandle)?.asMutable)
        .setData(resources.data[index])
  }

  override fun height(fraction: Double) {
    heightFraction = fraction.toFloat()
  }

  override fun padding(bottom: Double) {
    viewportInsets = PaddingValues(bottom = bottom.dp)
  }

  override fun recompose() {
    recomposeTick++
  }

  override suspend fun renderedRevisions(): List<Int> {
    val offset = checkNotNull(state.screenLocationFromPosition(BenchmarkOrigin))
    return state.queryRenderedFeatures(offset).mapNotNull {
      it.properties?.get("revision")?.jsonPrimitive?.intOrNull
    }
  }

  override suspend fun settled(block: suspend () -> Unit) {
    block()
    // Frame callbacks run before recomposition. Cross a second frame boundary so declarations and
    // layout have been applied before requesting camera/render settlement.
    if (declared) repeat(2) { withFrameNanos {} }
    // An image lookup runs after the resource commands enqueued before it, so it is the barrier
    // that puts queued image writes inside the measured operation.
    state.style.images["workload-image"]
    awaitSettled(state)
  }

  override fun viewport(): List<Double> {
    val size = checkNotNull(state.viewport).size
    return listOf(size.width.value.toDouble(), size.height.value.toDouble(), density.toDouble())
  }

  /** Frames are collected from [prepare] on, so a recorder attached earlier misses none. */
  override fun recordFrames(recorder: BenchmarkFrameRecorder?) {
    this.recorder = recorder
    if (this::scope.isInitialized) collectFrames()
  }

  private fun collectFrames() {
    frames?.cancel()
    frames = recorder?.let { recorder ->
      scope.launch(Dispatchers.Unconfined) {
        state.events
          .filterIsInstance<MapEvent.FrameRendered>()
          .map { event ->
            FrameSample(
              encodingMs = event.stats?.encodingTime?.inWholeMicroseconds?.div(1e3),
              renderingMs = event.stats?.renderingTime?.inWholeMicroseconds?.div(1e3),
              drawCalls = event.stats?.drawCallCount,
              mode = event.stats?.mode?.name?.lowercase(),
            )
          }
          .collect(recorder::record)
      }
    }
  }

  override fun close() {
    state.close()
  }

  override suspend fun awaitClosed() {
    withTimeout(10000) { state.awaitClosed() }
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
    // after subscribing so completion cannot wait for an event that already happened.
    benchmarkRequestRepaint(state)
    settled.await()
  }
}

internal expect suspend fun benchmarkRequestRepaint(state: MapState)
