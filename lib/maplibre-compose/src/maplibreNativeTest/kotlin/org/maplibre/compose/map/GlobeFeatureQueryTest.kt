package org.maplibre.compose.map

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class GlobeFeatureQueryTest {
  @Test
  fun a_globe_viewport_query_finds_visible_features_across_the_antimeridian(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(
          BaseStyle.Json(
            """{
              "version":8,"projection":{"type":"globe"},
              "sources":{"points":{"type":"geojson","data":{
                "type":"FeatureCollection","features":[
                  {"type":"Feature","id":"east","properties":{},"geometry":{"type":"Point","coordinates":[170,0]}},
                  {"type":"Feature","id":"west","properties":{},"geometry":{"type":"Point","coordinates":[-170,0]}},
                  {"type":"Feature","id":"back","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}
                ]}}},
              "layers":[{"id":"points","type":"circle","source":"points","paint":{"circle-radius":8,"circle-color":"red"}}]
            }"""
          )
        )
        fixture.state.setCameraPosition(CameraPosition(center = Position(180.0, 0.0), zoom = 0.0))
        val viewport = DpRect(0.dp, 0.dp, 512.dp, 512.dp)
        fixture.pumpUntil("both visible points to be queryable") {
          fixture
            .awaitWhileRendering("the viewport query") {
              fixture.state.queryRenderedFeatures(viewport, setOf("points"))
            }
            .map { it.id?.content }
            .toSet() == setOf("east", "west")
        }
        assertEquals(emptyList(), fixture.errors)
      }
    }
}
