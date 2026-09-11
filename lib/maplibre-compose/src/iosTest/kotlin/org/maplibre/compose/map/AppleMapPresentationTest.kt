@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.UIWindow

class AppleMapPresentationTest {
  @Test
  fun standalone_styles_recompose_without_a_ui_host_and_close_synchronously() = withFixture { f ->
    val presentation = f.present()
    awaitApple { f.effects == 1 && f.composedColor == Color.Red }
    f.color = Color.Green
    awaitApple { f.composedColor == Color.Green }
    presentation.density = Density(2f, 1.5f)
    presentation.layoutDirection = LayoutDirection.Rtl
    awaitApple {
      f.composedDensity == Density(2f, 1.5f) && f.composedDirection == LayoutDirection.Rtl
    }
    presentation.isActive = false
    f.color = Color.Blue
    awaitApple { f.composedColor == Color.Blue }
    presentation.close()
    assertEquals(0, f.effects)
    assertFalse(f.state.isClosed)
    assertFailsWith<IllegalStateException> { presentation.isActive = true }
    f.present()
    awaitApple { f.effects == 1 && f.composedColor == Color.Blue }
  }

  @Test
  fun rejecting_a_duplicate_preserves_the_original_presentation() = withFixture { f ->
    val presentation = f.present()
    awaitApple { f.effects == 1 }
    assertFailsWith<IllegalStateException> { f.present() }
    f.color = Color.Green
    awaitApple { f.composedColor == Color.Green }
    assertEquals(1, f.effects)
    assertNull(presentation.failure)
  }

  @Test
  fun closing_the_state_disposes_the_standalone_composition() = withFixture { f ->
    val presentation = f.present()
    awaitApple { f.effects == 1 }
    f.state.close()
    awaitApple { presentation.isClosed && f.effects == 0 }
    assertFailsWith<IllegalStateException> {
      presentation.attachLayer(CAMetalLayer(), 32, 32, 1f)
    }
  }

  @Test
  fun style_effect_failure_is_observable_and_releases_the_state() = withFixture { f ->
    val presentation = f.present()
    awaitApple { f.effects == 1 }
    f.fail = true
    awaitApple { presentation.failure != null && f.effects == 0 }
    assertSame(f.expectedFailure, presentation.failure)
    assertFalse(f.state.isClosed)
    f.fail = false
    f.present()
    awaitApple { f.effects == 1 }
  }

  @Test
  fun layer_replacement_ignores_stale_bindings_and_supplies_style_density() = withFixture { f ->
    // No drawable consumer is needed for this attachment contract; inactive maps still compose.
    val presentation = f.present().apply { isActive = false }
    val first = presentation.attachLayer(CAMetalLayer(), 64, 64, 2f)
    awaitApple { f.composedDensity.density == 2f }
    val second = presentation.attachLayer(CAMetalLayer(), 64, 64, 3f)
    first.update(0, 0, Float.NaN)
    first.close()
    awaitApple { f.composedDensity.density == 3f }
    assertFailsWith<IllegalArgumentException> { second.update(0, 64, 1f) }
    assertFailsWith<IllegalArgumentException> {
      presentation.attachLayer(CAMetalLayer(), 64, 64, Float.NaN)
    }
    second.update(128, 64, 2f)
    awaitApple { f.composedDensity.density == 2f }
    second.close()
    awaitApple { f.composedDensity.density == 1f }
    second.update(0, 0, Float.NaN)
  }

  @Test
  fun uikit_layout_attaches_resizes_and_detaches_the_public_view() = withFixture { f ->
    val presentation = f.present()
    val window = UIWindow(frame = CGRectMake(0.0, 0.0, 320.0, 240.0))
    val view = MaplibreMapView(presentation, CGRectMake(0.0, 0.0, 160.0, 120.0))
    try {
      window.addSubview(view)
      view.layoutIfNeeded()
      awaitApple { f.state.viewport?.size?.width == 160.dp }
      assertEquals(120.dp, assertNotNull(f.state.viewport).size.height)
      view.setFrame(CGRectMake(0.0, 0.0, 80.0, 60.0))
      view.layoutIfNeeded()
      awaitApple { f.state.viewport?.size?.width == 80.dp }
      view.removeFromSuperview()
      // A detached view leaves style composition alive, and can be reinserted.
      f.color = Color.Green
      awaitApple { f.composedColor == Color.Green }
      window.addSubview(view)
      view.layoutIfNeeded()
      awaitApple { f.state.viewport?.size?.width == 80.dp }
      presentation.close()
      view.removeFromSuperview()
      window.addSubview(view)
      view.layoutIfNeeded()
      assertNull(presentation.failure)
    } finally {
      view.removeFromSuperview()
    }
  }

  @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
  @Test
  fun compose_integration_inherits_locals_and_disposes_with_its_parent() {
    val local = staticCompositionLocalOf { "missing" }
    withFixture { f ->
      var inherited = ""
      var mounted by mutableStateOf(true)
      var value by mutableStateOf("first")
      var effects = 0
      val state =
        f.runtime.createMapState(BaseStyle.Empty) {
          val current = local.current
          SideEffect { inherited = current }
          DisposableEffect(Unit) {
            effects++
            onDispose { effects-- }
          }
        }
      runPlainComposeUiTest {
        setContent {
          CompositionLocalProvider(local provides value) {
            if (mounted) {
              val presentation =
                androidx.compose.runtime.remember {
                  AppleMapPresentation(state, MapPresentationOwnerToken(), MapViewOptions())
                }
              DisposableEffect(presentation) { onDispose { presentation.close() } }
              presentation.Content()
            }
          }
        }
        waitUntil { inherited == "first" && effects == 1 }
        runOnIdle { value = "second" }
        waitUntil { inherited == "second" }
        runOnIdle { mounted = false }
        waitUntil { effects == 0 }
        runOnIdle {
          assertFalse(state.isClosed)
          assertNull(state.currentMapAttachment)
        }
      }
      state.close()
    }
  }
}

private class AppleFixture : AutoCloseable {
  private val cache = FfiTestPlatform.createCacheFile()
  val runtime = createMapRuntime(MapRuntimeOptions(cacheFile = cache))
  var effects = 0
  var color by mutableStateOf(Color.Red)
  var fail by mutableStateOf(false)
  val expectedFailure = IllegalStateException("deliberate Apple presentation style failure")
  var composedColor: Color? = null
  var composedDensity = Density(1f)
  var composedDirection = LayoutDirection.Ltr
  val state =
    runtime.createMapState(BaseStyle.Empty) {
      DisposableEffect(Unit) {
        effects++
        onDispose { effects-- }
      }
      LaunchedEffect(fail) { if (fail) throw expectedFailure }
      val current = color
      val density = LocalDensity.current
      val direction = LocalLayoutDirection.current
      BackgroundLayer("background", color = const(current))
      SideEffect {
        composedColor = current
        composedDensity = density
        composedDirection = direction
      }
    }
  private val presentations = mutableListOf<AppleMapPresentation>()

  fun present(): AppleMapPresentation = AppleMapPresentation(state).also { presentations += it }

  override fun close() {
    presentations.forEach { it.close() }
    state.close()
    runtime.close()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var finished = false
    scope.launch {
      runtime.awaitClosed()
      finished = true
    }
    try {
      awaitApple { finished }
    } finally {
      scope.cancel()
    }
    FfiTestPlatform.deleteCacheFile(cache)
  }
}

private fun withFixture(block: (AppleFixture) -> Unit) {
  checkAppleMainThread()
  AppleFixture().use(block)
}

/** Pumps the native main run loop without installing Compose UI's snapshot manager or clock. */
internal fun awaitApple(predicate: () -> Boolean) {
  val deadline = NSProcessInfo.processInfo.systemUptime + 10.0
  while (!predicate()) {
    check(NSProcessInfo.processInfo.systemUptime < deadline) {
      "Timed out waiting for Apple map state"
    }
    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
  }
}
