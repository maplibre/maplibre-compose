package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.captureWarnings
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.readSourceIds
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.setImage

class LoadedStyleResourceMutationTest {
  @Test
  fun public_commands_mutate_a_live_source_and_style_image(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EmptyStyle)
      val source =
        GeoJsonSource(
          id = "imperative",
          data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        )

      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources.add(source))
      handle.asMutable!!.setData(
        GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
      )
      assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["imperative"])
      fixture.state.style.setImage("imperative", ImageBitmap(1, 1))
      fixture.settle()

      fixture.state.style.images["imperative"]!!.asMutable!!.remove()
      fixture.state.style.sources["imperative"]!!.asMutable!!.remove()
      assertNull(fixture.state.style.sources["imperative"])
    }
  }

  @Test
  fun overlapping_source_additions_each_return_their_handle(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EmptyStyle)
      val binding = assertNotNull(fixture.style)
      coroutineScope {
        val first = async { fixture.state.style.sources.add(emptyGeoJson("first")) }
        val second = async { fixture.state.style.sources.add(emptyGeoJson("second")) }
        // A composition revision while both additions wait for the engine.
        fixture.state.styleAuthority.applyStyleRevision(fixture.session, binding, layerRevision())
        assertEquals("first", first.await()?.id)
        assertEquals("second", second.await()?.id)
      }
      assertNotNull(fixture.state.style.sources["first"])
      assertNotNull(fixture.state.style.sources["second"])
    }
  }

  @Test
  fun a_refused_source_removal_keeps_the_source_and_its_ownership(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EmptyStyle)
      val binding = assertNotNull(fixture.style)
      val source = emptyGeoJson("used")
      assertNotNull(fixture.state.style.sources.add(source))
      // A composed layer over the imperative source makes the engine refuse its removal.
      fixture.state.styleAuthority.applyStyleRevision(
        fixture.session,
        binding,
        layerRevision(TestLayer("over-used", "circle", source)),
      )
      captureWarnings { warnings ->
        fixture.state.style.sources["used"]!!.asMutable!!.remove()
        fixture.pumpUntil("the refused removal to restore the source") {
          fixture.state.style.sources["used"] != null
        }
        assertTrue(warnings.any { "Source 'used'" in it }, "warnings: $warnings")
      }
      val restored = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["used"])
      assertNotNull(restored.asMutable, "the restored source must stay imperatively owned")
      assertEquals(true, "used" in fixture.readSourceIds())
    }
  }

  @Test
  fun a_rejected_layer_is_dropped_and_the_rest_of_the_revision_installs(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(EmptyStyle)
        val binding = assertNotNull(fixture.style)
        val source = emptyGeoJson("installed")
        captureWarnings { warnings ->
          fixture.state.styleAuthority.applyStyleRevision(
            fixture.session,
            binding,
            layerRevision(
              TestLayer("rejected", "unknown", source),
              TestLayer("valid", "background"),
              sources = listOf(source),
            ),
          )
          assertTrue(warnings.any { "Layer 'rejected'" in it }, "warnings: $warnings")
        }
        assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
        assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["installed"])
        assertNotNull(fixture.state.style.layers["valid"])
        assertNull(fixture.state.style.layers["rejected"])

        // A corrected definition installs the layer that was rejected.
        fixture.state.styleAuthority.applyStyleRevision(
          fixture.session,
          binding,
          layerRevision(
            TestLayer("rejected", "circle", source),
            TestLayer("valid", "background"),
            sources = listOf(source),
          ),
        )
        assertNotNull(fixture.state.style.layers["rejected"])
        assertEquals(listOf("rejected", "valid"), fixture.state.style.layers.map { it.id })
      }
    }

  private fun emptyGeoJson(id: String) =
    GeoJsonSource(id, GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""))

  private fun layerRevision(vararg layers: TestLayer, sources: List<GeoJsonSource> = emptyList()) =
    StyleSnapshot(
      sources = sources.map { it.definition() },
      layers = layers.map { StyleSnapshot.Layer(it.definition(), Anchor.Top, null, null) },
      images = emptyList(),
    )

  private companion object {
    val EmptyStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
  }
}
