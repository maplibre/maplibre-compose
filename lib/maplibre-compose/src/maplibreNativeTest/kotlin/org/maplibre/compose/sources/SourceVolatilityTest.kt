package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class SourceVolatilityTest {
  @Test
  fun volatility_updates_the_live_source_and_rejects_a_removed_handle(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val source =
          VectorSource("tiles", listOf("https://example.invalid/{z}/{x}/{y}.pbf"), TileSetOptions())
        fixture.state.style.sources.add(source)
        val handle = assertNotNull(fixture.state.style.sources[source.id])
        assertEquals(false, handle.isVolatile)
        handle.isVolatile = true
        // A separately acquired handle must see native state, rather than a handle-local copy.
        assertEquals(true, assertNotNull(fixture.state.style.sources[source.id]).isVolatile)
        handle.isVolatile = false
        assertEquals(false, handle.isVolatile)
        fixture.state.style.sources.remove(source.id)
        assertFailsWith<IllegalStateException> { handle.isVolatile }
        assertFailsWith<IllegalStateException> { handle.isVolatile = true }
      }
    }
}
