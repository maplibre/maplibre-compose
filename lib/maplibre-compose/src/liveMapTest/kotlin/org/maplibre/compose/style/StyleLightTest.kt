package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLightTest {
  @Test
  fun a_light_write_replaces_every_property(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EMPTY_STYLE)
      val light = fixture.state.style.light
      assertNull(light.getProperty("intensity"))

      light.set(Light(intensity = const(0.25f)))
      assertEquals(JsonPrimitive(0.25), light.getProperty("intensity"))
      assertEquals(JsonPrimitive("viewport"), light.getProperty("anchor"))

      light.set(Light(intensity = nil()))
      assertNull(light.getProperty("intensity"))
      assertEquals(JsonPrimitive("viewport"), light.getProperty("anchor"))

      light.set(Light(colorTransition = TransitionOptions(duration = 1.seconds)))
      val transition = assertNotNull(light.getProperty("color-transition")).jsonObject
      assertEquals(1000.0, transition.getValue("duration").jsonPrimitive.double)
      assertEquals(0.0, transition.getValue("delay").jsonPrimitive.double)
      light.set(Light())
      assertNull(light.getProperty("color-transition"))
    }
  }

  @Test
  fun a_declared_light_reads_back_and_a_rejected_write_changes_nothing(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(LIT_STYLE)
        val light = fixture.state.style.light
        assertEquals(JsonPrimitive("map"), light.getProperty("anchor"))

        assertFailsWith<StyleHandleException> {
          light.set(
            Light(
              anchor = const(IlluminationAnchor.Map),
              intensity = const("bright").cast<FloatValue>(),
            )
          )
        }
        assertFailsWith<StyleHandleException> {
          light.set(Light(anchor = nil(), intensity = const("bright").cast<FloatValue>()))
        }
        assertEquals(JsonPrimitive("map"), light.getProperty("anchor"))
        assertNull(light.getProperty("intensity"))
      }
    }

  private companion object {
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val LIT_STYLE =
      BaseStyle.Json("""{"version":8,"light":{"anchor":"map"},"sources":{},"layers":[]}""")
  }
}
