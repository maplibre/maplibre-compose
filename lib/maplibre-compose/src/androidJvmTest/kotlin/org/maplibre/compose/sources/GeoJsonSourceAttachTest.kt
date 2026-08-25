package org.maplibre.compose.sources

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Desktop GeoJSON parse happens on the caller, then the handle is installed on the owner thread.
 * `call()` waits until that install has run or been dropped, so interrupting the waiter must not
 * leave the source half-attached or leave a prepared handle for `setData` to close.
 *
 * The same wait without interruption is [GeoJsonSourceAttachWaitTest], on every FFI platform.
 */
class GeoJsonSourceAttachTest {

  @Test
  fun an_interrupted_attach_wait_still_installs_and_does_not_race_setData() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      val style = assertIs<MlnFfiStyle>(fixture.style, "Errors: ${fixture.errors}")

      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      assertTrue(
        fixture.session.postOwnerTaskForTest {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
      )
      assertTrue(entered.await(5, TimeUnit.SECONDS))

      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(0.0)), GeoJsonOptions())
      val error = AtomicReference<Throwable?>()
      val attacher =
        thread(name = "geojson-attach") {
          try {
            style.addSource(source)
            source.setData(GeoJsonData.Features(pointAt(1.0)))
          } catch (thrown: Throwable) {
            error.set(thrown)
          }
        }
      assertTrue(attacher.isAlive)
      Thread.sleep(200)
      assertTrue(attacher.isAlive, "attach should be waiting behind the owner-thread backlog")
      attacher.interrupt()
      Thread.sleep(200)
      assertTrue(attacher.isAlive, "interrupt should not end the owner-thread wait")

      release.countDown()
      attacher.join(5_000)
      assertFalse(attacher.isAlive, "attach should finish once the owner thread runs it")
      assertNull(error.get(), "attach failed: ${error.get()}")
      assertNotNull(style.getSource(SOURCE_ID))
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
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
