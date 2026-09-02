package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLightTest {
  @Test
  fun a_light_property_is_unset_until_written_and_a_null_write_clears_it(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(EMPTY_STYLE)
        val light = fixture.state.style.light
        assertNull(light.getProperty("intensity"))

        light.setProperty("intensity", JsonPrimitive(0.25))
        assertEquals(JsonPrimitive(0.25), light.getProperty("intensity"))

        light.setProperty("intensity", JsonNull)
        assertNull(light.getProperty("intensity"))
      }
    }

  @Test
  fun a_declared_light_reads_back_and_an_unknown_property_is_rejected(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(LIT_STYLE)
        val light = fixture.state.style.light
        assertEquals(JsonPrimitive("map"), light.getProperty("anchor"))

        assertFailsWith<StyleHandleException> {
          light.setProperty("not-a-light-property", JsonPrimitive(1))
        }
        assertFailsWith<StyleHandleException> {
          light.setProperty("not-a-light-property", JsonNull)
        }
        assertFailsWith<StyleHandleException> {
          light.setProperty("intensity", JsonPrimitive("bright"))
        }
        assertEquals(JsonPrimitive("map"), light.getProperty("anchor"))
      }
    }

  private companion object {
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val LIT_STYLE =
      BaseStyle.Json("""{"version":8,"light":{"anchor":"map"},"sources":{},"layers":[]}""")
  }
}
