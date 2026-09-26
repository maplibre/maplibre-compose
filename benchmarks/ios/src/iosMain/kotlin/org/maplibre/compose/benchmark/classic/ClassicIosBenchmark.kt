@file:OptIn(
  kotlinx.cinterop.ExperimentalForeignApi::class,
  kotlinx.cinterop.BetaInteropApi::class,
  kotlin.native.runtime.NativeRuntimeApi::class,
)

package org.maplibre.compose.benchmark.classic

import kotlin.coroutines.resume
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import maplibre.*
import org.maplibre.compose.benchmark.*
import platform.CoreGraphics.*
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.*
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CAFrameRateRangeMake
import platform.UIKit.*
import platform.darwin.NSObject
import platform.objc.sel_registerName
import platform.posix.*

@Suppress("unused") // Swift app entry point.
fun classicBenchmarkViewController(): UIViewController = BenchmarkController()

private class BenchmarkController : UIViewController(nibName = null, bundle = null) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val clock = DisplayClock()
  private var driver: IosDriver? = null
  private var launched = false

  override fun viewDidAppear(animated: Boolean) {
    super.viewDidAppear(animated)
    if (launched) return
    launched = true
    clock.preferFrameRate(checkNotNull(view.window).screen.maximumFramesPerSecond)
    UIApplication.sharedApplication.idleTimerDisabled = true
    scope.launch {
      try {
        val config =
          checkNotNull(
            BenchmarkConfig.parse(NSProcessInfo.processInfo.environment["MAP_BENCHMARK"] as? String)
          ) {
            "Missing benchmark configuration"
          }
        require(config.implementation == BenchmarkImplementation.ClassicIos)
        val root = checkNotNull(NSBundle.mainBundle.resourcePath) + "/benchmarks/"
        val fixture =
          loadBenchmarkFixture(
            config,
            read = { path ->
              NSString.stringWithContentsOfFile(root + path, NSUTF8StringEncoding, null)
                ?: error("Missing fixture $path")
            },
            uri = { path -> NSURL.fileURLWithPath(root + path).absoluteString!! },
          )
        val active = IosDriver(fixture, view, clock::nextFrame)
        driver = active
        var start = 0.0
        runClassicBenchmark(
          active,
          cpu = { measuring ->
            if (measuring) start = cpuMillis()
            else println("MAP_BENCHMARK CPU ${cpuMillis() - start}")
          },
          collectGarbage = { kotlin.native.runtime.GC.collect() },
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        println("MAP_BENCHMARK ERROR ${e.message}")
      } finally {
        driver = null
        clock.close()
        UIApplication.sharedApplication.idleTimerDisabled = false
      }
    }
  }

  override fun viewDidDisappear(animated: Boolean) {
    scope.cancel()
    driver?.close()
    clock.close()
    super.viewDidDisappear(animated)
  }
}

private fun cpuMillis(): Double = memScoped {
  val usage = alloc<rusage>()
  check(getrusage(RUSAGE_SELF, usage.ptr) == 0)
  (usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) * 1000.0 +
    (usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1000.0
}

private class DisplayClock : NSObject() {
  private var pending: CancellableContinuation<Long>? = null
  private val link = CADisplayLink.displayLinkWithTarget(this, sel_registerName("tick:"))

  init {
    link.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
    link.paused = true
  }

  fun preferFrameRate(framesPerSecond: Long) {
    val fps = framesPerSecond.toFloat()
    // ProMotion otherwise defaults to 60 Hz, halving frame-driven work versus Compose.
    link.preferredFrameRateRange = CAFrameRateRangeMake(fps, fps, fps)
  }

  @ObjCAction
  fun tick(sender: CADisplayLink) {
    val continuation = pending
    pending = null
    link.paused = true
    if (continuation?.isActive == true) continuation.resume((sender.timestamp * 1e9).toLong())
  }

  suspend fun nextFrame(): Long = suspendCancellableCoroutine { continuation ->
    check(pending == null)
    pending = continuation
    link.paused = false
    continuation.invokeOnCancellation {
      pending = null
      link.paused = true
    }
  }

  fun close() {
    pending?.cancel()
    pending = null
    link.invalidate()
  }
}

private class IosDriver(
  fixture: PreparedBenchmarkFixture,
  private val container: UIView,
  nextFrame: suspend () -> Long,
) : ClassicBenchmarkDriver(fixture, nextFrame) {
  private val map = MLNMapView(frame = container.bounds, styleJSON = fixture.baseStyles[0])
  private var ready = CompletableDeferred<Unit>()
  private var idle: CompletableDeferred<Unit>? = null
  private var recorder: BenchmarkFrameRecorder? = null
  private var closed = false
  private val shapes =
    fixture.data.map { json ->
      val bytes = json.encodeToByteArray()
      val data = bytes.usePinned {
        NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
      }
      checkNotNull(MLNShape.shapeWithData(data, NSUTF8StringEncoding, null))
    }
  private val colors = BenchmarkColorStrings.map { color ->
    val rgb = color.drop(1).toInt(16)
    UIColor(
      red = ((rgb shr 16) and 255) / 255.0,
      green = ((rgb shr 8) and 255) / 255.0,
      blue = (rgb and 255) / 255.0,
      alpha = 1.0,
    )
  }
  private val images =
    if (config.scenario == BenchmarkScenario.Images)
      colors.map { color ->
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(32.0, 32.0), false, 1.0)
        try {
          color.setFill()
          UIRectFill(CGRectMake(0.0, 0.0, 32.0, 32.0))
          checkNotNull(UIGraphicsGetImageFromCurrentImageContext())
        } finally {
          UIGraphicsEndImageContext()
        }
      }
    else emptyList()
  private val delegate =
    object : NSObject(), MLNMapViewDelegateProtocol {
      override fun mapView(mapView: MLNMapView, didFinishLoadingStyle: MLNStyle) {
        ready.complete(Unit)
      }

      override fun mapViewDidBecomeIdle(mapView: MLNMapView) {
        idle?.complete(Unit)
      }

      override fun mapViewDidFailLoadingMap(mapView: MLNMapView, withError: NSError) {
        val failure = IllegalStateException(withError.localizedDescription)
        println("MAP_BENCHMARK ERROR ${failure.message}")
        ready.completeExceptionally(failure)
        idle?.completeExceptionally(failure)
      }

      override fun mapViewDidFinishRenderingFrame(
        mapView: MLNMapView,
        fullyRendered: Boolean,
        renderingStats: MLNRenderingStats,
      ) {
        recorder?.record(
          FrameSample(
            encodingMs = renderingStats.encodingTime * 1000,
            renderingMs = renderingStats.renderingTime * 1000,
            drawCalls = renderingStats.numDrawCalls.toLong(),
            mode = if (fullyRendered) "full" else "partial",
          )
        )
      }
    }

  override suspend fun prepare() {
    map.delegate = delegate
    map.automaticallyAdjustsContentInset = false
    map.showsCompassView = false
    map.showsScale = false
    map.showsLogoView = false
    map.showsAttributionButton = false
    map.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    map.preferredFramesPerSecond =
      config.maximumFps?.toLong() ?: checkNotNull(container.window).screen.maximumFramesPerSecond
    container.addSubview(map)
    // Inline JSON can finish loading during construction, before the delegate is attached.
    if (map.style != null) ready.complete(Unit)
    withTimeout(15000) { ready.await() }
    settled {
      camera(benchmarkCamera(-1.0))
      if (images.isNotEmpty()) image(0)
    }
  }

  private fun sdkCamera(value: BenchmarkCamera): MLNMapCamera =
    MLNMapCamera.cameraLookingAtCenterCoordinate(
      CLLocationCoordinate2DMake(value.latitude, value.longitude),
      altitude =
        MLNAltitudeForZoomLevel(
          value.zoom,
          value.tilt,
          value.latitude,
          map.bounds.useContents { size.readValue() },
        ),
      pitch = value.tilt,
      heading = value.bearing,
    )

  override fun camera(value: BenchmarkCamera) {
    map.setCamera(sdkCamera(value), animated = false)
  }

  override suspend fun animate(value: BenchmarkCamera, durationMs: Long) =
    suspendCancellableCoroutine { continuation ->
      map.flyToCamera(
        sdkCamera(value),
        withDuration = durationMs / 1000.0,
        completionHandler = { if (continuation.isActive) continuation.resume(Unit) },
      )
      continuation.invokeOnCancellation { map.setCamera(map.camera, animated = false) }
    }

  override fun style(index: Int): Deferred<Unit> {
    ready = CompletableDeferred()
    map.styleJSON = fixture.baseStyles[index]
    return ready
  }

  override fun image(index: Int) {
    checkNotNull(map.style).setImage(images[index], forName = "workload-image")
  }

  override fun layers(show: Boolean) {
    val style = checkNotNull(map.style)
    repeat(config.layers) { index ->
      val id = "workload-$index"
      val layer = style.layerWithIdentifier(id)
      if (!show) {
        if (layer != null) style.removeLayer(layer)
      } else if (layer == null) {
        val source = checkNotNull(style.sourceWithIdentifier("data"))
        val added =
          if (fixture.line)
            MLNLineStyleLayer(id, source).apply {
              lineColor = NSExpression.expressionForConstantValue(colors[0])
              lineWidth = NSExpression.expressionForConstantValue(3)
            }
          else
            MLNCircleStyleLayer(id, source).apply {
              circleColor = NSExpression.expressionForConstantValue(colors[0])
              circleRadius = NSExpression.expressionForConstantValue(5)
            }
        style.addLayer(added)
      }
    }
  }

  override fun paint(index: Int) {
    repeat(config.layers) { layer ->
      val target = checkNotNull(map.style?.layerWithIdentifier("workload-$layer"))
      val color = NSExpression.expressionForConstantValue(colors[index])
      if (target is MLNLineStyleLayer) target.lineColor = color
      else (target as MLNCircleStyleLayer).circleColor = color
    }
  }

  override fun visible(show: Boolean) {
    repeat(config.layers) {
      checkNotNull(map.style?.layerWithIdentifier("workload-$it")).visible = show
    }
  }

  override fun source(index: Int) {
    (checkNotNull(map.style?.sourceWithIdentifier("data")) as MLNShapeSource).shape = shapes[index]
  }

  override fun height(fraction: Double) {
    container.bounds.useContents {
      map.setFrame(
        CGRectMake(0.0, size.height * (1 - fraction) / 2, size.width, size.height * fraction)
      )
    }
  }

  override fun padding(bottom: Double) {
    map.setContentInset(UIEdgeInsetsMake(0.0, 0.0, bottom, 0.0), animated = false)
  }

  override fun hasRevision(revision: Int): Boolean {
    val point =
      map.convertCoordinate(
        CLLocationCoordinate2DMake(BenchmarkLatitude, BenchmarkLongitude),
        toPointToView = map,
      )
    return map
      .visibleFeaturesAtPoint(point, inStyleLayersWithIdentifiers = setOf("workload-0"))
      .any {
        ((it as MLNFeatureProtocol).attributes["revision"] as? NSNumber)?.intValue == revision
      }
  }

  override suspend fun settled(block: suspend () -> Unit) =
    withTimeout(15000) {
      val signal = CompletableDeferred<Unit>()
      idle = signal
      try {
        block()
        map.triggerRepaint()
        signal.await()
      } finally {
        idle = null
      }
    }

  override fun viewport() =
    map.bounds.useContents { listOf(size.width, size.height, map.traitCollection.displayScale) }

  override fun recordFrames(recorder: BenchmarkFrameRecorder?) {
    this.recorder = recorder
  }

  override fun close() {
    if (closed) return
    closed = true
    recorder = null
    map.delegate = null
    map.setCamera(map.camera, animated = false)
    map.removeFromSuperview()
  }
}
