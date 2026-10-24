package org.maplibre.compose.mlnffi

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.AndroidRenderMode
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.map.resetForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** Screen captures include the SurfaceControl buffer as well as the Compose window. */
class AndroidCoordinatedPresentationTest {
  @Test
  fun moving_map_buffers_and_overlays_appear_in_the_same_screen_capture() {
    assumeTrue(Build.VERSION.SDK_INT >= 33)
    withMapActivity { scenario ->
      val initial = awaitPixels { it.aligned }
      scenario.onActivity { activity ->
        activity.lifecycleScope.launch {
          // The device runner disables system animations. Advance the camera explicitly so
          // captures still exercise changing buffers and projections on every CI run. Large
          // steps make even one frame of separation visible, rather than hiding it in rounding.
          repeat(120) { index ->
            activity.map.setCameraPosition(
              CameraPosition(center = Position((index % 5 - 2) * 4.0, 0.0), zoom = 3.0)
            )
            delay(16)
          }
        }
      }
      val positions = mutableSetOf<Int>()
      repeat(15) {
        val pixels = screenPixels()
        assertTrue(pixels.aligned, "The map and overlay appeared in different frames: $pixels")
        positions.add(pixels.redX.toInt())
        Thread.sleep(80)
      }
      assertTrue(positions.size >= 3, "The captures must include moving map buffers")
      awaitPixels { it.aligned && abs(it.redX - initial.redX) > 30 }
    }
  }

  @Test
  fun a_capped_map_retains_the_displayed_projection_while_the_logical_camera_advances() {
    assumeTrue(Build.VERSION.SDK_INT >= 33)
    withMapActivity { scenario ->
      val initial = awaitPixels { it.aligned }
      scenario.onActivity {
        it.fps = 1
        it.map.setCameraPosition(CameraPosition(center = Position(2.0, 0.0), zoom = 3.0))
      }
      val retained = awaitPixels { it.aligned && abs(it.redX - initial.redX) > 5 }
      val started = System.nanoTime()
      scenario.onActivity {
        it.map.setCameraPosition(CameraPosition(center = Position(8.0, 0.0), zoom = 3.0))
      }
      awaitCondition {
        var longitude = 0.0
        scenario.onActivity { longitude = it.map.cameraPosition.center.longitude }
        abs(longitude - 8.0) < 0.001
      }
      val held = screenPixels()
      assertTrue((System.nanoTime() - started) < 700_000_000L, "Capture missed the capped interval")
      assertTrue(held.aligned, "The overlay followed the logical camera ahead of the map: $held")
      assertEquals(retained.redX, held.redX, 1.0, "The map should retain its previous image")
      awaitPixels { it.aligned && abs(it.redX - retained.redX) > 20 }
    }
  }

  @Test
  fun resize_removal_and_replacement_preserve_buffer_ownership_and_overlay_alignment() {
    withMapActivity { scenario ->
      awaitPixels { it.aligned }
      repeat(3) { index ->
        scenario.onActivity {
          it.width = if (index % 2 == 0) 200 else 280
          it.map.setCameraPosition(CameraPosition(center = Position(index + 1.0, 0.0), zoom = 3.0))
        }
        awaitPixels { it.aligned }
        scenario.onActivity { it.showMap = false }
        awaitCondition {
          val bitmap = screenshot()
          try {
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == android.graphics.Color.GREEN
          } finally {
            bitmap.recycle()
          }
        }
        scenario.onActivity { it.showMap = true }
        awaitPixels { it.aligned }
      }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.moveToState(Lifecycle.State.RESUMED)
      awaitPixels { it.aligned }
    }
  }

  @Test
  fun texture_mode_still_presents_inside_the_window() {
    withMapActivity { scenario ->
      scenario.onActivity { it.mode = AndroidRenderMode.Texture }
      awaitCondition {
        var hasTexture = false
        scenario.onActivity { hasTexture = it.window.decorView.hasTexture() }
        hasTexture
      }
      awaitPixels { it.aligned }
    }
  }

  private fun withMapActivity(block: (ActivityScenario<CoordinatedMapActivity>) -> Unit) {
    val cache = FfiTestPlatform.createCacheFile()
    DefaultMapRuntime.configure { cacheFile = cache }
    try {
      ActivityScenario.launch(CoordinatedMapActivity::class.java).use(block)
    } finally {
      DefaultMapRuntime.resetForTest()
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }
}

private fun View.hasTexture(): Boolean =
  this is TextureView ||
    (this is ViewGroup && (0 until childCount).any { getChildAt(it).hasTexture() })

class CoordinatedMapActivity : ComponentActivity() {
  lateinit var map: MapState
  var fps by mutableStateOf<Int?>(60)
  var width by mutableStateOf(280)
  var showMap by mutableStateOf(true)
  var mode by mutableStateOf(AndroidRenderMode.Surface)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val state =
        rememberMapState(
          baseStyle = PointStyle,
          initialCameraPosition = CameraPosition(center = Position(0.0, 0.0), zoom = 3.0),
        )
      map = state
      Box(Modifier.fillMaxSize().background(Color.Green), contentAlignment = Alignment.Center) {
        if (showMap)
          MaplibreMap(
            state = state,
            modifier = Modifier.size(width.dp, 300.dp),
            renderOptions = RenderOptions { maximumFps = fps },
            uiOptions = MapUiOptions(MapUiOptions.Standard) { renderMode = mode },
          ) {
            Box(Modifier.placedAt(Position(0.0, 0.0)).size(8.dp).background(Color.Blue))
          }
      }
    }
  }
}

private val PointStyle =
  BaseStyle.Json(
    """
  {"version":8,"sources":{"point":{"type":"geojson","data":{
    "type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}
  }}},"layers":[
    {"id":"background","type":"background","paint":{"background-color":"#ffffff"}},
    {"id":"point","type":"circle","source":"point","paint":{"circle-color":"#ff0000","circle-radius":16}}
  ]}
"""
  )

private data class MarkerPixels(
  val redX: Double,
  val redY: Double,
  val blueX: Double,
  val blueY: Double,
) {
  val aligned: Boolean
    get() =
      redX.isFinite() && blueX.isFinite() && abs(redX - blueX) <= 1.5 && abs(redY - blueY) <= 1.5
}

private fun screenshot(): Bitmap =
  checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())

private fun screenPixels(): MarkerPixels {
  val bitmap = screenshot()
  try {
    var redCount = 0
    var blueCount = 0
    var redX = 0L
    var redY = 0L
    var blueX = 0L
    var blueY = 0L
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      val pixel = bitmap.getPixel(x, y)
      val red = android.graphics.Color.red(pixel)
      val green = android.graphics.Color.green(pixel)
      val blue = android.graphics.Color.blue(pixel)
      if (red > 200 && green < 60 && blue < 60) {
        redCount++
        redX += x
        redY += y
      }
      if (blue > 200 && red < 60 && green < 60) {
        blueCount++
        blueX += x
        blueY += y
      }
    }
    return MarkerPixels(
      redX.toDouble() / redCount,
      redY.toDouble() / redCount,
      blueX.toDouble() / blueCount,
      blueY.toDouble() / blueCount,
    )
  } finally {
    bitmap.recycle()
  }
}

private fun awaitPixels(condition: (MarkerPixels) -> Boolean): MarkerPixels {
  var pixels = screenPixels()
  awaitCondition {
    pixels = screenPixels()
    condition(pixels)
  }
  return pixels
}

private fun awaitCondition(condition: () -> Boolean) {
  val deadline = System.nanoTime() + 10_000_000_000L
  while (!condition()) {
    check(System.nanoTime() < deadline) { "Timed out waiting for the Android map presentation" }
    Thread.sleep(20)
  }
}
