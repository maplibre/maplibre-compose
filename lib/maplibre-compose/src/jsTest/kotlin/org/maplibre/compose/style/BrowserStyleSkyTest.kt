package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class BrowserStyleSkyTest {
  @Test
  fun a_sky_write_replaces_every_property_and_a_null_write_removes_the_sky(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(EMPTY_STYLE)
        val sky = fixture.state.style.sky
        assertNull(sky.getProperty("sky-color"))

        sky.set(Sky(skyColor = nil(), atmosphereBlend = const(0.25f)))
        assertEquals(JsonPrimitive(0.25), sky.getProperty("atmosphere-blend"))
        assertNull(sky.getProperty("sky-color"))

        sky.set(Sky())
        assertNotNull(sky.getProperty("sky-color"))
        assertEquals(JsonPrimitive(0.8), sky.getProperty("atmosphere-blend"))

        sky.set(null)
        assertNull(sky.getProperty("sky-color"))
        assertNull(sky.getProperty("atmosphere-blend"))
      }
    }

  @Test
  fun a_declared_sky_reads_back(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(SKY_STYLE)
      assertEquals(JsonPrimitive(0.5), fixture.state.style.sky.getProperty("atmosphere-blend"))
    }
  }

  private companion object {
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val SKY_STYLE =
      BaseStyle.Json("""{"version":8,"sky":{"atmosphere-blend":0.5},"sources":{},"layers":[]}""")
  }
}
