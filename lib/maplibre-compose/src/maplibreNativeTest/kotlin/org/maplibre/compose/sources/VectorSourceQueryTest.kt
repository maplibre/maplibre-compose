package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class VectorSourceQueryTest {
  @Test
  fun a_vector_handle_queries_the_loaded_source(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(VECTOR_STYLE)
      val handle = assertIs<VectorSourceHandle>(fixture.state.style.source("vector"))

      assertTrue(handle.querySourceFeatures(setOf("places")).isEmpty())
    }
  }

  private companion object {
    val VECTOR_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "vector": {
              "type": "vector",
              "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"]
            }
          },
          "layers": []
        }
        """
          .trimIndent()
      )
  }
}
