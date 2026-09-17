package org.maplibre.compose.gljs

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.IndicatorAnimation
import org.maplibre.compose.layers.IndicatorPoint
import org.maplibre.compose.layers.indicatorIntersects
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.testing.declare
import org.maplibre.spatialk.units.extensions.meters

class BrowserLocationIndicatorTest {
  @Test
  fun measurements_snap_initially_and_retarget_from_the_animated_value() {
    val channel = IndicatorAnimation(179.0)
    assertEquals(179.0, channel.value(0.0))
    channel.retarget(-179.0, 0.0, 100.0, 200.0, wrap = true)
    assertEquals(179.0, channel.value(100.0))
    // Native's cubic-bezier(0, 0, .25, 1), evaluated independently at half duration.
    val easedHalf = 0.8230854637602085
    val interrupted = channel.value(200.0)
    assertEquals(179.0 + 2 * easedHalf, interrupted, 0.0001)
    channel.retarget(-175.0, 200.0, 0.0, 200.0, wrap = true)
    assertEquals(interrupted, channel.value(200.0))
    assertEquals(interrupted + (185.0 - interrupted) * easedHalf, channel.value(300.0), 0.0001)
    assertEquals(185.0, channel.value(400.0))
    assertFalse(channel.active(400.0))
    val north = IndicatorAnimation(350.0)
    north.retarget(10.0, 0.0, 0.0, 100.0, wrap = true)
    assertEquals(350.0 + 20 * easedHalf, north.value(50.0), 0.0001)
    north.finish()
    assertEquals(370.0, north.value(50.0))
    assertFalse(north.active(50.0))
    val delayedSnap = IndicatorAnimation(0.0)
    delayedSnap.retarget(1.0, 0.0, 100.0, 0.0)
    assertEquals(0.0, delayedSnap.value(99.0))
    assertTrue(delayedSnap.active(99.0))
    assertEquals(1.0, delayedSnap.value(100.0))
  }

  @Test
  fun rotated_quads_reject_bounding_box_corners_and_accept_padding() {
    val diamond =
      listOf(
        IndicatorPoint(0.0, -10.0),
        IndicatorPoint(10.0, 0.0),
        IndicatorPoint(0.0, 10.0),
        IndicatorPoint(-10.0, 0.0),
      )
    assertTrue(indicatorIntersects(diamond, 0.0, 0.0, 0.0, 0.0))
    assertFalse(indicatorIntersects(diamond, 9.0, 9.0, 9.0, 9.0))
    assertTrue(indicatorIntersects(diamond, 4.0, 4.0, 14.0, 14.0))
  }

  @Test
  fun animations_settle_and_context_loss_rebuilds_textures() =
    MainScope().promise {
      browserGpu()
      org.maplibre.compose.testing
        .GlJsMapFixture(org.maplibre.compose.map.MapExtent.fromLogical(256, 256, 1.0))
        .use { fixture ->
          fixture.loadStyle(BaseStyle.Empty)
          fixture.awaitMapReady()
          fixture.declare {
            org.maplibre.compose.layers.LocationIndicatorLayer(
              id = "indicator",
              location = org.maplibre.spatialk.geojson.Position(0.0, 0.0),
              accuracyRadius = 60.meters,
            )
          }
          fixture.pumpUntil("indicator images") {
            (fixture.style as? GlJsStyleBinding)
              ?.indicator("indicator")
              ?.hitTest(128.0, 128.0, 128.0, 128.0) == true
          }
          val style = fixture.style as GlJsStyleBinding
          val renderer = assertNotNull(style.indicator("indicator"))
          style.addLayer(
            Json.parseToJsonElement(
                """{"id":"above","type":"background","paint":{"background-opacity":0}}"""
              )
              .jsonObject,
            "",
          )
          fixture.pump(1)
          val order = style.layerIds()
          style.setLayerProperty(
            "indicator",
            "location-transition",
            buildJsonObject {
              put("duration", 100)
              put("delay", 20)
            },
            LayerPropertyKind.PAINT,
          )
          style.setLayerProperty(
            "indicator",
            "location",
            JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive(0))),
            LayerPropertyKind.PAINT,
          )
          fixture.settle()
          assertEquals(1.0, renderer.renderedPosition!!.longitude)
          val count = renderer.renderCount
          fixture.settle()
          assertEquals(
            count,
            renderer.renderCount,
            "settled measurements must stop requesting frames",
          )
          val map = style.withMap { it }!!
          val gl = map.getCanvas().asDynamic().getContext("webgl2")
          val extension = gl.getExtension("WEBGL_lose_context")
          assertNotNull(extension)
          var lost = false
          var restored = false
          val lostSubscription = map.subscribe("webglcontextlost") { lost = true }
          val restoredSubscription = map.subscribe("webglcontextrestored") { restored = true }
          val accuracyUploads = renderer.accuracyUploadCount
          extension.loseContext()
          fixture.pumpUntil("context loss") { lost }
          assertFalse(renderer.hitTest(128.0, 128.0, 128.0, 128.0))
          extension.restoreContext()
          fixture.pumpUntil("context restoration") { restored }
          fixture.pumpUntil("restored indicator") { renderer.hitTest(128.0, 128.0, 128.0, 128.0) }
          assertEquals(order, style.layerIds(), "context recovery preserves layer order")
          assertTrue(
            renderer.accuracyUploadCount > accuracyUploads,
            "restoration reuploads accuracy geometry",
          )
          assertTrue(renderer.uploadCount >= 4, "restoration reuploads the dot and shadow")
          assertEquals(0, gl.getError() as Int)
          lostSubscription.cancel()
          restoredSubscription.cancel()
        }
    }

  @Test
  fun custom_indicator_renders_and_picks_in_the_shared_compositor() =
    MainScope().promise {
      val gpu = browserGpu()
      val gl = gpu.gl.asDynamic()
      browserRenderTarget(256, 256, generation = 1).use { target ->
        CompositedMap(BaseStyle.Empty).use { host ->
          host.drawUntil(target, "loaded style") { host.loadedBinding != null }
          val style = host.loadedBinding as GlJsStyleBinding
          val bitmap = ImageBitmap(24, 24)
          Canvas(bitmap).drawRect(Rect(0f, 0f, 24f, 24f), Paint().apply { color = Color.Red })
          style.addImage("dot", bitmap, false, null)
          style.addLayer(
            Json.parseToJsonElement(
                """{
          "id":"indicator","type":"location-indicator",
          "layout":{"top-image":"dot"},
          "paint":{"location":[0,0,0],"bearing":0,"top-image-size":1,
          "perspective-compensation":0.85,"accuracy-radius":1000000,
          "accuracy-radius-color":"rgba(0,0,255,0.15)","accuracy-radius-border-color":"blue"}
        }"""
              )
              .jsonObject,
            "",
          )
          val indicator = assertNotNull(style.indicator("indicator"))
          host.drawOnce(target)
          assertEquals(0, gl.getError() as Int, "custom rendering must not leave a WebGL error")
          assertTrue(indicator.hitTest(128.0, 128.0, 128.0, 128.0), "center hits the image")
          assertFalse(indicator.hitTest(150.0, 128.0, 150.0, 128.0), "accuracy is not interactive")
          val pixels = readFramebuffer(gl, target.framebuffer, 256, 256)
          assertTrue(
            histogram(pixels).getOrElse("#ff0000") { 0 } > 400,
            "red image reaches the map framebuffer: ${histogram(pixels)}",
          )
          assertEquals(1, indicator.uploadCount)
          val accuracyUploads = indicator.accuracyUploadCount
          repeat(3) { host.drawOnce(target) }
          assertEquals(accuracyUploads, indicator.accuracyUploadCount)
          assertEquals(1, indicator.uploadCount, "unchanged images do not upload every frame")
          style.withMap { map ->
            map.jumpTo(
              js.objects.unsafeJso {
                pitch = 60.0
                bearing = 35.0
                zoom = 3.0
              }
            )
          }
          style.setLayerProperty(
            "indicator",
            "image-tilt-displacement",
            JsonPrimitive(8),
            LayerPropertyKind.PAINT,
          )
          host.drawOnce(target)
          assertEquals(
            accuracyUploads,
            indicator.accuracyUploadCount,
            "camera movement reuses accuracy geometry",
          )
          val tilted = readFramebuffer(gl, target.framebuffer, 256, 256)
          var redPixels = 0
          for (y in 90..160) for (x in 90..160) {
            val offset = ((255 - y) * 256 + x) * 4
            val red =
              (tilted[offset].toInt() and 255) == 255 &&
                (tilted[offset + 1].toInt() and 255) == 0 &&
                (tilted[offset + 2].toInt() and 255) == 0
            if (red) {
              redPixels++
              assertTrue(
                indicator.hitTest(x + 0.49, y + 0.49, x + 0.51, y + 0.51),
                "rendered pixel $x,$y must hit",
              )
            }
          }
          assertTrue(redPixels > 100, "tilted image remains visible")
          assertTrue(
            host.session
              .queryRenderedFeatures(
                androidx.compose.ui.unit.DpOffset(128.dp, 128.dp),
                setOf("indicator"),
              )
              .isEmpty()
          )
          style.withMap { map ->
            map.jumpTo(
              js.objects.unsafeJso {
                pitch = 0.0
                bearing = 0.0
                zoom = 0.0
              }
            )
          }
          style.setLayerProperty(
            "indicator",
            "image-tilt-displacement",
            JsonPrimitive(0),
            LayerPropertyKind.PAINT,
          )
          style.setLayerProperty(
            "indicator",
            "visibility",
            JsonPrimitive("none"),
            LayerPropertyKind.LAYOUT,
          )
          assertFalse(indicator.hitTest(128.0, 128.0, 128.0, 128.0))
          host.drawOnce(target)
          style.setLayerProperty(
            "indicator",
            "visibility",
            JsonPrimitive("visible"),
            LayerPropertyKind.LAYOUT,
          )
          style.setProjection(buildJsonObject { put("type", "globe") })
          repeat(5) {
            host.drawOnce(target)
            yieldToBrowser()
          }
          assertEquals(
            accuracyUploads,
            indicator.accuracyUploadCount,
            "projection changes reuse accuracy geometry",
          )
          assertTrue(indicator.hitTest(128.0, 128.0, 128.0, 128.0), "globe center hits")
          assertEquals(0, gl.getError() as Int, "globe rendering must not leave a WebGL error")
          // The far side of the globe must not produce either pixels or interaction geometry.
          style.withMap { map -> map.jumpTo(js.objects.unsafeJso { center = LngLat(180.0, 0.0) }) }
          repeat(50) {
            host.drawOnce(target)
            yieldToBrowser()
          }
          assertFalse(indicator.hitTest(0.0, 0.0, 256.0, 256.0), "back side is clipped")
          assertEquals(
            0,
            histogram(readFramebuffer(gl, target.framebuffer, 256, 256)).getOrElse("#ff0000") { 0 },
          )
          style.setProjection(buildJsonObject { put("type", "mercator") })
          style.withMap { map -> map.jumpTo(js.objects.unsafeJso { center = LngLat(360.0, 0.0) }) }
          repeat(50) {
            host.drawOnce(target)
            yieldToBrowser()
          }
          assertTrue(
            indicator.hitTest(128.0, 128.0, 128.0, 128.0),
            "wrapped world copy is interactive",
          )
          browserRenderTarget(128, 192, generation = 2).use { resized ->
            host.drawOnce(resized)
            assertTrue(
              indicator.hitTest(64.0, 96.0, 64.0, 96.0),
              "resized viewport updates hit coordinates",
            )
          }
          Canvas(bitmap).drawRect(Rect(0f, 0f, 24f, 24f), Paint().apply { color = Color.Blue })
          style.addImage("dot", bitmap, false, null)
          host.drawOnce(target)
          assertEquals(2, indicator.uploadCount, "replaced image uploads exactly once")
          assertTrue(
            histogram(readFramebuffer(gl, target.framebuffer, 256, 256)).getOrElse("#0000ff") {
              0
            } > 400
          )
          style.setLayerProperty(
            "indicator",
            "accuracy-radius-transition",
            buildJsonObject { put("duration", 0) },
            LayerPropertyKind.PAINT,
          )
          style.setLayerProperty(
            "indicator",
            "accuracy-radius",
            JsonPrimitive(500000),
            LayerPropertyKind.PAINT,
          )
          host.drawOnce(target)
          assertEquals(
            accuracyUploads + 1,
            indicator.accuracyUploadCount,
            "a changed radius rebuilds accuracy geometry once",
          )
          style.removeLayer("indicator")
          assertFalse(indicator.hitTest(128.0, 128.0, 128.0, 128.0))
        }
      }
    }
}
