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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

  /**
   * A newer one-point payload remains after a slower older parse finishes. `publishData` parses on
   * Default without a suspend point, and the older worker runs to completion.
   */
  @Test
  fun a_newer_publish_keeps_its_data_when_an_older_parse_finishes_later() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      val style = assertIs<MlnFfiStyle>(fixture.style, "Errors: ${fixture.errors}")
      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(0.0)), GeoJsonOptions())
      style.addSource(source)

      val older = GeoJsonData.Features(manyPoints(longitude = 2.0, count = 4_000))
      val newer = GeoJsonData.Features(pointAt(longitude = 9.0))
      val olderJob =
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
          source.publishData(older)
        }
      source.publishData(newer)
      olderJob.join()

      val features = (source.toJson()["data"] as JsonObject)["features"] as JsonArray
      assertEquals(1, features.size, source.toJson().toString())
      val coordinates =
        ((features.single() as JsonObject)["geometry"] as JsonObject)["coordinates"] as JsonArray
      assertEquals(9.0, coordinates[0].jsonPrimitive.content.toDouble())
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  private fun pointAt(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(Position(longitude = longitude, latitude = 0.0)))
    }

  private fun manyPoints(longitude: Double, count: Int): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      repeat(count) { index ->
        addFeature(geometry = Point(Position(longitude = longitude, latitude = index * 0.0001)))
      }
    }

  private companion object {
    const val SOURCE_ID = "points"
  }
}
