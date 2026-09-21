package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.map.SnapshotStyleOwnership
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest

class RawLayerRoundTripTest {
  @Test
  fun raw_definitions_update_and_replace_through_the_loaded_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val initial =
        Json.parseToJsonElement(
            """{"type":"background","minzoom":3,"paint":{"background-opacity":0.5}}"""
          )
          .jsonObject
      fixture.declare { RawLayer("raw", initial) }
      val handle = assertNotNull(fixture.state.style.layers["raw"])
      assertNull(handle.asMutable)
      assertEquals(0.5, handle.getProperty("background-opacity")!!.jsonPrimitive.double)

      val changed =
        Json.parseToJsonElement("""{"type":"background","paint":{"background-opacity":0.8}}""")
          .jsonObject
      fixture.declare(ownership = SnapshotStyleOwnership(emptySet(), setOf("raw"))) {
        RawLayer("raw", changed)
      }
      assertEquals(0.8, handle.getProperty("background-opacity")!!.jsonPrimitive.double, 0.000001)
      assertEquals(0.0, handle.getProperty("minzoom")!!.jsonPrimitive.double)

      val replacement = JsonObject(changed + ("metadata" to JsonObject(emptyMap())))
      fixture.declare(ownership = SnapshotStyleOwnership(emptySet(), setOf("raw"))) {
        RawLayer("raw", replacement)
      }
      assertFailsWith<IllegalStateException> { handle.getProperty("background-opacity") }
      assertNotNull(fixture.state.style.layers["raw"])
      fixture.declare {}
      assertNull(fixture.state.style.layers["raw"])
    }
  }
}
