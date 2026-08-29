package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class StyleOwnershipTest {

  @Test
  fun loaded_styles_on_different_maps_have_distinct_identities(): MapTestResult = runMapTest {
    createMapFixture().use { first ->
      createMapFixture().use { second ->
        first.loadStyle(BaseStyle.Empty)
        second.loadStyle(BaseStyle.Empty)
        val firstStyle = assertNotNull(first.style)
        val secondStyle = assertNotNull(second.style)

        assertNotSame(firstStyle.identity, secondStyle.identity)
      }
    }
  }

  @Test
  fun reloading_a_style_invalidates_the_outgoing_identity(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val outgoing = assertNotNull(fixture.style)

      fixture.events.clear()
      fixture.loadStyle(REPLACEMENT_STYLE)
      val replacement = assertNotNull(fixture.style)
      val staleSource = emptySource("stale-source")

      assertNotSame(outgoing.identity, replacement.identity)
      val error =
        assertFailsWith<IllegalStateException> { SourceHandle(outgoing, staleSource.definition()) }

      assertTrue(error.message.orEmpty().contains("stale loaded-style identity"))
      assertNull(replacement.getSource(staleSource.id))
    }
  }

  @Test
  fun different_live_source_handles_cannot_share_an_id(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = requireNotNull(fixture.style)
      val first = emptySource("duplicate")
      val second = emptySource("duplicate")
      SourceHandle(style, first.definition())

      assertFailsWith<IllegalStateException> { SourceHandle(style, second.definition()) }

      assertNotNull(style.getSource(first.id))
    }
  }

  private companion object {
    fun emptySource(id: String) =
      GeoJsonSource(id, GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

    val REPLACEMENT_STYLE =
      BaseStyle.Json("""{"version":8,"name":"replacement","sources":{},"layers":[]}""")
  }
}
