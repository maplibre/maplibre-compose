package org.maplibre.compose.demoapp.benchmark

import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.compose.demoapp.benchmark.scenarios.BenchmarkWorkload
import org.maplibre.compose.style.BaseStyle

@Composable
internal actual fun ClassicAndroidBenchmark(
  fixture: BenchmarkFixture,
  onStatus: (String, Boolean) -> Unit,
) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val driver =
    remember(fixture) {
      MapLibre.getInstance(context)
      ClassicAndroidDriver(
        fixture,
        MapView(
          context,
          MapLibreMapOptions.createFromAttributes(context)
            .textureMode(fixture.config.surface == "texture"),
        ),
      )
    }
  DisposableEffect(driver, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (!driver.view.isDestroyed)
        when (event) {
          Lifecycle.Event.ON_START -> driver.view.onStart()
          Lifecycle.Event.ON_RESUME -> driver.view.onResume()
          Lifecycle.Event.ON_PAUSE -> driver.view.onPause()
          Lifecycle.Event.ON_STOP -> driver.view.onStop()
          else -> Unit
        }
    }
    driver.view.onCreate(null)
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      driver.close()
    }
  }
  AndroidView(
    factory = {
      FrameLayout(it).apply {
        addView(driver.view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
      }
    },
    modifier = Modifier.fillMaxSize(),
  )
  LaunchedEffect(driver) {
    val recorder = BenchmarkFrameRecorder()
    val listener = MapView.OnDidFinishRenderingFrameListener { full, encoding, rendering ->
      recorder.record(
        FrameSample(
          encodingMs = encoding * 1e3,
          renderingMs = rendering * 1e3,
          mode = if (full) "full" else "partial",
        )
      )
    }
    var measuring = false
    val failure = MapView.OnDidFailLoadingMapListener { reason ->
      println("MAP_BENCHMARK ERROR $reason")
    }
    driver.view.addOnDidFailLoadingMapListener(failure)
    try {
      onStatus("Loading", true)
      driver.prepare()
      println("MAP_BENCHMARK START ${fixture.config.encode()}")
      val density = context.resources.displayMetrics.density
      // NativeMapView rounds the logical map size up after dividing by pixel ratio.
      val width = ceil(driver.view.width / density)
      val height = ceil(driver.view.height / density)
      println("MAP_BENCHMARK VIEWPORT [$width,$height,$density]")
      onStatus("Warming up", true)
      driver.run(BenchmarkWorkload(fixture.config.durationMs))
      driver.reset()
      onStatus("Measuring", true)
      benchmarkCpu(true)
      measuring = true
      recorder.start()
      driver.view.addOnDidFinishRenderingFrameListener(listener)
      val workload = BenchmarkWorkload(fixture.config.durationMs)
      driver.run(workload)
      val report = workload.report()
      benchmarkCpu(false)
      measuring = false
      driver.view.removeOnDidFinishRenderingFrameListener(listener)
      recorder.stop()
      report.printResult()
      driver.close()
      println("MAP_BENCHMARK DONE")
      onStatus("Done. Results are in the benchmark log.", false)
    } catch (e: CancellationException) {
      if (e is TimeoutCancellationException) println("MAP_BENCHMARK ERROR Workload timed out")
      else throw e
      onStatus("Workload timed out", false)
    } catch (e: Exception) {
      println("MAP_BENCHMARK ERROR ${e.message}")
      onStatus(e.message ?: "Failed", false)
    } finally {
      if (measuring) benchmarkCpu(false)
      driver.view.removeOnDidFinishRenderingFrameListener(listener)
      driver.view.removeOnDidFailLoadingMapListener(failure)
      withContext(NonCancellable) { recorder.stop() }
      driver.close()
    }
  }
}

private class ClassicAndroidDriver(private val fixture: BenchmarkFixture, val view: MapView) {
  private val config = fixture.config
  private lateinit var map: MapLibreMap
  private var revision = 0
  private var styleIndex = 0
  private val density = view.resources.displayMetrics.density
  private val styles =
    fixture.baseStyles.map {
      (it as BaseStyle.Json).json.replace("file:///android_asset/", "asset://")
    }
  private val images = fixture.images.map { it.asAndroidBitmap() }

  suspend fun prepare() {
    map = suspendCancellableCoroutine { continuation ->
      view.getMapAsync { if (continuation.isActive) continuation.resume(it) }
    }
    config.maximumFps?.let(view::setMaximumFps)
    withTimeout(15000) {
      while (view.width == 0 || view.height == 0) withFrameNanos {}
    }
    map.moveCamera(CameraUpdateFactory.newCameraPosition(camera(benchmarkCamera(-1.0))))
    settled {
      setStyle(0)
      if (images.isNotEmpty()) replaceImage(0)
    }
  }

  fun close() {
    if (!view.isDestroyed) {
      view.onPause()
      view.onStop()
      view.onDestroy()
    }
  }

  private suspend fun setStyle(index: Int) =
    withTimeout(10000) {
      suspendCancellableCoroutine { continuation ->
        map.setStyle(Style.Builder().fromJson(styles[index])) {
          if (continuation.isActive) continuation.resume(Unit)
        }
      }
    }

  private fun camera(value: org.maplibre.compose.camera.CameraPosition) =
    CameraPosition.Builder()
      .target(LatLng(value.target.latitude, value.target.longitude))
      .zoom(value.zoom)
      .bearing(value.bearing)
      .tilt(value.tilt)
      .build()

  private fun replaceImage(index: Int) {
    val style = checkNotNull(map.style)
    style.removeImage("workload-image")
    style.addImage("workload-image", images[index])
  }

  private fun layers(show: Boolean) {
    val style = checkNotNull(map.style)
    repeat(config.layers) { index ->
      val id = "workload-$index"
      if (!show) style.removeLayer(id)
      else if (style.getLayer(id) == null) {
        if (fixture.line)
          style.addLayer(
            LineLayer(id, "data")
              .withProperties(
                lineColor(BenchmarkColorStrings[0]),
                lineWidth(3f),
              )
          )
        else
          style.addLayer(
            CircleLayer(id, "data")
              .withProperties(
                circleColor(BenchmarkColorStrings[0]),
                circleRadius(5f),
              )
          )
      }
    }
  }

  private fun paint(index: Int) {
    val style = checkNotNull(map.style)
    repeat(config.layers) { layer ->
      checkNotNull(style.getLayer("workload-$layer"))
        .setProperties(
          if (fixture.line) lineColor(BenchmarkColorStrings[index])
          else circleColor(BenchmarkColorStrings[index])
        )
    }
  }

  private fun visible(show: Boolean) {
    val style = checkNotNull(map.style)
    repeat(config.layers) { index ->
      checkNotNull(style.getLayer("workload-$index"))
        .setProperties(visibility(if (show) "visible" else "none"))
    }
  }

  private fun source(index: Int) {
    checkNotNull(map.style?.getSourceAs<GeoJsonSource>("data")).setGeoJson(fixture.data[index].json)
  }

  private fun height(fraction: Double) {
    val parent = view.parent as FrameLayout
    view.layoutParams =
      (view.layoutParams as FrameLayout.LayoutParams).apply {
        height = (parent.height * fraction).toInt()
      }
  }

  private suspend fun settled(block: suspend () -> Unit) =
    withTimeout(15000) {
      val idle = CompletableDeferred<Unit>()
      val listener = MapView.OnDidBecomeIdleListener { idle.complete(Unit) }
      view.addOnDidBecomeIdleListener(listener)
      try {
        block()
        map.triggerRepaint()
        idle.await()
      } finally {
        view.removeOnDidBecomeIdleListener(listener)
      }
    }

  suspend fun reset() {
    height(1.0)
    map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
    repeat(2) { withFrameNanos {} }
    settled {
      revision = 0
      styleIndex = 0
      if (config.scenario == BenchmarkScenario.Style) setStyle(0)
      if (images.isNotEmpty()) replaceImage(0)
      if (fixture.data.isNotEmpty() && images.isEmpty()) {
        layers(true)
        source(0)
        paint(0)
        visible(true)
      }
      map.moveCamera(CameraUpdateFactory.newCameraPosition(camera(benchmarkCamera(-1.0))))
    }
  }

  suspend fun run(clock: BenchmarkWorkload) {
    when (config.scenario) {
      BenchmarkScenario.Idle -> clock.idle()
      BenchmarkScenario.Camera ->
        clock.frames {
          map.moveCamera(CameraUpdateFactory.newCameraPosition(camera(tourCamera(it))))
        }
      BenchmarkScenario.Animation -> {
        repeat(4) { index ->
          withTimeout(clock.durationMillis + 10000) {
            suspendCancellableCoroutine { continuation ->
              map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                  camera(benchmarkCamera(if (index % 2 == 0) 1.0 else -1.0))
                ),
                (clock.durationMillis / 4).toInt(),
                object : MapLibreMap.CancelableCallback {
                  override fun onFinish() {
                    if (continuation.isActive) continuation.resume(Unit)
                  }

                  override fun onCancel() {
                    continuation.cancel()
                  }
                },
              )
            }
          }
          clock.submitted()
        }
        clock.idle()
      }
      BenchmarkScenario.Resize -> clock.frames { height(0.75 + 0.25 * cos(it * 4 * PI)) }
      BenchmarkScenario.Padding ->
        clock.frames {
          map.moveCamera(
            CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, (1 - cos(it * 4 * PI)) * 100 * density)
          )
        }
      BenchmarkScenario.Recompose -> error("Recomposition is Compose-only")
      else ->
        clock.scheduled(config.rateHz) { tick ->
          val started = TimeSource.Monotonic.markNow()
          var styleReady: CompletableDeferred<Unit>? = null
          when (config.scenario) {
            BenchmarkScenario.Paint -> paint((tick + 1) % 2)
            BenchmarkScenario.Layout -> visible(tick % 2 != 0)
            BenchmarkScenario.Layers -> layers(tick % 2 != 0)
            BenchmarkScenario.Source,
            BenchmarkScenario.SourceLatency -> {
              revision = 1 - revision
              source(revision)
            }
            BenchmarkScenario.Images -> replaceImage((tick + 1) % 2)
            BenchmarkScenario.Style -> {
              styleIndex = 1 - styleIndex
              val ready = CompletableDeferred<Unit>()
              styleReady = ready
              map.setStyle(Style.Builder().fromJson(styles[styleIndex])) { ready.complete(Unit) }
            }
          }
          val submission = started.elapsedNow().inWholeNanoseconds / 1e6
          var completion: Double? = null
          if (config.scenario == BenchmarkScenario.Style) {
            clock.completionSignal = "style-ready"
            withTimeout(10000) { checkNotNull(styleReady).await() }
            completion = started.elapsedNow().inWholeNanoseconds / 1e6
          } else if (config.scenario == BenchmarkScenario.SourceLatency) {
            clock.completionSignal = "rendered-feature-revision"
            withTimeout(10000) {
              while (true) {
                withFrameNanos {}
                val point =
                  map.projection.toScreenLocation(
                    LatLng(BenchmarkOrigin.latitude, BenchmarkOrigin.longitude)
                  )
                if (
                  map.queryRenderedFeatures(point, "workload-0").any {
                    it.getNumberProperty("revision")?.toInt() == revision
                  }
                )
                  break
              }
            }
            completion = started.elapsedNow().inWholeNanoseconds / 1e6
          }
          clock.submitted(submission, completion)
        }
    }
  }
}
