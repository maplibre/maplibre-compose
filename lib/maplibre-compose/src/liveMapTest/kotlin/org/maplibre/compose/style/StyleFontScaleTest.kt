package org.maplibre.compose.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.SnapshotStyleOwnership
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class StyleFontScaleTest {
  @Test
  fun revisions_update_rendering_and_reinitialize_the_scale_after_reload(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        val source =
          GeoJsonSource(
            "point",
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(0.0, 0.0)))
              }
            ),
            GeoJsonOptions(),
          )
        suspend fun declare(scale: Float) {
          fixture.declare(
            density = Density(1f, scale),
            ownership = SnapshotStyleOwnership(setOf("point"), setOf("circle")),
          ) {
            // Exercise the synchronized scale without a font download or platform glyph
            // differences.
            CircleLayer(
              "circle",
              source,
              radius = const(24.dp) * styleFontScale(),
              color = const(Color.Red),
            )
          }
        }
        fixture.loadStyle(BlackStyle)
        declare(1f)
        val binding = assertNotNull(fixture.style)
        val expression = binding.awaitOwner { binding.layerProperty("circle", "circle-radius") }
        fixture.pumpUntilPixel("initial circle", 256, 256, Red)
        fixture.pumpUntilPixel("outside the initial radius", 292, 256, Black)
        fixture.state.style.globalState.setProperty("theme", JsonPrimitive("dark"))
        declare(2f)
        binding.onOwner {
          assertEquals(expression, binding.layerProperty("circle", "circle-radius"))
          assertEquals(
            2.0,
            binding.globalState()?.get(FontScaleGlobalState)?.jsonPrimitive?.double,
          )
        }
        fixture.pumpUntilPixel("scaled circle", 292, 256, Red)
        assertEquals(
          JsonObject(mapOf("theme" to JsonPrimitive("dark"))),
          fixture.state.style.globalState.get(),
        )
        assertFailsWith<IllegalArgumentException> {
          fixture.state.style.globalState.setProperty(FontScaleGlobalState, JsonPrimitive(9))
        }
        assertFailsWith<IllegalArgumentException> {
          fixture.state.style.globalState.resetProperty(FontScaleGlobalState)
        }

        fixture.loadStyle(BaseStyle.Empty)
        assertNull(
          fixture.style?.let { it.awaitOwner { it.globalState() } }?.get(FontScaleGlobalState)
        )
        fixture.loadStyle(BlackStyle)
        declare(2f)
        fixture.pumpUntilPixel("scale restored on the new style", 292, 256, Red)
        assertEquals(emptyList(), fixture.errors.toList())
      }
    }

  private companion object {
    val Red = RgbaPixel(255, 0, 0, 255)
    val Black = RgbaPixel(0, 0, 0, 255)
    val BlackStyle =
      BaseStyle.Json(
        """{"version":8,"transition":{"duration":0},"sources":{},"layers":[
        {"id":"background","type":"background","paint":{"background-color":"black"}}
      ]}"""
      )
  }
}
