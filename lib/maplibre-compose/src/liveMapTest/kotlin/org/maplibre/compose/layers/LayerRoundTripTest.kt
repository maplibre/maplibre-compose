package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.map.SnapshotStyleOwnership
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest

class LayerRoundTripTest {
  @Test
  fun properties_update_and_replace_through_the_loaded_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.declare {
        Layer("custom", "background") {
          root("minzoom", const(3))
          paint("background-opacity", const(0.5f))
        }
      }
      val handle = assertNotNull(fixture.state.style.layers["custom"])
      assertNull(handle.asMutable)
      assertEquals(0.5, handle.getProperty("background-opacity")!!.jsonPrimitive.double)

      fixture.declare(ownership = SnapshotStyleOwnership(emptySet(), setOf("custom"))) {
        Layer("custom", "background") { paint("background-opacity", const(0.8f)) }
      }
      assertEquals(0.8, handle.getProperty("background-opacity")!!.jsonPrimitive.double, 0.000001)
      assertEquals(0.0, handle.getProperty("minzoom")!!.jsonPrimitive.double)

      fixture.declare(ownership = SnapshotStyleOwnership(emptySet(), setOf("custom"))) {
        Layer("custom", "background") {
          root("metadata", JsonObject(emptyMap()))
          paint("background-opacity", const(0.8f))
        }
      }
      assertFailsWith<IllegalStateException> { handle.getProperty("background-opacity") }
      assertNotNull(fixture.state.style.layers["custom"])
      fixture.declare {}
      assertNull(fixture.state.style.layers["custom"])
    }
  }
}
