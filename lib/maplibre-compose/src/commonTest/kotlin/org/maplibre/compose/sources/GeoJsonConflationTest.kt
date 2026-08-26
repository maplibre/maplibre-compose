package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.PreparedGeoJson
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * The newest-wins install machine, run against a binding that defers installs the way a backed-up
 * parse worker does.
 */
class GeoJsonConflationTest {

  /** Queues installs instead of applying them, so a test can run claims in any order. */
  private class DeferringBinding(val delegate: RecordingStyleBinding = RecordingStyleBinding()) :
    StyleBinding by delegate {

    val queued = mutableListOf<Pair<() -> Boolean, GeoJsonData>>()

    override fun setGeoJsonSourceData(
      sourceId: String,
      prepared: PreparedGeoJson,
      claim: () -> Boolean,
    ) {
      queued += claim to (prepared as RecordingStyleBinding.RecordedPreparedGeoJson).data
    }
  }

  private fun pointAt(longitude: Double): GeoJsonData =
    GeoJsonData.Features(Point(Position(longitude = longitude, latitude = 0.0)))

  @Test
  fun an_older_install_cannot_overwrite_a_newer_one() {
    val binding = DeferringBinding()
    val source = GeoJsonSource("s", pointAt(0.0), GeoJsonOptions())
    source.attach(binding)

    val older = pointAt(1.0)
    val newer = pointAt(2.0)
    source.setData(older)
    source.setData(newer)

    assertEquals(2, binding.queued.size)
    assertTrue(binding.queued[1].first(), "the newest data claims its install")
    assertFalse(binding.queued[0].first(), "an older install must be refused after a newer one")
    assertEquals(newer.toDataJson(), source.toJson()["data"])
  }

  @Test
  fun setData_while_detached_still_reaches_the_next_attach() {
    val binding = RecordingStyleBinding()
    val source = GeoJsonSource("s", pointAt(0.0), GeoJsonOptions())

    val replacement = pointAt(3.0)
    source.setData(replacement)
    assertEquals(replacement.toDataJson(), source.toJson()["data"])

    source.attach(binding)
    assertEquals(replacement.toDataJson(), (binding.sources["s"] as JsonObject)["data"])
  }
}
