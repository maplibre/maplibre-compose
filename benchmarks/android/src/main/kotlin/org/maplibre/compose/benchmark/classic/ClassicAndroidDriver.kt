package org.maplibre.compose.benchmark.classic

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.FrameLayout
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.compose.benchmark.*

class ClassicAndroidDriver(fixture: PreparedBenchmarkFixture, val view: MapView) :
  ClassicBenchmarkDriver(fixture, ::nextAndroidFrame) {
  private lateinit var map: MapLibreMap
  private val density = view.resources.displayMetrics.density
  private val styles = fixture.baseStyles
  private val images =
    if (config.scenario == BenchmarkScenario.Images)
      BenchmarkColorStrings.map { color ->
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
          eraseColor(Color.parseColor(color))
        }
      }
    else emptyList()
  private var recorder: BenchmarkFrameRecorder? = null
  private val frameListener =
    MapView.OnDidFinishRenderingFrameListener { full, encoding, rendering ->
      recorder?.record(
        FrameSample(
          encodingMs = encoding * 1e3,
          renderingMs = rendering * 1e3,
          mode = if (full) "full" else "partial",
        )
      )
    }
  private val failure = MapView.OnDidFailLoadingMapListener { reason ->
    println("MAP_BENCHMARK ERROR $reason")
  }

  override fun recordFrames(recorder: BenchmarkFrameRecorder?) {
    this.recorder = recorder
  }

  override fun viewport() =
    listOf(
      ceil(view.width / density).toDouble(),
      ceil(view.height / density).toDouble(),
      density.toDouble(),
    )

  override suspend fun prepare() {
    view.addOnDidFinishRenderingFrameListener(frameListener)
    view.addOnDidFailLoadingMapListener(failure)
    map = suspendCancellableCoroutine { continuation ->
      view.getMapAsync { if (continuation.isActive) continuation.resume(it) }
    }
    config.maximumFps?.let(view::setMaximumFps)
    withTimeout(15000) {
      while (view.width == 0 || view.height == 0) nextFrame()
    }
    camera(benchmarkCamera(-1.0))
    settled {
      style(0).await()
      if (images.isNotEmpty()) image(0)
    }
  }

  override fun close() {
    if (!view.isDestroyed) {
      view.removeOnDidFinishRenderingFrameListener(frameListener)
      view.removeOnDidFailLoadingMapListener(failure)
      view.onPause()
      view.onStop()
      view.onDestroy()
    }
  }

  override fun style(index: Int): Deferred<Unit> {
    val ready = CompletableDeferred<Unit>()
    map.setStyle(Style.Builder().fromJson(styles[index])) { ready.complete(Unit) }
    return ready
  }

  override fun camera(value: BenchmarkCamera) {
    map.moveCamera(CameraUpdateFactory.newCameraPosition(position(value)))
  }

  override suspend fun animate(value: BenchmarkCamera, durationMs: Long) =
    suspendCancellableCoroutine { continuation ->
      map.animateCamera(
        CameraUpdateFactory.newCameraPosition(position(value)),
        durationMs.toInt(),
        object : MapLibreMap.CancelableCallback {
          override fun onFinish() {
            if (continuation.isActive) continuation.resume(Unit)
          }

          override fun onCancel() {
            continuation.cancel()
          }
        },
      )
      continuation.invokeOnCancellation { map.cancelTransitions() }
    }

  private fun position(value: BenchmarkCamera) =
    CameraPosition.Builder()
      .target(LatLng(value.latitude, value.longitude))
      .zoom(value.zoom)
      .bearing(value.bearing)
      .tilt(value.tilt)
      .build()

  override fun image(index: Int) {
    // The SDK replaces an existing image in place.
    checkNotNull(map.style).addImage("workload-image", images[index])
  }

  override fun layers(show: Boolean) {
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

  override fun paint(index: Int) {
    val style = checkNotNull(map.style)
    repeat(config.layers) { layer ->
      checkNotNull(style.getLayer("workload-$layer"))
        .setProperties(
          if (fixture.line) lineColor(BenchmarkColorStrings[index])
          else circleColor(BenchmarkColorStrings[index])
        )
    }
  }

  override fun visible(show: Boolean) {
    val style = checkNotNull(map.style)
    repeat(config.layers) { index ->
      checkNotNull(style.getLayer("workload-$index"))
        .setProperties(visibility(if (show) "visible" else "none"))
    }
  }

  override fun source(index: Int) {
    checkNotNull(map.style?.getSourceAs<GeoJsonSource>("data")).setGeoJson(fixture.data[index])
  }

  override fun height(fraction: Double) {
    val parent = view.parent as FrameLayout
    view.layoutParams =
      (view.layoutParams as FrameLayout.LayoutParams).apply {
        height = (parent.height * fraction).toInt()
      }
  }

  override suspend fun settled(block: suspend () -> Unit) =
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

  override fun padding(bottom: Double) {
    map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, bottom * density))
  }

  override fun hasRevision(revision: Int): Boolean {
    val point = map.projection.toScreenLocation(LatLng(BenchmarkLatitude, BenchmarkLongitude))
    return map.queryRenderedFeatures(point, "workload-0").any {
      it.getNumberProperty("revision")?.toInt() == revision
    }
  }
}
