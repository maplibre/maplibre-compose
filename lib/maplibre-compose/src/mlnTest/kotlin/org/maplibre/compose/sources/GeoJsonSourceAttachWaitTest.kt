package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.launchTestTask
import org.maplibre.compose.mlnffi.parkForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * GeoJSON parse happens on the caller, then the handle is installed on the owner thread. `call()`
 * waits until that install has run or been dropped, so a waiter parked behind owner-thread work
 * must still attach and must not leave a prepared handle for `setData` to close.
 *
 * Interrupting that waiter is a JVM-only contract.
 */
class GeoJsonSourceAttachWaitTest {

  @Test
  fun an_attach_waiting_behind_the_owner_thread_still_installs_and_does_not_race_setData() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      val style = assertIs<MlnFfiStyle>(fixture.style, "Errors: ${fixture.errors}")

      val entered = TestLatch(1)
      val release = TestLatch(1)
      assertTrue(
        fixture.session.postOwnerTaskForTest {
          entered.countDown()
          check(release.await(5_000))
        }
      )
      assertTrue(entered.await(5_000))

      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(0.0)), GeoJsonOptions())
      var error: Throwable? = null
      val finished = TestLatch(1)
      launchTestTask {
        try {
          style.addSource(source)
          source.setData(GeoJsonData.Features(pointAt(1.0)))
        } catch (thrown: Throwable) {
          error = thrown
        } finally {
          finished.countDown()
        }
      }
      try {
        parkForTest(200)
        assertEquals(1L, finished.count, "attach should be waiting behind the owner-thread backlog")

        release.countDown()
        assertTrue(finished.await(5_000), "attach should finish once the owner thread runs it")
        assertNull(error, "attach failed: $error")
        assertNotNull(style.getSource(SOURCE_ID))
        assertEquals(emptyList(), fixture.errors, "the map should report nothing")
      } finally {
        release.countDown()
        finished.await(5_000)
      }
    }
  }

  private fun pointAt(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(Position(longitude = longitude, latitude = 0.0)))
    }

  private companion object {
    const val SOURCE_ID = "points"
  }
}
