package org.maplibre.compose.map

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest

class MissingImageEvictionTest {
  @Test
  fun native_eviction_does_not_permanently_remove_quest_icons(): MapTestResult = runMapTest {
    createMapFixture(MapExtent.fromLogical(width = 128, height = 128, scaleFactor = 3.0)).use {
      fixture ->
      // Eight 71 dp images at 3x exceed Native's 800 KiB on-demand image cache.
      val ids = (0 until 8).map { "quest-$it" }
      val image = ImageBitmap(213, 213)
      Canvas(image).drawRect(Rect(0f, 0f, 213f, 213f), Paint().apply { color = Color.Green })
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { id ->
        requests += id
        ResolvedStyleImage(image)
      }
      val features =
        ids.joinToString(",") { id ->
          """{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"icon":"$id"}}"""
        }
      val data = """{"type":"FeatureCollection","features":[$features]}"""
      fixture.loadStyle(
        BaseStyle.Json(
          """
        {
          "version":8,
          "sources":{"quests":{"type":"geojson","data":$data}},
          "layers":[
            {"id":"markers","type":"circle","source":"quests","paint":{"circle-radius":5,"circle-color":"red"}},
            {"id":"pins","type":"symbol","source":"quests","layout":{"icon-image":["get","icon"],"icon-allow-overlap":true}}
          ]
        }
      """
            .trimIndent()
        )
      )
      val style = assertNotNull(fixture.style)
      val green = RgbaPixel(0, 255, 0, 255)
      fixture.pumpUntil("all quest icons to be supplied") {
        ids.all { style.imageExists(it) == true }
      }
      // Reparse tiles with the supplied images: a camera move can reuse a tile that completed
      // layout before the asynchronous resolver added its images.
      style.setLayerProperty("pins", "icon-size", JsonPrimitive(0.5), LayerPropertyKind.LAYOUT)
      fixture.pumpUntilPixel("initial quest artwork", 192, 192, green)
      fixture.settle()
      assertEquals(ids.toSet(), requests.toList().toSet())

      val sourceDefinition =
        GeoJsonSource("quests", GeoJsonData.JsonString(data), GeoJsonOptions()).definition()
      val markerDefinition = assertNotNull(style.getLayer("markers")).definition()
      val pinDefinition = assertNotNull(style.getLayer("pins")).definition()
      // Removing the source releases cached tile requestors. Native itself reclaims the images;
      // the loaded style and MapState stay alive throughout.
      style.removeLayer("pins")
      style.removeLayer("markers")
      style.removeSource("quests")
      fixture.pumpUntil("Native to evict unused quest icons") {
        ids.all { style.imageExists(it) == false }
      }
      fixture.settle()
      requests.clear()

      style.addSource(sourceDefinition)
      style.addLayer(markerDefinition, "")
      style.addLayer(pinDefinition, "")
      fixture.pumpUntil("evicted quest icons to be restored") {
        ids.all { style.imageExists(it) == true }
      }
      style.setLayerProperty("pins", "icon-size", JsonPrimitive(1.0), LayerPropertyKind.LAYOUT)
      fixture.pumpUntilPixel("restored quest artwork above the circle markers", 192, 192, green)
      assertEquals(ids.toSet(), requests.toList().toSet())
    }
  }
}
