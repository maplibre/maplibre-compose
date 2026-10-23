package org.maplibre.compose.map

import androidx.compose.ui.test.ExperimentalTestApi
import js.objects.unsafeJso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.maplibre.compose.gljs.CustomLayerInterface
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.GlJsMapFixture
import org.maplibre.compose.testing.declare

@OptIn(ExperimentalTestApi::class)
class BrowserPlatformMapAccessTest {
  @Test
  fun web_access_requires_a_current_presentation() = runBrowserMapTest {
    val runtime = createMapRuntime()
    val state = runtime.createMapState(BaseStyle.Demo)

    val failure = assertFailsWith<IllegalStateException> { state.withPlatformMap { map.getZoom() } }

    assertEquals("Platform map access requires an attached Web map surface", failure.message)
    assertNull(state.currentMapAttachment)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun web_access_uses_the_current_attached_engine_map() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      fixture.awaitMapReady()

      val zoom = fixture.state.withPlatformMap { map.getZoom() }

      assertEquals(0.0, zoom)
      assertTrue(fixture.state.currentMapAttachment?.isValid == true)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun raw_custom_layers_leave_published_resources_to_the_style_api() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      fixture.loadStyle(BaseStyle.Empty)
      fixture.state.withPlatformMap {
        map.addLayer(
          unsafeJso<CustomLayerInterface> {
            id = "custom"
            type = "custom"
            renderingMode = "2d"
            onAdd = { _, _ -> }
            onRemove = { _, _ -> }
            render = { _, _ -> }
          },
        )
      }
      fixture.state.style.sources.add(
        GeoJsonSource(
          "points",
          GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
        ),
      )
      fixture.declare { BackgroundLayer("above", visible = true) }
      assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
      // A layer added through the platform map is not reflected in the published layers.
      assertEquals(listOf("above"), fixture.state.style.layers.map { it.id })
      assertTrue(fixture.state.style.sources["points"]?.asMutable != null)
      assertEquals(
        listOf("custom", "above"),
        fixture.state.withPlatformMap { (map.getLayersOrder() as Array<String>).toList() },
      )
    } finally {
      fixture.close()
    }
  }

  @Test
  fun a_web_invocation_queued_for_a_departed_lease_does_not_run() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      var callbackRan = false
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.withPlatformMap {
              callbackRan = true
              map.getZoom()
            }
          }

        fixture.detachPresentationForTest()

        val failure = assertFailsWith<CancellationException> { access.await() }
        assertEquals("The Web platform map changed before access could begin", failure.message)
      }
      assertFalse(callbackRan)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun cancelling_a_queued_web_invocation_prevents_its_callback() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      var callbackRan = false
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.withPlatformMap {
              callbackRan = true
              map.getZoom()
            }
          }

        access.cancel()
        assertFailsWith<CancellationException> { access.await() }
      }
      fixture.awaitMapReady()
      assertFalse(callbackRan)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun closing_from_a_queued_web_callback_makes_the_first_frame_inert() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.withPlatformMap {
              fixture.state.close()
              map.getZoom()
            }
          }

        assertFalse(fixture.renderFrameForTest())

        assertEquals(0.0, access.await())
        assertTrue(fixture.state.isClosed)
      }
    } finally {
      fixture.close()
    }
  }
}
