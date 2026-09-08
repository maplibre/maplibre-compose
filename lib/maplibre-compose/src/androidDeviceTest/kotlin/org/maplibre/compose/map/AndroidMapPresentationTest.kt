package org.maplibre.compose.map

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle

/** Exercises the public presenter with a real buffer consumer, without a Compose UI host. */
class AndroidMapPresentationTest {
  @Test
  fun declarative_content_survives_surface_replacement_density_changes_and_inactivity() =
    runBlocking {
      withSurfaceMap { fixture ->
        fixture.withConsumer(256, 192) { first ->
          fixture.withConsumer(320, 240) { second ->
            val oldBinding = fixture.onMain { presenter.attachSurface(first.surface, 256, 192, 1f) }
            first.awaitColor(RED)

            fixture.onMain { color = Color.Green }
            first.awaitColor(GREEN)

            val current = fixture.onMain { presenter.attachSurface(second.surface, 320, 240, 1f) }
            fixture.onMain {
              oldBinding.update(1, 1, 1f)
              oldBinding.close()
              oldBinding.close()
            }
            second.awaitColor(GREEN)
            fixture.awaitViewport(320f, 240f)
            assertTrue(first.surface.isValid, "Replacing a binding must not release its Surface")

            val beforeDensity = second.frameCount.get()
            fixture.onMain { current.update(320, 240, 2f) }
            fixture.awaitViewport(160f, 120f)
            second.awaitColor(GREEN, after = beforeDensity)

            // Font scale changes composition locals without changing native engine compatibility.
            val originalConfiguration = fixture.configuration
            fixture.onMain {
              presenter.updateConfiguration(
                Configuration(originalConfiguration).apply { fontScale = 1.5f }
              )
            }
            second.awaitColor(YELLOW)
            fixture.awaitViewport(160f, 120f)
            fixture.onMain { presenter.updateConfiguration(originalConfiguration) }
            second.awaitColor(GREEN)

            val dayConfiguration =
              Configuration(originalConfiguration).apply {
                uiMode =
                  (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_NO
              }
            fixture.onMain {
              checkNightConfiguration = true
              presenter.updateConfiguration(dayConfiguration)
            }
            second.awaitColor(GREEN)
            val nightConfiguration =
              Configuration(dayConfiguration).apply {
                uiMode =
                  (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_YES
              }
            fixture.onMain {
              presenter.updateConfiguration(nightConfiguration)
            }
            second.awaitColor(MAGENTA)
            fixture.onMain {
              checkNightConfiguration = false
              presenter.updateConfiguration(originalConfiguration)
            }
            second.awaitColor(GREEN)

            val beforeResize = second.frameCount.get()
            fixture.onMain { current.update(240, 160, 2f) }
            fixture.awaitViewport(120f, 80f)
            second.awaitColor(GREEN, after = beforeResize)

            fixture.onMain { lifecycle.currentState = Lifecycle.State.CREATED }
            fixture.onMain { color = Color.Blue }
            fixture.onMain { lifecycle.currentState = Lifecycle.State.STARTED }
            second.awaitColor(BLUE)

            fixture.onMain {
              current.close()
              current.close()
              presenter.close()
              presenter.close()
              assertNull(state.viewport)
              assertFalse(state.isClosed)
            }
            assertTrue(second.surface.isValid, "Closing a presenter must not release its Surface")

            val beforeReattach = second.frameCount.get()
            fixture.onMain { presenter = newPresenter() }
            fixture.onMain { presenter.attachSurface(second.surface, 320, 240, 1f) }
            second.awaitColor(BLUE, after = beforeReattach)
            fixture.awaitViewport(320f, 240f)
            fixture.onMain { assertNull(presenter.failure) }
          }
        }
      }
    }

  @Test
  fun recognized_gestures_use_logical_units_and_finish_camera_movement() = runBlocking {
    withSurfaceMap { fixture ->
      fixture.withConsumer(320, 240) { consumer ->
        fixture.onMain { presenter.attachSurface(consumer.surface, 320, 240, 2f) }
        consumer.awaitColor(RED)
        fixture.awaitViewport(160f, 120f)
        val initial = fixture.onMain { state.cameraPosition }

        fixture.onMain { showPoint = true }
        consumer.awaitColor(GREEN)
        fixture.onMain { state.click(DpOffset(80.dp, 60.dp)) }
        fixture.await("map and layer click dispatch") { clickedFeatures == 1 }
        fixture.onMain {
          assertEquals(listOf("map", "layer"), clickOrder)
          lastClick = null
        }

        fixture.onMain { state.panBy(DpOffset(32.dp, 0.dp)) }
        fixture.await("pan to move the camera and finish") {
          state.cameraPosition.target.longitude < initial.target.longitude - 0.1 &&
            !state.isCameraMoving
        }
        val afterPan = fixture.onMain { state.cameraPosition }
        // The world is 512 * 2^zoom dp wide.
        val expectedLongitude = initial.target.longitude - 32.0 / (512.0 * 8.0) * 360.0
        assertEquals(expectedLongitude, afterPan.target.longitude, 0.01)
        fixture.onMain { assertEquals(CameraMoveReason.GESTURE, state.cameraMoveReason) }

        fixture.onMain { state.scaleBy(2.0, DpOffset(80.dp, 60.dp)) }
        fixture.await("anchored scale to reach its zoom and finish") {
          abs(state.cameraPosition.zoom - initial.zoom - 1.0) < 0.01 && !state.isCameraMoving
        }
        fixture.onMain {
          assertEquals(afterPan.target.longitude, state.cameraPosition.target.longitude, 0.01)
          assertEquals(afterPan.target.latitude, state.cameraPosition.target.latitude, 0.01)
          state.click(DpOffset(40.dp, 30.dp))
        }
        fixture.await("the click callback") { lastClick != null }
        fixture.onMain {
          val click = assertNotNull(lastClick)
          assertEquals(40f, click.x.value)
          assertEquals(30f, click.y.value)
        }

        val beforeFling = fixture.onMain { state.cameraPosition.target.longitude }
        fixture.onMain { state.fling(DpOffset(2100.dp, 0.dp)) }
        fixture.await("fling to move the camera and settle") {
          state.cameraPosition.target.longitude < beforeFling - 0.1 && !state.isCameraMoving
        }
        fixture.onMain {
          assertEquals(CameraMoveReason.GESTURE, state.cameraMoveReason)
          state.fling(DpOffset(2100.dp, 0.dp))
          presenter.close()
          assertFalse(state.isCameraMoving)
        }
        val detached = fixture.onMain { state.cameraPosition }
        fixture.onMain {
          lastClick = null
          state.panBy(DpOffset(32.dp, 0.dp))
          state.scaleBy(2.0)
          state.fling(DpOffset(2100.dp, 0.dp))
          state.click(DpOffset(40.dp, 30.dp))
        }
        fixture.onMain {
          assertEquals(detached, state.cameraPosition)
          assertNull(lastClick)
          assertNull(presenter.failure)
        }
        assertTrue(consumer.surface.isValid)
      }
    }
  }

  @Test
  fun the_same_map_moves_between_direct_and_compose_hosts_with_its_camera_and_content() =
    runBlocking {
      withSurfaceMap { fixture ->
        fixture.withConsumer(256, 192) { consumer ->
          fixture.onMain { presenter.attachSurface(consumer.surface, 256, 192, 1f) }
          consumer.awaitColor(RED)
          val retainedCamera = CameraPosition(zoom = 4.0)
          fixture.onMain { state.setCameraPosition(retainedCamera) }
          fixture.await("the camera update before leaving the direct host") {
            state.cameraPosition.isCloseTo(retainedCamera)
          }
          fixture.onMain { presenter.close() }

          ActivityScenario.launch(SurfaceHandoffActivity::class.java).use { scenario ->
            scenario.onActivity { it.map = fixture.state }
            awaitScreenColor(android.graphics.Color.RED)
            fixture.onMain {
              assertCameraPosition(retainedCamera, state.cameraPosition)
              color = Color.Blue
            }
            awaitScreenColor(android.graphics.Color.BLUE)
            scenario.onActivity { it.map = null }
            fixture.await("Compose to release its presentation") { state.viewport == null }
          }

          val beforeReattach = consumer.frameCount.get()
          fixture.onMain {
            presenter = newPresenter()
            presenter.attachSurface(consumer.surface, 256, 192, 1f)
          }
          consumer.awaitColor(BLUE, after = beforeReattach)
          fixture.onMain {
            assertCameraPosition(retainedCamera, state.cameraPosition)
            assertNull(presenter.failure)
          }
        }
      }
    }

  @Test
  fun closing_a_map_with_an_attached_surface_leaves_the_borrowed_surface_valid() = runBlocking {
    withSurfaceMap { fixture ->
      fixture.withConsumer(256, 192) { consumer ->
        fixture.onMain { presenter.attachSurface(consumer.surface, 256, 192, 1f) }
        consumer.awaitColor(RED)
        fixture.onMain { state.close() }
        withTimeout(TIMEOUT_MILLIS) { fixture.state.awaitClosed() }
        fixture.onMain {
          presenter.close()
          assertTrue(state.isClosed)
          assertNull(state.viewport)
        }
        assertTrue(consumer.surface.isValid)
      }
    }
  }
}

private class SurfaceMapFixture(val runtime: MapRuntime) {
  var color by mutableStateOf(Color.Red)
  var checkNightConfiguration by mutableStateOf(false)
  var showPoint by mutableStateOf(false)
  var lastClick: DpOffset? = null
  var clickedFeatures = 0
  val clickOrder = mutableListOf<String>()
  val state =
    runtime.createMapState(BaseStyle.Empty, cameraPosition = CameraPosition(zoom = 3.0)) {
      val localNight = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK
      val contextNight = contextNightMode(LocalContext.current)
      val background =
        when {
          checkNightConfiguration && localNight != contextNight -> Color.Cyan
          checkNightConfiguration && localNight == Configuration.UI_MODE_NIGHT_YES -> Color.Magenta
          LocalDensity.current.fontScale > 1f -> Color.Yellow
          else -> color
        }
      BackgroundLayer(
        "color",
        color = const(background),
      )
      if (showPoint) {
        CircleLayer(
          "point",
          source = rememberGeoJsonSource(GeoJsonData.JsonString(POINT)),
          color = const(Color.Green),
          radius = const(12.dp),
          onClick = { features ->
            clickOrder += "layer"
            clickedFeatures += features.size
            ClickResult.Consume
          },
        )
      }
    }
  private val lifecycleOwner =
    object : LifecycleOwner {
      override val lifecycle =
        LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
    }
  val lifecycle: LifecycleRegistry
    get() = lifecycleOwner.lifecycle

  val configuration: Configuration
    get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration

  var presenter = newPresenter()

  fun newPresenter(): AndroidMapPresentation =
    AndroidMapPresentation(
      context = InstrumentationRegistry.getInstrumentation().targetContext,
      state = state,
      lifecycle = lifecycleOwner.lifecycle,
      interactions =
        org.maplibre.compose.interaction.MapInteractions {
          callbacks {
            click {
              onEvent { event ->
                clickOrder += "map"
                lastClick = event.screenOffset
                ClickResult.Pass
              }
            }
          }
        },
    )

  suspend fun withConsumer(width: Int, height: Int, action: suspend (SurfaceConsumer) -> Unit) {
    val consumer = SurfaceConsumer(width, height)
    try {
      action(consumer)
    } finally {
      try {
        onMain { presenter.close() }
      } finally {
        consumer.close()
      }
    }
  }

  suspend fun <T> onMain(action: SurfaceMapFixture.() -> T): T =
    withContext(Dispatchers.Main) { action() }

  suspend fun await(description: String, predicate: SurfaceMapFixture.() -> Boolean) {
    try {
      withTimeout(TIMEOUT_MILLIS) {
        while (!onMain(predicate)) {
          onMain { presenter.failure?.let { throw AssertionError("Presentation failed", it) } }
          delay(10)
        }
      }
    } catch (failure: Throwable) {
      throw AssertionError("Timed out waiting for $description", failure)
    }
  }

  suspend fun awaitViewport(width: Float, height: Float) =
    await("viewport ${width}x$height dp") {
      state.viewport?.size?.let { it.width.value == width && it.height.value == height } == true
    }
}

private suspend fun withSurfaceMap(action: suspend (SurfaceMapFixture) -> Unit) {
  val cacheFile = FfiTestPlatform.createCacheFile()
  val runtime = createMapRuntime(MapRuntimeOptions(cacheFile = cacheFile))
  try {
    val fixture = withContext(Dispatchers.Main) { SurfaceMapFixture(runtime) }
    try {
      action(fixture)
    } finally {
      fixture.onMain { presenter.close() }
    }
  } finally {
    runtime.close()
    try {
      withTimeout(TIMEOUT_MILLIS) { runtime.awaitClosed() }
    } finally {
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}

/** Consuming every image prevents BufferQueue backpressure from stalling synchronous teardown. */
private class SurfaceConsumer(width: Int, height: Int) : AutoCloseable {
  private val thread = HandlerThread("map-surface-test-consumer").apply { start() }
  private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
  private val pixel = AtomicReference<Pair<Long, Int>?>(null)
  private val failure = AtomicReference<Throwable?>(null)
  val frameCount = AtomicLong()
  val surface = reader.surface

  init {
    reader.setOnImageAvailableListener(
      { source ->
        try {
          source.acquireLatestImage()?.use { image ->
            val plane = image.planes.single()
            val offset =
              (image.height / 2) * plane.rowStride + (image.width / 2) * plane.pixelStride
            val bytes = plane.buffer
            val rgba =
              ((bytes.get(offset).toInt() and 0xff) shl 24) or
                ((bytes.get(offset + 1).toInt() and 0xff) shl 16) or
                ((bytes.get(offset + 2).toInt() and 0xff) shl 8) or
                (bytes.get(offset + 3).toInt() and 0xff)
            pixel.set(frameCount.incrementAndGet() to rgba)
          }
        } catch (error: Throwable) {
          failure.compareAndSet(null, error)
        }
      },
      Handler(thread.looper),
    )
  }

  suspend fun awaitColor(expected: Int, after: Long = 0L) {
    try {
      withTimeout(TIMEOUT_MILLIS) {
        while (true) {
          failure.get()?.let { throw AssertionError("Could not consume Surface pixels", it) }
          val frame = pixel.get()
          if (frame != null && frame.first > after && frame.second == expected) return@withTimeout
          delay(10)
        }
      }
    } catch (error: Throwable) {
      throw AssertionError(
        "Expected Surface RGBA 0x${expected.toUInt().toString(16)} after frame $after; " +
          "latest=${pixel.get()}",
        error,
      )
    }
  }

  override fun close() {
    reader.setOnImageAvailableListener(null, null)
    reader.close()
    surface.release()
    thread.quitSafely()
    thread.join(TIMEOUT_MILLIS)
    assertFalse(thread.isAlive, "Image consumer thread did not stop")
  }
}

private const val POINT =
  """{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}}"""
private const val TIMEOUT_MILLIS = 10_000L
private const val RED = -16776961
private const val GREEN = 16711935
private const val BLUE = 65535
private const val YELLOW = -65281
private const val MAGENTA = -16711681

// Intentionally inspect resources as well as LocalConfiguration: the host must configure both.
@Suppress("LocalContextConfigurationRead")
private fun contextNightMode(context: Context): Int =
  context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

private fun CameraPosition.isCloseTo(other: CameraPosition): Boolean =
  abs(bearing - other.bearing) < 0.000001 &&
    abs(tilt - other.tilt) < 0.000001 &&
    abs(zoom - other.zoom) < 0.000001 &&
    abs(target.longitude - other.target.longitude) < 0.000001 &&
    abs(target.latitude - other.target.latitude) < 0.000001

private fun assertCameraPosition(expected: CameraPosition, actual: CameraPosition) {
  assertTrue(actual.isCloseTo(expected), "Expected camera $expected, actual=$actual")
}

/** The Activity supplies a real Compose host only for the handoff leg of the test. */
class SurfaceHandoffActivity : ComponentActivity() {
  var map by mutableStateOf<MapState?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      map?.let { state ->
        MaplibreMap(state = state, modifier = Modifier.fillMaxSize()) { include(MapOverlay {}) }
      }
    }
  }
}

private suspend fun awaitScreenColor(expected: Int) {
  var actual: Int? = null
  try {
    withTimeout(TIMEOUT_MILLIS) {
      while (true) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (screenshot != null) {
          try {
            actual = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
          } finally {
            screenshot.recycle()
          }
          if (actual == expected) return@withTimeout
        }
        delay(50)
      }
    }
  } catch (error: Throwable) {
    throw AssertionError("Expected Compose screen color $expected, latest=$actual", error)
  }
}
