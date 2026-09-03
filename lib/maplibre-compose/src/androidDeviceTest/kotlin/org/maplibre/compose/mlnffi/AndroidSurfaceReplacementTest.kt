package org.maplibre.compose.mlnffi

import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle

class AndroidSurfaceReplacementTest {

  @Test
  fun a_surface_map_without_an_overlay_produces_a_frame_after_replacement() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    DefaultMapRuntime.installForTest(
      MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
    )

    try {
      // Screen capture and Compose test synchronization invalidate the window and mask this
      // failure. Removing the #1150 workaround prevents this passive frame callback.
      ActivityScenario.launch(SurfaceReplacementActivity::class.java).use { scenario ->
        lateinit var activity: SurfaceReplacementActivity
        scenario.onActivity { activity = it }
        awaitOrFail(
          scenario,
          activity.initialPresentation,
          "the initial presentation was not published",
        )
        awaitOrFail(
          scenario,
          activity.initialFrame,
          "the initial surface map did not produce a frame",
        )

        scenario.onActivity { it.showReplacement() }
        awaitOrFail(
          scenario,
          activity.replacementPresentation,
          "the replacement presentation was not published",
        )
        // Start the frame wait only after publication. A screenshot or Compose test synchronization
        // would invalidate the window and can supply the frame that this regression loses.
        awaitOrFail(
          scenario,
          activity.replacementFrame,
          "the replacement surface map did not produce a frame",
        )
      }
    } finally {
      DefaultMapRuntime.resetForTest()
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

  private fun awaitOrFail(
    scenario: ActivityScenario<SurfaceReplacementActivity>,
    latch: CountDownLatch,
    message: String,
  ) {
    val completed = latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    if (completed) return

    var diagnostic = "activity unavailable"
    scenario.onActivity { diagnostic = it.diagnostic() }
    assertTrue(completed, "$message\n$diagnostic")
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
  val initialPresentation = CountDownLatch(1)
  val replacementPresentation = CountDownLatch(1)

  private val initialFrameCount = AtomicInteger()
  private val replacementFrameCount = AtomicInteger()
  private var initialState: MapState? = null
  private var replacementState: MapState? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      if (showingReplacement) {
        TestMap(
          overlay = MapOverlay {},
          onState = { replacementState = it },
          onPresentation = { replacementPresentation.countDown() },
          onFrame = {
            replacementFrameCount.incrementAndGet()
            replacementFrame.countDown()
          },
        )
      } else {
        TestMap(
          overlay = MapOverlay.Default,
          onState = { initialState = it },
          onPresentation = { initialPresentation.countDown() },
          onFrame = {
            initialFrameCount.incrementAndGet()
            initialFrame.countDown()
          },
        )
      }
    }
  }

  fun showReplacement() {
    showingReplacement = true
  }

  fun diagnostic(): String {
    val state = if (showingReplacement) replacementState else initialState
    val session = state?.currentMapAttachment?.adapter as? MlnFfiMapSession
    val surfaces =
      window.decorView.descendants().filterIsInstance<SurfaceView>().joinToString(
        prefix = "[",
        postfix = "]",
      ) { surface ->
        "shown=${surface.isShown}, valid=${surface.holder.surface.isValid}, " +
          "size=${surface.width}x${surface.height}"
      }
    return "showingReplacement=$showingReplacement, " +
      "presentation=${state?.currentMapAttachment != null}, style=${state?.style?.loadState}, " +
      "frames=${initialFrameCount.get()}/${replacementFrameCount.get()}, " +
      "nativeTargets=${session?.attachCount}/${session?.retargetCount}, surfaces=$surfaces"
  }
}

@Composable
private fun TestMap(
  overlay: MapOverlay,
  onState: (MapState) -> Unit,
  onPresentation: () -> Unit,
  onFrame: () -> Unit,
) {
  val state = rememberMapState(baseStyle = SOLID_STYLE)
  LaunchedEffect(state) {
    onState(state)
    snapshotFlow { state.currentMapAttachment }.first { it != null }
    onPresentation()
  }
  LaunchedEffect(state) {
    state.events.collect { if (it is MapEvent.FrameRendered) onFrame() }
  }
  MaplibreMap(
    state = state,
    modifier = Modifier.fillMaxSize(),
  ) {
    include(overlay)
  }
}

private fun View.descendants(): Sequence<View> = sequence {
  yield(this@descendants)
  if (this@descendants is ViewGroup) {
    repeat(childCount) { childIndex -> yieldAll(getChildAt(childIndex).descendants()) }
  }
}

private val SOLID_STYLE =
  BaseStyle.Json(
    """
    {"version":8,"sources":{},"layers":[
      {"id":"background","type":"background","paint":{"background-color":"#336699"}}
    ]}
    """
  )
