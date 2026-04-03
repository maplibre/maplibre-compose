package org.maplibre.compose.sources

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer

@RunWith(AndroidJUnit4::class)
class AndroidGeoJsonSourceTest {
  @Test
  fun shouldRenderInitialFeatureWhenSynchronousUpdatesAreEnabled() {
    withLoadedMap { scenario, map, style ->
      scenario.onActivity {
        map.cameraPosition = CameraPosition.Builder().target(featurePosition).zoom(16.0).build()

        val source =
          GeoJsonSource(
            id = "sync-initial",
            data = GeoJsonData.JsonString(singleFeatureCollectionJson),
            options = GeoJsonOptions(synchronousUpdate = true),
          )

        style.addSource(source.impl)
        style.addLayer(CircleLayer("sync-initial-layer", source.id))
      }

      assertTrue(
        "Timed out waiting for initial feature to render",
        waitForRenderedFeature(scenario, map, "sync-initial-layer"),
      )
    }
  }

  @Test
  fun shouldRenderUpdatedFeatureWhenSynchronousUpdatesAreEnabled() {
    withLoadedMap { scenario, map, style ->
      scenario.onActivity {
        map.cameraPosition = CameraPosition.Builder().target(featurePosition).zoom(16.0).build()

        val source =
          GeoJsonSource(
            id = "sync-update",
            data = GeoJsonData.JsonString(emptyFeatureCollectionJson),
            options = GeoJsonOptions(synchronousUpdate = true),
          )

        style.addSource(source.impl)
        style.addLayer(CircleLayer("sync-update-layer", source.id))
        source.setData(GeoJsonData.JsonString(singleFeatureCollectionJson))
      }

      assertTrue(
        "Timed out waiting for updated feature to render",
        waitForRenderedFeature(scenario, map, "sync-update-layer"),
      )
    }
  }

  private fun withLoadedMap(
    block: (ActivityScenario<GeoJsonSourceTestActivity>, MapLibreMap, Style) -> Unit
  ) {
    val latch = CountDownLatch(1)
    val mapRef = AtomicReference<MapLibreMap?>()
    val styleRef = AtomicReference<Style?>()
    val failure = AtomicReference<Throwable?>()

    ActivityScenario.launch(GeoJsonSourceTestActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        MapLibre.getInstance(activity)

        val mapView = MapView(activity)
        activity.setContentView(mapView)

        mapView.getMapAsync { map ->
          map.setStyle(Style.Builder().fromJson(emptyStyleJson)) { style ->
            mapRef.set(map)
            styleRef.set(style)
            latch.countDown()
          }
        }
      }

      assertTrue("Timed out waiting for map style to load", latch.await(30, TimeUnit.SECONDS))

      try {
        block(scenario, mapRef.get()!!, styleRef.get()!!)
      } catch (t: Throwable) {
        failure.set(t)
      }

      failure.get()?.let { throw it }
    }
  }

  private fun waitForRenderedFeature(
    scenario: ActivityScenario<GeoJsonSourceTestActivity>,
    map: MapLibreMap,
    layerId: String,
    timeoutMs: Long = 5_000,
  ): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val renderedCount = AtomicReference(0)

    while (System.nanoTime() < deadline) {
      scenario.onActivity {
        renderedCount.set(
          map.queryRenderedFeatures(map.projection.toScreenLocation(featurePosition), layerId).size
        )
      }

      if (renderedCount.get() > 0) return true
      Thread.sleep(50)
    }

    return false
  }

  private companion object {
    val featurePosition = LatLng(48.2089, 16.3725)
    val emptyStyleJson = """{"version":8,"sources":{},"layers":[]}"""
    val emptyFeatureCollectionJson = """{"type":"FeatureCollection","features":[]}"""

    val singleFeatureCollectionJson =
      """
      {
        "type":"FeatureCollection",
        "features":[
          {
            "type":"Feature",
            "properties":{"id":"feature-1"},
            "geometry":{"type":"Point","coordinates":[16.3725,48.2089]}
          }
        ]
      }
      """
        .trimIndent()
  }
}
