package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LoadedStyleContents
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.onRejected
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.runtime.CommandCompletion

class MlnFfiStylePresentationTest {

  @Test
  fun an_already_completed_refusal_completes_on_main_after_the_write_returns() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(InitialStyle)
      val loaded = assertNotNull(fixture.style) as MlnFfiStyleBinding
      val binding =
        MlnFfiStyleBinding(
          map = loaded.mapForTest,
          owner = fixture.state.runtime.nativeOwner,
          contents = LoadedStyleContents.read(loaded.mapForTest),
          sessionOpen = { loaded.isLoaded },
        )
      val refusal =
        CompletableDeferred<CommandCompletion>().apply {
          completeExceptionally(IllegalArgumentException("refused"))
        }
      var reported: StyleMutationException? = null
      val outcome = binding.addSourceWith { refusal }
      outcome.onRejected { reported = it }
      assertNull(reported, "a refusal must not call application code during the write")
      fixture.awaitUntil("the refusal to be delivered on main") { reported != null }
      assertEquals("refused", reported?.message)
    }
  }

  @Test
  fun property_updates_render_without_repeating_style_readiness() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(InitialStyle)
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
      session.reconcileStyleRevision(ApplicationRevision)
      fixture.awaitUntil("the style readiness to publish") { readyCount == 1 }
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
            ),
          ),
          emptyList(),
        )
      session.reconcileStyleRevision(updated)
      val extent = BridgeMapFixture.DefaultExtent
      fixture.awaitUntil("the updated paint to render") {
        fixture
          .tryReadPixel(extent.physicalWidth / 2, extent.physicalHeight / 2)
          ?.isNear(RgbaPixel(0, 255, 0, 255)) == true
      }
      assertEquals(1, readyCount, "a paint update repeated style initialization")
    }
  }

  @Test
  fun a_replacement_base_style_waits_for_application_content_before_presentation() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      val extent = BridgeMapFixture.DefaultExtent
      val centerX = extent.physicalWidth / 2
      val centerY = extent.physicalHeight / 2

      fixture.loadStyle(InitialStyle)
      fixture.session.reconcileStyleRevision(ApplicationRevision)
      fixture.awaitUntil("the application background to be presented") {
        fixture.tryReadPixel(centerX, centerY)?.isNear(ApplicationColor) == true
      }

      fixture.loadStyleBeforeRendering(ReplacementStyle)
      assertTrue("replacement" in fixture.session.currentStyleLayerIds())
      assertTrue("application" !in fixture.session.currentStyleLayerIds())

      assertEquals(MlnFfiFrameResult.AwaitUpdate, fixture.frame())
      assertTrue(
        fixture.readPixel(centerX, centerY).isNear(ApplicationColor),
        "the last complete frame must remain presented while application content is absent",
      )

      fixture.session.reconcileStyleRevision(ApplicationRevision)
      fixture.renderingPaused = false
      fixture.awaitUntil("the replacement style with application content to be presented") {
        "application" in fixture.session.currentStyleLayerIds() &&
          fixture.tryReadPixel(centerX, centerY)?.isNear(ApplicationColor) == true
      }
    }
  }

  @Test
  fun a_failed_style_replacement_releases_its_command_group() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(InitialStyle)
      fixture.session.reconcileStyleRevision(ApplicationRevision)
      fixture.awaitUntil("the application background to be presented") {
        fixture.tryReadPixel(32, 32)?.isNear(ApplicationColor) == true
      }

      fixture.session.setBaseStyle(BaseStyle.Json("invalid"))
      fixture.awaitUntil("the invalid style to fail") { fixture.errors.isNotEmpty() }
      fixture.loadStyleBeforeRendering(ReplacementStyle)
      fixture.session.reconcileStyleRevision(StyleSnapshot(emptyList(), emptyList(), emptyList()))
      fixture.renderingPaused = false
      fixture.awaitUntil("the next style to render after the refusal") {
        fixture.tryReadPixel(32, 32)?.isNear(RgbaPixel(0, 255, 0, 255)) == true
      }
    }
  }

  private companion object {
    val ApplicationColor = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)

    val ApplicationRevision =
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
                      (const(Color(ApplicationColorArgb)).compile(ExpressionContext.None))
                        .asLayerProperty(),
                    )
                  }
                  .definition(),
              anchor = Anchor.Top,
              onClick = null,
              onLongClick = null,
            ),
          ),
        images = emptyList(),
      )

    val InitialStyle =
      BaseStyle.Json(
        """{"version":8,"sources":{"points":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}},"layers":[{"id":"initial","type":"background","paint":{"background-color":"#ff0000"}}]}""",
      )

    val ReplacementStyle =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"replacement","type":"background","paint":{"background-color":"#00ff00"}}]}""",
      )

    const val ApplicationColorArgb = 0xff336699
  }
}
