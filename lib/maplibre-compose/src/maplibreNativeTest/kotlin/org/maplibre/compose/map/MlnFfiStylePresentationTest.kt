package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.testing.RgbaPixel

class MlnFfiStylePresentationTest {

  @Test
  fun property_updates_render_without_repeating_style_readiness() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(INITIAL_STYLE)
      val session = fixture.session
      val callbacks = session.callbacks
      var readyCount = 0
      session.callbacks =
        object : MapAdapter.Callbacks by callbacks {
          override fun onStyleReady(map: MapAdapter) {
            readyCount++
            callbacks.onStyleReady(map)
          }
        }
      session.reconcileStyleRevision(APPLICATION_REVISION)
      // Readiness publishes on the map's main thread, which the pump drains.
      fixture.pumpUntil("the style readiness to publish") { readyCount == 1 }
      assertEquals(1, readyCount)
      session.reconcileStyleRevision(applicationRevision(Color.Green))
      val extent = BridgeMapFixture.DEFAULT_EXTENT
      fixture.pumpUntil("the updated paint to render") {
        fixture
          .tryReadPixel(extent.physicalWidth / 2, extent.physicalHeight / 2)
          ?.isNear(RgbaPixel(0, 255, 0, 255)) == true
      }
      assertEquals(1, readyCount, "a paint update repeated style initialization")
    }
  }

  @Test
  fun a_failed_readiness_callback_fails_loudly_on_the_main_thread() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(INITIAL_STYLE)
      val session = fixture.session
      val callbacks = session.callbacks
      val failure = IllegalStateException("readiness publication failed")
      session.callbacks =
        object : MapAdapter.Callbacks by callbacks {
          override fun onStyleReady(map: MapAdapter) {
            throw failure
          }
        }
      try {
        // Readiness is delivered on the map's main thread, so a failing callback surfaces at
        // the delivery, not at the reconcile call site.
        session.reconcileStyleRevision(APPLICATION_REVISION)
        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.pump() })
      } finally {
        session.callbacks = callbacks
      }

      // The engine marked its content ready before the delivery, so the map still presents
      // and answers gestures once the callback is restored.
      assertTrue(session.canPresentFrames)
      val shown = session.getCameraPosition()
      session.moveBy(deltaX = 100.0, deltaY = 0.0)
      fixture.pumpUntil("the map to keep answering gestures") {
        session.getCameraPosition() != shown
      }
    }
  }

  /**
   * A paint change at idle with no transition must cost one frame. A second render request for the
   * same change draws the previous update first, and a frame inside the engine's placement
   * transition window makes it repaint until that window ends.
   */
  @Test
  fun a_paint_update_at_idle_renders_one_frame() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(UNANIMATED_STYLE)
      fixture.session.reconcileStyleRevision(APPLICATION_REVISION)
      fixture.awaitSettled()

      fixture.session.reconcileStyleRevision(applicationRevision(Color.Green))
      val rendered = fixture.awaitRenderOnDemand(600.milliseconds)
      assertEquals(1, rendered, "a paint update rendered more than one frame")
    }
  }

  @Test
  fun a_replacement_base_style_waits_for_application_content_before_presentation() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      val extent = BridgeMapFixture.DEFAULT_EXTENT
      val centerX = extent.physicalWidth / 2
      val centerY = extent.physicalHeight / 2

      fixture.loadStyle(INITIAL_STYLE)
      fixture.session.reconcileStyleRevision(APPLICATION_REVISION)
      fixture.pumpUntil("the application background to be presented") {
        fixture.tryReadPixel(centerX, centerY)?.isNear(APPLICATION_COLOR) == true
      }

      fixture.loadStyleBeforeRendering(REPLACEMENT_STYLE)
      assertTrue("replacement" in fixture.session.currentStyleLayerIds())
      assertTrue("application" !in fixture.session.currentStyleLayerIds())

      assertEquals(MlnFfiFrameResult.AwaitUpdate, fixture.frame())
      assertTrue(
        fixture.readPixel(centerX, centerY).isNear(APPLICATION_COLOR),
        "the last complete frame must remain presented while application content is absent",
      )

      fixture.session.reconcileStyleRevision(APPLICATION_REVISION)
      fixture.pumpUntil("the replacement style with application content to be presented") {
        "application" in fixture.session.currentStyleLayerIds() &&
          fixture.tryReadPixel(centerX, centerY)?.isNear(APPLICATION_COLOR) == true
      }
    }
  }

  private companion object {
    val APPLICATION_COLOR = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)

    fun applicationRevision(color: Color) =
      DesiredStyleRevision(
        sources = emptyList(),
        layers =
          listOf(
            DesiredStyleLayer(
              definition =
                TestLayer("application", "background")
                  .apply {
                    paint(
                      "background-color",
                      (const(color).compile(ExpressionContext.None)).asLayerProperty(),
                    )
                  }
                  .definition(),
              anchor = Anchor.Top,
              onClick = null,
              onLongClick = null,
            )
          ),
        images = emptyList(),
      )

    val APPLICATION_REVISION = applicationRevision(Color(APPLICATION_COLOR_ARGB))

    val INITIAL_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"initial","type":"background","paint":{"background-color":"#ff0000"}}]}"""
      )

    val UNANIMATED_STYLE =
      BaseStyle.Json(
        """{"version":8,"transition":{"duration":0},"sources":{},"layers":[{"id":"initial","type":"background","paint":{"background-color":"#ff0000"}}]}"""
      )

    val REPLACEMENT_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"replacement","type":"background","paint":{"background-color":"#00ff00"}}]}"""
      )

    const val APPLICATION_COLOR_ARGB = 0xff336699
  }
}
