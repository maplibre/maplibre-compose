package org.maplibre.compose.map

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.maplibre.compose.browser.configureMapLibreWorker
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.gljs.LOCAL_WORKER_URL
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.style.BaseStyle
import web.dom.document
import web.html.HTMLCanvasElement
import web.html.HTMLElement

class WebMapPresentationTest {
  @Test
  fun standalone_styles_render_recompose_and_dispose_without_a_compose_ui_host() =
    withWebFixture { f ->
      val presentation = f.present()
      presentation.attachContainer(f.host)
      f.await("the first composed style") {
        f.state.style.loadState == StyleLoadState.Ready && f.canvas() != null
      }
      f.assertRenderedColor(Color.Red)
      val root = assertNotNull(f.host.firstElementChild).unsafeCast<org.w3c.dom.Element>()
      f.await("the first visible frame") { window.getComputedStyle(root).visibility == "visible" }
      f.host.style.visibility = "hidden"
      assertEquals("hidden", window.getComputedStyle(root).visibility)
      f.host.style.visibility = "visible"
      f.color = Color.Blue
      f.assertRenderedColor(Color.Blue)
      presentation.layoutDirection = LayoutDirection.Rtl
      f.await("standalone composition locals") { f.direction == LayoutDirection.Rtl }

      // Inactivity stops rendering, but must not stop the standalone recomposer.
      presentation.isActive = false
      var inactiveFrames = 0
      val frames = f.engine().subscribe("render") { inactiveFrames++ }
      f.color = Color.Green
      f.host.style.width = "200px"
      f.await("style content while inactive") { f.composedColor == Color.Green }
      // Give both resize observers and GL JS's throttled callback time to run.
      delay(100)
      assertEquals(0, inactiveFrames)
      frames.cancel()
      presentation.isActive = true
      f.assertRenderedColor(Color.Green)
      presentation.close()
      assertEquals(0, f.effects)
      assertNull(f.host.firstElementChild)
      assertFalse(f.state.isClosed)
      assertFailsWith<IllegalStateException> { presentation.attachContainer(f.host) }
    }

  @Test
  fun attachments_resize_replace_and_preserve_the_engine_and_detached_camera() =
    withWebFixture { f ->
      val presentation = f.present()
      val other = document.createElement("span")
      f.host.appendChild(other)
      val first = presentation.attachContainer(f.host)
      f.await("initial viewport") { f.state.viewport?.size?.width == 160.dp }
      val engine = f.engine()
      val canvas = assertNotNull(f.canvas())
      f.host.style.width = "240px"
      f.host.style.height = "180px"
      f.await("container resize") {
        f.state.viewport?.size?.width == 240.dp && f.state.viewport?.size?.height == 180.dp
      }
      assertTrue(canvas.width >= 240)

      // Change the browser scale while a real ResizeObserver notification updates the viewport.
      val pixelRatio = js("Object.getOwnPropertyDescriptor(window, 'devicePixelRatio')")
      try {
        js("Object.defineProperty(window, 'devicePixelRatio', {configurable: true, value: 2.5})")
        f.host.style.width = "200px"
        f.await("fractional display scale") {
          canvas.width == 500 && f.density == 2.5f && f.state.viewport?.size?.width == 200.dp
        }
        assertSame(engine, f.engine())
      } finally {
        if (pixelRatio == null) js("delete window.devicePixelRatio")
        else js("Object.defineProperty(window, 'devicePixelRatio', pixelRatio)")
      }

      f.host.style.width = "0px"
      f.host.style.height = "0px"
      f.await("zero-size hiding") {
        f.host.lastElementChild?.unsafeCast<HTMLElement>()?.style?.visibility == "hidden"
      }
      f.host.style.width = "160px"
      f.host.style.height = "120px"
      f.await("positive-size recovery") { f.state.viewport?.size?.width == 160.dp }
      f.assertRenderedColor(Color.Red)

      val replacement = document.createElement("div").unsafeCast<HTMLElement>()
      replacement.style.cssText = "width:80px;height:60px"
      document.body.appendChild(replacement)
      try {
        val second = presentation.attachContainer(replacement)
        first.close()
        f.await("replacement viewport") { f.state.viewport?.size?.width == 80.dp }
        assertSame(engine, f.engine())
        assertTrue(replacement.contains(canvas))
        assertSame(other, f.host.firstElementChild)
        assertEquals(1, f.host.childElementCount)
        second.close()
        assertNull(replacement.firstElementChild)
        f.color = Color.Blue
        f.state.setCameraPosition(CameraPosition(zoom = 5.0))
        f.await("detached style and camera updates") {
          f.composedColor == Color.Blue && engine.getZoom() == 5.0
        }
        presentation.attachContainer(f.host)
        f.await("reattachment") { f.state.viewport?.size?.width == 160.dp }
        assertSame(engine, f.engine())
        assertEquals(5.0, f.state.cameraPosition.zoom)
        f.assertRenderedColor(Color.Blue)
        presentation.close()
        assertSame(other, f.host.firstElementChild)
        assertEquals(1, f.host.childElementCount)
      } finally {
        replacement.remove()
      }
    }

  @Test
  fun duplicate_presentations_do_not_disrupt_the_owner_and_state_close_disposes_it() =
    withWebFixture { f ->
      val presentation = f.present()
      presentation.attachContainer(f.host)
      f.await("style effects") { f.effects == 1 }
      assertFailsWith<IllegalStateException> { f.present() }
      f.color = Color.Blue
      f.assertRenderedColor(Color.Blue)
      assertEquals(1, f.effects)
      assertNull(presentation.failure)
      f.state.close()
      f.await("state closure cleanup") { f.effects == 0 && f.host.firstElementChild == null }
      assertFailsWith<IllegalStateException> { presentation.isActive = true }
    }

  @Test
  fun failed_style_effects_release_the_presentation_for_retry() = withWebFixture { f ->
    val presentation = f.present()
    presentation.attachContainer(f.host)
    f.assertRenderedColor(Color.Red)
    f.fail = true
    f.await("failure cleanup") { presentation.failure != null && f.effects == 0 }
    assertSame(f.expectedFailure, presentation.failure)
    assertNull(f.host.firstElementChild)
    assertFalse(f.state.isClosed)
    f.fail = false
    f.present().attachContainer(f.host)
    f.assertRenderedColor(Color.Red)
    assertEquals(1, f.effects)
  }
}

/** Uses only the browser event loop: no Compose UI test host, snapshot manager, or Skiko setup. */
private fun withWebFixture(block: suspend (WebFixture) -> Unit): Promise<Unit> =
  MainScope().promise {
    configureMapLibreWorker(LOCAL_WORKER_URL)
    val fixture = WebFixture()
    try {
      block(fixture)
    } finally {
      fixture.close()
      fixture.runtime.awaitClosed()
    }
  }

private class WebFixture : AutoCloseable {
  val runtime = createMapRuntime(MapRuntimeOptions())
  val host =
    document.createElement("div").unsafeCast<HTMLElement>().also {
      it.style.cssText = "width:160px;height:120px"
      document.body.appendChild(it)
    }
  var effects = 0
  var color by mutableStateOf(Color.Red)
  var fail by mutableStateOf(false)
  val expectedFailure = IllegalStateException("deliberate web presentation style failure")
  var composedColor: Color? = null
  var direction = LayoutDirection.Ltr
  var density = 1f
  val state =
    runtime.createMapState(BaseStyle.Empty) {
      DisposableEffect(Unit) {
        effects++
        onDispose { effects-- }
      }
      LaunchedEffect(fail) { if (fail) throw expectedFailure }
      val current = color
      val currentDirection = LocalLayoutDirection.current
      val currentDensity = LocalDensity.current.density
      BackgroundLayer("background", color = const(current))
      SideEffect {
        composedColor = current
        direction = currentDirection
        density = currentDensity
      }
    }
  private val presentations = mutableListOf<WebMapPresentation>()

  fun present() = WebMapPresentation(state).also { presentations += it }

  fun engine() =
    assertNotNull((state.currentMapAttachment?.adapter as? GlJsMapSession)?.engineMapForTest())

  fun canvas() = host.querySelector("canvas")?.unsafeCast<HTMLCanvasElement>()

  suspend fun await(description: String, condition: () -> Boolean) {
    val start = Date.now()
    while (!condition()) {
      presentations.forEach {
        assertNull(
          it.failure?.takeUnless { error -> error === expectedFailure },
          "Waiting for $description",
        )
      }
      check(Date.now() - start < 15_000) { "Timed out waiting for $description" }
      yieldToBrowser()
    }
  }

  suspend fun assertRenderedColor(color: Color) {
    await("engine") { state.currentMapAttachment != null }
    val map = engine()
    var actual: Color? = null
    // Read during the render event, before the browser clears the default framebuffer.
    val subscription =
      map.subscribe("render") {
        val gl = map.getCanvas().asDynamic().getContext("webgl2")
        val bytes = js("new Uint8Array(4)")
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, bytes)
        actual = Color(bytes[0] as Int, bytes[1] as Int, bytes[2] as Int, bytes[3] as Int)
      }
    try {
      map.asDynamic().triggerRepaint()
      await("rendered $color, last pixel $actual") { actual == color }
    } finally {
      subscription.cancel()
    }
  }

  override fun close() {
    presentations.forEach { it.close() }
    host.remove()
    state.close()
    runtime.close()
  }
}
