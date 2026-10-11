package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest

class StyleOverridesTest {
  @Test
  fun light_overrides_restore_the_current_base_style_and_prevent_command_writes(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        val mutable = assertNotNull(fixture.state.style.asMutable)
        val overrides = StyleOverrides { light = Light(intensity = const(0.25f)) }
        for (intensity in listOf(0.5, 0.75)) {
          fixture.loadStyle(
            BaseStyle.Json(
              """{"version":8,"light":{"intensity":$intensity},"sources":{},"layers":[]}"""
            )
          )
          mutable.overrides = overrides
          fixture.declare(content = fixture.state.styleContent)
          assertEquals(JsonPrimitive(0.25), fixture.state.style.light.getProperty("intensity"))
          assertFailsWith<IllegalStateException> { fixture.state.style.light.set(Light()) }
          mutable.overrides = StyleOverrides.None
          fixture.declare(content = fixture.state.styleContent)
          assertEquals(JsonPrimitive(intensity), fixture.state.style.light.getProperty("intensity"))
          assertNull(fixture.state.style.light.getProperty("anchor"))
        }
      }
    }
}
