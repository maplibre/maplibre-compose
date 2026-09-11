package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.font
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.StyleFontStore
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.BOX_FONT
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.fontFacesForTest
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.registeredFontSkipReason
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.skipMapTest
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * A registered font file draws the text of a stack the style's glyph server does not have. The test
 * style's glyphs URL answers every request with 404, so the text can only come from the file.
 */
class RegisteredFontTest {

  @Test
  fun a_registered_font_draws_text_without_the_glyph_server(): MapTestResult = runMapTest {
    registeredFontSkipReason()?.let(::skipMapTest)
    val glyphRequests = RecordingList<String>()
    val glyphServer =
      MapResourceProvider(
        accepts = {
          glyphRequests += "asked ${it.url}"
          it.url.startsWith("glyphs://")
        },
        load = { request ->
          glyphRequests += request.url
          MapResourceLoad.Failed(MapResourceError.NotFound, "no glyphs")
        },
      )
    val fonts = StyleFontStore { url, found -> glyphRequests += "font $url found=$found" }
    val config = MapResourceConfig(provider = glyphServer, fonts = fonts)
    createMapFixture(resourceConfig = config).use { fixture ->
      fixture.loadStyle(BaseStyle.Json(whiteStyle("first")))
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 2.0))
      val content = labelContent()

      fixture.declare(content)
      if (mapLibreFlavor == MapLibreFlavor.NATIVE) {
        // Native declares registered fonts in the document it parses, so the font it learned from
        // this revision reaches the engine at the next load.
        fixture.loadStyle(BaseStyle.Json(whiteStyle("second")))
        fixture.declare(content)
      }

      fixture.pumpUntilTextDrawn(glyphRequests)
      assertTrue(
        glyphRequests.none { it.startsWith("glyphs://") && FONT_NAME in it },
        "the glyph server was asked for the registered stack: ${glyphRequests.toList()}",
      )
    }
  }

  private fun labelContent(): @Composable () -> Unit = {
    val source =
      GeoJsonSource(
        id = "points",
        data =
          GeoJsonData.Features(
            buildFeatureCollection<Geometry, JsonObject?> {
              addFeature(geometry = Point(Position(0.0, 0.0)))
            }
          ),
        options = GeoJsonOptions(),
      )
    SymbolLayer(
      id = "labels",
      source = source,
      textField = const("AB").cast(),
      textFont = font(FONT_NAME, BOX_FONT),
      textSize = const(64.sp),
      textColor = const(Color.Red),
      textAllowOverlap = const(true),
      textIgnorePlacement = const(true),
    )
  }

  private suspend fun MapFixture.pumpUntilTextDrawn(glyphRequests: RecordingList<String>) {
    val deadline = TimeSource.Monotonic.markNow() + 30.seconds
    while (!textDrawn()) {
      if (deadline.hasPassedNow()) {
        val center = MapFixture.DEFAULT_EXTENT.width / 2
        val row = (0 until MapFixture.DEFAULT_EXTENT.width step 32).map { readPixel(it, center) }
        error(
          "Timed out waiting for the text to draw. Errors: $errors. Glyph requests: " +
            "${glyphRequests.toList()}. Declared font faces: ${style?.fontFacesForTest()}. " +
            "Center row: $row"
        )
      }
      pump(frames = 1)
    }
  }

  /** The box glyphs cover the center of the map once the text has drawn. */
  private suspend fun MapFixture.textDrawn(): Boolean {
    val center = MapFixture.DEFAULT_EXTENT.width / 2
    return listOf(-8, 0, 8).any { dx -> readPixel(center + dx, center).isNear(RED, tolerance = 40) }
  }

  private companion object {
    const val FONT_NAME = "Box Test Font"
    val RED = RgbaPixel(255, 0, 0, 255)

    fun whiteStyle(name: String): String =
      """
      {
        "version": 8,
        "name": "$name",
        "glyphs": "glyphs://fonts/{fontstack}/{range}.pbf",
        "sources": {},
        "layers": [
          {"id": "bg", "type": "background", "paint": {"background-color": "#ffffff"}}
        ]
      }
      """
        .trimIndent()
  }
}
