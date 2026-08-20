package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

/**
 * Proves that a style change actually redraws, not just that it reaches MapLibre.
 *
 * `addSource`, `removeSource`, and `removeImage` publish no render update on their own, so the
 * style binding requests a repaint for them. A base style load can finish during a frame that
 * started beforehand, so `MAP_STYLE_LOADED` also requests a repaint. Each test settles first,
 * because a frame still in flight would render the mutation by accident.
 */
class MlnFfiMapRepaintTest {

  @Test
  fun replacing_the_base_style_after_the_map_settles_redraws() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadEmptyStyle()
      fixture.assertRedrawsAfter("replacing the base style") {
        fixture.session.setBaseStyle(COLORED_BACKGROUND)
      }
    }
  }

  @Test
  fun adding_a_layer_after_the_map_settles_redraws() {
    BridgeMapFixture.create().use { fixture ->
      val style = fixture.loadEmptyStyle()
      val layer = BackgroundLayer("toggled")
      fixture.assertRedrawsAfter("adding a layer") { style.addLayer(layer) }
    }
  }

  @Test
  fun removing_a_layer_after_the_map_settles_redraws() {
    BridgeMapFixture.create().use { fixture ->
      val style = fixture.loadEmptyStyle()
      val layer = BackgroundLayer("toggled")
      style.addLayer(layer)
      fixture.assertRedrawsAfter("removing a layer") { style.removeLayer(layer) }
    }
  }

  @Test
  fun adding_a_source_after_the_map_settles_redraws() {
    BridgeMapFixture.create().use { fixture ->
      val style = fixture.loadEmptyStyle()
      val source = emptySource()
      fixture.assertRedrawsAfter("adding a source") { style.addSource(source) }
    }
  }

  @Test
  fun removing_a_source_after_the_map_settles_redraws() {
    BridgeMapFixture.create().use { fixture ->
      val style = fixture.loadEmptyStyle()
      val source = emptySource()
      style.addSource(source)
      fixture.assertRedrawsAfter("removing a source") { style.removeSource(source) }
    }
  }

  @Test
  fun removing_an_image_after_the_map_settles_redraws() {
    BridgeMapFixture.create().use { fixture ->
      val style = fixture.loadEmptyStyle()
      style.addImage("icon", ImageBitmap(4, 4), sdf = false, resizeOptions = null)
      fixture.assertRedrawsAfter("removing an image") { style.removeImage("icon") }
    }
  }

  private fun BridgeMapFixture.loadEmptyStyle(): MlnFfiStyle {
    loadStyle(BaseStyle.Empty)
    pumpUntilRendered()
    return assertIs<MlnFfiStyle>(style, "Errors: $errors")
  }

  /**
   * Settles, runs [mutate], and asserts the map asked for another rendered frame.
   *
   * The assertion counts frames MapLibre rendered into, not frames acquired: the host hands out a
   * frame whenever the test pumps.
   */
  private fun BridgeMapFixture.assertRedrawsAfter(description: String, mutate: () -> Unit) {
    settle()
    mutate()
    val deadline = TimeSource.Monotonic.markNow() + REDRAW_TIMEOUT
    var drawn = 0
    while (drawn == 0) {
      check(deadline.hasNotPassedNow()) {
        "$description produced no new rendered frame, so it would not appear until something " +
          "else woke the render loop. Errors: $errors"
      }
      drawn += renderOnDemand(POLL_WINDOW)
    }
    assertEquals(emptyList(), errors, "the map should report nothing")
  }

  private fun emptySource() =
    GeoJsonSource("points", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

  private companion object {
    val REDRAW_TIMEOUT: Duration = 30.seconds

    val POLL_WINDOW: Duration = 50.milliseconds

    val COLORED_BACKGROUND =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#ff0000"}}
        ]}
        """
      )
  }
}
