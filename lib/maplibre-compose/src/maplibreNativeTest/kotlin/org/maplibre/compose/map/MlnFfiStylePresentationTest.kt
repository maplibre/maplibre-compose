package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.testing.RgbaPixel

class MlnFfiStylePresentationTest {

  @Test
  fun reconciling_a_style_suspends_the_caller_while_native_work_is_queued() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(INITIAL_STYLE)
      val session = fixture.session
      val updated =
        APPLICATION_REVISION.copy(
          layers =
            listOf(
              StyleSnapshot.Layer(
                TestLayer("application", "background")
                  .apply { paint("background-opacity", JsonPrimitive(0.25)) }
                  .definition(),
                Anchor.Above { it.id == "initial" },
                null,
                null,
              )
            )
        )
      val parked = TestLatch(1)
      val release = TestLatch(1)
      val ownerReleased = CompletableDeferred<Boolean>()
      try {
        assertTrue(
          session.postOwnerTaskForTest {
            parked.countDown()
            ownerReleased.complete(release.await(5_000L))
          }
        )
        assertTrue(parked.await(5_000L), "native owner did not reach the gate")
        val node = StyleNode(assertNotNull(fixture.style), this)
        try {
          assertEquals("points", assertNotNull(node.getBaseSource("points")).id)
        } finally {
          node.close()
        }
        val commit =
          try {
            async(start = CoroutineStart.UNDISPATCHED) {
                session.reconcileStyleRevision(updated)
              }
              .also {
                assertFalse(
                  it.isCompleted,
                  "caller must regain control before native work can finish",
                )
              }
          } finally {
            release.countDown()
          }
        commit.await()
        assertTrue(ownerReleased.await(), "composition or reconciliation blocked its caller")
        assertEquals(
          JsonPrimitive(0.25),
          assertNotNull(fixture.style).layerProperty("application", "background-opacity"),
        )
      } finally {
        release.countDown()
      }
    }
  }

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
      val updated =
        StyleSnapshot(
          emptyList(),
          listOf(
            StyleSnapshot.Layer(
              TestLayer("application", "background")
                .apply {
                  paint(
                    "background-color",
                    (const(Color.Green).compile(ExpressionContext.None)).asLayerProperty(),
                  )
                }
                .definition(),
              Anchor.Top,
              null,
              null,
            )
          ),
          emptyList(),
        )
      session.reconcileStyleRevision(updated)
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

    val APPLICATION_REVISION =
      StyleSnapshot(
        sources = emptyList(),
        layers =
          listOf(
            StyleSnapshot.Layer(
              definition =
                TestLayer("application", "background")
                  .apply {
                    paint(
                      "background-color",
                      (const(Color(APPLICATION_COLOR_ARGB)).compile(ExpressionContext.None))
                        .asLayerProperty(),
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

    val INITIAL_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{"points":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}},"layers":[{"id":"initial","type":"background","paint":{"background-color":"#ff0000"}}]}"""
      )

    val REPLACEMENT_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"replacement","type":"background","paint":{"background-color":"#00ff00"}}]}"""
      )

    const val APPLICATION_COLOR_ARGB = 0xff336699
  }
}
