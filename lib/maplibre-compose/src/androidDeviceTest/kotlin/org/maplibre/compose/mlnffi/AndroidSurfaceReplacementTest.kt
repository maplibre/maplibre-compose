package org.maplibre.compose.mlnffi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapPresentationCallbacks
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.style.BaseStyle

class AndroidSurfaceReplacementTest {

  @Test
  fun a_surface_map_without_an_overlay_produces_a_frame_after_replacement() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    MlnFfiApplication.configure(
      MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
    )

    try {
      // Screen capture and Compose test synchronization invalidate the window and mask this
      // failure. Removing the #1150 workaround prevents this passive frame callback.
      ActivityScenario.launch(SurfaceReplacementActivity::class.java).use { scenario ->
        lateinit var activity: SurfaceReplacementActivity
        scenario.onActivity { activity = it }
        assertTrue(
          activity.initialFrame.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
          "the initial surface map did not produce a frame",
        )

        scenario.onActivity { it.showReplacement() }
        assertTrue(
          activity.replacementFrame.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
          "the replacement surface map did not produce a frame",
        )
      }
    } finally {
      MlnFfiApplication.resetForTest()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun a_surface_host_receives_its_surface_after_reusable_content_is_reactivated() {
    ActivityScenario.launch(ReusableSurfaceActivity::class.java).use { scenario ->
      lateinit var activity: ReusableSurfaceActivity
      scenario.onActivity { activity = it }
      assertTrue(
        activity.initialSurface.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        "the initial surface host did not receive its surface",
      )

      scenario.onActivity { it.deactivateSurface() }
      assertTrue(
        activity.surfaceDisposed.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        "the reusable surface content was not disposed",
      )

      scenario.onActivity { it.reactivateSurface() }
      assertTrue(
        activity.reactivatedSurface.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        "the reactivated surface host did not receive its surface",
      )
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
  }
}

class ReusableSurfaceActivity : ComponentActivity() {
  private var surfaceActive by mutableStateOf(true)
  private var reactivationRequested = false

  val initialSurface = CountDownLatch(1)
  val surfaceDisposed = CountDownLatch(1)
  val reactivatedSurface = CountDownLatch(1)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      ReusableContentHost(active = surfaceActive) {
        val renderer = remember { ReusableTestRenderer(::onSurfaceAvailable) }
        DisposableEffect(Unit) { onDispose { surfaceDisposed.countDown() } }
        AndroidMlnFfiSurface(
          renderer = renderer,
          runtimeBackends = setOf(MapRenderBackend.OPENGL),
          backend = MapRenderBackend.OPENGL,
          kind = AndroidMapSurfaceKind.Surface,
          modifier = Modifier.fillMaxSize(),
          logger = null,
        )
      }
    }
  }

  fun deactivateSurface() {
    surfaceActive = false
  }

  fun reactivateSurface() {
    reactivationRequested = true
    surfaceActive = true
  }

  private fun onSurfaceAvailable() {
    if (reactivationRequested) {
      reactivatedSurface.countDown()
    } else {
      initialSurface.countDown()
    }
  }
}

private class ReusableTestRenderer(private val onSurfaceAvailable: () -> Unit) : MlnFfiMapRenderer {
  override val backend = MapRenderBackend.OPENGL

  override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
    onSurfaceAvailable()
  }

  override fun render(frame: MlnFfiMapFrame) = MlnFfiFrameResult.SKIPPED

  override fun close() {}
}

class SurfaceReplacementActivity : ComponentActivity() {
  var showingReplacement by mutableStateOf(false)
    private set

  val initialFrame = CountDownLatch(1)
  val replacementFrame = CountDownLatch(1)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      if (showingReplacement) {
        TestMap(
          overlay = MapOverlay.None,
          onFrame = { replacementFrame.countDown() },
        )
      } else {
        TestMap(
          overlay = MapOverlay.Default,
          onFrame = { initialFrame.countDown() },
        )
      }
    }
  }

  fun showReplacement() {
    showingReplacement = true
  }
}

@Composable
private fun TestMap(overlay: MapOverlay, onFrame: () -> Unit) {
  val state = rememberMapState(initialBaseStyle = SOLID_STYLE)
  MaplibreMap(
    state = state,
    modifier = Modifier.fillMaxSize(),
    callbacks = MapPresentationCallbacks(onFrame = { onFrame() }),
    overlay = overlay,
  )
}

private val SOLID_STYLE =
  BaseStyle.Json(
    """
    {"version":8,"sources":{},"layers":[
      {"id":"background","type":"background","paint":{"background-color":"#336699"}}
    ]}
    """
  )
