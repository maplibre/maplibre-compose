package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
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
        GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}""")
      )
      assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["imperative"])
      fixture.state.style.setImage("imperative", ImageBitmap(1, 1))
      fixture.settle()

      fixture.state.style.images["imperative"]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
      fixture.state.style.sources["imperative"]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
      assertNull(fixture.state.style.sources["imperative"])
    }
  }

  @Test
  fun a_partial_reconciliation_stays_failed_until_a_complete_revision_is_published():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EmptyStyle)
      val binding = assertNotNull(fixture.style)
      val source =
        GeoJsonSource(
          "installed",
          GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        )
      val rejected =
        StyleSnapshot(
          sources = listOf(source.definition()),
          layers =
            listOf(
              StyleSnapshot.Layer(
                TestLayer("rejected", "unknown", source).definition(),
                Anchor.Top,
                null,
                null,
              )
            ),
          images = emptyList(),
        )
      fixture.state.styleAuthority.applyStyleRevision(fixture.session, binding, rejected)
      assertIs<StyleLoadState.Failed>(fixture.state.style.loadState)
      assertEquals(true, binding.awaitOwner { binding.sourceExists("installed") })
      assertNull(fixture.state.style.sources["installed"])
      fixture.state.styleAuthority.applyStyleRevision(
        fixture.session,
        binding,
        rejected.copy(layers = emptyList()),
      )
      fixture.state.styleAuthority.markStyleReady(fixture.session)
      assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
      assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["installed"])
      assertNull(fixture.state.style.layers["rejected"])
    }
  }

  private companion object {
    val EmptyStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
  }
}
