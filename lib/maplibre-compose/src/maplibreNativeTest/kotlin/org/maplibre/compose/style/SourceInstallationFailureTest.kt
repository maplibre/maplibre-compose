package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

class SourceInstallationFailureTest {
  @Test
  fun an_image_update_records_accepted_bounds_even_when_its_content_is_rejected(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val native = assertIs<MlnFfiStyleBinding>(fixture.style)
        native.awaitOwner {
          val bounds =
            PositionQuad(
              Position(-1.0, 1.0),
              Position(1.0, 1.0),
              Position(1.0, -1.0),
              Position(-1.0, -1.0),
            )
          val changedBounds = bounds.copy(topLeft = Position(-2.0, 1.0))
          val initial =
            assertIs<SourceDefinition.Image>(
              ImageSource("image", bounds, PreparedImage.fromBitmap(ImageBitmap(1, 1))).definition()
            )
          val next =
            assertIs<SourceDefinition.Image>(
              ImageSource("image", changedBounds, "https://example.invalid/image.png").definition()
            )
          var rejectContent = true
          val acceptedBounds = mutableListOf<List<Position>>()
          val binding =
            object : StyleBinding by native {
              override fun setImageSourceCoordinates(
                sourceId: String,
                coordinates: List<Position>,
              ) {
                native.setImageSourceCoordinates(sourceId, coordinates)
                acceptedBounds += coordinates
              }

              override fun setImageSourceUrl(sourceId: String, url: String) {
                // Force the real native setter to reject content while leaving the accepted
                // coordinates intact on the original source.
                native.setImageSourceUrl(if (rejectContent) "missing" else sourceId, url)
              }

              override fun setImageSourceImage(sourceId: String, image: PreparedImage) {
                native.setImageSourceImage(if (rejectContent) "missing" else sourceId, image)
              }
            }
          val installation = SourceInstallation(binding, initial)
          repeat(2) {
            assertFailsWith<StyleMutationException> { installation.update(next) }
            val partial = assertIs<SourceDefinition.Image>(installation.definition)
            assertEquals(next.coordinates, partial.coordinates)
            assertEquals(initial.image, partial.image)
          }
          installation.update(initial)
          assertEquals(listOf(next.coordinates, initial.coordinates), acceptedBounds)
          assertEquals(initial, installation.definition)
          rejectContent = false
          installation.update(next)
          assertEquals(next, installation.definition)

          // Rejected pixels are retried in the same way.
          rejectContent = true
          assertFailsWith<StyleMutationException> { installation.update(initial) }
          val partial = assertIs<SourceDefinition.Image>(installation.definition)
          assertEquals(initial.coordinates, partial.coordinates)
          assertEquals(next.image, partial.image)
          rejectContent = false
          installation.update(initial)
          assertEquals(initial, installation.definition)
        }
      }
    }

  @Test
  fun a_rejected_geojson_url_update_remains_unapplied(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = assertIs<MlnFfiStyleBinding>(fixture.style)
      binding.awaitOwner {
        val initial =
          GeoJsonSource(
              "geojson",
              GeoJsonData.Uri("https://example.invalid/first.json"),
              GeoJsonOptions.Standard,
            )
            .definition()
        val next =
          GeoJsonSource(
              "geojson",
              GeoJsonData.Uri("https://example.invalid/next.json"),
              GeoJsonOptions.Standard,
            )
            .definition()
        val installation = SourceInstallation(binding, initial)
        // A missing native source forces a real setter rejection without a mock error path.
        binding.removeSource(initial.id)
        repeat(2) {
          assertFailsWith<StyleMutationException> { installation.update(next) }
          assertEquals(initial, installation.definition)
        }
        binding.addSource(initial)
        installation.update(next)
        assertEquals(next, installation.definition)
      }
    }
  }
}
