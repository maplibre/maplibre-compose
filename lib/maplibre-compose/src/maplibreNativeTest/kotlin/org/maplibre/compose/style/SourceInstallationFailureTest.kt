package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.captureWarnings
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

class SourceInstallationFailureTest {
  @Test
  fun accepted_image_bounds_survive_a_rejected_content_write(): MapTestResult = runMapTest {
    captureWarnings { warnings ->
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val native = assertIs<MlnFfiStyleBinding>(fixture.style)
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
            ImageSource(
                "image",
                bounds,
                PreparedImage.fromBitmap(ImageBitmap(1, 1)),
              )
              .definition(),
          )
        val next =
          assertIs<SourceDefinition.Image>(
            ImageSource(
                "image",
                changedBounds,
                "https://example.invalid/image.png",
              )
              .definition(),
          )
        val binding =
          object : StyleBinding by native {
            override fun setImageSourceUrl(sourceId: String, url: String) {
              native.setImageSourceUrl("missing", url)
            }

            override fun setImageSourceImage(sourceId: String, image: PreparedImage) {
              native.setImageSourceImage("missing", image)
            }
          }
        val installation = SourceInstallation(binding, initial)
        installation.update(next)
        assertEquals(next, installation.definition, "the installation records submitted writes")
        val corners = native.mapForTest.getImageSourceCoordinates("image").await()
        assertEquals(changedBounds.topLeft.latitude, corners.first().latitude)
        assertEquals(changedBounds.topLeft.longitude, corners.first().longitude)
        fixture.pumpUntil("the rejected URL warning") {
          warnings.any { "discarded command failed" in it }
        }
        assertTrue(warnings.any { "discarded command failed" in it })
        val beforePixels = warnings.size
        installation.update(initial)
        val restored = native.mapForTest.getImageSourceCoordinates("image").await()
        assertEquals(bounds.topLeft.latitude, restored.first().latitude)
        assertEquals(bounds.topLeft.longitude, restored.first().longitude)
        fixture.pumpUntil("the rejected pixels warning") { warnings.size > beforePixels }
        assertEquals(initial, installation.definition)
        assertTrue(fixture.errors.isEmpty(), "a rejected write does not fail the loaded style")
      }
    }
  }

  @Test
  fun a_rejected_geojson_url_write_is_logged_without_recreating_a_removed_source(): MapTestResult =
    runMapTest {
      captureWarnings { warnings ->
        createMapFixture().use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          val binding = assertIs<MlnFfiStyleBinding>(fixture.style)
          val initial =
            GeoJsonSource(
                "geojson",
                GeoJsonData.Uri("https://example.invalid/first.json"),
              )
              .definition()
          val next =
            GeoJsonSource(
                "geojson",
                GeoJsonData.Uri("https://example.invalid/next.json"),
              )
              .definition()
          val installation = SourceInstallation(binding, initial)
          binding.removeSource(initial.id)
          installation.update(next)
          assertNull(binding.getSource(initial.id))
          fixture.pumpUntil("the rejected GeoJSON URL warning") {
            warnings.any { "discarded command failed" in it }
          }
          assertTrue(warnings.any { "discarded command failed" in it })
          assertEquals(next, installation.definition)
          binding.addSource(initial)
          installation.update(initial)
          installation.update(next)
          assertEquals(next, installation.definition)
          assertEquals(
            "https://example.invalid/next.json",
            binding.mapForTest.getStyleSource(initial.id).await()?.url,
          )
        }
      }
    }
}
