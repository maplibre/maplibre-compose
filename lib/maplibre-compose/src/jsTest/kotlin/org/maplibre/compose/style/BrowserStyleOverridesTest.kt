package org.maplibre.compose.style

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ProjectionType
import org.maplibre.compose.gljs.LngLat
import org.maplibre.compose.gljs.constantDem
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.sources.RasterDemTileSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.toJsonElement

class BrowserStyleOverridesTest {
  @Test
  fun sky_projection_and_terrain_overrides_restore_the_base_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val tile = constantDem(1000)
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["$tile"],"tileSize":256,"maxzoom":0}},"layers":[],
        "sky":{"atmosphere-blend":0.5},"projection":{"type":"globe"},
        "terrain":{"source":"dem","exaggeration":2}}"""
        )
      )
      val engine = assertNotNull((fixture.session as GlJsMapSession).engineMapForTest())
      val mutable = assertNotNull(fixture.state.style.asMutable)
      mutable.overrides = StyleOverrides {
        removeSky()
        projection = Projection(type = const(ProjectionType.Mercator))
        removeTerrain()
      }
      fixture.declare(content = fixture.state.styleContent)
      assertNull(engine.getSky())
      assertNull(engine.getTerrain())
      assertEquals(JsonPrimitive("mercator"), fixture.state.style.projection.getProperty("type"))
      assertFailsWith<IllegalStateException> { fixture.state.style.sky.set(Sky()) }
      assertFailsWith<IllegalStateException> { fixture.state.style.projection.set(Projection()) }
      mutable.overrides = StyleOverrides.None
      fixture.declare(content = fixture.state.styleContent)
      assertEquals(JsonPrimitive(0.5), fixture.state.style.sky.getProperty("atmosphere-blend"))
      assertEquals(JsonPrimitive("globe"), fixture.state.style.projection.getProperty("type"))
      assertEquals(
        JsonPrimitive(2),
        engine.getTerrain()?.toJsonElement()?.jsonObject?.get("exaggeration"),
      )
    }
  }

  @Test
  fun terrain_owns_a_source_without_a_layer_and_survives_style_reload(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        val tile = constantDem(1000)
        val source =
          RasterDemTileSource("terrain-dem", tiles = listOf(tile), tileSize = 256) { maxZoom = 0 }
        val mutable = assertNotNull(fixture.state.style.asMutable)
        val overrides = StyleOverrides { terrain = WebTerrain(source, exaggeration = 1.5) }
        fixture.state.setCameraPosition(CameraPosition(zoom = 2.0))
        repeat(2) {
          fixture.loadStyle(
            BaseStyle.Json(
              """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"white"}}]}"""
            )
          )
          mutable.overrides = overrides
          fixture.declare(content = fixture.state.styleContent)
          val engine = assertNotNull((fixture.session as GlJsMapSession).engineMapForTest())
          assertEquals(
            JsonPrimitive("terrain-dem"),
            engine.getTerrain()?.toJsonElement()?.jsonObject?.get("source"),
          )
          assertEquals(
            JsonPrimitive(1.5),
            engine.getTerrain()?.toJsonElement()?.jsonObject?.get("exaggeration"),
          )
          assertNotNull(fixture.state.style.sources.get("terrain-dem"))
          var elevation: Double? = null
          try {
            fixture.pumpUntil("exaggerated terrain elevation") {
              elevation = engine.queryTerrainElevation(LngLat(0.0, 0.0))
              elevation?.let { abs(it - 1500.0) < 1.0 } == true
            }
          } catch (error: IllegalStateException) {
            throw IllegalStateException("Last terrain elevation was $elevation", error)
          }
          // A new descriptor with the same ID forces source replacement while terrain is active.
          val replacement =
            RasterDemTileSource("terrain-dem", tiles = listOf(tile), tileSize = 512) { maxZoom = 0 }
          mutable.overrides = StyleOverrides { terrain = WebTerrain(replacement) }
          fixture.declare(content = fixture.state.styleContent)
          assertNotNull(engine.getTerrain())
          mutable.overrides = StyleOverrides.None
          fixture.declare(content = fixture.state.styleContent)
          assertNull(engine.getTerrain())
          assertNull(fixture.state.style.sources.get("terrain-dem"))
        }
        assertEquals(emptyList(), fixture.errors.toList())
      }
    }

  @Test
  fun clearing_an_optional_override_inherits_instead_of_removing() {
    val removed = StyleOverrides {
      removeSky()
      removeTerrain()
    }
    val inherited =
      StyleOverrides(from = removed) {
        sky = null
        terrain = null
      }
    assertEquals(StyleOverrides.None, inherited)
  }
}
