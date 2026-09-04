package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.testing.RgbaPixel

class MlnFfiStylePresentationTest {

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

      assertEquals(MlnFfiFrameResult.SKIPPED, fixture.frame())
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
      DesiredStyleRevision(
        sources = emptyList(),
        layers =
          listOf(
            DesiredStyleLayer(
              definition =
                BackgroundLayer("application")
                  .apply {
                    setBackgroundColor(
                      const(Color(APPLICATION_COLOR_ARGB)).compile(ExpressionContext.None)
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
        """{"version":8,"sources":{},"layers":[{"id":"initial","type":"background","paint":{"background-color":"#ff0000"}}]}"""
      )

    val REPLACEMENT_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"replacement","type":"background","paint":{"background-color":"#00ff00"}}]}"""
      )

    const val APPLICATION_COLOR_ARGB = 0xff336699
  }
}
